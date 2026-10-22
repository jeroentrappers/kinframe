// Command kinframe-server runs the kinframe backend.
//
//	kinframe-server           serve the API (default)
//	kinframe-server migrate      apply database migrations and exit
//	kinframe-server healthcheck  probe GET /healthz on the local listener (for Docker)
//	kinframe-server bootstrap-logto  configure Logto for kinframe (idempotent, see server/README.md)
//	kinframe-server migrate-blobs --from fs --to s3  copy stored media between blob backends
package main

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/app"
	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/db"
	"github.com/jeroentrappers/kinframe/server/internal/logtoboot"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "kinframe-server:", err)
		os.Exit(1)
	}
}

func run() error {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		return healthcheck()
	}
	if len(os.Args) > 1 && os.Args[1] == "bootstrap-logto" {
		return bootstrapLogto()
	}
	if len(os.Args) > 1 && os.Args[1] == "migrate-blobs" {
		return migrateBlobs(os.Args[2:])
	}
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	opts := &slog.HandlerOptions{Level: cfg.LogLevel}
	var h slog.Handler = slog.NewTextHandler(os.Stderr, opts)
	if cfg.LogJSON {
		h = slog.NewJSONHandler(os.Stderr, opts)
	}
	log := slog.New(h)
	slog.SetDefault(log)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	cmd := "serve"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	switch cmd {
	case "migrate":
		pool, err := db.Open(ctx, cfg.DatabaseURL)
		if err != nil {
			return err
		}
		defer pool.Close()
		if err := db.Migrate(ctx, pool); err != nil {
			return err
		}
		log.Info("migrations applied")
		return nil
	case "serve":
		a, err := app.New(ctx, cfg, log)
		if err != nil {
			return err
		}
		l, err := net.Listen("tcp", cfg.Listen)
		if err != nil {
			return err
		}
		return a.Serve(ctx, l)
	default:
		return fmt.Errorf("unknown command %q (want serve, migrate, healthcheck, bootstrap-logto or migrate-blobs)", cmd)
	}
}

// bootstrapLogto has its own configuration (it needs neither the database
// nor the signing key). Logs go to stderr; stdout only carries the one-time
// machine-to-machine credentials.
func bootstrapLogto() error {
	cfg, err := logtoboot.LoadConfig()
	if err != nil {
		return err
	}
	var h slog.Handler = slog.NewTextHandler(os.Stderr, nil)
	if os.Getenv("KINFRAME_LOG_FORMAT") == "json" {
		h = slog.NewJSONHandler(os.Stderr, nil)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	return logtoboot.Run(ctx, cfg, slog.New(h), os.Stdout)
}

func healthcheck() error {
	addr := os.Getenv("KINFRAME_LISTEN")
	if addr == "" {
		addr = ":8110"
	}
	if strings.HasPrefix(addr, ":") {
		addr = "127.0.0.1" + addr
	}
	client := &http.Client{Timeout: 4 * time.Second}
	resp, err := client.Get("http://" + addr + "/healthz")
	if err != nil {
		return err
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("healthz: %s", resp.Status)
	}
	return nil
}
