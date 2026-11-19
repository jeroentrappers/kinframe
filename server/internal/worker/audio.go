package worker

// Voice messages (README §5.19): media of kind audio get an AAC master and a waveform; no thumbnails, no
// renditions (they are never placed on frames).

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/google/uuid"

	"github.com/jeroentrappers/kinframe/server/internal/blob"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// MediaReadyEvents is implemented by Events that want to hear about media without placements becoming ready
// (chat photos, videos and voice messages).
type MediaReadyEvents interface {
	MediaReady(mediaID uuid.UUID)
}

func (w *Worker) mediaReady(mediaID uuid.UUID) {
	if r, ok := w.Events.(MediaReadyEvents); ok {
		r.MediaReady(mediaID)
	}
}

// MaxVoiceMS is the voice message length limit.
const MaxVoiceMS = 300_000

func (w *Worker) processAudio(ctx context.Context, m *store.Media) error {
	if m.SourcePath == nil {
		w.failMedia(ctx, m.ID, "corrupt_media")
		return nil
	}
	src := *m.SourcePath
	if _, err := os.Stat(src); err != nil {
		w.failMedia(ctx, m.ID, "corrupt_media")
		return nil
	}
	tmp, err := w.tempDir()
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)
	start := time.Now()
	masterPath := filepath.Join(tmp, "master.m4a")
	info, err := w.Tools.VoiceMaster(ctx, src, masterPath, MaxVoiceMS)
	if code := audioFailure(err); code != "" {
		w.Log.Info("voice rejected", "media", m.ID, "code", code, "err", err)
		w.failMedia(ctx, m.ID, code)
		return nil
	}
	if err != nil {
		return err
	}
	if info.DurationMS < media.MinVoiceMS {
		w.failMedia(ctx, m.ID, "validation_failed")
		return nil
	}
	waveform, err := w.Tools.Waveform(ctx, masterPath)
	if err != nil {
		return err
	}
	srcSHA, _, err := media.FileDigest(src)
	if err != nil {
		return err
	}
	out := store.MediaMaster{SourceSHA256: srcSHA, Pipeline: "transcode", Mime: media.VoiceMasterMime(),
		Key: fmt.Sprintf("media/%s/master.m4a", m.ID), DurationMS: &info.DurationMS, HasAudio: ptrBool(true)}
	if out.SHA256, out.Bytes, err = media.FileDigest(masterPath); err != nil {
		return err
	}
	if err := w.Blob.PutFile(ctx, out.Key, masterPath, blob.PutOptions{ContentType: out.Mime, SHA256: out.SHA256}); err != nil {
		return err
	}
	wf := make([]int16, len(waveform))
	for i, v := range waveform {
		wf[i] = int16(v)
	}
	err = w.Store.InTx(ctx, func(tx *store.Tx) error {
		if err := tx.SetMediaReady(ctx, m.ID, out); err != nil {
			return err
		}
		return tx.SetMediaWaveform(ctx, m.ID, wf)
	})
	if err != nil {
		return err
	}
	if w.RemoveSource != nil {
		w.RemoveSource(src)
	}
	w.Log.Info("voice master", "media", m.ID, "ms", info.DurationMS, "bytes", out.Bytes, "took", time.Since(start).Round(time.Millisecond))
	w.mediaReady(m.ID)
	return nil
}

// audioFailure maps a voice pipeline error to a failure code; "" means retry.
func audioFailure(err error) string {
	switch {
	case errors.Is(err, media.ErrAudioTooLong):
		return "audio_too_long"
	case errors.Is(err, media.ErrUnsupported):
		return "unsupported_media"
	case errors.Is(err, media.ErrCorrupt):
		return "corrupt_media"
	}
	return ""
}

func ptrBool(b bool) *bool { return &b }
