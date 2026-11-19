package api

import (
	"encoding/json"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/config"
)

func TestFamilyAppHeader(t *testing.T) {
	tests := []struct {
		header   string
		platform string
		build    int
		ok       bool
	}{
		{"kinframe-family-android/1.4.0 (412)", "android", 412, true},
		{"kinframe-family-ios/1.4.0 (57)", "ios", 57, true},
		{"kinframe-family-macos/2.0.0-beta.1 (9)", "macos", 9, true},
		{"  kinframe-family-ios/1.4.0 (57)  ", "ios", 57, true},
		{"", "", 0, false},
		{"kinframe-frame/0.2.0 (20099)", "", 0, false},
		{"kinframe-family-windows/1.0 (3)", "", 0, false},
		{"kinframe-family-ios/1.4.0", "", 0, false},
		{"kinframe-family-ios/1.4.0 (abc)", "", 0, false},
		{"kinframe-family-ios/1.4.0 (0)", "", 0, false},
		{"kinframe-family-ios/ (5)", "", 0, false},
		{"kinframe-family-IOS/1.4.0 (5)", "", 0, false},
		{"kinframe-family-ios/1.4.0 (99999999999999999999999)", "", 0, false},
		{"412", "", 0, false},
	}
	for _, tt := range tests {
		p, b, ok := familyApp(tt.header)
		if p != tt.platform || b != tt.build || ok != tt.ok {
			t.Errorf("familyApp(%q) = %q, %d, %v; want %q, %d, %v", tt.header, p, b, ok, tt.platform, tt.build, tt.ok)
		}
	}
	// The frame's own parser still reads the frame header.
	if n, ok := appVersionCode("kinframe-frame/0.2.0 (20099)"); !ok || n != 20099 {
		t.Errorf("appVersionCode = %d, %v", n, ok)
	}
}

func TestAppVersionsJSON(t *testing.T) {
	if got := appVersionsJSON(nil); got != nil {
		t.Fatalf("no policy: %+v", got)
	}
	got := appVersionsJSON(map[string]config.AppVersionPolicy{
		"android": {MinBuild: 100, RecommendedBuild: 120, StoreURL: "https://play.google.com/store/apps/details?id=app.kinframe"},
		"ios":     {RecommendedBuild: 7},
	})
	b, err := json.Marshal(got)
	if err != nil {
		t.Fatal(err)
	}
	want := `{"android":{"minimum_build":100,"recommended_build":120,"store_url":"https://play.google.com/store/apps/details?id=app.kinframe"},"ios":{"recommended_build":7}}`
	if string(b) != want {
		t.Fatalf("app_versions = %s\nwant %s", b, want)
	}
}
