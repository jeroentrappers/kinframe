// Package blob stores media files on the local filesystem under opaque keys.
// Keys are slash-separated relative paths such as "media/<id>/master.webp".
package blob

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

type Local struct{ Root string }

// Path returns the absolute path of a key, rejecting traversal.
func (l Local) Path(key string) (string, error) {
	clean := filepath.Clean("/" + key)
	if clean == "/" || strings.Contains(key, "..") || strings.HasPrefix(key, "/") {
		return "", fmt.Errorf("invalid blob key %q", key)
	}
	return filepath.Join(l.Root, filepath.FromSlash(clean)), nil
}

// Put moves (or copies, across filesystems) src into place atomically.
func (l Local) Put(key, src string) error {
	dst, err := l.Path(key)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	if err := os.Rename(src, dst); err == nil {
		return nil
	}
	tmp := dst + ".tmp"
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	if err := out.Sync(); err != nil {
		out.Close()
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp, dst); err != nil {
		return err
	}
	return os.Remove(src)
}

// Write stores data under key.
func (l Local) Write(key string, data []byte) error {
	dst, err := l.Path(key)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	tmp := dst + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, dst)
}

// Delete removes a key; missing keys are not an error.
func (l Local) Delete(key string) error {
	p, err := l.Path(key)
	if err != nil {
		return err
	}
	if err := os.Remove(p); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

// DeletePrefix removes a key directory recursively.
func (l Local) DeletePrefix(prefix string) error {
	p, err := l.Path(prefix)
	if err != nil {
		return err
	}
	return os.RemoveAll(p)
}

// TempDir returns a scratch directory on the same filesystem as the store.
func (l Local) TempDir() (string, error) {
	base := filepath.Join(l.Root, "tmp")
	if err := os.MkdirAll(base, 0o755); err != nil {
		return "", err
	}
	return os.MkdirTemp(base, "job-*")
}
