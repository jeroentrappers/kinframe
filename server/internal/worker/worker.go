// Package worker runs the media pipeline from a Postgres-backed job queue
// (FOR UPDATE SKIP LOCKED leases), so several workers and server processes can
// share one queue safely.
package worker

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/blob"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// Events receives pipeline outcomes so the API layer can notify people.
type Events interface {
	PlacementsChanged(changes []store.StatusChange)
	MediaFailed(mediaID uuid.UUID)
}

// FocalDetector finds the point of interest (e.g. faces) in an image.
// Face detection is not implemented yet; CentreFocal is the default.
type FocalDetector interface {
	Focal(ctx context.Context, imagePath string) (x, y float64)
}

type CentreFocal struct{}

func (CentreFocal) Focal(context.Context, string) (float64, float64) { return 0.5, 0.5 }

type Worker struct {
	Store *store.Store
	Blob  blob.Store
	// Scratch is a local directory for intermediate files; vips and ffmpeg
	// need real files, so masters are downloaded here when the store is
	// remote. Every job removes its own directory, also on failure.
	Scratch string
	Tools   media.Tools
	Log     *slog.Logger
	Events  Events
	Focal   FocalDetector
	Workers int
	// MaxVideoMS returns the current max video length after trim.
	MaxVideoMS func(ctx context.Context) int
	// RemoveSource deletes an upload's bytes once the master exists.
	RemoveSource func(path string)

	Lease       time.Duration
	PollEvery   time.Duration
	MaxAttempts int

	wakeOnce sync.Once
	wake     chan struct{}
}

func (w *Worker) init() {
	w.wakeOnce.Do(func() {
		w.wake = make(chan struct{}, 1)
		if w.Lease == 0 {
			w.Lease = 30 * time.Minute
		}
		if w.PollEvery == 0 {
			w.PollEvery = 2 * time.Second
		}
		if w.MaxAttempts == 0 {
			w.MaxAttempts = 5
		}
		if w.Focal == nil {
			w.Focal = CentreFocal{}
		}
		if w.Workers < 1 {
			w.Workers = 1
		}
	})
}

// Wake nudges idle workers after a job was enqueued.
func (w *Worker) Wake() {
	w.init()
	select {
	case w.wake <- struct{}{}:
	default:
	}
}

// Run processes jobs until ctx is cancelled.
func (w *Worker) Run(ctx context.Context) {
	w.init()
	var wg sync.WaitGroup
	for i := 0; i < w.Workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			w.loop(ctx)
		}()
	}
	wg.Wait()
}

func (w *Worker) loop(ctx context.Context) {
	t := time.NewTicker(w.PollEvery)
	defer t.Stop()
	for ctx.Err() == nil {
		ran, err := w.RunOne(ctx)
		if err != nil && ctx.Err() == nil {
			w.Log.Error("job queue", "err", err)
		}
		if ran {
			continue
		}
		select {
		case <-ctx.Done():
		case <-w.wake:
		case <-t.C:
		}
	}
}

// RunOne claims and runs a single job; ran is false when the queue is empty.
func (w *Worker) RunOne(ctx context.Context) (bool, error) {
	w.init()
	job, err := w.Store.ClaimJob(ctx, w.Lease)
	if err != nil || job == nil {
		return false, err
	}
	start := time.Now()
	err = w.handle(ctx, job)
	if err == nil {
		w.Log.Debug("job done", "kind", job.Kind, "id", job.ID, "took", time.Since(start))
		return true, w.Store.CompleteJob(ctx, job.ID)
	}
	if ctx.Err() != nil {
		return true, nil // lease expires and another worker retries
	}
	if job.Attempts >= w.MaxAttempts {
		w.Log.Error("job failed permanently", "kind", job.Kind, "id", job.ID, "err", err)
		w.giveUp(ctx, job)
		return true, w.Store.CompleteJob(ctx, job.ID)
	}
	delay := time.Duration(job.Attempts*job.Attempts) * 10 * time.Second
	w.Log.Warn("job failed, will retry", "kind", job.Kind, "id", job.ID, "attempt", job.Attempts, "in", delay, "err", err)
	return true, w.Store.RetryJob(ctx, job.ID, delay, err)
}

type mediaPayload struct {
	MediaID uuid.UUID `json:"media_id"`
}

type itemPayload struct {
	ItemID uuid.UUID `json:"item_id"`
}

