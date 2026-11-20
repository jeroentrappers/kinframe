package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"html"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/crash"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/notify"
	"github.com/jeroentrappers/kinframe/server/internal/photosource"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Google Photos import (docs/spec/photo-sources.md §7): OAuth consent in a
// browser session, the Picker session, polling, copying the picked items
// within their base URLs' lifetime, then deleting the session and the token.

const (
	consentTimeout = 15 * time.Minute
	tokenReuseMin  = 10 * time.Minute
	tokenMaxAge    = 2 * time.Hour
	importKeep     = 7 * 24 * time.Hour
	baseURLLife    = 50 * time.Minute // Google's base URLs live 60 minutes: list again before that
	videoWait      = 10 * time.Minute
)

type googleImports struct {
	mu      sync.Mutex
	running map[uuid.UUID]bool
	wg      sync.WaitGroup
	ctx     context.Context
	pollMin time.Duration // shortest poll interval (tests lower it)
}

// SetGooglePollMin lowers the shortest Picker poll interval (tests).
func (s *Server) SetGooglePollMin(d time.Duration) { s.gi.pollMin = d }

func hashState(state string) string {
	sum := sha256.Sum256([]byte(state))
	return hex.EncodeToString(sum[:])
}

// returnURIs are the allowed return_uri values (§7.3).
func (s *Server) returnURIs() []string {
	return []string{"app.kinframe:/google-import", s.cfg.PublicURL + "/google-import/return"}
}

func (s *Server) googleImportJSON(g *store.GoogleImportView) gen.GoogleImport {
	out := gen.GoogleImport{Id: g.ID, Status: gen.GoogleImportStatus(g.Status), CreatedAt: g.CreatedAt, UpdatedAt: ptr(g.UpdatedAt),
		FinishedAt: g.FinishedAt}
	out.Progress.Picked, out.Progress.Imported = g.Picked, g.Imported
	out.Progress.Duplicates, out.Progress.Skipped, out.Progress.Failed, out.Progress.Bytes = ptr(g.Duplicates), ptr(g.Skipped), ptr(g.Failed), ptr(int(g.Bytes))
	ids := g.SendIDs
	if ids == nil {
		ids = []uuid.UUID{}
	}
	out.SendIds = &ids
	if g.ErrorCode != nil {
		out.ErrorCode = ptr(gen.GoogleImportErrorCode(*g.ErrorCode))
	}
	if g.TargetKind == "album" {
		out.Target = gen.PhotoSourceTarget{Kind: gen.PhotoSourceTargetKindAlbum, FrameId: g.FrameID, FrameName: g.FrameName, AlbumId: g.AlbumID, AlbumName: g.AlbumName}
	} else {
		out.Target = gen.PhotoSourceTarget{Kind: gen.PhotoSourceTargetKindFeed, FeedId: g.FeedID, FeedName: g.FeedName}
	}
	switch g.Status {
	case "needs_auth":
		if u, err := s.psSealer.Open(g.AuthURLEnc); err == nil && u != "" {
			out.AuthUrl = &u
		}
	case "picking":
		out.PickerUri, out.PickerExpiresAt = g.PickerURI, g.PickerExpiresAt
	}
	return out
}

func (s *Server) publishImport(ctx context.Context, id uuid.UUID, push *notify.Push) {
	g, err := s.st.GetGoogleImport(ctx, id)
	if err != nil {
		return
	}
	s.notify.User(g.UserID, notify.Event{"type": "google_import.updated", "import": s.googleImportJSON(g)}, push)
}

func (s *Server) ownImport(ctx context.Context, id uuid.UUID) (*store.GoogleImportView, error) {
	g, err := s.st.GetGoogleImport(ctx, id)
	if err == store.ErrNotFound || (err == nil && g.UserID != me(ctx).ID) {
		return nil, problem.New(problem.NotFound, "import not found")
	}
	return g, err
}

// ---------------------------------------------------------------- endpoints

