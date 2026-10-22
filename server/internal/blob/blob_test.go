package blob

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLocal(t *testing.T) {
	l := Local{Root: t.TempDir()}
	for _, bad := range []string{"", "/etc/passwd", "../x", "a/../../x"} {
		if _, err := l.Path(bad); err == nil {
			t.Errorf("key %q accepted", bad)
		}
	}
	src := filepath.Join(t.TempDir(), "f")
	_ = os.WriteFile(src, []byte("hi"), 0o644)
	if err := l.Put("media/x/master.webp", src); err != nil {
		t.Fatal(err)
	}
	p, _ := l.Path("media/x/master.webp")
	if b, _ := os.ReadFile(p); string(b) != "hi" {
		t.Fatal("content")
	}
	if err := l.Delete("media/x/master.webp"); err != nil {
		t.Fatal(err)
	}
	if err := l.Delete("media/x/master.webp"); err != nil {
		t.Fatal("double delete should be fine")
	}
}
