package api

// Link previews (README §5.19, operator opt-in with KINFRAME_LINK_PREVIEWS): fetched by the server through
// internal/linkpreview (public addresses only), the image re-encoded to a WebP of at most 512 px and served by
// the server. Cached per normalized URL: 7 days, a failure 1 day.

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/api/gen"
	"github.com/jeroentrappers/kinframe/server/internal/auth"
	"github.com/jeroentrappers/kinframe/server/internal/blob"
	"github.com/jeroentrappers/kinframe/server/internal/linkpreview"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

const (
	previewTTL       = 7 * 24 * time.Hour
	previewFailedTTL = 24 * time.Hour
	previewImageEdge = 512
)

// linkPreviewJSON renders a preview; url is the link as written in the message (else the normalized one).
func (s *Server) linkPreviewJSON(p *store.LinkPreview, body *string, enc []byte) *gen.LinkPreview {
	link := p.URL
	if b := s.openBody(body, enc); b != nil {
		if l := linkpreview.FirstURL(*b); l != "" {
			link = l
		}
	}
	out := &gen.LinkPreview{Url: link, Status: gen.LinkPreviewStatus(p.Status)}
	if p.Status == "ok" || (p.Status == "pending" && p.FetchedAt != nil && p.ErrorCode == nil) {
		out.Title, out.Description, out.SiteName = p.Title, p.Description, p.SiteName
		if p.ImageKey != nil {
			out.ImageUrl = ptr(s.signedURL(*p.ImageKey, thumbURLTTL))
			out.ImageWidth, out.ImageHeight = p.ImageWidth, p.ImageHeight
		}
	}
	return out
}

func (s *Server) CreateLinkPreview(ctx context.Context, req gen.CreateLinkPreviewRequestObject) (gen.CreateLinkPreviewResponseObject, error) {
	if s.chat.previews == nil {
		return nil, problem.New(problem.FeatureDisabled, "link previews are turned off on this server")
	}
	norm, err := linkpreview.Normalize(req.Body.Url)
	if err != nil {
		return nil, problem.Validation("url", "format", "not a link")
	}
	p, stale, err := s.st.EnsureLinkPreview(ctx, norm)
	if err != nil {
		return nil, err
	}
	if stale || p.Status == "pending" {
		if allowed, retry := s.chat.preview.Allow(me(ctx).ID.String()); !allowed {
			pr := problem.New(problem.RateLimited, "")
			pr.RetryAfter = retry
			return nil, pr
		}
		// The composer waits for it (at most the fetch time limit and the image).
		if err := s.fetchLinkPreview(ctx, p.ID); err != nil {
			return nil, err
		}
		if p, err = s.st.GetLinkPreview(ctx, s.st.Q(), p.ID); err != nil {
			return nil, err
		}
	}
	out := s.linkPreviewJSON(p, nil, nil)
	out.Url = req.Body.Url
	return ok(out), nil
}

// fetchLinkPreview fetches a preview, stores the result and updates the messages that show it.
func (s *Server) fetchLinkPreview(ctx context.Context, id uuid.UUID) error {
	p, err := s.st.GetLinkPreview(ctx, s.st.Q(), id)
	if err == store.ErrNotFound {
		return nil
	}
	if err != nil {
		return err
	}
	if p.Fresh(time.Now()) {
		return nil
	}
	res := s.fetchPreview(ctx, p.URL)
	old, err := s.st.FinishLinkPreview(ctx, id, res)
	if err != nil {
		return err
	}
	if old != nil {
		s.deleteBlob(ctx, *old)
	}
	ids, err := s.st.MessagesWithPreview(ctx, id)
	if err != nil {
		return err
	}
	s.publishMessagesUpdated(ctx, ids)
	return nil
}

func (s *Server) fetchPreview(ctx context.Context, link string) store.LinkPreviewResult {
	f := s.chat.previews
	if f == nil {
		return store.LinkPreviewResult{ErrorCode: linkpreview.ErrFetchFailed, TTL: previewFailedTTL}
	}
	page, err := f.Fetch(ctx, link)
	if err != nil {
		s.log.Info("link preview failed", "url", link, "err", err)
		return store.LinkPreviewResult{ErrorCode: linkpreview.Code(err), TTL: previewFailedTTL}
	}
	res := store.LinkPreviewResult{OK: true, TTL: previewTTL}
	if page.Title != "" {
		res.Title = &page.Title
	}
	if page.Description != "" {
		res.Description = &page.Description
	}
	if page.SiteName != "" {
		res.SiteName = &page.SiteName
	}
	if res.Title == nil && res.Description == nil {
		return store.LinkPreviewResult{ErrorCode: linkpreview.ErrNotHTML, TTL: previewFailedTTL}
	}
	if page.ImageURL != "" {
		if key, w, h, err := s.previewImage(ctx, page.ImageURL); err != nil {
			s.log.Info("link preview image skipped", "url", page.ImageURL, "err", err)
		} else {
			res.ImageKey, res.ImageWidth, res.ImageHeight = &key, &w, &h
		}
	}
	return res
}

// previewImage downloads a page's image (guarded, at most 5 MiB) and stores it as a WebP of at most 512 px.
func (s *Server) previewImage(ctx context.Context, imageURL string) (string, int, int, error) {
	data, err := s.chat.previews.FetchImage(ctx, imageURL)
	if err != nil {
		return "", 0, 0, err
	}
	if kind, _ := media.SniffBytes(data); kind != media.Photo {
		return "", 0, 0, media.ErrUnsupported
	}
	tmp, err := s.tempDir()
	if err != nil {
		return "", 0, 0, err
	}
	defer os.RemoveAll(tmp)
	src, out := filepath.Join(tmp, "in"), filepath.Join(tmp, "preview.webp")
	if err := os.WriteFile(src, data, 0o600); err != nil {
		return "", 0, 0, err
	}
	if err := s.tools.ResizeImage(ctx, src, media.Size{W: previewImageEdge, H: previewImageEdge}, media.ThumbQ, out); err != nil {
		return "", 0, 0, err
	}
	hdr, err := s.tools.ImageHeader(ctx, out)
	if err != nil {
		return "", 0, 0, err
	}
	key := "link-previews/" + auth.RandomToken(16) + ".webp"
	if err := s.blob.PutFile(ctx, key, out, blob.PutOptions{ContentType: "image/webp"}); err != nil {
		return "", 0, 0, err
	}
	return key, hdr.Size.W, hdr.Size.H, nil
}

// runLinkPreviewJob is the worker job chat.link_preview {preview_id}. Fetch failures are results, not job
// errors: only storage errors are retried.
func (s *Server) runLinkPreviewJob(ctx context.Context, job *store.Job) error {
	var p struct {
		PreviewID uuid.UUID `json:"preview_id"`
	}
	if err := json.Unmarshal(job.Payload, &p); err != nil {
		return nil
	}
	return s.fetchLinkPreview(ctx, p.PreviewID)
}