func (s *Server) ListMyGoogleImports(ctx context.Context, _ gen.ListMyGoogleImportsRequestObject) (gen.ListMyGoogleImportsResponseObject, error) {
	list, err := s.st.ListGoogleImports(ctx, me(ctx).ID, s.now().Add(-importKeep))
	if err != nil {
		return nil, err
	}
	out := make([]gen.GoogleImport, len(list))
	for i := range list {
		out[i] = s.googleImportJSON(&list[i])
	}
	return ok(out), nil
}

func (s *Server) GetGoogleImport(ctx context.Context, req gen.GetGoogleImportRequestObject) (gen.GetGoogleImportResponseObject, error) {
	g, err := s.ownImport(ctx, req.ImportId)
	if err != nil {
		return nil, err
	}
	return ok(s.googleImportJSON(g)), nil
}

type importRef struct {
	ImportID uuid.UUID `json:"import_id"`
}

func (s *Server) StartGoogleImport(ctx context.Context, req gen.StartGoogleImportRequestObject) (gen.StartGoogleImportResponseObject, error) {
	u := me(ctx)
	if !s.ps.google.Configured() {
		return nil, problem.New(problem.FeatureDisabled, "Google Photos import is not set up on this server")
	}
	b := req.Body
	key := ""
	if req.Params.IdempotencyKey != nil {
		key = *req.Params.IdempotencyKey
		var prev importRef
		if found, err := store.IdempotentResult(ctx, s.st.Q(), u.ID, "google_import", key, &prev); err != nil {
			return nil, err
		} else if found {
			g, err := s.st.GetGoogleImport(ctx, prev.ImportID)
			if err != nil {
				return nil, err
			}
			return created(s.googleImportJSON(g)), nil
		}
	}
	if (b.AlbumId == nil) == (b.FeedId == nil) {
		return nil, problem.Validation("album_id", "oneOf", "choose exactly one of album_id or feed_id")
	}
	allowed := false
	for _, r := range s.returnURIs() {
		if b.ReturnUri == r {
			allowed = true
		}
	}
	if !allowed {
		return nil, problem.Validation("return_uri", "enum", "this return address is not allowed")
	}
	g := &store.GoogleImport{ID: store.NewID(), UserID: u.ID, ReturnURI: b.ReturnUri, AlbumID: b.AlbumId, FeedID: b.FeedId}
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
		g.FrameID, g.TargetKind = &a.FrameID, "album"
	} else {
		if _, err := s.feedView(ctx, s.st.Q(), *b.FeedId); err != nil {
			return nil, err
		}
		g.TargetKind = "feed" // any member: an import is a send
	}
	if ok, retry := s.googleStartUser.Allow(u.ID.String()); !ok {
		p := problem.New(problem.RateLimited, "")
		p.RetryAfter = retry
		return nil, p
	}
	now := s.now()
	g.Status = "needs_auth"
	// A fresh token from the user's previous import skips consent (§7.3).
	if prev, err := s.st.ReusableGoogleToken(ctx, u.ID, now.Add(tokenReuseMin)); err == nil {
		if tok, err := s.psSealer.Open(prev.AccessTokenEnc); err == nil && tok != "" {
			if sess, err := s.ps.google.CreateSession(ctx, tok, s.Limits(ctx).WithDefaults().MaxGoogleImportItems); err == nil {
				g.Status, g.AccessTokenEnc, g.TokenExpiresAt = "picking", prev.AccessTokenEnc, prev.TokenExpiresAt
				setSession(g, sess)
			}
		}
	} else if err != store.ErrNotFound {
		return nil, err
	}
	if g.Status == "needs_auth" {
		state, verifier := photosource.RandomToken(), photosource.RandomToken()
		h := hashState(state)
		g.StateHash, g.PKCEVerifierEnc = &h, s.psSealer.Seal(verifier)
		g.AuthURLEnc = s.psSealer.Seal(s.ps.google.Consent(state, verifier))
	}
	if err := s.st.CreateGoogleImport(ctx, g); err != nil {
		return nil, err
	}
	if key != "" {
		if err := s.st.InTx(ctx, func(tx *store.Tx) error { return tx.SaveIdempotent(ctx, u.ID, "google_import", key, importRef{g.ID}) }); err != nil &&
			!db.IsUniqueViolation(err, "idempotency_keys_pkey") {
			return nil, err
		}
	}
	if g.Status == "picking" {
		s.driveImport(g.ID)
	}
	v, err := s.st.GetGoogleImport(ctx, g.ID)
	if err != nil {
		return nil, err
	}
	return created(s.googleImportJSON(v)), nil
}

