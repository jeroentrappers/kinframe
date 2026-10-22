package api

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"os"
	"path"
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
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/worker"
)

// ---------------------------------------------------------------- enrollment

func (s *Server) EnrollDevice(ctx context.Context, req gen.EnrollDeviceRequestObject) (gen.EnrollDeviceResponseObject, error) {
	if ok, retry := s.enrollIP.Allow(clientIP(ctx)); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	b := req.Body
	if b.Screen.Width < 1 || b.Screen.Height < 1 || b.Screen.Width > 16384 || b.Screen.Height > 16384 {
		return nil, problem.Validation("screen", "range", "screen size is invalid")
	}
	decode := media.DefaultDecodeBox
	if d := b.Hardware.MaxVideoDecode; d != nil && d.Width > 0 && d.Height > 0 {
		decode = media.Size{W: max(d.Width, d.Height), H: min(d.Width, d.Height)}
	}
	hw, err := json.Marshal(b.Hardware)
	if err != nil {
		return nil, err
	}
	channel := "stable"
	if b.App.Channel != nil {
		channel = string(*b.App.Channel)
	}
	secret := auth.NewDeviceSecret()
	d := store.Device{
		ID: store.NewID(), SecretHash: auth.HashSecret(secret), Hardware: hw,
		ScreenW: b.Screen.Width, ScreenH: b.Screen.Height, DensityDPI: b.Screen.DensityDpi,
		DecodeW: decode.W, DecodeH: decode.H,
		AppVersion: b.App.Version, AppVersionCode: b.App.VersionCode, AppChannel: channel,
		Locale: b.Locale, Timezone: b.Timezone,
	}
	if b.Screen.Orientation != nil {
		o := string(*b.Screen.Orientation)
		d.Orientation = &o
	}
	if _, err := s.st.CreateDevice(ctx, d); err != nil {
		return nil, err
	}
	return created(map[string]any{"device_id": d.ID, "device_secret": secret}), nil
}

func (s *Server) DeviceToken(ctx context.Context, req gen.DeviceTokenRequestObject) (gen.DeviceTokenResponseObject, error) {
	if ok, retry := s.tokenIP.Allow(clientIP(ctx)); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	d, err := s.st.GetDevice(ctx, s.st.Q(), req.Body.DeviceId)
	if err == store.ErrNotFound {
		return nil, problem.New(problem.Unauthorized, "unknown device or wrong secret")
	}
	if err != nil {
		return nil, err
	}
	if d.RevokedAt != nil {
		return nil, problem.New(problem.DeviceRevoked, "")
	}
	match, err := auth.VerifySecret(req.Body.DeviceSecret, d.SecretHash)
	if err != nil || !match {
		return nil, problem.New(problem.Unauthorized, "unknown device or wrong secret")
	}
	tok, ttl, err := s.devices.Issue(d.ID)
	if err != nil {
		return nil, err
	}
	return ok(map[string]any{"access_token": tok, "expires_in": ttl}), nil
}

// ---------------------------------------------------------------- state and code

func (s *Server) DeviceGetState(ctx context.Context, _ gen.DeviceGetStateRequestObject) (gen.DeviceGetStateResponseObject, error) {
	d := deviceFrom(ctx)
	st := gen.DeviceState{DeviceId: d.ID, ServerTime: ptr(time.Now().UTC().Truncate(time.Second))}
	if d.FrameID != nil {
		f, err := s.st.GetFrame(ctx, s.st.Q(), *d.FrameID)
		if err != nil {
			return nil, notFound(err, "frame")
		}
		owner, err := s.st.FrameOwnerName(ctx, s.st.Q(), f.ID)
		if err != nil {
			return nil, err
		}
		fid := f.ID.String()
		st.Claimed, st.FrameId, st.FrameName, st.OwnerName, st.LatestSeq = true, &fid, &f.Name, &owner, int(f.LatestSeq)
	}
	return ok(st), nil
}

const (
	codeTTL     = 12 * time.Hour
	codeRenewal = 10 * time.Minute
)

func randomCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(100_000_000))
	if err != nil {
		panic(err)
	}
	return fmt.Sprintf("%08d", n.Int64())
}

