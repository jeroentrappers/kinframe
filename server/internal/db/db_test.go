package db_test

import (
	"context"
	"os"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/testdb"
)

func TestMain(m *testing.M) {
	code := m.Run()
	testdb.Stop()
	os.Exit(code)
}

func TestMigrate(t *testing.T) {
	pool, _ := testdb.New(t)
	var n int
	if err := pool.QueryRow(context.Background(),
		`SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='placements'`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("placements table missing")
	}
}
