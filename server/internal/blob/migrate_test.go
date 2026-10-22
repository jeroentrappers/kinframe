package blob_test

import (
	"context"
	"fmt"
	"strings"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/blob"
	"github.com/jeroentrappers/kinframe/server/internal/blob/blobtest"
)

func TestMigrateFSToS3AndBack(t *testing.T) {
	ctx := context.Background()
	fs := blob.Local{Root: t.TempDir()}
	s3 := blobtest.New(t)
	want := map[string]string{}
	for i := range 12 {
		key := fmt.Sprintf("media/m%d/master.webp", i)
		if i%3 == 0 {
			key = fmt.Sprintf("items/i%d/thumb_256-x.webp", i)
		}
		body := strings.Repeat(fmt.Sprintf("object %d ", i), 100*(i+1))
		want[key] = body
		if err := fs.Put(ctx, key, strings.NewReader(body), int64(len(body)), blob.PutOptions{}); err != nil {
			t.Fatal(err)
		}
	}

	// Dry run copies nothing.
	st, err := blob.Migrate(ctx, fs, s3, blob.MigrateOptions{DryRun: true})
	if err != nil || st.Scanned != 12 || st.Copied != 12 {
		t.Fatalf("dry run: %+v %v", st, err)
	}
	if n := count(t, s3); n != 0 {
		t.Fatalf("dry run wrote %d objects", n)
	}

	// Partial run (prefix), then the full run copies the rest.
	if st, err = blob.Migrate(ctx, fs, s3, blob.MigrateOptions{Prefix: "items/"}); err != nil || st.Copied != 4 {
		t.Fatalf("prefix run: %+v %v", st, err)
	}
	if st, err = blob.Migrate(ctx, fs, s3, blob.MigrateOptions{Workers: 3}); err != nil || st.Copied != 8 || st.Skipped != 4 {
		t.Fatalf("resume: %+v %v", st, err)
	}
	for k, body := range want {
		info, err := s3.Stat(ctx, k)
		if err != nil || info.Size != int64(len(body)) || info.SHA256 == "" || info.ContentType != "image/webp" {
			t.Fatalf("%s: %+v %v", k, info, err)
		}
	}

	// A damaged copy (same size, different bytes) is detected and replaced.
	damaged := strings.Repeat("X", len(want["media/m1/master.webp"]))
	if err := s3.Put(ctx, "media/m1/master.webp", strings.NewReader(damaged), int64(len(damaged)), blob.PutOptions{}); err != nil {
		t.Fatal(err)
	}
	if st, err = blob.Migrate(ctx, fs, s3, blob.MigrateOptions{}); err != nil || st.Copied != 1 || st.Skipped != 11 {
		t.Fatalf("repair: %+v %v", st, err)
	}

	// And back to an empty filesystem store.
	back := blob.Local{Root: t.TempDir()}
	if st, err = blob.Migrate(ctx, s3, back, blob.MigrateOptions{}); err != nil || st.Copied != 12 {
		t.Fatalf("s3 to fs: %+v %v", st, err)
	}
	for k, body := range want {
		sum, n, err := blob.Digest(ctx, back, k)
		ref, _, _ := blob.Digest(ctx, fs, k)
		if err != nil || n != int64(len(body)) || sum != ref {
			t.Fatalf("%s round trip: %v", k, err)
		}
	}
}

func count(t *testing.T, s blob.Store) int {
	n := 0
	if err := s.List(context.Background(), "", func(blob.Info) error { n++; return nil }); err != nil {
		t.Fatal(err)
	}
	return n
}
