package api

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/photosource"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Photo sources (docs/spec/photo-sources.md, README §5.14): endpoints,
// permissions and the PhotoSource rendering. The sync itself is in
// photosync.go, Google imports in googleimport.go.

// photoSources holds the photo source machinery of a Server.
type photoSources struct {
	immich *photosource.Immich
	icloud *photosource.ICloud
	google *photosource.Google
	sched  *photosource.Scheduler

	confirmMu sync.Mutex
	confirm   map[uuid.UUID]bool // sync now with confirm_removals, consumed by the next run
}

// PhotoProviders returns the provider clients (tests point them at fakes).
func (s *Server) PhotoProviders() (*photosource.Immich, *photosource.ICloud, *photosource.Google) {
	return s.ps.immich, s.ps.icloud, s.ps.google
}

// PhotoScheduler returns the sync scheduler (tests wait on it).
func (s *Server) PhotoScheduler() *photosource.Scheduler { return s.ps.sched }

func (s *Server) providersEnabled() []string {
	if !s.cfg.PhotoSources {
		return nil
	}
	out := []string{photosource.ImmichLink, photosource.ImmichAPI}
	if s.cfg.ICloudSources {
		out = append(out, photosource.ICloudLink)
	}
	return out
}

func (s *Server) providerEnabled(p string) bool {
	for _, x := range s.providersEnabled() {
		if x == p {
			return true
		}
	}
	return false
}

func (s *Server) providerFor(p string) photosource.Provider {
	if p == photosource.ICloudLink {
		return s.ps.icloud
	}
	return s.ps.immich
}

func serviceOf(provider string) (service, label string) {
	if provider == photosource.ICloudLink {
		return "icloud", "iCloud"
	}
	return "immich", "Immich"
}

// viaJSON renders the credit of an item, send or post.
func viaJSON(via *string, sourceID *uuid.UUID) *gen.ItemVia {
	if via == nil {
		return nil
	}
	label := map[string]string{"immich": "Immich", "icloud": "iCloud", "google_photos": "Google Photos"}[*via]
	return &gen.ItemVia{Service: gen.PhotoService(*via), Label: label, SourceId: sourceID}
}

// ---------------------------------------------------------------- permissions

// sourceTarget is what a source points at, resolved for permission checks.
type sourceTarget struct {
	frameID *uuid.UUID
	albumID *uuid.UUID
	feedID  *uuid.UUID
}

// canManageTarget: album managers of the album's frame, feed owner/admins.
func (s *Server) canManageTarget(ctx context.Context, q db.DBTX, userID uuid.UUID, t sourceTarget) (bool, error) {
	switch {
	case t.albumID != nil && t.frameID != nil:
		m, err := s.st.GetMembership(ctx, q, *t.frameID, userID)
		if err == store.ErrNotFound {
			return false, nil
		}
		if err != nil {
			return false, err
		}
		return m.Effective().ManageLibrary, nil
	case t.feedID != nil:
		v, err := s.st.GetFeedView(ctx, q, userID, *t.feedID)
		if err == store.ErrNotFound {
			return false, nil
		}
		if err != nil {
			return false, err
		}
		return v.IsAdmin(), nil
	}
	return false, nil
}

func targetOf(p *store.PhotoSource) sourceTarget {
	return sourceTarget{frameID: p.FrameID, albumID: p.AlbumID, feedID: p.FeedID}
}

// visibleSource loads a source the caller may see (managers and the owner);
// others get 404. manage reports whether they may change it.
func (s *Server) visibleSource(ctx context.Context, id uuid.UUID) (*store.PhotoSourceView, bool, error) {
	v, err := s.st.GetPhotoSource(ctx, s.st.Q(), id)
	if err == store.ErrNotFound {
		return nil, false, problem.New(problem.NotFound, "photo source not found")
	}
	if err != nil {
		return nil, false, err
	}
	manage, err := s.canManageTarget(ctx, s.st.Q(), me(ctx).ID, targetOf(&v.PhotoSource))
	if err != nil {
		return nil, false, err
	}
	owner := v.OwnerUserID != nil && *v.OwnerUserID == me(ctx).ID
	if !manage && !owner {
		return nil, false, problem.New(problem.NotFound, "photo source not found")
	}
	return v, manage, nil
}

func (s *Server) managedSource(ctx context.Context, id uuid.UUID) (*store.PhotoSourceView, error) {
	v, manage, err := s.visibleSource(ctx, id)
	if err != nil {
		return nil, err
	}
	if !manage {
		return nil, problem.New(problem.MissingPermission, "only the album's or feed's managers can change this photo source")
	}
	return v, nil
}

// sourceAudience: the target's managers and the owner.
func (s *Server) sourceAudience(ctx context.Context, p *store.PhotoSource) []uuid.UUID {
	var ids []uuid.UUID
	switch {
	case p.FrameID != nil:
		ids, _ = s.st.LibraryManagerIDs(ctx, *p.FrameID)
	case p.FeedID != nil:
		ids, _ = s.st.FeedAdminIDs(ctx, s.st.Q(), *p.FeedID)
	}
	if p.OwnerUserID != nil {
		ids = appendUnique(ids, *p.OwnerUserID)
	}
	return ids
}

// ---------------------------------------------------------------- rendering

type runJSON struct {
	StartedAt  time.Time  `json:"started_at"`
	FinishedAt *time.Time `json:"finished_at,omitempty"`
	Added      int        `json:"added"`
	Updated    int        `json:"updated"`
	Removed    int        `json:"removed"`
	Skipped    int        `json:"skipped"`
	Failed     int        `json:"failed"`
	Bytes      int64      `json:"bytes"`
	ErrorCode  *string    `json:"error_code,omitempty"`
}