func (w *Worker) handle(ctx context.Context, job *store.Job) error {
	switch job.Kind {
	case store.JobProcessMedia:
		var p mediaPayload
		if err := json.Unmarshal(job.Payload, &p); err != nil {
			return err
		}
		return w.processMedia(ctx, p.MediaID)
	case store.JobEnsureRenditions:
		var p mediaPayload
		if err := json.Unmarshal(job.Payload, &p); err != nil {
			return err
		}
		return w.ensureRenditions(ctx, p.MediaID)
	case store.JobThumbs:
		var p itemPayload
		if err := json.Unmarshal(job.Payload, &p); err != nil {
			return err
		}
		return w.thumbs(ctx, p.ItemID)
	}
	return fmt.Errorf("unknown job kind %q", job.Kind)
}

func (w *Worker) giveUp(ctx context.Context, job *store.Job) {
	if job.Kind != store.JobProcessMedia {
		return
	}
	var p mediaPayload
	if json.Unmarshal(job.Payload, &p) == nil {
		w.failMedia(ctx, p.MediaID, "server_error")
	}
}

func (w *Worker) failMedia(ctx context.Context, mediaID uuid.UUID, code string) {
	m, _ := w.Store.GetMedia(ctx, w.Store.Q(), mediaID)
	err := w.Store.InTx(ctx, func(tx *store.Tx) error { return tx.SetMediaFailed(ctx, mediaID, code) })
	if err != nil {
		w.Log.Error("mark media failed", "media", mediaID, "err", err)
		return
	}
	if m != nil && m.SourcePath != nil && w.RemoveSource != nil {
		w.RemoveSource(*m.SourcePath)
	}
	if w.Events != nil {
		w.Events.MediaFailed(mediaID)
	}
}

// failureCode maps a pipeline error to an item failure code; "" means retry.
func failureCode(err error) string {
	switch {
	case errors.Is(err, errTooLong):
		return "video_too_long"
	case errors.Is(err, media.ErrUnsupported):
		return "unsupported_media"
	case errors.Is(err, media.ErrCorrupt):
		return "corrupt_media"
	}
	return ""
}

var errTooLong = errors.New("video too long")

func (w *Worker) processMedia(ctx context.Context, mediaID uuid.UUID) error {
	m, err := w.Store.GetMedia(ctx, w.Store.Q(), mediaID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return nil
		}
		return err
	}
	if m.Status != "processing" {
		return w.ensureRenditions(ctx, mediaID)
	}
	if m.SourcePath == nil {
		w.failMedia(ctx, mediaID, "corrupt_media")
		return nil
	}
	master, err := w.makeMaster(ctx, m)
	if code := failureCode(err); code != "" {
		w.Log.Info("media rejected", "media", mediaID, "code", code, "err", err)
		w.failMedia(ctx, mediaID, code)
		return nil
	}
	if err != nil {
		return err
	}
	if err := w.Store.InTx(ctx, func(tx *store.Tx) error { return tx.SetMediaReady(ctx, mediaID, *master) }); err != nil {
		return err
	}
	if w.RemoveSource != nil {
		w.RemoveSource(*m.SourcePath)
	}
	items, err := w.Store.ItemRowsOfMedia(ctx, mediaID)
	if err != nil {
		return err
	}
	for _, it := range items {
		if err := w.thumbs(ctx, it.ItemID); err != nil {
			return err
		}
	}
	return w.ensureRenditions(ctx, mediaID)
}