// DeviceGetCode returns the active code; the same code is returned until it is
// within codeRenewal of expiring, then a fresh one is issued.
func (s *Server) DeviceGetCode(ctx context.Context, _ gen.DeviceGetCodeRequestObject) (gen.DeviceGetCodeResponseObject, error) {
	d := deviceFrom(ctx)
	c, err := s.st.GetDeviceCode(ctx, d.ID)
	if err != nil && err != store.ErrNotFound {
		return nil, err
	}
	if err == store.ErrNotFound || time.Until(c.ExpiresAt) < codeRenewal {
		c = nil
		for attempt := 0; attempt < 20 && c == nil; attempt++ {
			c, err = s.st.PutDeviceCode(ctx, d.ID, randomCode(), codeTTL)
			if db.IsUniqueViolation(err, "") {
				c, err = nil, nil
				continue
			}
			if err != nil {
				return nil, err
			}
		}
		if c == nil {
			return nil, errors.New("could not allocate a unique frame code")
		}
	}
	return ok(gen.FrameCode{
		Code: c.Code, ExpiresAt: c.ExpiresAt.UTC().Truncate(time.Second), JoinUrl: s.cfg.PublicURL + "/f/" + c.Code,
		Claimed: ptr(d.FrameID != nil),
	}), nil
}

// ---------------------------------------------------------------- change feed

func (s *Server) DeviceGetChanges(ctx context.Context, req gen.DeviceGetChangesRequestObject) (gen.DeviceGetChangesResponseObject, error) {
	d := deviceFrom(ctx)
	since := int64(req.Params.Since)
	limit := 500
	if req.Params.Limit != nil {
		limit = *req.Params.Limit
	}
	empty := map[string]any{"changes": []any{}, "next_seq": since, "has_more": false}
	if d.FrameID == nil {
		if since != 0 {
			return ok(map[string]any{"changes": []any{map[string]any{"seq": 0, "type": "reset"}}, "next_seq": 0, "has_more": false}), nil
		}
		return ok(empty), nil
	}
	f, err := s.st.GetFrame(ctx, s.st.Q(), *d.FrameID)
	if err != nil {
		return nil, notFound(err, "frame")
	}
	if since > f.LatestSeq || (since > 0 && since < f.FeedFloorSeq) {
		return ok(map[string]any{"changes": []any{map[string]any{"seq": f.LatestSeq, "type": "reset"}}, "next_seq": 0, "has_more": false}), nil
	}
	entries, err := s.st.FeedSince(ctx, f.ID, since, limit+1)
	if err != nil {
		return nil, err
	}
	hasMore := len(entries) > limit
	if hasMore {
		entries = entries[:limit]
	}
	next := since
	if len(entries) > 0 {
		next = entries[len(entries)-1].Seq
	}
	changes, err := s.renderChanges(ctx, f, d, entries, since == 0)
	if err != nil {
		return nil, err
	}
	return ok(map[string]any{"changes": changes, "next_seq": next, "has_more": hasMore}), nil
}

type mediaJSON struct {
	URL       string  `json:"url"`
	SHA256    string  `json:"sha256"`
	Bytes     int64   `json:"bytes"`
	Mime      string  `json:"mime"`
	Width     int     `json:"width"`
	Height    int     `json:"height"`
	PosterURL *string `json:"poster_url"`
}

type motionJSON struct {
	URL        string `json:"url"`
	SHA256     string `json:"sha256"`
	Bytes      int64  `json:"bytes"`
	Mime       string `json:"mime"`
	Width      int    `json:"width"`
	Height     int    `json:"height"`
	DurationMS int    `json:"duration_ms"`
	HasAudio   bool   `json:"has_audio"`
}

type placementChange struct {
	gen.Placement
	Media  *mediaJSON  `json:"media,omitempty"`
	Motion *motionJSON `json:"motion,omitempty"`
}

func deviceTarget(d *store.Device) store.MediaTarget {
	return store.MediaTarget{ScreenW: d.ScreenW, ScreenH: d.ScreenH, DecodeW: d.DecodeW, DecodeH: d.DecodeH}
}

