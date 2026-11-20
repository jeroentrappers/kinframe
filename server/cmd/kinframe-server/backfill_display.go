package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/store"
	"github.com/jeroentrappers/kinframe/server/internal/worker"
)

// backfillDisplay queues the job that gives existing items their display
// rendition (README §5.3). The running server's workers do the work in
// throttled batches; queueing it again while it runs is a no-op, and a run
// over items that all have one changes nothing.
func backfillDisplay(ctx context.Context, cfg *config.Config, log *slog.Logger, args []string) error {
	fs := flag.NewFlagSet("backfill-display", flag.ContinueOnError)
	batch := fs.Int("batch", worker.DefaultDisplayBatch, "items per batch (one at a time, at most 500)")
	pause := fs.Duration("pause", worker.DefaultDisplayPauseMS*time.Millisecond, "pause between batches (0 means the default)")
	fs.Usage = func() {
		fmt.Fprintln(fs.Output(), "usage: kinframe-server backfill-display [--batch n] [--pause d]")
		fs.PrintDefaults()
	}
	if err := fs.Parse(args); errors.Is(err, flag.ErrHelp) {
		return nil
	} else if err != nil {
		return err
	}
	if fs.NArg() > 0 {
		return fmt.Errorf("unexpected argument %q", fs.Arg(0))
	}
	if *batch < 1 || *batch > 500 || *pause < 0 {
		return errors.New("--batch must be 1–500 and --pause not negative")
	}
	pool, err := db.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()
	st := store.New(pool)
	missing, err := st.CountItemsMissingDisplay(ctx)
	if err != nil {
		return err
	}
	queued, err := worker.EnqueueDisplayBackfill(ctx, st, *batch, *pause)
	if err != nil {
		return err
	}
	if !queued {
		log.Info("display backfill already queued or running", "items_missing", missing)
		return nil
	}
	log.Info("display backfill queued; the server's workers run it", "items_missing", missing, "batch", *batch, "pause", *pause)
	return nil
}
