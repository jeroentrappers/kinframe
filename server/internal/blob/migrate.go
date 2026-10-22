package blob

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"
)

// MigrateOptions tune Migrate.
type MigrateOptions struct {
	Prefix        string        // only keys with this prefix ("" for all)
	DryRun        bool          // report what would be copied, change nothing
	Workers       int           // parallel copies (default 4)
	SkipVerify    bool          // skip re-reading each copy to check its sha256
	ProgressEvery time.Duration // progress log interval (default 10 s)
	Log           *slog.Logger
}

// MigrateStats counts what Migrate did.
type MigrateStats struct {
	Scanned, Copied, Skipped, Failed int64
	Bytes                            int64 // bytes copied (or that would be, in a dry run)
}

// Migrate copies every object from src to dst. It is idempotent and
// resumable: objects already in dst with the same size and sha256 are
// skipped, others are (re)copied and verified. src is never modified.
func Migrate(ctx context.Context, src, dst Store, o MigrateOptions) (MigrateStats, error) {
	if o.Workers < 1 {
		o.Workers = 4
	}
	if o.ProgressEvery <= 0 {
		o.ProgressEvery = 10 * time.Second
	}
	log := o.Log
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	var st MigrateStats
	keys := make(chan Info)
	var wg sync.WaitGroup
	for range o.Workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for info := range keys {
				copied, err := migrateOne(ctx, src, dst, info, o, log)
				switch {
				case err != nil:
					atomic.AddInt64(&st.Failed, 1)
					log.Error("copy failed", "key", info.Key, "err", err)
				case copied:
					atomic.AddInt64(&st.Copied, 1)
					atomic.AddInt64(&st.Bytes, info.Size)
				default:
					atomic.AddInt64(&st.Skipped, 1)
				}
			}
		}()
	}
	start := time.Now()
	progress := func(msg string) {
		log.Info(msg, "scanned", atomic.LoadInt64(&st.Scanned), "copied", atomic.LoadInt64(&st.Copied),
			"skipped", atomic.LoadInt64(&st.Skipped), "failed", atomic.LoadInt64(&st.Failed),
			"bytes", atomic.LoadInt64(&st.Bytes), "elapsed", time.Since(start).Round(time.Second), "dry_run", o.DryRun)
	}
	done := make(chan struct{})
	go func() {
		tick := time.NewTicker(o.ProgressEvery)
		defer tick.Stop()
		for {
			select {
			case <-tick.C:
				progress("migrating")
			case <-done:
				return
			}
		}
	}()
	listErr := src.List(ctx, o.Prefix, func(info Info) error {
		atomic.AddInt64(&st.Scanned, 1)
		select {
		case keys <- info:
			return nil
		case <-ctx.Done():
			return ctx.Err()
		}
	})
	close(keys)
	wg.Wait()
	close(done)
	progress("migration finished")
	if listErr != nil {
		return st, fmt.Errorf("list %s: %w", src.Backend(), listErr)
	}
	if st.Failed > 0 {
		return st, fmt.Errorf("%d object(s) failed; run the command again to retry them", st.Failed)
	}
	return st, nil
}

// migrateOne returns whether the object was (or, dry run, would be) copied.
func migrateOne(ctx context.Context, src, dst Store, info Info, o MigrateOptions, log *slog.Logger) (bool, error) {
	srcSum := info.SHA256
	if srcSum == "" {
		var n int64
		var err error
		if srcSum, n, err = Digest(ctx, src, info.Key); err != nil {
			return false, fmt.Errorf("read source: %w", err)
		}
		if n != info.Size {
			return false, fmt.Errorf("source changed while migrating (%d != %d bytes)", n, info.Size)
		}
	}
	if di, err := dst.Stat(ctx, info.Key); err == nil && di.Size == info.Size {
		dstSum := di.SHA256
		if dstSum == "" {
			if dstSum, _, err = Digest(ctx, dst, info.Key); err != nil {
				return false, fmt.Errorf("read destination: %w", err)
			}
		}
		if dstSum == srcSum {
			return false, nil
		}
	} else if err != nil && !errors.Is(err, ErrNotFound) {
		return false, fmt.Errorf("stat destination: %w", err)
	}
	if o.DryRun {
		log.Debug("would copy", "key", info.Key, "bytes", info.Size)
		return true, nil
	}
	obj, _, err := src.Open(ctx, info.Key)
	if err != nil {
		return false, fmt.Errorf("open source: %w", err)
	}
	defer obj.Close()
	h := sha256.New()
	ct := info.ContentType
	if ct == "" || ct == "application/octet-stream" {
		ct = ContentType(info.Key)
	}
	if err := dst.Put(ctx, info.Key, io.TeeReader(obj, h), info.Size, PutOptions{ContentType: ct, SHA256: srcSum}); err != nil {
		return false, fmt.Errorf("write destination: %w", err)
	}
	if got := hex.EncodeToString(h.Sum(nil)); got != srcSum {
		_ = dst.Delete(ctx, info.Key)
		return false, fmt.Errorf("source changed while copying (sha256 %s, expected %s)", got, srcSum)
	}
	di, err := dst.Stat(ctx, info.Key)
	if err != nil {
		return false, fmt.Errorf("verify: %w", err)
	}
	if di.Size != info.Size {
		return false, fmt.Errorf("verify: destination has %d bytes, expected %d", di.Size, info.Size)
	}
	if !o.SkipVerify {
		sum, _, err := Digest(ctx, dst, info.Key)
		if err != nil {
			return false, fmt.Errorf("verify: %w", err)
		}
		if sum != srcSum {
			return false, fmt.Errorf("verify: destination sha256 %s, expected %s", sum, srcSum)
		}
	}
	log.Debug("copied", "key", info.Key, "bytes", info.Size)
	return true, nil
}