// renderChanges turns compacted feed entries into change objects using the
// entities' current state. In a snapshot (since=0) removals are omitted.
func (s *Server) renderChanges(ctx context.Context, f *store.Frame, d *store.Device, entries []store.FeedEntry, snapshot bool) ([]any, error) {
	var placementIDs, albumIDs []uuid.UUID
	for _, e := range entries {
		switch e.Kind {
		case store.FeedPlacement:
			placementIDs = append(placementIDs, e.EntityID)
		case store.FeedAlbum:
			albumIDs = append(albumIDs, e.EntityID)
		}
	}
	placements := map[uuid.UUID]store.PlacementRow{}
	renditions := map[uuid.UUID][]store.Rendition{}
	if len(placementIDs) > 0 {
		var err error
		if placements, err = s.st.PlacementRowsByIDs(ctx, placementIDs); err != nil {
			return nil, err
		}
		var mediaIDs []uuid.UUID
		for _, p := range placements {
			mediaIDs = append(mediaIDs, p.MediaID)
			if p.MotionMediaID != nil {
				mediaIDs = append(mediaIDs, *p.MotionMediaID)
			}
		}
		if renditions, err = s.st.RenditionsFor(ctx, mediaIDs); err != nil {
			return nil, err
		}
	}
	albums := map[uuid.UUID]store.Album{}
	if len(albumIDs) > 0 {
		var err error
		if albums, err = s.st.AlbumsByIDs(ctx, albumIDs); err != nil {
			return nil, err
		}
	}
	members := map[uuid.UUID]store.MemberView{}
	if ms, err := s.st.ListMembers(ctx, s.st.Q(), f.ID); err == nil {
		for _, m := range ms {
			members[m.UserID] = m
		}
	} else {
		return nil, err
	}
	owner, _ := s.st.FrameOwnerName(ctx, s.st.Q(), f.ID)
	target := deviceTarget(d)

	out := make([]any, 0, len(entries))
	remove := func(e store.FeedEntry, typ string) {
		if !snapshot {
			out = append(out, map[string]any{"seq": e.Seq, "type": typ, "id": e.EntityID})
		}
	}
	for _, e := range entries {
		switch e.Kind {
		case store.FeedPlacement:
			p, found := placements[e.EntityID]
			if e.Removed || !found || p.RemovedAt != nil || p.PendingModeration || p.Status == "failed" || p.FrameID != f.ID {
				remove(e, "placement.remove")
				continue
			}
			pc := placementChange{Placement: s.placementJSON(&p)}
			variant := worker.Variant(p.Kind, target)
			for _, r := range renditions[p.MediaID] {
				if r.Variant != variant {
					continue
				}
				m := &mediaJSON{
					URL:    fmt.Sprintf("%s/v1/device/media/%s", s.cfg.PublicURL, p.Placement.ID),
					SHA256: r.SHA256, Bytes: r.Bytes, Mime: r.Mime, Width: r.Width, Height: r.Height,
				}
				if r.PosterKey != nil {
					m.PosterURL = ptr(m.URL + "?variant=poster")
				}
				pc.Media = m
			}
			if p.IsLive() && pc.Media != nil {
				mv := worker.Variant(string(media.Video), target)
				for _, r := range renditions[*p.MotionMediaID] {
					if r.Variant != mv {
						continue
					}
					pc.Motion = &motionJSON{
						URL:    fmt.Sprintf("%s/v1/device/media/%s?variant=motion", s.cfg.PublicURL, p.Placement.ID),
						SHA256: r.SHA256, Bytes: r.Bytes, Mime: r.Mime, Width: r.Width, Height: r.Height,
					}
					if p.MotionDurationMS != nil {
						pc.Motion.DurationMS = *p.MotionDurationMS
					}
					pc.Motion.HasAudio = p.MotionHasAudio != nil && *p.MotionHasAudio
				}
			}
			out = append(out, map[string]any{"seq": e.Seq, "type": "placement.upsert", "placement": pc})
		case store.FeedAlbum:
			a, found := albums[e.EntityID]
			if e.Removed || !found {
				remove(e, "album.remove")
				continue
			}
			out = append(out, map[string]any{"seq": e.Seq, "type": "album.upsert", "album": albumOut(&a)})
		case store.FeedMember:
			m, found := members[e.EntityID]
			if e.Removed || !found {
				remove(e, "member.remove")
				continue
			}
			out = append(out, map[string]any{"seq": e.Seq, "type": "member.upsert", "member": map[string]any{
				"user_id": m.UserID, "display_name": m.DisplayName, "avatar_url": s.avatarURL(m.AvatarKey), "role": m.Role,
			}})
		case store.FeedSettings:
			out = append(out, map[string]any{"seq": e.Seq, "type": "settings", "settings": deviceSettingsDoc(f)})
		case store.FeedWeather:
			w, err := s.frameWeather(ctx, f)
			if err != nil {
				return nil, err
			}
			if w == nil && snapshot {
				continue
			}
			out = append(out, map[string]any{"seq": e.Seq, "type": "weather", "weather": w})
		case store.FeedCalendar:
			c, err := s.frameCalendar(ctx, f)
			if err != nil {
				return nil, err
			}
			if c == nil && snapshot {
				continue
			}
			out = append(out, map[string]any{"seq": e.Seq, "type": "calendar", "calendar": c})
		case store.FeedFrame:
			out = append(out, map[string]any{"seq": e.Seq, "type": "frame", "frame": map[string]any{
				"name": f.Name, "location": f.Location, "owner_name": owner,
			}})
		}
	}
	return out, nil
}