// farFuture is the next run of a source waiting for a person (shown as null).
var farFuture = time.Date(9999, 1, 1, 0, 0, 0, 0, time.UTC)

func (s *Server) renderSources(ctx context.Context, userID uuid.UUID, vs []store.PhotoSourceView) ([]gen.PhotoSource, error) {
	ids := make([]uuid.UUID, len(vs))
	for i := range vs {
		ids[i] = vs[i].ID
	}
	counts, err := s.st.PhotoSourceCounts(ctx, ids)
	if err != nil {
		return nil, err
	}
	out := make([]gen.PhotoSource, len(vs))
	manageCache := map[string]bool{}
	for i := range vs {
		v := &vs[i]
		key := ""
		if v.AlbumID != nil {
			key = "a" + v.FrameID.String()
		} else if v.FeedID != nil {
			key = "f" + v.FeedID.String()
		}
		manage, ok := manageCache[key]
		if !ok {
			manage, err = s.canManageTarget(ctx, s.st.Q(), userID, targetOf(&v.PhotoSource))
			if err != nil {
				return nil, err
			}
			manageCache[key] = manage
		}
		out[i] = s.sourceJSON(v, counts[v.ID], userID, manage)
	}
	return out, nil
}

func (s *Server) renderSource(ctx context.Context, userID, id uuid.UUID) (*gen.PhotoSource, error) {
	v, err := s.st.GetPhotoSource(ctx, s.st.Q(), id)
	if err != nil {
		return nil, err
	}
	out, err := s.renderSources(ctx, userID, []store.PhotoSourceView{*v})
	if err != nil {
		return nil, err
	}
	return &out[0], nil
}

func (s *Server) sourceJSON(v *store.PhotoSourceView, c store.SourceCounts, userID uuid.UUID, manage bool) gen.PhotoSource {
	isOwner := v.OwnerUserID != nil && *v.OwnerUserID == userID
	out := gen.PhotoSource{
		Id: v.ID, Provider: gen.PhotoSourceProvider(v.Provider), Name: v.Name,
		Status: gen.PhotoSourceStatus(v.Status), Running: v.RunStartedAt != nil && v.Status != "paused" || s.ps.sched.Running(v.ID),
		History: gen.PhotoSourceHistory(v.History), HistoryCount: v.HistoryCount,
		OnUpstreamDelete: gen.PhotoSourceDeletePolicy(v.OnUpstreamDelete), IncludeVideos: v.IncludeVideos,
		NotifyReactions: ptr(v.NotifyReactions), BestEffort: ptr(v.Provider == photosource.ICloudLink),
		HasApiKey: ptr(len(v.APIKeyEnc) > 0), HasPassword: ptr(len(v.PasswordEnc) > 0),
		PendingRemovals: ptr(v.PendingRemovals), ErrorSince: v.ErrorSince,
		LastSyncedAt: v.LastSyncedAt, LastAttemptAt: v.LastAttemptAt,
		CanManage: ptr(manage), IsOwner: ptr(isOwner), CreatedAt: v.CreatedAt,
		Counts: gen.PhotoSourceCounts{Imported: c.Imported, Pending: c.Pending, Duplicates: ptr(c.Duplicates), Skipped: ptr(c.Skipped),
			Failed: ptr(c.Failed), RemovedLocally: ptr(c.RemovedLocally), GoneUpstream: ptr(c.GoneUpstream), Upstream: v.UpstreamCount},
	}
	warnings := make([]gen.PhotoSourceWarning, len(v.Warnings))
	for i, w := range v.Warnings {
		warnings[i] = gen.PhotoSourceWarning(w)
	}
	out.Warnings = &warnings
	if v.ErrorCode != nil {
		out.LastErrorCode = ptr(gen.PhotoSourceErrorCode(*v.ErrorCode))
	}
	if manage || isOwner {
		out.UrlHint, out.ServerUrl, out.ImmichAlbumId, out.LastError = v.URLHint, v.ServerURL, v.UpstreamAlbumID, v.ErrorDetail
		if v.Provider != photosource.ImmichAPI {
			out.ImmichAlbumId = nil
		}
	}
	if v.Status != "paused" && v.NextSyncAt.Before(farFuture.Add(-24*time.Hour)) {
		out.NextSyncAt = ptr(v.NextSyncAt)
	}
	switch {
	case v.AlbumID != nil:
		out.Target = gen.PhotoSourceTarget{Kind: gen.PhotoSourceTargetKindAlbum, FrameId: v.FrameID, FrameName: v.FrameName, AlbumId: v.AlbumID, AlbumName: v.AlbumName}
	default:
		out.Target = gen.PhotoSourceTarget{Kind: gen.PhotoSourceTargetKindFeed, FeedId: v.FeedID, FeedName: v.FeedName}
	}
	if v.OwnerUserID != nil && !v.OwnerDeleted && v.OwnerName != nil {
		out.Owner = &struct {
			AvatarUrl   *string `json:"avatar_url,omitempty"`
			DisplayName string  `json:"display_name"`
			UserId      gen.Id  `json:"user_id"`
		}{AvatarUrl: s.avatarURL(v.OwnerAvatarKey), DisplayName: *v.OwnerName, UserId: *v.OwnerUserID}
	}
	if len(v.LastRun) > 0 && string(v.LastRun) != "null" {
		var r runJSON
		if json.Unmarshal(v.LastRun, &r) == nil {
			lr := gen.PhotoSourceRun{StartedAt: r.StartedAt, FinishedAt: r.FinishedAt, Added: ptr(r.Added), Updated: ptr(r.Updated),
				Removed: ptr(r.Removed), Skipped: ptr(r.Skipped), Failed: ptr(r.Failed), Bytes: ptr(int(r.Bytes))}
			if r.ErrorCode != nil {
				lr.ErrorCode = ptr(gen.PhotoSourceErrorCode(*r.ErrorCode))
			}
			out.LastRun = &lr
		}
	}
	return out
}

