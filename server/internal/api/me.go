package api

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/blob"
	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// ---------------------------------------------------------------- discovery

func (s *Server) GetDiscovery(ctx context.Context, _ gen.GetDiscoveryRequestObject) (gen.GetDiscoveryResponseObject, error) {
	d := gen.Discovery{
		Name:    s.cfg.ServerName,
		Version: s.cfg.Version,
		Api:     s.cfg.APIURL(),
		Web:     ptr(s.cfg.PublicURL),
		Limits:  limitsJSON(s.Limits(ctx)),
	}
	d.Auth.Issuer = s.cfg.OIDCIssuer
	d.Auth.ApiResource = s.cfg.OIDCAudience
	d.Auth.MobileClientId = s.cfg.MobileClientID
	d.Auth.WebClientId = s.cfg.WebClientID
	if s.cfg.DesktopClientID != "" {
		d.Auth.DesktopClientId = ptr(s.cfg.DesktopClientID)
	}
	d.AppVersions = appVersionsJSON(s.cfg.AppVersions)
	d.DevAuth = ptr(s.dev != nil)
	d.Weather = ptr(s.weather != nil)
	d.Calendar = ptr(s.calendar != nil)
	d.Feeds = ptr(true)
	d.Birthdays = ptr(true)
	providers := []gen.PhotoSourceProvider{}
	for _, p := range s.providersEnabled() {
		providers = append(providers, gen.PhotoSourceProvider(p))
	}
	d.PhotoSources = ptr(len(providers) > 0)
	d.PhotoSourceProviders = &providers
	d.PhotoSourcesPrivateNetworks = ptr(len(providers) > 0 && s.guard.PrivateAllowed())
	d.GooglePhotosImport = ptr(s.ps.google.Configured())
	s.telegramDiscovery(&d)
	s.socialDiscovery(&d)
	s.chatDiscovery(&d)
	s.engagementDiscovery(&d)
	d.EmailInbox, d.EmailInboxDomain = s.emailDiscovery()
	if s.cfg.PushEnabled && s.cfg.VAPIDPublicKey != "" {
		d.WebpushPublicKey = ptr(s.cfg.VAPIDPublicKey)
	}
	if s.cfg.Shop != nil {
		d.Shop = shopJSON(s.cfg.Shop)
		if s.cfg.Shop.Stock != nil {
			stock, err := s.stockCached(ctx)
			if err != nil {
				return nil, err
			}
			withStock(d.Shop, s.cfg.Shop, stock, time.Now())
		}
	}
	return ok(d), nil
}

func limitsJSON(l config.Limits) gen.Limits {
	out := gen.Limits{
		MaxItemsPerSend: l.MaxItemsPerSend, MaxVideoSeconds: l.MaxVideoSeconds,
		MaxUploadBytes: int(l.MaxUploadBytes), TrashRetentionDays: ptr(l.TrashRetentionDays),
		MaxFeedsPerUser: ptr(l.MaxFeedsPerUser), MaxSubscriptionsPerFeed: ptr(l.MaxSubscriptionsPerFeed),
		MaxFeedBackfillItems: ptr(l.MaxFeedBackfillItems), MaxBirthdaysPerFeed: ptr(l.MaxBirthdaysPerFeed),
		MaxPhotoSourcesPerTarget: ptr(l.MaxPhotoSourcesPerTarget), MaxPhotoSourcesPerUser: ptr(l.MaxPhotoSourcesPerUser),
		MaxPhotoSourceItems: ptr(l.MaxPhotoSourceItems), MaxPhotoSourceRunBytes: ptr(int(l.MaxPhotoSourceRunBytes)),
		MaxGoogleImportItems: ptr(l.MaxGoogleImportItems),
	}
	emailLimitsJSON(l, &out)
	telegramLimitsJSON(l, &out)
	return out
}

// ---------------------------------------------------------------- dev auth