func (w *Worker) makeMaster(ctx context.Context, m *store.Media) (*store.MediaMaster, error) {
	src := *m.SourcePath
	if _, err := os.Stat(src); err != nil {
		return nil, fmt.Errorf("source missing: %w", media.ErrCorrupt)
	}
	kind, _, err := media.Sniff(src)
	if err != nil {
		return nil, err
	}
	srcSHA, _, err := media.FileDigest(src)
	if err != nil {
		return nil, err
	}
	tmp, err := w.tempDir()
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(tmp)

	out := &store.MediaMaster{SourceSHA256: srcSHA}
	var masterPath string
	switch kind {
	case media.Photo:
		masterPath = filepath.Join(tmp, "master.webp")
		size, captured, err := w.Tools.PhotoMaster(ctx, src, masterPath)
		if err != nil {
			return nil, err
		}
		out.Width, out.Height, out.CapturedAt = size.W, size.H, captured
		out.Mime = "image/webp"
		out.Key = fmt.Sprintf("media/%s/master.webp", m.ID)
	case media.Video:
		info, err := w.Tools.ProbeVideo(ctx, src)
		if err != nil {
			return nil, err
		}
		var trim *media.Trim
		start, end := 0, info.DurationMS
		if m.TrimStartMS != nil || m.TrimEndMS != nil {
			trim = &media.Trim{}
			if m.TrimStartMS != nil {
				trim.StartMS, start = *m.TrimStartMS, *m.TrimStartMS
			}
			if m.TrimEndMS != nil && *m.TrimEndMS < info.DurationMS {
				trim.EndMS, end = *m.TrimEndMS, *m.TrimEndMS
			}
		}
		if w.MaxVideoMS != nil && end-start > w.MaxVideoMS(ctx) {
			return nil, errTooLong
		}
		masterPath = filepath.Join(tmp, "master.mp4")
		vi, err := w.Tools.VideoMaster(ctx, src, info, trim, m.IncludeAudio, masterPath)
		if err != nil {
			return nil, err
		}
		posterPath := filepath.Join(tmp, "poster.webp")
		if err := w.Tools.Poster(ctx, masterPath, vi.DurationMS, posterPath); err != nil {
			return nil, err
		}
		posterKey := fmt.Sprintf("media/%s/poster.webp", m.ID)
		if err := w.Blob.PutFile(ctx, posterKey, posterPath, blob.PutOptions{ContentType: "image/webp"}); err != nil {
			return nil, err
		}
		out.Width, out.Height, out.CapturedAt = vi.Size.W, vi.Size.H, vi.CapturedAt
		out.DurationMS, out.HasAudio = &vi.DurationMS, &vi.HasAudio
		out.PosterKey = &posterKey
		out.Mime = "video/mp4"
		out.Key = fmt.Sprintf("media/%s/master.mp4", m.ID)
	}
	if out.SHA256, out.Bytes, err = media.FileDigest(masterPath); err != nil {
		return nil, err
	}
	if err := w.Blob.PutFile(ctx, out.Key, masterPath, blob.PutOptions{ContentType: out.Mime, SHA256: out.SHA256}); err != nil {
		return nil, err
	}
	return out, nil
}

