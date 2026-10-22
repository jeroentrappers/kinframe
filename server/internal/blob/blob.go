// Package blob stores media files under opaque keys, either on the local
// filesystem (Local) or in an S3-compatible bucket (S3: AWS S3, MinIO/Silo,
// Hetzner, Backblaze B2, Cloudflare R2, …).
//
// Keys are slash-separated relative paths such as "media/<id>/master.webp".
// Only processed files are stored (masters, renditions, posters, thumbnails,
// avatars); tus uploads and the media pipeline's scratch files stay on local
// disk and are never written to a Store.
package blob

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"
)

// ErrNotFound is returned (wrapped) for keys that do not exist.
var ErrNotFound = errors.New("blob not found")

// ErrUnsupported is returned by optional operations a backend cannot do
// (presigned URLs on the filesystem store).
var ErrUnsupported = errors.New("not supported by this blob store")

// Info describes a stored object.
type Info struct {
	Key         string
	Size        int64
	ModTime     time.Time
	ETag        string // quoted HTTP entity tag
	ContentType string // may be empty on the filesystem store
	SHA256      string // hex, when the backend recorded it at upload; may be empty
}

// PutOptions are optional attributes of a stored object.
type PutOptions struct {
	ContentType string // derived from the key's extension when empty
	SHA256      string // hex digest, recorded as object metadata where supported
}

// Object is an open blob. It supports Seek so http.ServeContent can serve
// Range requests; for S3 each Read after a Seek is one ranged GET.
type Object interface {
	io.ReadSeekCloser
}

// Store is a blob backend.
type Store interface {
	// Backend names the implementation ("fs" or "s3").
	Backend() string
	// Put stores size bytes from r under key (size -1 if unknown), replacing
	// any existing object. Readers see either the old or the new object.
	Put(ctx context.Context, key string, r io.Reader, size int64, opts PutOptions) error
	// PutFile stores the local file src under key. The store may consume
	// src (the filesystem store renames it into place); callers must not
	// rely on src existing afterwards.
	PutFile(ctx context.Context, key, src string, opts PutOptions) error
	// Stat returns an object's metadata, or ErrNotFound.
	Stat(ctx context.Context, key string) (Info, error)
	// Open opens an object for reading, or returns ErrNotFound.
	Open(ctx context.Context, key string) (Object, Info, error)
	// Delete removes a key; missing keys are not an error.
	Delete(ctx context.Context, key string) error
	// List calls fn for every object whose key starts with prefix ("" for
	// all), in lexical order. Returning an error from fn stops the walk.
	List(ctx context.Context, prefix string, fn func(Info) error) error
	// PresignGet returns a time-limited URL that GETs the object directly
	// from the backend, or ErrUnsupported.
	PresignGet(ctx context.Context, key string, ttl time.Duration, contentType string) (string, error)
}

// Localer is implemented by stores whose objects are plain local files, so
// the media pipeline can read them in place instead of copying.
type Localer interface {
	LocalPath(key string) (string, error)
}

// CheckKey rejects empty, absolute and traversing keys.
func CheckKey(key string) error {
	if key == "" || strings.HasPrefix(key, "/") || strings.Contains(key, "..") || strings.Contains(key, "\\") ||
		strings.HasSuffix(key, "/") || path.Clean(key) != key {
		return fmt.Errorf("invalid blob key %q", key)
	}
	return nil
}

// ContentType guesses a content type from the key's extension.
func ContentType(key string) string {
	switch strings.ToLower(path.Ext(key)) {
	case ".webp":
		return "image/webp"
	case ".mp4":
		return "video/mp4"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".json":
		return "application/json"
	}
	return "application/octet-stream"
}

// LocalFile returns a local path holding key's content: the object itself
// for filesystem stores, otherwise a copy downloaded into dir (which the
// caller removes).
func LocalFile(ctx context.Context, s Store, key, dir string) (string, error) {
	if l, ok := s.(Localer); ok {
		p, err := l.LocalPath(key)
		if err != nil {
			return "", err
		}
		if _, err := os.Stat(p); err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return "", fmt.Errorf("%s: %w", key, ErrNotFound)
			}
			return "", err
		}
		return p, nil
	}
	obj, _, err := s.Open(ctx, key)
	if err != nil {
		return "", err
	}
	defer obj.Close()
	f, err := os.CreateTemp(dir, "src-*"+filepath.Ext(key))
	if err != nil {
		return "", err
	}
	if _, err := io.Copy(f, obj); err != nil {
		f.Close()
		os.Remove(f.Name())
		return "", fmt.Errorf("download %s: %w", key, err)
	}
	if err := f.Close(); err != nil {
		os.Remove(f.Name())
		return "", err
	}
	return f.Name(), nil
}

// Digest streams an object and returns its sha256 (hex) and size.
func Digest(ctx context.Context, s Store, key string) (string, int64, error) {
	obj, _, err := s.Open(ctx, key)
	if err != nil {
		return "", 0, err
	}
	defer obj.Close()
	h := sha256.New()
	n, err := io.Copy(h, obj)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}
