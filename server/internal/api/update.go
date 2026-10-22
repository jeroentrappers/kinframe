package api

import (
	"encoding/json"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/problem"
	"github.com/jeroentrappers/kinframe/server/internal/store"
)

// frameUpdate is the FrameUpdate schema.
type frameUpdate struct {
	VersionName  string    `json:"version_name"`
	VersionCode  int       `json:"version_code"`
	MinSDK       int       `json:"min_sdk"`
	URL          string    `json:"url"`
	SHA256       string    `json:"sha256"`
	Size         int64     `json:"size"`
	ReleaseNotes *string   `json:"release_notes,omitempty"`
	PublishedAt  time.Time `json:"published_at"`
	Prerelease   bool      `json:"prerelease"`
}

// appHeaderRE matches the version code in `X-Kinframe-App: kinframe-frame/0.2.0 (20099)`.
var appHeaderRE = regexp.MustCompile(`\((\d+)\)\s*$`)

func appVersionCode(header string) (int, bool) {
	header = strings.TrimSpace(header)
	if m := appHeaderRE.FindStringSubmatch(header); m != nil {
		n, err := strconv.Atoi(m[1])
		return n, err == nil
	}
	n, err := strconv.Atoi(header)
	return n, err == nil && n > 0
}

// deviceSDK is the Android API level from enrollment, else from the last heartbeat (0 = unknown).
func deviceSDK(d *store.Device) int {
	var v struct {
		AndroidSDK int `json:"android_sdk"`
	}
	if json.Unmarshal(d.Hardware, &v) == nil && v.AndroidSDK > 0 {
		return v.AndroidSDK
	}
	if len(d.LastHeartbeat) > 0 && json.Unmarshal(d.LastHeartbeat, &v) == nil {
		return v.AndroidSDK
	}
	return 0
}

// updateChannel: the frame's `updates.channel` setting, else the channel from enrollment.
func (s *Server) updateChannel(r *http.Request, d *store.Device) string {
	if d.FrameID != nil {
		if f, err := s.st.GetFrame(r.Context(), s.st.Q(), *d.FrameID); err == nil {
			var doc struct {
				Updates struct {
					Channel string `json:"channel"`
				} `json:"updates"`
			}
			if json.Unmarshal(f.Settings, &doc) == nil && (doc.Updates.Channel == "stable" || doc.Updates.Channel == "beta") {
				return doc.Updates.Channel
			}
		}
	}
	if d.AppChannel == "beta" {
		return "beta"
	}
	return "stable"
}

// deviceCheckUpdate implements GET /v1/device/update (README §5.7).
func (s *Server) deviceCheckUpdate(w http.ResponseWriter, r *http.Request) {
	d := deviceFrom(r.Context())
	q := r.URL.Query()
	channel := q.Get("channel")
	switch channel {
	case "":
		channel = s.updateChannel(r, d)
	case "stable", "beta":
	default:
		problem.Write(w, r, problem.Validation("channel", "enum", "channel must be stable or beta").WithStatus(http.StatusBadRequest))
		return
	}
	skip := map[int]bool{}
	for _, v := range q["skip"] {
		for _, part := range strings.Split(v, ",") {
			if part = strings.TrimSpace(part); part == "" {
				continue
			}
			n, err := strconv.Atoi(part)
			if err != nil || len(skip) >= 50 {
				problem.Write(w, r, problem.Validation("skip", "format", "skip is a comma-separated list of version codes").WithStatus(http.StatusBadRequest))
				return
			}
			skip[n] = true
		}
	}
	current, okHeader := appVersionCode(r.Header.Get("X-Kinframe-App"))
	if !okHeader {
		current = d.AppVersionCode
	}
	if s.releases == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	rel := s.releases.Latest(channel, current, deviceSDK(d), skip)
	if rel == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	body := frameUpdate{
		VersionName: rel.VersionName, VersionCode: rel.VersionCode, MinSDK: rel.MinSDK,
		URL:    s.cfg.APIURL() + "/device/update/apk/" + strconv.Itoa(rel.VersionCode),
		SHA256: rel.SHA256, Size: rel.Size, PublishedAt: rel.PublishedAt.UTC(), Prerelease: rel.Prerelease,
	}
	if rel.Notes != "" {
		body.ReleaseNotes = &rel.Notes
	}
	_ = Response{Status: http.StatusOK, Body: body}.write(w)
}

// deviceGetUpdateApk serves a mirrored APK with Range/ETag support.
func (s *Server) deviceGetUpdateApk(w http.ResponseWriter, r *http.Request) {
	code, err := strconv.Atoi(r.PathValue("versionCode"))
	if err != nil || s.releases == nil {
		problem.Write(w, r, problem.New(problem.NotFound, "no such release"))
		return
	}
	rel := s.releases.Get(code)
	if rel == nil {
		problem.Write(w, r, problem.New(problem.NotFound, "no such release"))
		return
	}
	f, err := os.Open(rel.Path)
	if err != nil {
		problem.Write(w, r, problem.New(problem.NotFound, "release no longer available"))
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("ETag", `"`+rel.SHA256+`"`)
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	w.Header().Set("Content-Disposition", `attachment; filename="kinframe-frame-`+rel.VersionName+"-"+strconv.Itoa(rel.VersionCode)+`.apk"`)
	http.ServeContent(w, r, "", st.ModTime(), f)
}