// publishSource sends photo_source.updated to the target's managers and the owner.
func (s *Server) publishSource(ctx context.Context, id uuid.UUID, push map[uuid.UUID]*notify.Push) {
	v, err := s.st.GetPhotoSource(ctx, s.st.Q(), id)
	if err != nil {
		return
	}
	for _, u := range s.sourceAudience(ctx, &v.PhotoSource) {
		src, err := s.renderSource(ctx, u, id)
		if err != nil {
			s.log.Warn("render photo source", "source", id, "err", err)
			return
		}
		s.notify.User(u, notify.Event{"type": "photo_source.updated", "source": src}, push[u])
	}
}

// publishSourceRemoved sends photo_source.removed to the given audience.
func (s *Server) publishSourceRemoved(p *store.PhotoSource, audience []uuid.UUID) {
	ev := notify.Event{"type": "photo_source.removed", "source_id": p.ID}
	if p.AlbumID != nil {
		ev["album_id"] = *p.AlbumID
	}
	if p.FeedID != nil {
		ev["feed_id"] = *p.FeedID
	}
	s.notify.Users(audience, ev, nil)
}

// ---------------------------------------------------------------- lists

func (s *Server) ListAlbumPhotoSources(ctx context.Context, req gen.ListAlbumPhotoSourcesRequestObject) (gen.ListAlbumPhotoSourcesResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	if _, err := s.st.GetAlbum(ctx, s.st.Q(), req.FrameId, req.AlbumId); err != nil {
		return nil, notFound(err, "album")
	}
	if err := requirePerm(m, m.Effective().ManageLibrary, "manage_library"); err != nil {
		return nil, err
	}
	vs, err := s.st.ListPhotoSources(ctx, &req.AlbumId, nil, nil)
	if err != nil {
		return nil, err
	}
	out, err := s.renderSources(ctx, me(ctx).ID, vs)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

func (s *Server) ListFeedPhotoSources(ctx context.Context, req gen.ListFeedPhotoSourcesRequestObject) (gen.ListFeedPhotoSourcesResponseObject, error) {
	v, err := s.feedView(ctx, s.st.Q(), req.FeedId)
	if err != nil {
		return nil, err
	}
	if !v.IsAdmin() {
		return nil, problem.New(problem.MissingPermission, "only the feed's owner and admins can see its photo sources")
	}
	vs, err := s.st.ListPhotoSources(ctx, nil, &req.FeedId, nil)
	if err != nil {
		return nil, err
	}
	out, err := s.renderSources(ctx, me(ctx).ID, vs)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

func (s *Server) ListMyPhotoSources(ctx context.Context, _ gen.ListMyPhotoSourcesRequestObject) (gen.ListMyPhotoSourcesResponseObject, error) {
	u := me(ctx).ID
	vs, err := s.st.ListPhotoSources(ctx, nil, nil, &u)
	if err != nil {
		return nil, err
	}
	out, err := s.renderSources(ctx, u, vs)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

func (s *Server) GetPhotoSource(ctx context.Context, req gen.GetPhotoSourceRequestObject) (gen.GetPhotoSourceResponseObject, error) {
	if _, _, err := s.visibleSource(ctx, req.SourceId); err != nil {
		return nil, err
	}
	out, err := s.renderSource(ctx, me(ctx).ID, req.SourceId)
	if err != nil {
		return nil, err
	}
	return ok(out), nil
}

// ---------------------------------------------------------------- connections

// connInput is a connection as entered (create, test, update).
type connInput struct {
	provider  string
	url       string
	serverURL string
	apiKey    string
	password  string
	albumID   string
}

// checked is a connection that passed validation: what to store and test.
type checked struct {
	conn     photosource.Conn
	host     string // upstream host
	urlHint  *string
	server   *string // immich_api display URL
	linkKey  string  // link key/token for the duplicate check
	urlField string  // field name for errors
}

// validateConn checks the fields a provider needs and the network guard.
func (s *Server) validateConn(ctx context.Context, in connInput) (*checked, error) {
	if !s.cfg.PhotoSources {
		return nil, problem.New(problem.FeatureDisabled, "photo sources are turned off on this server")
	}
	if !s.providerEnabled(in.provider) {
		return nil, problem.New(problem.FeatureDisabled, "this kind of photo source is turned off on this server")
	}
	c := &checked{conn: photosource.Conn{Provider: in.provider, Password: in.password, APIKey: in.apiKey, AlbumID: in.albumID}}
	switch in.provider {
	case photosource.ImmichLink, photosource.ICloudLink:
		c.urlField = "url"
		if in.url == "" {
			return nil, problem.Validation("url", "required", "the link is required")
		}
		pl, err := photosource.ParseLink(in.provider, in.url)
		if err != nil {
			return nil, problem.New(problem.SourceTestFailed, "%s", photosource.CodeUnsupportedLink).WithField("url", photosource.CodeUnsupportedLink)
		}
		c.conn.URL, c.host, c.linkKey = strings.TrimSpace(in.url), pl.Host, pl.Key+pl.Slug
		c.urlHint = ptr(photosource.URLHint(in.url))
		if in.provider == photosource.ICloudLink {
			c.conn.Password, c.conn.APIKey = "", ""
		}
	case photosource.ImmichAPI:
		c.urlField = "server_url"
		if in.serverURL == "" {
			return nil, problem.Validation("server_url", "required", "the server address is required")
		}
		if in.apiKey == "" {
			return nil, problem.Validation("api_key", "required", "the API key is required")
		}
		_, display, host, err := photosource.ImmichServer(in.serverURL)
		if err != nil {
			return nil, problem.Validation("server_url", "format", "the server address is not valid")
		}
		c.conn.URL, c.host, c.server, c.conn.Password = display, host, &display, ""
	default:
		return nil, problem.Validation("provider", "enum", "unknown provider")
	}
	if in.provider != photosource.ICloudLink {
		if err := s.guard.Check(ctx, c.conn.URL); err != nil {
			var pe *photosource.Error
			if errors.As(photosource.AsError(err), &pe) && pe.Code == photosource.CodePrivateBlocked {
				return nil, problem.Validation(c.urlField, photosource.CodePrivateBlocked, "this server may not reach that address")
			}
			// Unresolvable hosts fail the test itself (unreachable).
		}
	}
	return c, nil
}

// testList reads the outside album; private addresses found at dial time
// are a validation error like the pre-check.
func (s *Server) testList(ctx context.Context, c *checked) (*photosource.Listing, *photosource.Error) {
	l, err := s.providerFor(c.conn.Provider).List(ctx, c.conn)
	if err != nil {
		return nil, photosource.AsError(err)
	}
	return l, nil
}

func (s *Server) limitTests(ctx context.Context) error {
	if allowed, retry := s.sourceTestUser.Allow(me(ctx).ID.String()); !allowed {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return p
	}
	return nil
}

func testResult(l *photosource.Listing, provider string) gen.PhotoSourceTestResult {
	photos, videos, bytes := l.Counts()
	w := make([]gen.PhotoSourceWarning, len(l.Warnings))
	for i, x := range l.Warnings {
		w[i] = gen.PhotoSourceWarning(x)
	}
	out := gen.PhotoSourceTestResult{Ok: true, AlbumName: ptr(l.Name), Photos: ptr(photos), Videos: ptr(videos), Warnings: &w,
		BestEffort: ptr(provider == photosource.ICloudLink)}
	if bytes > 0 {
		out.EstimateBytes = ptr(int(bytes))
	}
	if l.ServerVersion != "" {
		out.ServerVersion = ptr(l.ServerVersion)
	}
	return out
}

// testFailure turns validation problems that are about the connection
// (unsupported link, blocked address) into a test outcome: tests answer 200.
func testFailure(err error) *photosource.Error {
	var p *problem.Problem
	if !errors.As(err, &p) {
		return nil
	}
	switch {
	case p.Code == problem.SourceTestFailed:
		return &photosource.Error{Code: photosource.CodeUnsupportedLink, Detail: "this link is not supported"}
	case len(p.Errors) > 0 && p.Errors[0].Code == photosource.CodePrivateBlocked:
		return &photosource.Error{Code: photosource.CodePrivateBlocked, Detail: "this server may not reach that address"}
	}
	return nil
}

func failedTest(e *photosource.Error, provider string) gen.PhotoSourceTestResult {
	return gen.PhotoSourceTestResult{Ok: false, ErrorCode: ptr(gen.PhotoSourceErrorCode(e.Code)), Error: ptr(e.Detail),
		BestEffort: ptr(provider == photosource.ICloudLink), Warnings: &[]gen.PhotoSourceWarning{}}
}

func immichAlbumsJSON(list []photosource.ImmichAlbumInfo) []gen.ImmichAlbum {
	out := make([]gen.ImmichAlbum, len(list))
	for i, a := range list {
		out[i] = gen.ImmichAlbum{Id: a.ID, Name: a.Name, AssetCount: a.AssetCount, UpdatedAt: a.UpdatedAt, Shared: ptr(a.Shared)}
		if a.OwnerName != "" {
			out[i].OwnerName = ptr(a.OwnerName)
		}
	}
	return out
}

func (s *Server) TestPhotoSource(ctx context.Context, req gen.TestPhotoSourceRequestObject) (gen.TestPhotoSourceResponseObject, error) {
	b := req.Body
	c, err := s.validateConn(ctx, connInput{provider: string(b.Provider), url: trimmed(b.Url), serverURL: trimmed(b.ServerUrl),
		apiKey: trimmed(b.ApiKey), password: derefStr(b.Password), albumID: trimmed(b.ImmichAlbumId)})
	if err != nil {
		if e := testFailure(err); e != nil {
			return ok(failedTest(e, string(b.Provider))), nil
		}
		return nil, err
	}
	if err := s.limitTests(ctx); err != nil {
		return nil, err
	}
	if c.conn.Provider == photosource.ImmichAPI && c.conn.AlbumID == "" {
		albums, ver, err := s.ps.immich.Albums(ctx, c.conn.URL, c.conn.APIKey)
		if err != nil {
			return ok(failedTest(photosource.AsError(err), c.conn.Provider)), nil
		}
		js := immichAlbumsJSON(albums)
		out := gen.PhotoSourceTestResult{Ok: true, Albums: &js, Warnings: &[]gen.PhotoSourceWarning{}, BestEffort: ptr(false)}
		if ver != "" {
			out.ServerVersion = ptr(ver)
		}
		return ok(out), nil
	}
	l, perr := s.testList(ctx, c)
	if perr != nil {
		return ok(failedTest(perr, c.conn.Provider)), nil
	}
	return ok(testResult(l, c.conn.Provider)), nil
}

func (s *Server) ListImmichAlbums(ctx context.Context, req gen.ListImmichAlbumsRequestObject) (gen.ListImmichAlbumsResponseObject, error) {
	c, err := s.validateConn(ctx, connInput{provider: photosource.ImmichAPI, serverURL: strings.TrimSpace(req.Body.ServerUrl), apiKey: trimmed(req.Body.ApiKey)})
	if e := testFailure(err); e != nil {
		return ok(gen.ImmichAlbumList{Ok: false, ErrorCode: ptr(gen.PhotoSourceErrorCode(e.Code)), Error: ptr(e.Detail), Albums: []gen.ImmichAlbum{}}), nil
	}
	if err != nil {
		return nil, err
	}
	if err := s.limitTests(ctx); err != nil {
		return nil, err
	}
	albums, ver, aerr := s.ps.immich.Albums(ctx, c.conn.URL, c.conn.APIKey)
	out := gen.ImmichAlbumList{Ok: aerr == nil, Albums: immichAlbumsJSON(albums)}
	if ver != "" {
		out.ServerVersion = ptr(ver)
	}
	if aerr != nil {
		e := photosource.AsError(aerr)
		out.ErrorCode, out.Error = ptr(gen.PhotoSourceErrorCode(e.Code)), ptr(e.Detail)
		out.Albums = []gen.ImmichAlbum{}
	}
	return ok(out), nil
}

func derefStr(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

// upstreamKey of a tested connection: the outside album's identity.
func upstreamKey(c *checked, l *photosource.Listing) string {
	id := c.linkKey
	if l.AlbumID != "" && c.conn.Provider != photosource.ICloudLink {
		id = "album:" + l.AlbumID
	}
	return photosource.UpstreamKey(c.conn.Provider, c.host, id)
}

// ---------------------------------------------------------------- create

type sourceRef struct {
	SourceID uuid.UUID `json:"source_id"`
}

func (s *Server) CreatePhotoSource(ctx context.Context, req gen.CreatePhotoSourceRequestObject) (gen.CreatePhotoSourceResponseObject, error) {
	u := me(ctx)
	b := req.Body
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev sourceRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "photo_source", key, &prev); err != nil {
			return nil, err
		} else if found {
			out, err := s.renderSource(ctx, u.ID, prev.SourceID)
			if err != nil {
				return nil, err
			}
			return created(out), nil
		}
	}
	if (b.AlbumId == nil) == (b.FeedId == nil) {
		return nil, problem.Validation("album_id", "oneOf", "choose exactly one of album_id or feed_id")
	}
	c, err := s.validateConn(ctx, connInput{provider: string(b.Provider), url: trimmed(b.Url), serverURL: trimmed(b.ServerUrl),
		apiKey: trimmed(b.ApiKey), password: derefStr(b.Password), albumID: trimmed(b.ImmichAlbumId)})
	if err != nil {
		return nil, err
	}
	if c.conn.Provider == photosource.ImmichAPI && c.conn.AlbumID == "" {
		return nil, problem.Validation("immich_album_id", "required", "choose an Immich album")
	}
	// Target and permission.
	t := sourceTarget{albumID: b.AlbumId, feedID: b.FeedId}
	isFeed := b.FeedId != nil
	if b.AlbumId != nil {
		a, err := s.st.AlbumByID(ctx, s.st.Q(), *b.AlbumId)
		if err != nil {
			return nil, notFound(err, "album")
		}
		m, err := s.membership(ctx, a.FrameID)
		if err != nil {
			return nil, problem.New(problem.NotFound, "album not found")
		}
		if err := requirePerm(m, m.Effective().ManageLibrary, "manage_library"); err != nil {
			return nil, err
		}
		t.frameID = &a.FrameID
	} else {
		v, err := s.feedView(ctx, s.st.Q(), *b.FeedId)
		if err != nil {
			return nil, err
		}
		if !v.IsAdmin() {
			return nil, problem.New(problem.MissingPermission, "only the feed's owner and admins can add photo sources")
		}
	}
	limits := s.Limits(ctx)
	nTarget, nOwned, err := s.st.CountPhotoSources(ctx, s.st.Q(), b.AlbumId, b.FeedId, u.ID)
	if err != nil {
		return nil, err
	}
	if nTarget >= limits.MaxPhotoSourcesPerTarget {
		return nil, problem.New(problem.LimitReached, "at most %d photo sources per album or feed", limits.MaxPhotoSourcesPerTarget)
	}
	if nOwned >= limits.MaxPhotoSourcesPerUser {
		return nil, problem.New(problem.LimitReached, "you can set up at most %d photo sources", limits.MaxPhotoSourcesPerUser)
	}
	// Settings.
	history, count := "all", (*int)(nil)
	policy := "mirror"
	if isFeed {
		history, count, policy = "last_n", ptr(20), "keep"
	}
	if b.History != nil {
		history = string(*b.History)
		count = nil
	}
	if b.HistoryCount != nil {
		count = b.HistoryCount
	}
	if history == "last_n" && count == nil {
		return nil, problem.Validation("history_count", "required", "history_count is required with last_n")
	}
	if history != "last_n" {
		count = nil
	}
	if b.OnUpstreamDelete != nil {
		policy = string(*b.OnUpstreamDelete)
	}
	if b.Name != nil && (strings.TrimSpace(*b.Name) == "" || utf8.RuneCountInString(*b.Name) > 60) {
		return nil, problem.Validation("name", "length", "a name has 1 to 60 characters")
	}
	if allowed, retry := s.sourceCreateUser.Allow(u.ID.String()); !allowed {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	l, perr := s.testList(ctx, c)
	if perr != nil {
		if perr.Code == photosource.CodePrivateBlocked {
			return nil, problem.Validation(c.urlField, photosource.CodePrivateBlocked, "this server may not reach that address")
		}
		return nil, problem.New(problem.SourceTestFailed, "%s", perr.Code)
	}
	upKey := upstreamKey(c, l)
	targetID := derefID(b.AlbumId, b.FeedId)
	if dup, err := s.st.SourceWithKey(ctx, s.st.Q(), targetID, upKey, nil); err != nil {
		return nil, err
	} else if dup != nil {
		return nil, problem.New(problem.DuplicateSource, "%s", dup.String())
	}
	name := strings.TrimSpace(l.Name)
	if b.Name != nil {
		name = strings.TrimSpace(*b.Name)
	}
	if name == "" {
		_, name = serviceOf(c.conn.Provider)
	}
	if r := []rune(name); len(r) > 60 {
		name = string(r[:60])
	}
	src := store.PhotoSource{
		ID: store.NewID(), Provider: c.conn.Provider, OwnerUserID: &u.ID, FrameID: t.frameID, AlbumID: b.AlbumId, FeedID: b.FeedId,
		Name: name, URLEnc: s.psSealer.Seal(c.conn.URL), URLHint: c.urlHint, ServerURL: c.server,
		APIKeyEnc: s.psSealer.Seal(c.conn.APIKey), PasswordEnc: s.psSealer.Seal(c.conn.Password),
		UpstreamKey: upKey, UpstreamHost: c.host, History: history, HistoryCount: count, OnUpstreamDelete: policy,
		IncludeVideos: b.IncludeVideos == nil || *b.IncludeVideos, NotifyReactions: b.NotifyReactions == nil || *b.NotifyReactions,
		Warnings: l.Warnings, NextSyncAt: s.now(),
	}
	if c.conn.Provider == photosource.ImmichAPI {
		src.UpstreamAlbumID = &c.conn.AlbumID
	}
	if l.ResolvedHost != "" {
		src.ResolvedHost = &l.ResolvedHost
	}
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.CreatePhotoSource(ctx, src); err != nil {
			return err
		}
		if key != "" {
			return tx.SaveIdempotent(ctx, u.ID, "photo_source", key, sourceRef{src.ID})
		}
		return nil
	})
	if db.IsUniqueViolation(err, "photo_sources_upstream_unique") {
		if dup, _ := s.st.SourceWithKey(ctx, s.st.Q(), targetID, upKey, nil); dup != nil {
			return nil, problem.New(problem.DuplicateSource, "%s", dup.String())
		}
	}
	if err != nil {
		return nil, err
	}
	s.kickSource(src.ID, src.UpstreamHost)
	out, err := s.renderSource(ctx, u.ID, src.ID)
	if err != nil {
		return nil, err
	}
	out.Running = true
	return created(out), nil
}

func derefID(a, b *uuid.UUID) uuid.UUID {
	if a != nil {
		return *a
	}
	return *b
}

func (s *Server) kickSource(id uuid.UUID, host string) {
	s.ps.sched.Kick(id, host)
}

// ---------------------------------------------------------------- update

func (s *Server) UpdatePhotoSource(ctx context.Context, req gen.UpdatePhotoSourceRequestObject) (gen.UpdatePhotoSourceResponseObject, error) {
	u := me(ctx)
	v, err := s.managedSource(ctx, req.SourceId)
	if err != nil {
		return nil, err
	}
	b := req.Body
	src := v.PhotoSource
	isOwner := src.OwnerUserID != nil && *src.OwnerUserID == u.ID
	takeOver := b.TakeOver != nil && *b.TakeOver && !isOwner
	if b.NotifyReactions != nil && *b.NotifyReactions != src.NotifyReactions && !isOwner && !takeOver {
		return nil, problem.New(problem.Forbidden, "only the source's owner can change its reaction notifications")
	}
	if b.Name != nil {
		n := strings.TrimSpace(*b.Name)
		if n == "" || utf8.RuneCountInString(n) > 60 {
			return nil, problem.Validation("name", "length", "a name has 1 to 60 characters")
		}
		src.Name = n
	}
	if b.OnUpstreamDelete != nil {
		src.OnUpstreamDelete = string(*b.OnUpstreamDelete)
		if src.OnUpstreamDelete == "keep" && src.ErrorCode != nil && *src.ErrorCode == photosource.CodeMassRemoval {
			// Switching to keep answers the mass-removal question: keep them.
			src.ErrorCode, src.ErrorDetail, src.PendingRemovals = nil, nil, 0
			src.Status, src.NextSyncAt = "active", s.now()
		}
	}
	videosOn := b.IncludeVideos != nil && *b.IncludeVideos && !src.IncludeVideos
	if b.IncludeVideos != nil {
		src.IncludeVideos = *b.IncludeVideos
	}
	if b.NotifyReactions != nil {
		src.NotifyReactions = *b.NotifyReactions
	}
	// Credentials as stored.
	curURL, err1 := s.psSealer.Open(src.URLEnc)
	curKey, err2 := s.psSealer.Open(src.APIKeyEnc)
	curPass, err3 := s.psSealer.Open(src.PasswordEnc)
	unreadable := err1 != nil || err2 != nil || err3 != nil
	in := connInput{provider: src.Provider, url: curURL, apiKey: curKey, password: curPass, albumID: derefStr(src.UpstreamAlbumID)}
	if src.Provider == photosource.ImmichAPI {
		in.serverURL = curURL
	}
	connChanged := false
	if b.Url != nil && src.Provider != photosource.ImmichAPI {
		in.url, connChanged = strings.TrimSpace(*b.Url), true
	}
	if b.ServerUrl != nil && src.Provider == photosource.ImmichAPI {
		in.serverURL, connChanged = strings.TrimSpace(*b.ServerUrl), true
	}
	if b.ApiKey != nil && src.Provider == photosource.ImmichAPI {
		in.apiKey, connChanged = strings.TrimSpace(*b.ApiKey), true
	}
	if present, null := rawField(ctx, "password"); present && src.Provider == photosource.ImmichLink {
		connChanged = true
		in.password = ""
		if !null && b.Password != nil {
			in.password = *b.Password
		}
	}
	if b.ImmichAlbumId != nil && src.Provider == photosource.ImmichAPI && *b.ImmichAlbumId != in.albumID {
		in.albumID, connChanged = strings.TrimSpace(*b.ImmichAlbumId), true
	}
	if takeOver && src.Provider == photosource.ImmichAPI && b.ApiKey == nil {
		return nil, problem.Validation("api_key", "required", "taking over an API key source needs your own API key")
	}
	if unreadable && !connChanged {
		// Nothing to test with; settings changes are still fine.
		connChanged = false
	}
	kick := false
	if connChanged {
		c, err := s.validateConn(ctx, in)
		if err != nil {
			return nil, err
		}
		l, perr := s.testList(ctx, c)
		if perr != nil {
			if perr.Code == photosource.CodePrivateBlocked {
				return nil, problem.Validation(c.urlField, photosource.CodePrivateBlocked, "this server may not reach that address")
			}
			return nil, problem.New(problem.SourceTestFailed, "%s", perr.Code)
		}
		upKey := upstreamKey(c, l)
		if dup, err := s.st.SourceWithKey(ctx, s.st.Q(), derefID(src.AlbumID, src.FeedID), upKey, &src.ID); err != nil {
			return nil, err
		} else if dup != nil {
			return nil, problem.New(problem.DuplicateSource, "%s", dup.String())
		}
		src.URLEnc, src.URLHint, src.ServerURL = s.psSealer.Seal(c.conn.URL), c.urlHint, c.server
		src.APIKeyEnc, src.PasswordEnc = s.psSealer.Seal(c.conn.APIKey), s.psSealer.Seal(c.conn.Password)
		src.UpstreamKey, src.UpstreamHost, src.Warnings = upKey, c.host, l.Warnings
		src.ResolvedHost = nil
		if l.ResolvedHost != "" {
			src.ResolvedHost = &l.ResolvedHost
		}
		if c.conn.Provider == photosource.ImmichAPI {
			src.UpstreamAlbumID = &c.conn.AlbumID
		}
		src.ChangeHint = nil
		if src.Status == "error" || src.Status == "needs_attention" {
			src.Status = "active"
		}
		if src.ErrorCode != nil && *src.ErrorCode == photosource.CodeMassRemoval {
			src.PendingRemovals = 0 // replacing a wrong link keeps the photos
		}
		src.ErrorCode, src.ErrorDetail, src.ErrorSince, src.Failures = nil, nil, nil, 0
		kick = true
	}
	if takeOver {
		src.OwnerUserID = &u.ID
		if src.ErrorCode != nil && *src.ErrorCode == photosource.CodeOwnerLostAccess {
			src.ErrorCode, src.ErrorDetail, src.ErrorSince, src.Failures = nil, nil, nil, 0
			if src.Status != "paused" {
				src.Status = "active"
			}
		}
		kick = true
	}
	if b.Paused != nil {
		switch {
		case *b.Paused && src.Status != "paused":
			src.Status = "paused"
		case !*b.Paused && src.Status == "paused":
			src.Status = "active"
			if src.ErrorCode != nil && !photosource.Transient(*src.ErrorCode) {
				src.Status = "needs_attention"
			} else {
				kick = true
			}
		}
	}
	if videosOn {
		kick = true // previously skipped videos come in the next run
	}
	if kick && src.Status != "paused" {
		src.NextSyncAt = s.now()
	}
	if err := s.st.InTx(ctx, func(tx *store.Tx) error {
		if videosOn {
			if _, err := tx.Exec(ctx, `UPDATE photo_source_items SET state = 'pending', skip_reason = NULL
				WHERE source_id = $1 AND state = 'skipped' AND skip_reason = 'video_off'`, src.ID); err != nil {
				return err
			}
		}
		return tx.SavePhotoSource(ctx, &src)
	}); err != nil {
		return nil, err
	}
	if kick && src.Status != "paused" {
		s.kickSource(src.ID, src.UpstreamHost)
	}
	s.publishSource(ctx, src.ID, nil)
	out, err := s.renderSource(ctx, u.ID, src.ID)
	if err != nil {
		return nil, err
	}
	if kick && src.Status != "paused" {
		out.Running = true
	}
	return ok(out), nil
}

// ---------------------------------------------------------------- sync now

func (s *Server) SyncPhotoSource(ctx context.Context, req gen.SyncPhotoSourceRequestObject) (gen.SyncPhotoSourceResponseObject, error) {
	u := me(ctx)
	v, err := s.managedSource(ctx, req.SourceId)
	if err != nil {
		return nil, err
	}
	if v.Status == "paused" {
		return nil, problem.New(problem.InvalidTransition, "resume the photo source first")
	}
	now := s.now()
	if v.LastManualSyncAt != nil && now.Sub(*v.LastManualSyncAt) < photosource.ManualEvery {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = photosource.ManualEvery - now.Sub(*v.LastManualSyncAt)
		return nil, p
	}
	if allowed, retry := s.sourceSyncUser.Allow(u.ID.String()); !allowed {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	confirm := req.Body != nil && req.Body.ConfirmRemovals != nil && *req.Body.ConfirmRemovals
	if confirm {
		s.ps.confirmMu.Lock()
		s.ps.confirm[v.ID] = true
		s.ps.confirmMu.Unlock()
	}
	if _, err := s.st.Pool.Exec(ctx, `UPDATE photo_sources SET last_manual_sync_at = $2, next_sync_at = $2 WHERE id = $1`, v.ID, now); err != nil {
		return nil, err
	}
	s.kickSource(v.ID, v.UpstreamHost)
	out, err := s.renderSource(ctx, u.ID, v.ID)
	if err != nil {
		return nil, err
	}
	out.Running = true
	return accepted(out), nil
}

func (s *Server) takeConfirm(id uuid.UUID) bool {
	s.ps.confirmMu.Lock()
	defer s.ps.confirmMu.Unlock()
	c := s.ps.confirm[id]
	delete(s.ps.confirm, id)
	return c
}

// ---------------------------------------------------------------- delete

func (s *Server) DeletePhotoSource(ctx context.Context, req gen.DeletePhotoSourceRequestObject) (gen.DeletePhotoSourceResponseObject, error) {
	v, err := s.managedSource(ctx, req.SourceId)
	if err != nil {
		return nil, err
	}
	audience := s.sourceAudience(ctx, &v.PhotoSource)
	var sends map[uuid.UUID]uuid.UUID
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		if req.Params.RemoveItems {
			var err error
			if sends, err = s.removeSourceItems(ctx, tx, &v.PhotoSource, nil); err != nil {
				return err
			}
		}
		return tx.DeletePhotoSource(ctx, v.ID)
	})
	if err != nil {
		return nil, err
	}
	s.publishSourceRemoved(&v.PhotoSource, audience)
	s.publishSendUpdates(ctx, sends, "")
	return noContent(), nil
}