// ---------------------------------------------------------------- media download

// serveBlob streams a stored object with Range, ETag and If-None-Match
// support, whatever the backend. An empty etag uses the store's own (fs:
// modification time and size, as before; s3: the object's ETag).
func (s *Server) serveBlob(w http.ResponseWriter, r *http.Request, key, contentType, etag, cacheControl string) {
	obj, info, err := s.blob.Open(r.Context(), key)
	if err != nil {
		if !errors.Is(err, blob.ErrNotFound) {
			s.writeError(w, r, err)
			return
		}
		problem.Write(w, r, problem.New(problem.NotFound, ""))
		return
	}
	defer obj.Close()
	if contentType == "" {
		switch path.Ext(key) {
		case ".webp":
			contentType = "image/webp"
		case ".mp4":
			contentType = "video/mp4"
		}
	}
	if contentType != "" {
		w.Header().Set("Content-Type", contentType)
	}
	if etag == "" {
		etag = info.ETag
	}
	w.Header().Set("ETag", etag)
	if cacheControl != "" {
		w.Header().Set("Cache-Control", cacheControl)
	}
	http.ServeContent(w, r, "", info.ModTime, obj)
}

// presignTTL is how long presigned bucket URLs stay valid
// (KINFRAME_MEDIA_REDIRECT=presigned).
const presignTTL = 15 * time.Minute

// serveBlobOrRedirect serves URLs that browsers and apps load (signed media
// URLs, avatars). With KINFRAME_MEDIA_REDIRECT=presigned it redirects to a
// short-lived presigned bucket URL instead of streaming; the bucket then
// answers Range requests itself. Frame downloads (deviceGetMedia) always
// stream, because frames revalidate against the rendition's sha256 ETag.
func (s *Server) serveBlobOrRedirect(w http.ResponseWriter, r *http.Request, key, contentType, etag, cacheControl string) {
	if s.cfg.Blob.MediaRedirect != config.MediaRedirectPresigned {
		s.serveBlob(w, r, key, contentType, etag, cacheControl)
		return
	}
	if contentType == "" {
		contentType = blob.ContentType(key)
	}
	u, err := s.blob.PresignGet(r.Context(), key, presignTTL, contentType)
	if err != nil {
		s.log.Warn("presign blob URL, streaming instead", "key", key, "err", err)
		s.serveBlob(w, r, key, contentType, etag, cacheControl)
		return
	}
	// Cacheable for a fraction of the presigned URL's lifetime.
	w.Header().Set("Cache-Control", "private, max-age=300")
	http.Redirect(w, r, u, http.StatusFound)
}

// deleteBlob removes an object the database no longer references; failures
// only leave garbage behind, so they are logged.
func (s *Server) deleteBlob(ctx context.Context, key string) {
	if err := s.blob.Delete(context.WithoutCancel(ctx), key); err != nil {
		s.log.Warn("delete blob", "key", key, "err", err)
	}
}

// tempDir creates a scratch directory; the caller removes it.
func (s *Server) tempDir() (string, error) {
	dir := s.scratch
	if dir == "" {
		dir = os.TempDir()
	}
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return "", err
	}
	return os.MkdirTemp(dir, "req-*")
}

