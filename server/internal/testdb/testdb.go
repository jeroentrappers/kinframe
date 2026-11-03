// Package testdb provides a throwaway PostgreSQL 17 for integration tests.
//
// If KINFRAME_TEST_DATABASE_URL is set, that server is used (each call gets a
// fresh database). Otherwise an embedded PostgreSQL is started once per test
// binary (binaries are downloaded and cached under ~/.cache/kinframe).
package testdb

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	embeddedpostgres "github.com/fergusstrange/embedded-postgres"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/jeroentrappers/kinframe/server/internal/db"
)

var (
	once    sync.Once
	baseURL string
	initErr error
	counter atomic.Int64
	pg      *embeddedpostgres.EmbeddedPostgres
	pgRoot  string // per-run directory with the server's runtime and data (~420 MB); removed by Stop
)

// Stop shuts down the embedded server and deletes its directory; call from TestMain after m.Run.
func Stop() {
	if pg != nil {
		_ = pg.Stop()
	}
	if pgRoot != "" {
		_ = os.RemoveAll(pgRoot)
	}
}

// removeStaleRoots deletes directories left by test binaries that were killed before Stop ran.
// /tmp is often a RAM-backed tmpfs, so leftovers add up quickly.
func removeStaleRoots() {
	old, _ := filepath.Glob(filepath.Join(os.TempDir(), "kinframe-pg-*"))
	for _, dir := range old {
		if fi, err := os.Stat(dir); err == nil && time.Since(fi.ModTime()) > 6*time.Hour {
			_ = os.RemoveAll(dir)
		}
	}
}

func start() {
	if u := os.Getenv("KINFRAME_TEST_DATABASE_URL"); u != "" {
		baseURL = u
		return
	}
	port, err := freePort()
	if err != nil {
		initErr = err
		return
	}
	cache, _ := os.UserCacheDir()
	removeStaleRoots()
	root, err := os.MkdirTemp("", "kinframe-pg-*")
	if err != nil {
		initErr = err
		return
	}
	pgRoot = root
	cfg := embeddedpostgres.DefaultConfig().
		Version(embeddedpostgres.V17).
		Port(uint32(port)).
		Username("kinframe").Password("kinframe").Database("kinframe").
		RuntimePath(filepath.Join(root, "run")).
		DataPath(filepath.Join(root, "data")).
		CachePath(filepath.Join(cache, "kinframe", "embedded-postgres")).
		Logger(io.Discard)
	pg = embeddedpostgres.NewDatabase(cfg)
	if err := pg.Start(); err != nil {
		initErr = fmt.Errorf("start embedded postgres: %w", err)
		_ = os.RemoveAll(root)
		pgRoot = ""
		return
	}
	baseURL = fmt.Sprintf("postgres://kinframe:kinframe@127.0.0.1:%d/kinframe?sslmode=disable", port)
}

func freePort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}

// New returns a pool connected to a fresh, migrated database.
func New(t testing.TB) (*pgxpool.Pool, string) {
	t.Helper()
	once.Do(start)
	if initErr != nil {
		t.Fatalf("testdb: %v", initErr)
	}
	ctx := context.Background()
	admin, err := pgx.Connect(ctx, baseURL)
	if err != nil {
		t.Fatalf("testdb: connect: %v", err)
	}
	name := fmt.Sprintf("kf_test_%d_%d", os.Getpid(), counter.Add(1))
	if _, err := admin.Exec(ctx, "CREATE DATABASE "+name); err != nil {
		t.Fatalf("testdb: create database: %v", err)
	}
	_ = admin.Close(ctx)

	u, _ := url.Parse(baseURL)
	u.Path = "/" + name
	pool, err := db.Open(ctx, u.String())
	if err != nil {
		t.Fatalf("testdb: open: %v", err)
	}
	if err := db.Migrate(ctx, pool); err != nil {
		t.Fatalf("testdb: migrate: %v", err)
	}
	t.Cleanup(pool.Close)
	return pool, u.String()
}