// devToken issues a user token without Logto (KINFRAME_DEV_AUTH=1 only).
func (s *Server) devToken(w http.ResponseWriter, r *http.Request) {
	if ok, retry := s.devTokenIP.Allow(clientIP(r.Context())); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		problem.Write(w, r, p)
		return
	}
	var body struct {
		Email    string `json:"email"`
		Name     string `json:"name"`
		Operator bool   `json:"operator"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&body); err != nil {
		problem.Write(w, r, problem.New(problem.ValidationFailed, "malformed body").WithStatus(http.StatusBadRequest))
		return
	}
	body.Email = strings.TrimSpace(body.Email)
	if !strings.Contains(body.Email, "@") || len(body.Email) > 200 {
		problem.Write(w, r, problem.Validation("email", "format", "a valid email address is required"))
		return
	}
	tok, ttl, err := s.dev.Issue(body.Email, body.Name, body.Operator)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"access_token": tok, "token_type": "Bearer", "expires_in": ttl})
}

// ---------------------------------------------------------------- me

func (s *Server) GetMe(ctx context.Context, _ gen.GetMeRequestObject) (gen.GetMeResponseObject, error) {
	return ok(s.userJSON(ctx, me(ctx))), nil
}

func (s *Server) UpdateMe(ctx context.Context, req gen.UpdateMeRequestObject) (gen.UpdateMeResponseObject, error) {
	b := req.Body
	p := store.UserPatch{ShareLocationNames: b.ShareLocationNames, Locale: b.Locale}
	if b.Timezone != nil {
		tz := strings.TrimSpace(*b.Timezone)
		if _, err := time.LoadLocation(tz); err != nil || tz == "" || tz == "Local" {
			return nil, problem.Validation("timezone", "format", "not a valid time zone")
		}
		p.Timezone = &tz
	}
	if b.DisplayName != nil {
		name := strings.TrimSpace(*b.DisplayName)
		if name == "" {
			return nil, problem.Validation("display_name", "minLength", "display name must not be empty")
		}
		p.DisplayName = &name
	}
	u, err := s.st.UpdateUser(ctx, s.st.Q(), me(ctx).ID, p)
	if err != nil {
		return nil, err
	}
	if p.DisplayName != nil {
		if err := s.bumpMemberEverywhere(ctx, u.ID); err != nil {
			return nil, err
		}
	}
	return ok(s.userJSON(ctx, u)), nil
}

// bumpMemberEverywhere refreshes the user's member.upsert on all their frames
// (display name and avatar are shown on frames).
func (s *Server) bumpMemberEverywhere(ctx context.Context, userID uuid.UUID) error {
	return s.st.InTx(ctx, func(tx *store.Tx) error {
		ms, err := s.st.FramesOfUser(ctx, tx, userID)
		if err != nil {
			return err
		}
		for _, m := range ms {
			if err := tx.FeedPut(ctx, m.FrameID, store.FeedMember, userID, false); err != nil {
				return err
			}
		}
		return nil
	})
}

func (s *Server) DeleteMe(ctx context.Context, req gen.DeleteMeRequestObject) (gen.DeleteMeResponseObject, error) {
	u := me(ctx)
	if err := s.ownsNoFrames(ctx, s.st.Q(), u.ID); err != nil {
		return nil, err
	}
	// The sign-in account goes first: if that fails nothing has changed and the
	// app can try again; the kinframe account is still there for its token.
	if s.accounts != nil && u.Sub != nil && !strings.HasPrefix(*u.Sub, "dev|") {
		if err := s.accounts.Delete(ctx, *u.Sub); err != nil {
			s.log.Error("delete sign-in account", "user", u.ID, "err", err)
			return nil, problem.New(problem.UpstreamUnavailable, "the sign-in account could not be deleted; try again later")
		}
	}
	var notifyFrames []uuid.UUID
	err := s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := s.ownsNoFrames(ctx, tx, u.ID); err != nil {
			return err
		}
		ms, err := s.st.FramesOfUser(ctx, tx, u.ID)
		if err != nil {
			return err
		}
		if req.Params.RemoveItems {
			if _, err := tx.RemovePlacements(ctx, `p.item_id IN (SELECT id FROM items WHERE sender_user_id = $1) OR p.shared_by_user_id = $1`, u.ID); err != nil {
				return err
			}
			// ... and their posts leave every feed.
			posts, err := s.st.LivePostIDs(ctx, tx, nil, &u.ID, nil)
			if err != nil {
				return err
			}
			var sweeps []uuid.UUID
			for _, p := range posts {
				_, subs, err := tx.RemovePostItems(ctx, p, nil, &u.ID)
				if err != nil {
					return err
				}
				sweeps = append(sweeps, subs...)
			}
			if err := enqueueSweeps(ctx, tx, sweeps); err != nil {
				return err
			}
		}
		if _, err := tx.Exec(ctx, `DELETE FROM feed_members WHERE user_id = $1`, u.ID); err != nil {
			return err
		}
		// With "remove my items" their photo sources go too; otherwise they wait for a take-over (owner_lost_access).
		if req.Params.RemoveItems {
			if _, err := tx.Exec(ctx, `DELETE FROM photo_sources WHERE owner_user_id = $1`, u.ID); err != nil {
				return err
			}
		}
		// Birthday entries linked to them stay, unlinked (birthdays.md §10).
		if _, err := tx.UnlinkBirthdays(ctx, nil, u.ID); err != nil {
			return err
		}
		for _, m := range ms {
			if err := tx.RemoveMember(ctx, m.FrameID, u.ID); err != nil {
				return err
			}
			notifyFrames = append(notifyFrames, m.FrameID)
		}
		if _, err := tx.Exec(ctx, `DELETE FROM push_endpoints WHERE user_id = $1`, u.ID); err != nil {
			return err
		}
		return s.st.SoftDeleteUser(ctx, tx, u.ID)
	})
	if err != nil {
		return nil, err
	}
	if u.AvatarKey != nil {
		s.deleteBlob(ctx, *u.AvatarKey)
	}
	return noContent(), nil
}

// ownsNoFrames refuses account deletion while the user owns a frame: the
// frame needs a new owner, or to be removed, first.
func (s *Server) ownsNoFrames(ctx context.Context, q db.DBTX, userID uuid.UUID) error {
	ms, err := s.st.FramesOfUser(ctx, q, userID)
	if err != nil {
		return err
	}
	for _, m := range ms {
		if m.Role == "owner" {
			return problem.New(problem.OwnerCannotLeave, "transfer or remove your frames before deleting your account")
		}
	}
	fms, err := s.st.FeedMembershipsOfUser(ctx, q, userID)
	if err != nil {
		return err
	}
	for _, m := range fms {
		if m.Role == "owner" {
			return problem.New(problem.OwnerCannotLeave, "transfer or delete your feeds before deleting your account")
		}
	}
	return nil
}

// putAvatar stores a 256×256 WebP avatar cropped from the centre.
func (s *Server) putAvatar(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	u := me(ctx)
	ct := strings.ToLower(strings.TrimSpace(strings.Split(r.Header.Get("Content-Type"), ";")[0]))
	if ct != "image/jpeg" && ct != "image/png" && ct != "image/webp" {
		problem.Write(w, r, problem.New(problem.UnsupportedMedia, "avatars must be JPEG, PNG or WebP").WithStatus(http.StatusUnsupportedMediaType))
		return
	}
	tmp, err := s.tempDir()
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	defer os.RemoveAll(tmp)
	src := filepath.Join(tmp, "in")
	f, err := os.Create(src)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	n, err := io.Copy(f, io.LimitReader(r.Body, 20<<20+1))
	f.Close()
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	if n > 20<<20 {
		problem.Write(w, r, problem.New(problem.UploadTooLarge, "avatars are limited to 20 MB"))
		return
	}
	if kind, _, err := media.Sniff(src); err != nil || kind != media.Photo {
		problem.Write(w, r, problem.New(problem.UnsupportedMedia, ""))
		return
	}
	out := filepath.Join(tmp, "avatar.webp")
	// Thumb applies no EXIF rotation, so normalise orientation first.
	norm := filepath.Join(tmp, "norm.webp")
	size, _, err := s.tools.PhotoMaster(ctx, src, norm)
	if err != nil {
		problem.Write(w, r, problem.New(problem.CorruptMedia, ""))
		return
	}
	if err := s.tools.Thumb(ctx, norm, size, 256, 0.5, 0.5, out); err != nil {
		problem.Write(w, r, problem.New(problem.CorruptMedia, ""))
		return
	}
	key := "avatars/" + u.ID.String() + "/" + auth.RandomToken(12) + ".webp"
	if err := s.blob.PutFile(ctx, key, out, blob.PutOptions{ContentType: "image/webp"}); err != nil {
		s.writeError(w, r, err)
		return
	}
	nu, err := s.st.SetAvatar(ctx, u.ID, &key)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	if u.AvatarKey != nil {
		s.deleteBlob(ctx, *u.AvatarKey)
	}
	if err := s.bumpMemberEverywhere(ctx, u.ID); err != nil {
		s.writeError(w, r, err)
		return
	}
	if err := s.syncAvatarBirthdays(ctx, u.ID); err != nil {
		s.writeError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(s.userJSON(ctx, nu))
}

func (s *Server) DeleteAvatar(ctx context.Context, _ gen.DeleteAvatarRequestObject) (gen.DeleteAvatarResponseObject, error) {
	u := me(ctx)
	if _, err := s.st.SetAvatar(ctx, u.ID, nil); err != nil {
		return nil, err
	}
	if u.AvatarKey != nil {
		s.deleteBlob(ctx, *u.AvatarKey)
		if err := s.bumpMemberEverywhere(ctx, u.ID); err != nil {
			return nil, err
		}
		if err := s.syncAvatarBirthdays(ctx, u.ID); err != nil {
			return nil, err
		}
	}
	return noContent(), nil
}

// avatarFile serves avatars publicly; keys contain a random component.
func (s *Server) avatarFile(w http.ResponseWriter, r *http.Request) {
	key := "avatars/" + r.PathValue("user") + "/" + r.PathValue("file")
	s.serveBlobOrRedirect(w, r, key, "image/webp", "", "public, max-age=604800, immutable")
}

// ---------------------------------------------------------------- push endpoints

func pushJSON(e *store.PushEndpoint) gen.PushEndpoint {
	out := gen.PushEndpoint{
		Id: e.ID, Kind: gen.PushEndpointKind(e.Kind), Endpoint: e.Endpoint, DeviceName: e.DeviceName,
		Locale: e.Locale, CreatedAt: e.CreatedAt,
	}
	if e.P256dh != nil || e.Auth != nil {
		out.WebpushKeys = &struct {
			Auth   *string `json:"auth,omitempty"`
			P256dh *string `json:"p256dh,omitempty"`
		}{Auth: e.Auth, P256dh: e.P256dh}
	}
	return out
}

func (s *Server) ListPushEndpoints(ctx context.Context, _ gen.ListPushEndpointsRequestObject) (gen.ListPushEndpointsResponseObject, error) {
	eps, err := s.st.ListPushEndpoints(ctx, me(ctx).ID)
	if err != nil {
		return nil, err
	}
	out := make([]gen.PushEndpoint, len(eps))
	for i := range eps {
		out[i] = pushJSON(&eps[i])
	}
	return ok(out), nil
}

func (s *Server) RegisterPushEndpoint(ctx context.Context, req gen.RegisterPushEndpointRequestObject) (gen.RegisterPushEndpointResponseObject, error) {
	b := req.Body
	if b.Kind == "unifiedpush" || b.Kind == "webpush" {
		if !strings.HasPrefix(b.Endpoint, "https://") && !(!s.cfg.Production() && strings.HasPrefix(b.Endpoint, "http://")) {
			return nil, problem.Validation("endpoint", "format", "endpoint must be an https URL")
		}
	}
	e := store.PushEndpoint{UserID: me(ctx).ID, Kind: string(b.Kind), Endpoint: b.Endpoint, DeviceName: b.DeviceName, Locale: b.Locale}
	if b.WebpushKeys != nil {
		e.P256dh, e.Auth = b.WebpushKeys.P256dh, b.WebpushKeys.Auth
	}
	saved, err := s.st.UpsertPushEndpoint(ctx, e)
	if err != nil {
		return nil, err
	}
	return ok(pushJSON(saved)), nil
}

func (s *Server) DeletePushEndpoint(ctx context.Context, req gen.DeletePushEndpointRequestObject) (gen.DeletePushEndpointResponseObject, error) {
	if err := s.st.DeletePushEndpoint(ctx, me(ctx).ID, req.EndpointId); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// ---------------------------------------------------------------- operator

func requireOperator(ctx context.Context) error {
	if !isOperator(ctx) {
		return problem.New(problem.Forbidden, "operator access required")
	}
	return nil
}

func (s *Server) OperatorStats(ctx context.Context, _ gen.OperatorStatsRequestObject) (gen.OperatorStatsResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	var users, frames, items int
	err := s.st.Pool.QueryRow(ctx, `SELECT
		(SELECT count(*) FROM users WHERE deleted_at IS NULL),
		(SELECT count(*) FROM frames WHERE deleted_at IS NULL),
		(SELECT count(*) FROM items)`).Scan(&users, &frames, &items)
	if err != nil {
		return nil, err
	}
	storage, err := s.st.StorageBytes(ctx)
	if err != nil {
		return nil, err
	}
	queue, err := s.st.QueueLength(ctx)
	if err != nil {
		return nil, err
	}
	pipeline, err := s.st.PipelineStats(ctx)
	if err != nil {
		return nil, err
	}
	return ok(map[string]any{
		"users": users, "frames": frames, "frames_online": s.hub.OnlineFrames(), "items": items,
		"storage_bytes": storage, "processing_queue": queue, "media_pipeline": pipeline,
	}), nil
}

func (s *Server) OperatorGetLimits(ctx context.Context, _ gen.OperatorGetLimitsRequestObject) (gen.OperatorGetLimitsResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	return ok(limitsJSON(s.Limits(ctx))), nil
}

func (s *Server) OperatorSetLimits(ctx context.Context, req gen.OperatorSetLimitsRequestObject) (gen.OperatorSetLimitsResponseObject, error) {
	if err := requireOperator(ctx); err != nil {
		return nil, err
	}
	b := req.Body
	cur := s.Limits(ctx)
	l := config.Limits{MaxItemsPerSend: b.MaxItemsPerSend, MaxVideoSeconds: b.MaxVideoSeconds, MaxUploadBytes: int64(b.MaxUploadBytes),
		TrashRetentionDays: cur.TrashRetentionDays, MaxFeedsPerUser: cur.MaxFeedsPerUser,
		MaxSubscriptionsPerFeed: cur.MaxSubscriptionsPerFeed, MaxFeedBackfillItems: cur.MaxFeedBackfillItems,
		MaxBirthdaysPerFeed: cur.MaxBirthdaysPerFeed, MaxPhotoSourcesPerTarget: cur.MaxPhotoSourcesPerTarget,
		MaxPhotoSourcesPerUser: cur.MaxPhotoSourcesPerUser, MaxPhotoSourceItems: cur.MaxPhotoSourceItems,
		MaxPhotoSourceRunBytes: cur.MaxPhotoSourceRunBytes, MaxGoogleImportItems: cur.MaxGoogleImportItems}
	setPos := func(dst *int, src *int) {
		if src != nil && *src > 0 {
			*dst = *src
		}
	}
	setPos(&l.MaxPhotoSourcesPerTarget, b.MaxPhotoSourcesPerTarget)
	setPos(&l.MaxPhotoSourcesPerUser, b.MaxPhotoSourcesPerUser)
	setPos(&l.MaxPhotoSourceItems, b.MaxPhotoSourceItems)
	setPos(&l.MaxGoogleImportItems, b.MaxGoogleImportItems)
	if b.MaxPhotoSourceRunBytes != nil && *b.MaxPhotoSourceRunBytes > 0 {
		l.MaxPhotoSourceRunBytes = int64(*b.MaxPhotoSourceRunBytes)
	}
	if b.TrashRetentionDays != nil {
		l.TrashRetentionDays = *b.TrashRetentionDays
	}
	if b.MaxFeedsPerUser != nil {
		l.MaxFeedsPerUser = *b.MaxFeedsPerUser
	}
	if b.MaxSubscriptionsPerFeed != nil {
		l.MaxSubscriptionsPerFeed = *b.MaxSubscriptionsPerFeed
	}
	if b.MaxFeedBackfillItems != nil {
		l.MaxFeedBackfillItems = *b.MaxFeedBackfillItems
	}
	if b.MaxBirthdaysPerFeed != nil {
		l.MaxBirthdaysPerFeed = *b.MaxBirthdaysPerFeed
	}
	setEmailLimits(&l, cur, b)
	setTelegramLimits(&l, cur, b)
	p := problem.New(problem.ValidationFailed, "limits must be positive")
	if l.MaxItemsPerSend < 1 {
		p.WithField("max_items_per_send", "minimum")
	}
	if l.MaxVideoSeconds < 1 {
		p.WithField("max_video_seconds", "minimum")
	}
	if l.MaxUploadBytes < 1<<20 {
		p.WithField("max_upload_bytes", "minimum")
	}
	if l.TrashRetentionDays < 1 {
		p.WithField("trash_retention_days", "minimum")
	}
	if len(p.Errors) > 0 {
		return nil, p
	}
	if err := s.setLimits(ctx, l); err != nil {
		return nil, err
	}
	return ok(limitsJSON(l)), nil
}