func (s *Server) deviceGetMedia(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	d := deviceFrom(ctx)
	pid, err := uuid.Parse(r.PathValue("placementId"))
	if err != nil || d.FrameID == nil {
		problem.Write(w, r, problem.New(problem.NotFound, ""))
		return
	}
	p, err := s.st.GetPlacementRow(ctx, s.st.Q(), pid, d.FrameID)
	if err != nil {
		s.writeError(w, r, notFound(err, "placement"))
		return
	}
	variant := r.URL.Query().Get("variant")
	if variant == "avatar" {
		if p.SenderAvatarKey == nil {
			problem.Write(w, r, problem.New(problem.NotFound, "sender has no avatar"))
			return
		}
		s.serveBlob(w, r, *p.SenderAvatarKey, "image/webp", "", "private, max-age=86400")
		return
	}
	if variant != "" && variant != "display" && variant != "poster" && variant != "motion" {
		problem.Write(w, r, problem.Validation("variant", "enum", "unknown variant").WithStatus(http.StatusBadRequest))
		return
	}
	mediaID, kind := p.MediaID, p.Kind
	if variant == "motion" {
		if !p.IsLive() {
			problem.Write(w, r, problem.New(problem.NotFound, "not available (yet)"))
			return
		}
		mediaID, kind = *p.MotionMediaID, string(media.Video)
	}
	rend, err := s.st.GetRendition(ctx, mediaID, worker.Variant(kind, deviceTarget(d)))
	if err == store.ErrNotFound {
		problem.Write(w, r, problem.New(problem.NotFound, "rendition not ready"))
		return
	}
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	if variant == "poster" {
		if rend.PosterKey == nil {
			problem.Write(w, r, problem.New(problem.NotFound, "no poster"))
			return
		}
		s.serveBlob(w, r, *rend.PosterKey, "image/webp", "", "private, max-age=86400")
		return
	}
	s.serveBlob(w, r, rend.Key, rend.Mime, `"`+rend.SHA256+`"`, "private, max-age=31536000, immutable")
}

// ---------------------------------------------------------------- acks, heartbeat

