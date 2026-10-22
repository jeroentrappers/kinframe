package releases

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/releases/releasestest"
)

// writeTestAPK writes a fake APK to path (if set) and returns its bytes.
func writeTestAPK(t *testing.T, path, pkg, name string, code, minSDK int) []byte {
	t.Helper()
	b := releasestest.APK(pkg, name, code, minSDK)
	if path != "" {
		if err := os.WriteFile(path, b, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return b
}

func TestParseBinaryManifest(t *testing.T) {
	m, err := ParseBinaryManifest(releasestest.Manifest("app.kinframe.frame", "0.2.0-beta.1", 20001, 23))
	if err != nil {
		t.Fatal(err)
	}
	want := Manifest{Package: "app.kinframe.frame", VersionCode: 20001, VersionName: "0.2.0-beta.1", MinSDK: 23}
	if *m != want {
		t.Fatalf("got %+v, want %+v", *m, want)
	}
}

func TestParseBinaryManifestRejectsGarbage(t *testing.T) {
	for _, b := range [][]byte{nil, []byte("<manifest/>"), {3, 0, 8, 0, 0xff, 0xff, 0, 0}} {
		if _, err := ParseBinaryManifest(b); err == nil {
			t.Fatalf("expected error for %q", b)
		}
	}
	m := releasestest.Manifest("app.kinframe.frame", "1", 1, 23)
	for i := 8; i < len(m); i += 7 {
		// Truncations must fail cleanly, never panic.
		_, _ = ParseBinaryManifest(m[:i])
	}
}

func TestReadAPKManifest(t *testing.T) {
	p := filepath.Join(t.TempDir(), "a.apk")
	writeTestAPK(t, p, "app.kinframe.frame", "0.3.0", 30099, 21)
	m, err := ReadAPKManifest(p)
	if err != nil {
		t.Fatal(err)
	}
	if m.VersionCode != 30099 || m.MinSDK != 21 || m.Package != "app.kinframe.frame" {
		t.Fatalf("got %+v", m)
	}
}

// TestRealAPK parses an APK built by Gradle: KINFRAME_TEST_FRAME_APK=../android/frame/build/outputs/apk/release/frame-release.apk
func TestRealAPK(t *testing.T) {
	p := os.Getenv("KINFRAME_TEST_FRAME_APK")
	if p == "" {
		t.Skip("set KINFRAME_TEST_FRAME_APK to a built frame APK")
	}
	m, err := ReadAPKManifest(p)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("%+v", *m)
	if m.Package != "app.kinframe.frame" || m.VersionCode < 1 || m.MinSDK != 23 || m.VersionName == "" {
		t.Fatalf("unexpected manifest %+v", m)
	}
}
