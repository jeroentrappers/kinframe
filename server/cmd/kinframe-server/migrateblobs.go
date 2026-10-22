package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/jeroentrappers/kinframe/server/internal/blob"
	"github.com/jeroentrappers/kinframe/server/internal/config"
)

// migrateBlobs copies stored media between backends. It reads only the blob
// settings (KINFRAME_DATA_DIR / KINFRAME_BLOB_FS_ROOT for fs, KINFRAME_S3_*
// for s3), so it can run next to a live server: switch the server over after
// a final run, then run once more to pick up anything written in between.
func migrateBlobs(args []string) error {
	fs := flag.NewFlagSet("migrate-blobs", flag.ContinueOnError)
	from := fs.String("from", "fs", "source backend (fs or s3)")
	to := fs.String("to", "s3", "destination backend (fs or s3)")
	dry := fs.Bool("dry-run", false, "only report what would be copied")
	prefix := fs.String("prefix", "", "only keys with this prefix (e.g. media/)")
	workers := fs.Int("workers", 4, "parallel copies")
	noVerify := fs.Bool("no-verify", false, "do not re-read copies to check their sha256 (size is still checked)")
	verbose := fs.Bool("v", false, "log every object")
	fs.Usage = func() {
		fmt.Fprintln(fs.Output(), "usage: kinframe-server migrate-blobs --from fs --to s3 [--dry-run] [--prefix p] [--workers n] [--no-verify] [-v]")
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
	*from, *to = strings.ToLower(*from), strings.ToLower(*to)
	if *from == *to {
		return errors.New("--from and --to must differ")
	}
	level := slog.LevelInfo
	if *verbose {
		level = slog.LevelDebug
	}
	var h slog.Handler = slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level})
	if os.Getenv("KINFRAME_LOG_FORMAT") == "json" {
		h = slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: level})
	}
	log := slog.New(h)

	dataDir := os.Getenv("KINFRAME_DATA_DIR")
	if dataDir == "" {
		dataDir = "./data"
	}
	cfg, err := config.LoadBlob(func(k string) string {
		if k == "KINFRAME_BLOB_BACKEND" || k == "KINFRAME_MEDIA_REDIRECT" {
			return "" // the server's choice does not matter here
		}
		return os.Getenv(k)
	}, dataDir)
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	open := func(backend string) (blob.Store, error) {
		if backend == config.BlobFS {
			if _, err := os.Stat(cfg.FSRoot); err != nil {
				return nil, fmt.Errorf("fs store: %w", err)
			}
		}
		return cfg.OpenBackend(ctx, backend)
	}
	src, err := open(*from)
	if err != nil {
		return fmt.Errorf("source: %w", err)
	}
	if *dry && *to == config.BlobS3 {
		cfg.S3.CreateBucket = false // a dry run changes nothing
	}
	dst, err := open(*to)
	if err != nil {
		return fmt.Errorf("destination: %w", err)
	}
	log.Info("migrating blobs", "from", *from, "to", *to, "fs_root", cfg.FSRoot, "bucket", cfg.S3.Bucket,
		"s3_prefix", cfg.S3.Prefix, "prefix", *prefix, "dry_run", *dry)
	_, err = blob.Migrate(ctx, src, dst, blob.MigrateOptions{
		Prefix: *prefix, DryRun: *dry, Workers: *workers, SkipVerify: *noVerify, Log: log,
	})
	return err
}