func (s *Server) DeviceAck(ctx context.Context, req gen.DeviceAckRequestObject) (gen.DeviceAckResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	var changes []store.StatusChange
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		changes = nil
		if req.Body.Delivered != nil && len(*req.Body.Delivered) > 0 {
			ch, err := tx.MarkDelivered(ctx, f.ID, *req.Body.Delivered)
			if err != nil {
				return err
			}
			changes = append(changes, ch...)
		}
		if req.Body.Seen != nil {
			for _, sn := range *req.Body.Seen {
				at := sn.At
				if at.After(time.Now().Add(5 * time.Minute)) {
					at = time.Now()
				}
				ch, err := tx.MarkSeen(ctx, f.ID, sn.PlacementId, at)
				if err != nil {
					return err
				}
				changes = append(changes, ch...)
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	s.placementsChanged(ctx, changes, s.deliveredPushes(ctx, f, changes))
	return noContent(), nil
}

func (s *Server) DeviceHeartbeat(ctx context.Context, req gen.DeviceHeartbeatRequestObject) (gen.DeviceHeartbeatResponseObject, error) {
	d := deviceFrom(ctx)
	raw, err := json.Marshal(req.Body)
	if err != nil {
		return nil, err
	}
	unclean := req.Body.LastShutdownClean != nil && !*req.Body.LastShutdownClean
	if err := s.st.RecordHeartbeat(ctx, d.ID, raw, req.Body.AppVersion, req.Body.AppVersionCode, unclean); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// ---------------------------------------------------------------- frame-side actions

func (s *Server) devicePlacement(ctx context.Context, id uuid.UUID) (*store.Frame, *store.PlacementRow, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, nil, err
	}
	p, err := s.st.GetPlacementRow(ctx, s.st.Q(), id, &f.ID)
	if err != nil {
		return nil, nil, notFound(err, "placement")
	}
	return f, p, nil
}

func (s *Server) DevicePatchPlacement(ctx context.Context, req gen.DevicePatchPlacementRequestObject) (gen.DevicePatchPlacementResponseObject, error) {
	f, _, err := s.devicePlacement(ctx, req.PlacementId)
	if err != nil {
		return nil, err
	}
	b := req.Body
	p := store.PlacementPatch{Hidden: b.Hidden, Trashed: b.Trashed}
	if b.Fit != nil {
		v := string(*b.Fit)
		p.FitOverride = &v
	}
	if b.FocalPoint != nil {
		x, y := float64(b.FocalPoint.X), float64(b.FocalPoint.Y)
		p.FocalOverrideX, p.FocalOverrideY = &x, &y
	}
	if b.Rotation != nil {
		v := int(*b.Rotation)
		p.Rotation = &v
	}
	if err := s.st.InTx(ctx, func(tx *store.Tx) error { return tx.UpdatePlacement(ctx, f.ID, req.PlacementId, p) }); err != nil {
		return nil, err
	}
	row, err := s.st.GetPlacementRow(ctx, s.st.Q(), req.PlacementId, &f.ID)
	if err != nil {
		return nil, err
	}
	return ok(s.placementJSON(row)), nil
}

func (s *Server) DeviceSetReaction(ctx context.Context, req gen.DeviceSetReactionRequestObject) (gen.DeviceSetReactionResponseObject, error) {
	f, p, err := s.devicePlacement(ctx, req.PlacementId)
	if err != nil {
		return nil, err
	}
	var reaction *string
	if req.Body.Reaction != nil {
		r := string(*req.Body.Reaction)
		reaction = &r
	}
	if err := s.st.InTx(ctx, func(tx *store.Tx) error { return tx.SetReaction(ctx, f.ID, p.Placement.ID, reaction) }); err != nil {
		return nil, err
	}
	if p.SenderUserID != nil {
		var push *notify.Push
		if reaction != nil {
			push = &notify.Push{Key: "reaction", Args: map[string]string{"frame": f.Name},
				IDs: placementIDs(p, f)}
		}
		s.notify.User(*p.SenderUserID, notify.Event{
			"type": "reaction", "placement_id": p.Placement.ID, "frame_id": f.ID, "reaction": reaction, "by_frame_name": f.Name,
		}, push)
		if p.SendID != nil {
			s.publishSendUpdates(ctx, map[uuid.UUID]uuid.UUID{*p.SendID: *p.SenderUserID}, "")
		}
	}
	return noContent(), nil
}

func (s *Server) DeviceSendReply(ctx context.Context, req gen.DeviceSendReplyRequestObject) (gen.DeviceSendReplyResponseObject, error) {
	f, p, err := s.devicePlacement(ctx, req.PlacementId)
	if err != nil {
		return nil, err
	}
	preset := string(req.Body.Preset)
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.AddReply(ctx, p.Placement.ID, preset); err != nil {
			return err
		}
		return tx.FeedPut(ctx, f.ID, store.FeedPlacement, p.Placement.ID, false)
	})
	if err != nil {
		return nil, err
	}
	if p.SenderUserID != nil {
		s.notify.User(*p.SenderUserID, notify.Event{"type": "reply", "placement_id": p.Placement.ID, "frame_id": f.ID, "preset": preset},
			&notify.Push{Key: "reply", Args: map[string]string{"frame": f.Name, "preset": preset},
				IDs: placementIDs(p, f)})
		if p.SendID != nil {
			s.publishSendUpdates(ctx, map[uuid.UUID]uuid.UUID{*p.SendID: *p.SenderUserID}, "")
		}
	}
	return noContent(), nil
}

func (s *Server) DeviceListJoinRequests(ctx context.Context, req gen.DeviceListJoinRequestsRequestObject) (gen.DeviceListJoinRequestsResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	jrs, err := s.st.ListPendingJoinRequests(ctx, f.ID)
	if err != nil {
		return nil, err
	}
	out := make([]gen.JoinRequest, len(jrs))
	for i := range jrs {
		out[i] = s.joinRequestJSON(&jrs[i])
	}
	return ok(out), nil
}

func (s *Server) DeviceDecideJoin(ctx context.Context, req gen.DeviceDecideJoinRequestObject) (gen.DeviceDecideJoinResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	if err := s.decideJoin(ctx, f.ID, req.RequestId, req.Body.Decision == gen.DeviceDecideJoinJSONBodyDecisionApprove); err != nil {
		return nil, err
	}
	return noContent(), nil
}