// removeSourceItems removes what a source imported, like an unsend: its
// placements on the album's frame (duplicates only leave the album), or its
// items from the feed's posts. only limits it to some upstream ids. Returns
// the sends whose senders should hear about it.
func (s *Server) removeSourceItems(ctx context.Context, tx *store.Tx, p *store.PhotoSource, only map[string]bool) (map[uuid.UUID]uuid.UUID, error) {
	rows, err := s.st.SourceItems(ctx, tx, p.ID)
	if err != nil {
		return nil, err
	}
	sends := map[uuid.UUID]uuid.UUID{}
	var sweeps []uuid.UUID
	if p.FrameID != nil {
		if _, err := tx.LockFrame(ctx, *p.FrameID); err != nil && err != store.ErrNotFound {
			return nil, err
		}
	}
	for i := range rows {
		r := &rows[i]
		if r.ItemID == nil || (r.State != "imported" && r.State != "duplicate" && r.State != "gone_upstream") || (only != nil && !only[r.UpstreamID]) {
			continue
		}
		switch {
		case p.AlbumID != nil && (r.State == "duplicate"):
			if _, err := tx.Exec(ctx, `DELETE FROM album_placements WHERE album_id = $1 AND placement_id IN
				(SELECT id FROM placements WHERE item_id = $2 AND frame_id = $3 AND removed_at IS NULL)`, *p.AlbumID, *r.ItemID, *p.FrameID); err != nil {
				return nil, err
			}
		case p.FrameID != nil:
			// Only the source's own hold goes: a placement a feed also holds stays (feeds.md §6.2).
			removed, err := tx.RemovePlacements(ctx, `p.item_id = $1 AND p.frame_id = $2 AND NOT EXISTS
				(SELECT 1 FROM feed_deliveries d WHERE d.placement_id = p.id AND d.state = 'placed')`, *r.ItemID, *p.FrameID)
			if err != nil {
				return nil, err
			}
			for _, pl := range removed {
				if pl.SendID != nil && p.OwnerUserID != nil {
					sends[*pl.SendID] = *p.OwnerUserID
				}
			}
		case p.FeedID != nil:
			posts, err := s.st.LivePostsOfItem(ctx, tx, *p.FeedID, *r.ItemID)
			if err != nil {
				return nil, err
			}
			for _, post := range posts {
				_, subs, err := tx.RemovePostItems(ctx, post, []uuid.UUID{*r.ItemID}, p.OwnerUserID)
				if err != nil {
					return nil, err
				}
				sweeps = append(sweeps, subs...)
			}
			if r.SendID != nil && p.OwnerUserID != nil {
				sends[*r.SendID] = *p.OwnerUserID
			}
		}
	}
	if p.AlbumID != nil && p.FrameID != nil {
		if err := tx.FeedPut(ctx, *p.FrameID, store.FeedAlbum, *p.AlbumID, false); err != nil {
			return nil, err
		}
	}
	return sends, enqueueSweeps(ctx, tx, sweeps)
}

