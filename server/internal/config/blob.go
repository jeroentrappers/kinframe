package config

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"

	"github.com/jeroentrappers/kinframe/server/internal/blob"
)

// Blob storage backends.
const (
	BlobFS = "fs"
	BlobS3 = "s3"
)

// Media serving modes.
const (
	MediaRedirectOff       = ""
	MediaRedirectPresigned = "presigned"
)

// BlobConfig selects and configures where processed media is stored.
type BlobConfig struct {
	Backend string // BlobFS or BlobS3
	FSRoot  string // <data dir>/media
	S3      blob.S3Config
	// MediaRedirect "presigned" answers signed media URLs and avatars with a
	// 302 to a short-lived presigned bucket URL instead of streaming (S3 only).
	MediaRedirect string
}

// LoadBlob reads the blob settings only (used by migrate-blobs, which needs
// neither the database nor auth settings).
func LoadBlob(get func(string) string, dataDir string) (BlobConfig, error) {
	env := func(k, def string) string {
		if v := strings.TrimSpace(get(k)); v != "" {
			return v
		}
		return def
	}
	var errs []error
	boolv := func(k string, def bool) bool {
		switch strings.ToLower(env(k, "")) {
		case "":
			return def
		case "1", "true", "yes", "on":
			return true
		case "0", "false", "no", "off":
			return false
		}
		errs = append(errs, fmt.Errorf("%s must be true or false", k))
		return def
	}
	c := BlobConfig{
		Backend: strings.ToLower(env("KINFRAME_BLOB_BACKEND", BlobFS)),
		FSRoot:  env("KINFRAME_BLOB_FS_ROOT", filepath.Join(dataDir, "media")),
		S3: blob.S3Config{
			Endpoint:       env("KINFRAME_S3_ENDPOINT", ""),
			PublicEndpoint: env("KINFRAME_S3_PUBLIC_ENDPOINT", ""),
			Region:         env("KINFRAME_S3_REGION", ""),
			Bucket:         env("KINFRAME_S3_BUCKET", ""),
			AccessKey:      env("KINFRAME_S3_ACCESS_KEY", ""),
			SecretKey:      env("KINFRAME_S3_SECRET_KEY", ""),
			UseSSL:         boolv("KINFRAME_S3_USE_SSL", true),
			PathStyle:      boolv("KINFRAME_S3_PATH_STYLE", false),
			Prefix:         env("KINFRAME_S3_PREFIX", ""),
			CreateBucket:   boolv("KINFRAME_S3_CREATE_BUCKET", true),
		},
		MediaRedirect: strings.ToLower(env("KINFRAME_MEDIA_REDIRECT", "")),
	}
	switch c.MediaRedirect {
	case "off", "none", "stream":
		c.MediaRedirect = MediaRedirectOff
	case MediaRedirectOff, MediaRedirectPresigned:
	default:
		errs = append(errs, fmt.Errorf("KINFRAME_MEDIA_REDIRECT must be off or presigned, got %q", c.MediaRedirect))
	}
	switch c.Backend {
	case BlobFS:
		if c.MediaRedirect == MediaRedirectPresigned {
			errs = append(errs, errors.New("KINFRAME_MEDIA_REDIRECT=presigned needs KINFRAME_BLOB_BACKEND=s3"))
		}
	case BlobS3:
		if err := c.CheckS3(); err != nil {
			errs = append(errs, err)
		}
	default:
		errs = append(errs, fmt.Errorf("KINFRAME_BLOB_BACKEND must be fs or s3, got %q", c.Backend))
	}
	return c, errors.Join(errs...)
}

// CheckS3 reports missing S3 settings.
func (c BlobConfig) CheckS3() error {
	var missing []string
	for k, v := range map[string]string{
		"KINFRAME_S3_ENDPOINT": c.S3.Endpoint, "KINFRAME_S3_BUCKET": c.S3.Bucket,
		"KINFRAME_S3_ACCESS_KEY": c.S3.AccessKey, "KINFRAME_S3_SECRET_KEY": c.S3.SecretKey,
	} {
		if v == "" {
			missing = append(missing, k)
		}
	}
	if len(missing) > 0 {
		return fmt.Errorf("S3 storage needs %s", strings.Join(slices.Sorted(slices.Values(missing)), ", "))
	}
	return nil
}

// Open connects to the configured store (creating the fs root or the S3
// bucket when needed).
func (c BlobConfig) Open(ctx context.Context) (blob.Store, error) {
	return c.OpenBackend(ctx, c.Backend)
}

// OpenBackend opens the given backend with these settings, whatever
// KINFRAME_BLOB_BACKEND says (migrate-blobs uses both).
func (c BlobConfig) OpenBackend(ctx context.Context, backend string) (blob.Store, error) {
	switch backend {
	case BlobFS:
		if err := os.MkdirAll(c.FSRoot, 0o750); err != nil {
			return nil, err
		}
		return blob.Local{Root: c.FSRoot}, nil
	case BlobS3:
		if err := c.CheckS3(); err != nil {
			return nil, err
		}
		return blob.NewS3(ctx, c.S3)
	}
	return nil, fmt.Errorf("unknown blob backend %q (want fs or s3)", backend)
}