func (w *Worker) thumbs(ctx context.Context, itemID uuid.UUID) error {
	it, err := w.Store.GetItemRow(ctx, w.Store.Q(), itemID)
	if errors.Is(err, store.ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	if it.MediaStatus != "ready" || it.Width == nil || it.Height == nil {
		return nil // process_media generates thumbs once the master exists
	}
	srcKey := it.MasterKey
	if it.Kind == string(media.Video) {
		srcKey = it.PosterKey
	}
	if srcKey == nil {
		return nil
	}
	tmp, err := w.tempDir()
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	src, err := blob.LocalFile(ctx, w.Blob, *srcKey, tmp)
	if err != nil {
		return err
	}
	fx, fy := 0.5, 0.5
	if it.FocalX != nil && it.FocalY != nil {
		fx, fy = *it.FocalX, *it.FocalY
	} else {
		fx, fy = w.Focal.Focal(ctx, src)
	}
	// A fresh suffix per generation keeps signed thumbnail URLs cache-safe.
	suffix := uuid.NewString()[:8]
	keys := make([]string, len(media.ThumbSizes))
	size := media.Size{W: *it.Width, H: *it.Height}
	for i, n := range media.ThumbSizes {
		p := filepath.Join(tmp, fmt.Sprintf("t%d.webp", n))
		if err := w.Tools.Thumb(ctx, src, size, n, fx, fy, p); err != nil {
			return err
		}
		keys[i] = fmt.Sprintf("items/%s/thumb_%d-%s.webp", itemID, n, suffix)
		if err := w.Blob.PutFile(ctx, keys[i], p, blob.PutOptions{ContentType: "image/webp"}); err != nil {
			return err
		}
	}
	if err := w.Store.SetItemThumbs(ctx, itemID, keys[0], keys[1]); err != nil {
		return err
	}
	for _, old := range []*string{it.Thumb256Key, it.Thumb512Key} {
		if old != nil {
			if err := w.Blob.Delete(ctx, *old); err != nil {
				w.Log.Warn("delete old thumbnail", "key", *old, "err", err)
			}
		}
	}
	return nil
}

// Variant returns the rendition variant a device needs for a media kind.
func Variant(kind string, t store.MediaTarget) string {
	screen := media.Size{W: t.ScreenW, H: t.ScreenH}
	if kind == string(media.Video) {
		return media.VideoVariant(screen, media.Size{W: t.DecodeW, H: t.DecodeH})
	}
	return media.PhotoVariant(screen)
}

// ensureRenditions renders whatever frame renditions the media's frames need
// and marks their placements ready.
func (w *Worker) ensureRenditions(ctx context.Context, mediaID uuid.UUID) error {
	m, err := w.Store.GetMedia(ctx, w.Store.Q(), mediaID)
	if errors.Is(err, store.ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	if m.Status != "ready" {
		return nil
	}
	targets, err := w.Store.MediaTargets(ctx, mediaID)
	if err != nil {
		return err
	}
	existing, err := w.Store.RenditionsFor(ctx, []uuid.UUID{mediaID})
	if err != nil {
		return err
	}
	have := map[string]bool{}
	for _, r := range existing[mediaID] {
		have[r.Variant] = true
	}
	for _, t := range targets {
		v := Variant(m.Kind, t)
		if have[v] {
			continue
		}
		if err := w.render(ctx, m, t, v); err != nil {
			return err
		}
		have[v] = true
	}
	var changes []store.StatusChange
	err = w.Store.InTx(ctx, func(tx *store.Tx) error {
		changes = nil
		for _, t := range targets {
			ch, err := tx.MarkReady(ctx, mediaID, t.FrameID)
			if err != nil {
				return err
			}
			changes = append(changes, ch...)
			ids, err := w.Store.LivePlacementsOfMedia(ctx, tx, mediaID, t.FrameID)
			if err != nil {
				return err
			}
			for _, id := range ids {
				if err := tx.FeedPut(ctx, t.FrameID, store.FeedPlacement, id, false); err != nil {
					return err
				}
			}
		}
		return nil
	})
	if err != nil {
		return err
	}
	if len(changes) > 0 && w.Events != nil {
		w.Events.PlacementsChanged(changes)
	}
	return nil
}

func (w *Worker) render(ctx context.Context, m *store.Media, t store.MediaTarget, variant string) error {
	tmp, err := w.tempDir()
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	master, err := blob.LocalFile(ctx, w.Blob, *m.MasterKey, tmp)
	if err != nil {
		return err
	}
	screen := media.Size{W: t.ScreenW, H: t.ScreenH}
	r := store.Rendition{MediaID: m.ID, Variant: variant}
	var out string
	if m.Kind == string(media.Video) {
		out = filepath.Join(tmp, "r.mp4")
		info := media.VideoInfo{Size: media.Size{W: *m.Width, H: *m.Height}, HasAudio: m.HasAudio != nil && *m.HasAudio}
		if probed, err := w.Tools.ProbeVideo(ctx, master); err == nil {
			info.FPS = probed.FPS
		}
		size, err := w.Tools.VideoRendition(ctx, master, info, media.Size{W: t.DecodeW, H: t.DecodeH}, out)
		if err != nil {
			return err
		}
		r.Width, r.Height, r.Mime = size.W, size.H, "video/mp4"
		r.Key = fmt.Sprintf("media/%s/%s.mp4", m.ID, variant)
		if m.PosterKey != nil {
			poster, err := blob.LocalFile(ctx, w.Blob, *m.PosterKey, tmp)
			if err != nil {
				return err
			}
			pout := filepath.Join(tmp, "p.webp")
			if err := w.Tools.ResizeImage(ctx, poster, size, media.RenditionPhotoQ, pout); err != nil {
				return err
			}
			pk := fmt.Sprintf("media/%s/%s-poster.webp", m.ID, variant)
			if err := w.Blob.PutFile(ctx, pk, pout, blob.PutOptions{ContentType: "image/webp"}); err != nil {
				return err
			}
			r.PosterKey = &pk
		}
	} else {
		out = filepath.Join(tmp, "r.webp")
		size, err := w.Tools.PhotoRendition(ctx, master, media.Size{W: *m.Width, H: *m.Height}, screen.Long(), out)
		if err != nil {
			return err
		}
		r.Width, r.Height, r.Mime = size.W, size.H, "image/webp"
		r.Key = fmt.Sprintf("media/%s/%s.webp", m.ID, variant)
	}
	if r.SHA256, r.Bytes, err = media.FileDigest(out); err != nil {
		return err
	}
	if err := w.Blob.PutFile(ctx, r.Key, out, blob.PutOptions{ContentType: r.Mime, SHA256: r.SHA256}); err != nil {
		return err
	}
	return w.Store.PutRendition(ctx, r)
}

// tempDir creates a per-job scratch directory; callers remove it.
func (w *Worker) tempDir() (string, error) {
	dir := w.Scratch
	if dir == "" {
		dir = os.TempDir()
	}
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return "", err
	}
	return os.MkdirTemp(dir, "job-*")
}