func setSession(g *store.GoogleImport, sess *photosource.PickingSession) {
	g.PickerSessionID, g.PickerURI, g.PickerExpiresAt = &sess.ID, &sess.PickerURI, sess.ExpireTime
	if sess.PollInterval > 0 {
		ms := int(sess.PollInterval / time.Millisecond)
		g.PollIntervalMS = &ms
	}
}

func (s *Server) CancelGoogleImport(ctx context.Context, req gen.CancelGoogleImportRequestObject) (gen.CancelGoogleImportResponseObject, error) {
	g, err := s.ownImport(ctx, req.ImportId)
	if err != nil {
		return nil, err
	}
	switch g.Status {
	case "cancelled":
		return ok(s.googleImportJSON(g)), nil
	case "done", "failed", "expired":
		return nil, problem.New(problem.InvalidTransition, "this import has already finished")
	}
	imp := g.GoogleImport
	if imp.Status != "importing" {
		// The driver (if any) stops at its next poll; the session goes now.
		s.endSession(ctx, &imp)
		imp.AccessTokenEnc, imp.TokenExpiresAt = nil, nil
		imp.FinishedAt = ptr(s.now())
	}
	imp.Status, imp.StateHash, imp.PKCEVerifierEnc, imp.AuthURLEnc = "cancelled", nil, nil, nil
	if err := s.st.SaveGoogleImport(ctx, &imp); err != nil {
		return nil, err
	}
	s.publishImport(ctx, imp.ID, nil)
	v, err := s.st.GetGoogleImport(ctx, imp.ID)
	if err != nil {
		return nil, err
	}
	return ok(s.googleImportJSON(v)), nil
}

func (s *Server) endSession(ctx context.Context, g *store.GoogleImport) {
	if g.PickerSessionID == nil {
		return
	}
	if tok, err := s.psSealer.Open(g.AccessTokenEnc); err == nil && tok != "" {
		if err := s.ps.google.DeleteSession(ctx, tok, *g.PickerSessionID); err != nil {
			s.log.Info("google: delete picker session", "import", g.ID, "err", err)
		}
	}
}

// ---------------------------------------------------------------- OAuth callback