func (s *Server) DeviceRemoveMember(ctx context.Context, req gen.DeviceRemoveMemberRequestObject) (gen.DeviceRemoveMemberResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	var doc struct {
		Kiosk struct {
			Allow *bool `json:"allow_remove_members_on_frame"`
		} `json:"kiosk"`
	}
	_ = json.Unmarshal(f.Settings, &doc)
	if doc.Kiosk.Allow != nil && !*doc.Kiosk.Allow {
		return nil, problem.New(problem.Forbidden, "the owner disabled removing members on the frame")
	}
	m, err := s.st.GetMembership(ctx, s.st.Q(), f.ID, req.UserId)
	if err != nil {
		return nil, notFound(err, "member")
	}
	if m.Role == "owner" {
		return nil, problem.New(problem.Forbidden, "the owner cannot be removed")
	}
	removeItems := req.Params.RemoveItems != nil && *req.Params.RemoveItems
	if err := s.st.InTx(ctx, func(tx *store.Tx) error { return s.removeMember(ctx, tx, f.ID, req.UserId, removeItems) }); err != nil {
		return nil, err
	}
	s.notify.User(req.UserId, notify.Event{"type": "membership.updated", "frame_id": f.ID}, s.membershipPush(ctx, f.ID))
	return noContent(), nil
}

const maxImportBytes = 512 << 20

// DeviceImport accepts a file imported from SD/USB; the frame is the sender.
func (s *Server) DeviceImport(ctx context.Context, req gen.DeviceImportRequestObject) (gen.DeviceImportResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	path := filepath.Join(s.uploadDir, "import-"+store.NewID().String())
	out, err := os.Create(path)
	if err != nil {
		return nil, err
	}
	hasher := sha256.New()
	n, err := io.Copy(io.MultiWriter(out, hasher), io.LimitReader(req.Body, maxImportBytes+1))
	if cerr := out.Close(); err == nil {
		err = cerr
	}
	cleanup := func() { _ = os.Remove(path) }
	if err != nil {
		cleanup()
		return nil, err
	}
	if n > maxImportBytes {
		cleanup()
		return nil, problem.New(problem.UploadTooLarge, "imports are limited to 512 MiB")
	}
	sha := hex.EncodeToString(hasher.Sum(nil))
	// The same file again (a re-import, or a restore from a kinframe export of
	// this frame): return what the frame already has.
	if dup, err := s.st.PlacementOfFile(ctx, f.ID, sha); err == nil {
		cleanup()
		return ok(s.placementJSON(dup)), nil
	} else if err != store.ErrNotFound {
		cleanup()
		return nil, err
	}
	kind, mime, err := media.Sniff(path)
	if err != nil {
		cleanup()
		return nil, problem.New(problem.UnsupportedMedia, "")
	}
	var caption *string
	if c := req.Params.Caption; c != nil && strings.TrimSpace(*c) != "" {
		caption = ptr(strings.TrimSpace(*c))
	}
	mediaID, itemID, placementID := store.NewID(), store.NewID(), store.NewID()
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.CreateMedia(ctx, store.Media{ID: mediaID, Kind: string(kind), SourcePath: &path, SourceMime: &mime, IncludeAudio: true, SourceSHA256: &sha}); err != nil {
			return err
		}
		if err := tx.CreateItem(ctx, store.Item{ID: itemID, MediaID: mediaID, SenderFrameID: &f.ID, Caption: caption, CapturedHint: req.Params.XCapturedAt}); err != nil {
			return err
		}
		if err := tx.CreatePlacement(ctx, store.Placement{ID: placementID, FrameID: f.ID, ItemID: itemID, Status: "pending"}); err != nil {
			return err
		}
		return tx.Enqueue(ctx, store.JobProcessMedia, map[string]any{"media_id": mediaID})
	})
	if err != nil {
		cleanup()
		return nil, err
	}
	row, err := s.st.GetPlacementRow(ctx, s.st.Q(), placementID, &f.ID)
	if err != nil {
		return nil, err
	}
	return accepted(s.placementJSON(row)), nil
}

// DeviceImportCheck reports which file hashes the frame already has, so an
// import from SD/USB can skip duplicates before uploading them.
func (s *Server) DeviceImportCheck(ctx context.Context, req gen.DeviceImportCheckRequestObject) (gen.DeviceImportCheckResponseObject, error) {
	f, err := s.deviceFrame(ctx)
	if err != nil {
		return nil, err
	}
	known, err := s.st.KnownFileHashes(ctx, f.ID, req.Body.Sha256)
	if err != nil {
		return nil, err
	}
	return ok(map[string]any{"known": known}), nil
}

// placementIDs are the ids a push about a placement carries (README §6.3).
func placementIDs(p *store.PlacementRow, f *store.Frame) map[string]string {
	ids := map[string]string{"placement_id": p.Placement.ID.String(), "frame_id": f.ID.String()}
	if p.SendID != nil {
		ids["send_id"] = p.SendID.String()
	}
	return ids
}
