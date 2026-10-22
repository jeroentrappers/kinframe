package blob

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Local stores blobs as files below Root.
type Local struct{ Root string }

var (
	_ Store   = Local{}
	_ Localer = Local{}
)

func (Local) Backend() string { return "fs" }

// Path returns the absolute path of a key, rejecting traversal.
func (l Local) Path(key string) (string, error) {
	if err := CheckKey(key); err != nil {
		return "", err
	}
	return filepath.Join(l.Root, filepath.FromSlash(key)), nil
}

func (l Local) LocalPath(key string) (string, error) { return l.Path(key) }

// PutFile moves (or copies, across filesystems) src into place atomically.
func (l Local) PutFile(ctx context.Context, key, src string, opts PutOptions) error {
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
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	if err := l.Put(ctx, key, in, -1, opts); err != nil {
		return err
	}
	return os.Remove(src)
}

// Put writes r to a temporary file next to the destination, then renames it.
func (l Local) Put(_ context.Context, key string, r io.Reader, size int64, _ PutOptions) error {
	dst, err := l.Path(key)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	out, err := os.CreateTemp(filepath.Dir(dst), "."+filepath.Base(dst)+".*.tmp")
	if err != nil {
		return err
	}
	tmp := out.Name()
	fail := func(err error) error {
		out.Close()
		os.Remove(tmp)
		return err
	}
	n, err := io.Copy(out, r)
	if err != nil {
		return fail(err)
	}
	if size >= 0 && n != size {
		return fail(fmt.Errorf("put %s: wrote %d bytes, expected %d", key, n, size))
	}
	if err := out.Chmod(0o644); err != nil {
		return fail(err)
	}
	if err := out.Sync(); err != nil {
		return fail(err)
	}
	if err := out.Close(); err != nil {
		os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, dst); err != nil {
		os.Remove(tmp)
		return err
	}
	return nil
}

func (l Local) info(key string, st fs.FileInfo) Info {
	return Info{
		Key: key, Size: st.Size(), ModTime: st.ModTime(), ContentType: ContentType(key),
		// The ETag the server has always sent for files without a known digest.
		ETag: fmt.Sprintf(`"%x-%x"`, st.ModTime().UnixNano(), st.Size()),
	}
}

func (l Local) Stat(_ context.Context, key string) (Info, error) {
	p, err := l.Path(key)
	if err != nil {
		return Info{}, err
	}
	st, err := os.Stat(p)
	if errors.Is(err, os.ErrNotExist) || (err == nil && st.IsDir()) {
		return Info{}, fmt.Errorf("%s: %w", key, ErrNotFound)
	}
	if err != nil {
		return Info{}, err
	}
	return l.info(key, st), nil
}

func (l Local) Open(_ context.Context, key string) (Object, Info, error) {
	p, err := l.Path(key)
	if err != nil {
		return nil, Info{}, err
	}
	f, err := os.Open(p)
	if errors.Is(err, os.ErrNotExist) {
		return nil, Info{}, fmt.Errorf("%s: %w", key, ErrNotFound)
	}
	if err != nil {
		return nil, Info{}, err
	}
	st, err := f.Stat()
	if err != nil || st.IsDir() {
		f.Close()
		return nil, Info{}, fmt.Errorf("%s: %w", key, ErrNotFound)
	}
	return f, l.info(key, st), nil
}

// Delete removes a key; missing keys are not an error. Empty parent
// directories are left in place.
func (l Local) Delete(_ context.Context, key string) error {
	p, err := l.Path(key)
	if err != nil {
		return err
	}
	if err := os.Remove(p); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

// List walks the files below Root. Temporary files of interrupted writes and
// the scratch directory older versions kept in Root ("tmp/") are skipped.
func (l Local) List(ctx context.Context, prefix string, fn func(Info) error) error {
	err := filepath.WalkDir(l.Root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return nil
			}
			return err
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		rel, err := filepath.Rel(l.Root, p)
		if err != nil {
			return err
		}
		key := filepath.ToSlash(rel)
		if d.IsDir() {
			if key == "tmp" {
				return filepath.SkipDir
			}
			// Prune directories that cannot contain the prefix.
			if key != "." && !strings.HasPrefix(key+"/", prefix) && !strings.HasPrefix(prefix, key+"/") {
				return filepath.SkipDir
			}
			return nil
		}
		if !d.Type().IsRegular() || !strings.HasPrefix(key, prefix) || strings.HasSuffix(key, ".tmp") {
			return nil
		}
		st, err := d.Info()
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return nil
			}
			return err
		}
		return fn(l.info(key, st))
	})
	return err
}

func (Local) PresignGet(context.Context, string, time.Duration, string) (string, error) {
	return "", ErrUnsupported
}