const expiredPage = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>kinframe</title><style>body{font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem;color:#222;background:#fff}</style>
</head><body><h1>This link has expired</h1><p>Go back to kinframe and try again.</p></body></html>`

func expiredHTML(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.WriteHeader(http.StatusBadRequest)
	_, _ = w.Write([]byte(expiredPage))
}

// googleOAuthCallback is Google's redirect after consent (no kinframe
// sign-in: the browser only brings the state). It never redirects to an
// address that was not validated when the import started.
func (s *Server) googleOAuthCallback(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := r.URL.Query()
	state := q.Get("state")
	if state == "" || len(state) > 200 || len(q.Get("code")) > 2048 || !s.ps.google.Configured() {
		expiredHTML(w)
		return
	}
	g, err := s.st.GoogleImportByState(ctx, hashState(state)) // single use: the hash is cleared here
	if err != nil || g.Status != "needs_auth" || s.now().Sub(g.CreatedAt) > consentTimeout {
		if err != nil && err != store.ErrNotFound {
			s.log.Error("google callback", "err", err)
		}
		expiredHTML(w)
		return
	}
	fail := func(code string) {
		g.Status, g.ErrorCode, g.FinishedAt = "failed", &code, ptr(s.now())
		g.PKCEVerifierEnc, g.AuthURLEnc, g.AccessTokenEnc, g.TokenExpiresAt = nil, nil, nil, nil
	}
	verifier, verr := s.psSealer.Open(g.PKCEVerifierEnc)
	switch {
	case q.Get("error") != "":
		if q.Get("error") == "access_denied" {
			fail("consent_denied")
		} else {
			fail("auth_failed")
		}
	case q.Get("code") == "" || verr != nil:
		fail("auth_failed")
	default:
		tok, err := s.ps.google.Exchange(ctx, q.Get("code"), verifier)
		if err != nil {
			s.log.Info("google: token exchange failed", "import", g.ID, "err", err)
			fail("auth_failed")
			break
		}
		g.AccessTokenEnc, g.TokenExpiresAt = s.psSealer.Seal(tok.AccessToken), &tok.ExpiresAt
		g.PKCEVerifierEnc, g.AuthURLEnc = nil, nil
		sess, err := s.ps.google.CreateSession(ctx, tok.AccessToken, s.Limits(ctx).WithDefaults().MaxGoogleImportItems)
		if err != nil {
			s.log.Info("google: create picker session failed", "import", g.ID, "err", err)
			fail("google_unavailable")
			break
		}
		g.Status = "picking"
		setSession(g, sess)
	}
	if err := s.st.SaveGoogleImport(ctx, g); err != nil {
		s.log.Error("google callback: save", "err", err)
		expiredHTML(w)
		return
	}
	s.publishImport(ctx, g.ID, nil)
	if g.Status == "picking" {
		s.driveImport(g.ID)
	}
	sep := "?"
	if strings.Contains(g.ReturnURI, "?") {
		sep = "&"
	}
	loc := g.ReturnURI + sep + url.Values{"import_id": {g.ID.String()}, "status": {g.Status}}.Encode()
	w.Header().Set("Location", loc)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.WriteHeader(http.StatusFound)
	_, _ = w.Write([]byte(`<a href="` + html.EscapeString(loc) + `">Continue</a>`))
}

// ---------------------------------------------------------------- driving an import

// resumeGoogleImports picks up imports a restart interrupted.
func (s *Server) resumeGoogleImports(ctx context.Context) {
	s.gi.mu.Lock()
	s.gi.ctx = ctx
	s.gi.mu.Unlock()
	ids, err := s.st.ActiveGoogleImports(ctx)
	if err != nil {
		if ctx.Err() == nil {
			s.log.Warn("google imports: resume", "err", err)
		}
		return
	}
	for _, id := range ids {
		s.driveImport(id)
	}
}

// driveImport polls and copies an import in the background (one driver per import).
func (s *Server) driveImport(id uuid.UUID) {
	s.gi.mu.Lock()
	if s.gi.running[id] {
		s.gi.mu.Unlock()
		return
	}
	ctx := s.gi.ctx
	if ctx == nil {
		ctx = context.Background()
	}
	if ctx.Err() != nil {
		s.gi.mu.Unlock()
		return
	}
	s.gi.running[id] = true
	s.gi.wg.Add(1)
	s.gi.mu.Unlock()
	go func() {
		defer crash.Recover("google-import")
		defer s.gi.wg.Done()
		defer func() {
			s.gi.mu.Lock()
			delete(s.gi.running, id)
			s.gi.mu.Unlock()
		}()
		s.runImport(ctx, id)
	}()
}

// WaitGoogleImports blocks until running import drivers finished (tests).
func (s *Server) WaitGoogleImports() { s.gi.wg.Wait() }

func sleepCtx(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

func (s *Server) finishImport(ctx context.Context, g *store.GoogleImport, status, code string) {
	g.Status = status
	g.ErrorCode = nil
	if code != "" {
		g.ErrorCode = &code
	}
	g.FinishedAt = ptr(s.now())
	s.endSession(ctx, g)
	// A finished import's token stays until it expires (at most an hour) so the
	// next "add more" skips consent (§7.3); anything else forgets it now.
	if status != "done" {
		g.AccessTokenEnc, g.TokenExpiresAt = nil, nil
	}
	g.StateHash, g.PKCEVerifierEnc, g.AuthURLEnc = nil, nil, nil
	if err := s.st.SaveGoogleImport(ctx, g); err != nil {
		s.log.Error("google import: save", "import", g.ID, "err", err)
	}
	var push *notify.Push
	if status == "done" || status == "failed" {
		push = &notify.Push{Key: "google_import_done", IDs: map[string]string{"import_id": g.ID.String()}}
		if status == "failed" {
			push.Text = "google_import_failed"
		}
	}
	s.publishImport(ctx, g.ID, push)
}

func (s *Server) runImport(ctx context.Context, id uuid.UUID) {
	failures := 0
	for ctx.Err() == nil {
		v, err := s.st.GetGoogleImport(ctx, id)
		if err != nil {
			return
		}
		g := &v.GoogleImport
		tok, err := s.psSealer.Open(g.AccessTokenEnc)
		if g.Status == "picking" || g.Status == "importing" {
			if err != nil || tok == "" || (g.TokenExpiresAt != nil && !g.TokenExpiresAt.After(s.now())) {
				s.finishImport(ctx, g, "failed", "auth_failed")
				return
			}
		}
		switch g.Status {
		case "picking":
			if g.PickerExpiresAt != nil && !s.now().Before(*g.PickerExpiresAt) {
				s.finishImport(ctx, g, "expired", "picker_expired")
				return
			}
			sess, err := s.ps.google.GetSession(ctx, tok, *g.PickerSessionID)
			var ge *photosource.GoogleError
			switch {
			case errors.As(err, &ge) && ge.Auth():
				s.finishImport(ctx, g, "failed", "auth_failed")
				return
			case errors.As(err, &ge) && (ge.Status == http.StatusForbidden || ge.Status == http.StatusNotFound):
				s.finishImport(ctx, g, "expired", "picker_expired")
				return
			case err != nil:
				failures++
				if failures >= 10 {
					s.finishImport(ctx, g, "failed", "google_unavailable")
					return
				}
			case sess.MediaItemsSet:
				g.Status = "importing"
				if err := s.st.SaveGoogleImport(ctx, g); err != nil {
					return
				}
				s.publishImport(ctx, g.ID, nil)
				continue
			}
			wait := s.gi.pollMin
			if g.PollIntervalMS != nil {
				wait = max(wait, time.Duration(*g.PollIntervalMS)*time.Millisecond)
			}
			if err != nil {
				wait = max(wait, time.Duration(failures)*2*time.Second)
			}
			if !sleepCtx(ctx, min(wait, 30*time.Second)) {
				return
			}
		case "importing":
			s.copyImport(ctx, g, tok)
			return
		default:
			return
		}
	}
}

// importTarget checks the import's album or feed still exists.
func (s *Server) importTargetOK(ctx context.Context, g *store.GoogleImport) bool {
	switch {
	case g.TargetKind == "album":
		if g.AlbumID == nil || g.FrameID == nil {
			return false
		}
		_, err := s.st.GetAlbum(ctx, s.st.Q(), *g.FrameID, *g.AlbumID)
		return err == nil
	default:
		if g.FeedID == nil {
			return false
		}
		f, err := s.st.GetFeed(ctx, s.st.Q(), *g.FeedID)
		return err == nil && f.DeletedAt == nil
	}
}

// copyImport downloads the picked items, dedupes them and makes the sends.
func (s *Server) copyImport(ctx context.Context, g *store.GoogleImport, tok string) {
	if !s.importTargetOK(ctx, g) {
		s.finishImport(ctx, g, "failed", "target_gone")
		return
	}
	limits := s.Limits(ctx).WithDefaults()
	done := map[string]bool{}
	for _, id := range g.DoneIDs {
		done[id] = true
	}
	var created []store.SourceSendItem
	lastPublish := time.Now()
	videoSince := map[string]time.Time{}
	seen := map[string]bool{} // sha256 of files this import already added
	cancelled := false
	for round := 0; round < 100 && !cancelled; round++ {
		listedAt := time.Now()
		var items []photosource.PickedItem
		page := ""
		for {
			list, next, err := s.ps.google.ListItems(ctx, tok, *g.PickerSessionID, page)
			if err != nil {
				var ge *photosource.GoogleError
				code := "google_unavailable"
				if errors.As(err, &ge) && ge.Auth() {
					code = "auth_failed"
				}
				s.placeImport(ctx, g, created)
				s.finishImport(ctx, g, "failed", code)
				return
			}
			items = append(items, list...)
			if next == "" || len(items) >= limits.MaxGoogleImportItems {
				break
			}
			page = next
		}
		if len(items) > limits.MaxGoogleImportItems {
			items = items[:limits.MaxGoogleImportItems]
		}
		g.Picked = len(items)
		waiting := false
		for _, it := range items {
			if done[it.ID] {
				continue
			}
			if time.Since(listedAt) > baseURLLife {
				break // fresh base URLs: list again
			}
			if st, err := s.st.GoogleImportStatus(ctx, g.ID); err == nil && st == "cancelled" {
				cancelled = true
				break
			}
			if it.Type == "VIDEO" && it.VideoStatus != "" && it.VideoStatus != "READY" {
				if it.VideoStatus == "FAILED" {
					g.Failed++
					done[it.ID] = true
					g.DoneIDs = append(g.DoneIDs, it.ID)
					continue
				}
				if _, ok := videoSince[it.ID]; !ok {
					videoSince[it.ID] = time.Now()
				}
				if time.Since(videoSince[it.ID]) > videoWait {
					g.Failed++
					done[it.ID] = true
					g.DoneIDs = append(g.DoneIDs, it.ID)
				} else {
					waiting = true
				}
				continue
			}
			if c := s.copyGoogleItem(ctx, g, tok, it, limits.MaxUploadBytes, limits.MaxVideoSeconds, seen); c != nil {
				created = append(created, *c)
			}
			done[it.ID] = true
			g.DoneIDs = append(g.DoneIDs, it.ID)
			if time.Since(lastPublish) > 2*time.Second {
				_ = s.st.SaveGoogleImport(ctx, g)
				s.publishImport(ctx, g.ID, nil)
				lastPublish = time.Now()
			}
		}
		if ctx.Err() != nil {
			_ = s.st.SaveGoogleImport(context.WithoutCancel(ctx), g)
			return
		}
		all := true
		for _, it := range items {
			if !done[it.ID] {
				all = false
			}
		}
		if all || cancelled {
			break
		}
		if waiting && !sleepCtx(ctx, 15*time.Second) {
			return
		}
	}
	if !s.importTargetOK(ctx, g) {
		s.finishImport(ctx, g, "failed", "target_gone")
		return
	}
	s.placeImport(ctx, g, created)
	switch {
	case cancelled:
		s.finishImport(ctx, g, "cancelled", "")
	case g.Imported+g.Duplicates+g.Skipped == 0 && g.Failed > 0:
		s.finishImport(ctx, g, "failed", "download_failed")
	default:
		s.finishImport(ctx, g, "done", "")
	}
}

// copyGoogleItem downloads one picked item; nil when nothing new was made.
func (s *Server) copyGoogleItem(ctx context.Context, g *store.GoogleImport, tok string, it photosource.PickedItem, maxBytes int64, maxVideoS int,
	seen map[string]bool) *store.SourceSendItem {
	path := s.scratchPath()
	suffixes := []string{"=d", "=w2560-h2560"}
	if it.Type == "VIDEO" {
		suffixes = []string{"=dv"}
	}
	var f *fetched
	skipped := false
	for _, suf := range suffixes {
		d, err := s.ps.google.DownloadItem(ctx, tok, it.BaseURL, suf, path, maxBytes)
		if err != nil {
			var pe *photosource.Error
			if errors.As(err, &pe) && pe.Code == photosource.CodeTooLarge {
				skipped = true
				break
			}
			continue
		}
		kind, mime, err := media.Sniff(d.Path)
		if err != nil {
			_ = os.Remove(d.Path)
			skipped = true // RAW and other unreadable files: the rendered JPEG next
			continue
		}
		skipped = false
		f = &fetched{path: d.Path, kind: kind, mime: mime, sha: d.SHA256, size: d.Bytes}
		if kind == media.Video {
			info, err := s.tools.ProbeVideo(ctx, d.Path)
			if err != nil {
				_ = os.Remove(d.Path)
				f = nil
				break
			}
			if info.DurationMS > maxVideoS*1000 {
				_ = os.Remove(d.Path)
				f, skipped = nil, true
				break
			}
			f.dur = info.DurationMS
		}
		break
	}
	if f == nil {
		if skipped {
			g.Skipped++
		} else {
			g.Failed++
		}
		return nil
	}
	g.Bytes += f.size
	if seen[f.sha] { // picked twice (the same file under two ids)
		_ = os.Remove(f.path)
		g.Duplicates++
		return nil
	}
	seen[f.sha] = true
	// Already in the album or feed.
	var dupItem uuid.UUID
	var found bool
	var err error
	if g.TargetKind == "album" {
		_, dupItem, found, err = s.st.DuplicateOnFrame(ctx, s.st.Q(), *g.FrameID, f.sha, "")
	} else {
		dupItem, found, err = s.st.DuplicateInFeed(ctx, s.st.Q(), *g.FeedID, f.sha, "")
	}
	if err == nil && found {
		_ = os.Remove(f.path)
		g.Duplicates++
		if g.TargetKind == "album" {
			_ = s.st.InTx(ctx, func(tx *store.Tx) error {
				var pid uuid.UUID
				if err := tx.QueryRow(ctx, `SELECT id FROM placements WHERE frame_id = $1 AND item_id = $2 AND removed_at IS NULL`, *g.FrameID, dupItem).Scan(&pid); err != nil {
					return nil
				}
				return tx.AddToAlbum(ctx, *g.FrameID, *g.AlbumID, []uuid.UUID{pid})
			})
		}
		return nil
	}
	via := "google_photos"
	item := store.Item{ID: store.NewID(), SenderUserID: &g.UserID, CapturedHint: it.CreateTime, Via: &via}
	var out *store.SourceSendItem
	err = s.st.InTx(ctx, func(tx *store.Tx) error {
		mediaID, err := s.mediaFor(ctx, tx, g.UserID, f)
		if err != nil {
			return err
		}
		item.MediaID = mediaID
		if err := tx.CreateItem(ctx, item); err != nil {
			return err
		}
		out = &store.SourceSendItem{ItemID: item.ID, MediaID: mediaID, TakenAt: it.CreateTime}
		return nil
	})
	if err != nil {
		_ = os.Remove(f.path)
		g.Failed++
		return nil
	}
	g.Imported++
	return out
}

// placeImport makes the import's sends: one into the album, or posts of at
// most max_items_per_send into the feed. Never quiet: the person just picked them.
func (s *Server) placeImport(ctx context.Context, g *store.GoogleImport, items []store.SourceSendItem) {
	if len(items) == 0 {
		return
	}
	per := len(items)
	if g.TargetKind == "feed" {
		per = s.Limits(ctx).MaxItemsPerSend
	}
	for start := 0; start < len(items); start += per {
		chunk := items[start:min(start+per, len(items))]
		sendID := store.NewID()
		err := s.st.InTx(ctx, func(tx *store.Tx) error {
			if err := tx.CreateSourceSend(ctx, sendID, g.UserID, "google_photos", nil, &g.ID, chunk); err != nil {
				return err
			}
			if g.TargetKind == "album" {
				return s.placeInAlbum(ctx, tx, *g.FrameID, *g.AlbumID, g.UserID, sendID, chunk, false)
			}
			ids := make([]uuid.UUID, len(chunk))
			for i, it := range chunk {
				ids[i] = it.ItemID
			}
			return createPosts(ctx, s.st, tx, sendID, g.UserID, []uuid.UUID{*g.FeedID}, ids)
		})
		if err != nil {
			s.log.Error("google import: place", "import", g.ID, "err", err)
			continue
		}
		g.SendIDs = append(g.SendIDs, sendID)
		s.publishSendUpdates(ctx, map[uuid.UUID]uuid.UUID{sendID: g.UserID}, "")
	}
}

// PruneGoogleImports is the maintenance step: finished imports go after 7
// days, tokens after their expiry (at most 2 hours), consent waits after 15
// minutes.
func (s *Server) PruneGoogleImports(ctx context.Context) error {
	ids, err := s.st.PruneGoogleImports(ctx, s.now(), importKeep, tokenMaxAge, consentTimeout)
	for _, id := range ids {
		s.publishImport(ctx, id, nil)
	}
	return err
}