// deleteSourcesOf deletes the sources of an album or feed (with it), and
// removes their items first when asked. Returns what to announce.
func (s *Server) deleteSourcesOf(ctx context.Context, tx *store.Tx, albumID, feedID *uuid.UUID, removeItems bool) ([]removedSource, error) {
	srcs, err := s.st.SourcesOf(ctx, tx, albumID, feedID, nil)
	if err != nil {
		return nil, err
	}
	var out []removedSource
	for i := range srcs {
		p := &srcs[i]
		out = append(out, removedSource{src: *p, audience: s.sourceAudience(ctx, p)})
		if removeItems {
			if _, err := s.removeSourceItems(ctx, tx, p, nil); err != nil {
				return nil, err
			}
		}
		if err := tx.DeletePhotoSource(ctx, p.ID); err != nil {
			return nil, err
		}
	}
	return out, nil
}

type removedSource struct {
	src      store.PhotoSource
	audience []uuid.UUID
}

func (s *Server) announceRemovedSources(rs []removedSource) {
	for i := range rs {
		s.publishSourceRemoved(&rs[i].src, rs[i].audience)
	}
}

// reactionsOn: the owner of a source with notify_reactions off hears nothing
// about reactions and replies to its photos (they still show in history).
func (s *Server) reactionsOn(ctx context.Context, itemID uuid.UUID) bool {
	on, err := s.st.NotifyReactions(ctx, itemID)
	return err != nil || on
}
