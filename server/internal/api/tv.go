package api

import (
	"context"
	"net/http"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// TV links (product spec 4.11 "Google Cast / DLNA"): a short-lived, revocable
// token that lets a TV page or a Cast receiver fetch a slideshow of a frame's
// library without signing in. The token never grants more than its creator
// can see right now: membership and see_library are re-checked on every fetch.

const (
	maxTvSlides = 500
	// tvMediaTTL is the signer step for slide URLs: they stay valid 1-2 h, and
	// refresh_after tells clients to refetch well before that.
	tvMediaTTL      = time.Hour
	tvRefreshAfter  = 45 * time.Minute
	defaultTvHours  = 12
	defaultInterval = 10
)

func (s *Server) tvLinkJSON(l *store.TvLink) gen.TvLink {
	out := gen.TvLink{
		Id: l.ID, FrameId: l.FrameID, Token: l.Token, Url: s.cfg.PublicURL + "/tv/" + l.FrameID.String() + "?t=" + l.Token,
		Purpose: gen.TvLinkPurpose(l.Purpose), AlbumId: l.AlbumID, IntervalSeconds: l.IntervalSeconds,
		CreatedByName: l.CreatedByName, ExpiresAt: l.ExpiresAt, CreatedAt: l.CreatedAt,
	}
	if l.Kind != nil {
		out.Kind = ptr(gen.MediaKind(*l.Kind))
	}
	if l.PlacementIDs != nil {
		out.PlacementCount = ptr(len(l.PlacementIDs))
	}
	return out
}

func (s *Server) ListTvLinks(ctx context.Context, req gen.ListTvLinksRequestObject) (gen.ListTvLinksResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	var by *uuid.UUID
	if !m.IsManager() {
		by = &m.UserID
	}
	links, err := s.st.ListActiveTvLinks(ctx, req.FrameId, by)
	if err != nil {
		return nil, err
	}
	out := make([]gen.TvLink, len(links))
	for i := range links {
		out[i] = s.tvLinkJSON(&links[i])
	}
	return ok(out), nil
}

func (s *Server) CreateTvLink(ctx context.Context, req gen.CreateTvLinkRequestObject) (gen.CreateTvLinkResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	b := gen.TvLinkCreate{}
	if req.Body != nil {
		b = *req.Body
	}
	var ids []uuid.UUID
	if b.PlacementIds != nil && len(*b.PlacementIds) > 0 {
		ids = dedupeIDs(*b.PlacementIds)
	}
	if !m.Effective().SeeLibrary {
		// Without see_library a link may only show the caller's own placements.
		if len(ids) == 0 {
			return nil, requirePerm(m, false, "see_library")
		}
		n, err := s.st.CountOwnPlacements(ctx, req.FrameId, ids, m.UserID)
		if err != nil {
			return nil, err
		}
		if n != len(ids) {
			return nil, requirePerm(m, false, "see_library")
		}
	}
	if b.AlbumId != nil {
		if _, err := s.st.GetAlbum(ctx, s.st.Q(), req.FrameId, *b.AlbumId); err != nil {
			return nil, notFound(err, "album")
		}
	}
	if ok, retry := s.tvUser.Allow(m.UserID.String()); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	hours, interval, purpose := defaultTvHours, defaultInterval, "tv"
	if b.ExpiresInHours != nil {
		hours = *b.ExpiresInHours
	}
	if b.IntervalSeconds != nil {
		interval = *b.IntervalSeconds
	}
	if b.Purpose != nil {
		purpose = string(*b.Purpose)
	}
	var kind *string
	if b.Kind != nil {
		kind = ptr(string(*b.Kind))
	}
	l, err := s.st.CreateTvLink(ctx, s.st.Q(), store.TvLink{
		ID: store.NewID(), FrameID: req.FrameId, Token: auth.RandomToken(24), CreatedBy: m.UserID, Purpose: purpose,
		AlbumID: b.AlbumId, Kind: kind, PlacementIDs: ids, IntervalSeconds: interval,
		ExpiresAt: time.Now().Add(time.Duration(hours) * time.Hour).UTC().Truncate(time.Second),
	})
	if err != nil {
		return nil, err
	}
	return created(s.tvLinkJSON(l)), nil
}

func (s *Server) RevokeTvLink(ctx context.Context, req gen.RevokeTvLinkRequestObject) (gen.RevokeTvLinkResponseObject, error) {
	m, err := s.membership(ctx, req.FrameId)
	if err != nil {
		return nil, err
	}
	l, err := s.st.GetTvLink(ctx, req.FrameId, req.LinkId)
	if err != nil {
		return nil, notFound(err, "TV link")
	}
	if !m.IsManager() && l.CreatedBy != m.UserID {
		return nil, problem.New(problem.Forbidden, "only the creator or an owner/admin can revoke this link")
	}
	if err := s.st.RevokeTvLink(ctx, req.FrameId, req.LinkId); err != nil {
		return nil, err
	}
	return noContent(), nil
}

// GetTvSlideshow is public: the token is the credential.
func (s *Server) GetTvSlideshow(ctx context.Context, req gen.GetTvSlideshowRequestObject) (gen.GetTvSlideshowResponseObject, error) {
	if ok, retry := s.tvIP.Allow(clientIP(ctx)); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	gone := problem.New(problem.NotFound, "this TV link is not valid")
	l, err := s.st.GetTvLinkByToken(ctx, req.Token)
	if err == store.ErrNotFound || (err == nil && l.RevokedAt != nil) {
		return nil, gone
	}
	if err != nil {
		return nil, err
	}
	if !time.Now().Before(l.ExpiresAt) {
		return nil, problem.New(problem.LinkExpired, "")
	}
	// The creator must still be a member; without see_library only their own items show.
	m, err := s.st.GetMembership(ctx, s.st.Q(), l.FrameID, l.CreatedBy)
	if err == store.ErrNotFound {
		return nil, gone
	}
	if err != nil {
		return nil, err
	}
	var onlySender *uuid.UUID
	if !m.Effective().SeeLibrary {
		onlySender = &l.CreatedBy
	}
	f, err := s.st.GetFrame(ctx, s.st.Q(), l.FrameID)
	if err != nil {
		return nil, notFound(err, "frame")
	}
	var rows []store.PlacementRow
	if len(l.PlacementIDs) > 0 {
		rows, err = s.st.VisiblePlacementsByIDs(ctx, l.FrameID, l.PlacementIDs, onlySender)
	} else {
		rows, _, err = s.st.ListPlacements(ctx, store.PlacementFilter{
			FrameID: l.FrameID, OnlySender: onlySender, Visibility: "visible", Kind: l.Kind, AlbumID: l.AlbumID,
			Sort: "received_desc", Limit: maxTvSlides,
		})
	}
	if err != nil {
		return nil, err
	}
	out := gen.TvSlideshow{
		FrameId: f.ID, FrameName: f.Name, IntervalSeconds: l.IntervalSeconds, ExpiresAt: l.ExpiresAt,
		RefreshAfter: time.Now().Add(tvRefreshAfter).UTC().Truncate(time.Second), Slides: []gen.TvSlide{},
	}
	if l.AlbumID != nil {
		if a, err := s.st.GetAlbum(ctx, s.st.Q(), l.FrameID, *l.AlbumID); err == nil {
			out.AlbumName = &a.Name
		}
	}
	for i := range rows {
		if sl, ok := s.tvSlide(&rows[i]); ok {
			out.Slides = append(out.Slides, sl)
		}
	}
	return ok(out), nil
}

// tvSlide renders a placement as a slide; items without a master yet are skipped.
func (s *Server) tvSlide(r *store.PlacementRow) (gen.TvSlide, bool) {
	if r.MasterKey == nil || r.MediaStatus != "ready" {
		return gen.TvSlide{}, false
	}
	mime := "image/webp"
	if r.Kind == "video" {
		mime = "video/mp4"
	}
	if r.MasterMime != nil && *r.MasterMime != "" {
		mime = *r.MasterMime
	}
	sl := gen.TvSlide{
		PlacementId: r.Placement.ID, Kind: gen.MediaKind(r.Kind), Url: s.signedURL(*r.MasterKey, tvMediaTTL), Mime: mime,
		Caption: r.Caption, SenderName: tvSenderName(&r.ItemRow), CapturedAt: r.CapturedAt, ReceivedAt: r.ReceivedAt,
		DurationMs: r.DurationMS,
	}
	if sl.CapturedAt == nil {
		sl.CapturedAt = r.CapturedHint
	}
	if r.Width != nil {
		sl.Width = *r.Width
	}
	if r.Height != nil {
		sl.Height = *r.Height
	}
	if r.PosterKey != nil {
		sl.PosterUrl = ptr(s.signedURL(*r.PosterKey, tvMediaTTL))
	}
	if r.Thumb512Key != nil {
		sl.ThumbUrl = ptr(s.signedURL(*r.Thumb512Key, tvMediaTTL))
	}
	return sl, true
}

func dedupeIDs(ids []uuid.UUID) []uuid.UUID {
	seen := make(map[uuid.UUID]bool, len(ids))
	out := make([]uuid.UUID, 0, len(ids))
	for _, id := range ids {
		if !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	return out
}

// tvPublicPath reports whether a request path is the public slideshow endpoint.
func tvPublicPath(r *http.Request) bool {
	return r.Method == http.MethodGet && len(r.URL.Path) > len("/v1/tv/") && r.URL.Path[:len("/v1/tv/")] == "/v1/tv/"
}

// tvSenderName: an item imported from another app shows its original sender.
func tvSenderName(r *store.ItemRow) string {
	if r.OriginSenderName != nil && *r.OriginSenderName != "" {
		return *r.OriginSenderName
	}
	return r.SenderName
}
