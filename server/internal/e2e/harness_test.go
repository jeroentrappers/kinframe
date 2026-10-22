// Package e2e runs the whole server (real PostgreSQL, real HTTP, real media
// tools) and drives it like the web app, phone app and frame would.
package e2e

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/jeroentrappers/kinframe/server/internal/app"
	"github.com/jeroentrappers/kinframe/server/internal/blob/blobtest"
	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/media"
	"github.com/jeroentrappers/kinframe/server/internal/testdb"
)

func TestMain(m *testing.M) {
	code := m.Run()
	testdb.Stop()
	blobtest.Stop()
	os.Exit(code)
}

type env struct {
	t   *testing.T
	app *app.App
	url string
}

type option func(*config.Config)

func newEnv(t *testing.T, opts ...option) *env {
	t.Helper()
	pool, _ := testdb.New(t)
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	url := "http://" + l.Addr().String()
	cfg := &config.Config{
		Env: "development", PublicURL: url, ServerName: "kinframe test", Version: "0.1.0-test",
		DataDir: t.TempDir(), SigningKey: []byte(strings.Repeat("s", 32)),
		OIDCAudience: url + "/v1", DevAuth: true,
		Workers: 2, VipsBin: os.Getenv("KINFRAME_VIPS"), VipsHdr: os.Getenv("KINFRAME_VIPSHEADER"),
		FFmpegBin: os.Getenv("KINFRAME_FFMPEG"), FFprobe: os.Getenv("KINFRAME_FFPROBE"),
		FeedRetention: 30 * 24 * time.Hour, CommandTimeout: 2 * time.Second,
		DefaultLimits:   config.Limits{MaxItemsPerSend: 200, MaxVideoSeconds: 300, MaxUploadBytes: 2 << 30, TrashRetentionDays: 30},
		UploadExpiry:    24 * time.Hour,
		JanitorInterval: time.Hour,
		LogLevel:        slog.LevelWarn,
	}
	for _, o := range opts {
		o(cfg)
	}
	level := slog.LevelError
	if os.Getenv("KINFRAME_TEST_LOG") != "" {
		level = slog.LevelDebug
	}
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level}))
	a, err := app.NewWithPool(cfg, log, pool)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = a.Serve(ctx, l)
	}()
	t.Cleanup(func() {
		cancel()
		<-done
	})
	return &env{t: t, app: a, url: url}
}

func requireMedia(t *testing.T) {
	t.Helper()
	tools := media.Tools{Vips: os.Getenv("KINFRAME_VIPS"), VipsHeader: os.Getenv("KINFRAME_VIPSHEADER"),
		FFmpeg: os.Getenv("KINFRAME_FFMPEG"), FFprobe: os.Getenv("KINFRAME_FFPROBE")}
	if missing := tools.Missing(); len(missing) > 0 {
		if os.Getenv("KINFRAME_REQUIRE_MEDIA_TOOLS") != "" {
			t.Fatalf("media tools missing: %v", missing)
		}
		t.Skipf("media tools missing: %v (run make test-docker)", missing)
	}
}

// ---------------------------------------------------------------- HTTP helpers

type resp struct {
	*http.Response
	body []byte
}

func (r *resp) json(t *testing.T, v any) {
	t.Helper()
	if err := json.Unmarshal(r.body, v); err != nil {
		t.Fatalf("decode %s: %v\n%s", r.Request.URL, err, r.body)
	}
}

func (r *resp) m(t *testing.T) map[string]any {
	t.Helper()
	var m map[string]any
	r.json(t, &m)
	return m
}

func (r *resp) code() string {
	var p struct {
		Code string `json:"code"`
	}
	_ = json.Unmarshal(r.body, &p)
	return p.Code
}

type req struct {
	method, path, token string
	body                any
	raw                 []byte
	header              map[string]string
}

func (e *env) do(r req) *resp {
	e.t.Helper()
	var body io.Reader
	ct := ""
	switch {
	case r.raw != nil:
		body = bytes.NewReader(r.raw)
	case r.body != nil:
		b, err := json.Marshal(r.body)
		if err != nil {
			e.t.Fatal(err)
		}
		body, ct = bytes.NewReader(b), "application/json"
	}
	url := r.path
	if !strings.HasPrefix(url, "http") {
		url = e.url + r.path
	}
	hr, err := http.NewRequest(r.method, url, body)
	if err != nil {
		e.t.Fatal(err)
	}
	if ct != "" {
		hr.Header.Set("Content-Type", ct)
	}
	if r.token != "" {
		hr.Header.Set("Authorization", "Bearer "+r.token)
	}
	for k, v := range r.header {
		hr.Header.Set(k, v)
	}
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	res, err := client.Do(hr)
	if err != nil {
		e.t.Fatalf("%s %s: %v", r.method, r.path, err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return &resp{Response: res, body: b}
}

func (e *env) expect(r req, status int) *resp {
	e.t.Helper()
	res := e.do(r)
	if res.StatusCode != status {
		e.t.Fatalf("%s %s: status %d, want %d\n%s", r.method, r.path, res.StatusCode, status, res.body)
	}
	return res
}

func (e *env) get(path, token string) *resp {
	e.t.Helper()
	return e.expect(req{method: "GET", path: path, token: token}, 200)
}

// ---------------------------------------------------------------- actors

type user struct {
	token string
	id    string
	name  string
}

func (e *env) user(email, name string) *user {
	e.t.Helper()
	res := e.expect(req{method: "POST", path: "/dev/token", body: map[string]any{"email": email, "name": name}}, 200)
	tok := res.m(e.t)["access_token"].(string)
	me := e.get("/v1/me", tok).m(e.t)
	return &user{token: tok, id: me["id"].(string), name: name}
}

type device struct {
	id, secret, token string
}

func (e *env) enroll(screenW, screenH int) *device {
	e.t.Helper()
	res := e.expect(req{method: "POST", path: "/v1/devices/enroll", body: map[string]any{
		"hardware": map[string]any{"brand": "denver", "model": "PFF_1024", "board": "rk312x", "android_sdk": 23,
			"max_video_decode": map[string]any{"width": 1920, "height": 1080}},
		"screen": map[string]any{"width": screenW, "height": screenH, "density_dpi": 160, "orientation": "landscape"},
		"app":    map[string]any{"version": "0.1.0", "version_code": 1},
		"locale": "nl-NL", "timezone": "Europe/Amsterdam",
	}}, 201).m(e.t)
	d := &device{id: res["device_id"].(string), secret: res["device_secret"].(string)}
	tok := e.expect(req{method: "POST", path: "/v1/devices/token", body: map[string]any{"device_id": d.id, "device_secret": d.secret}}, 200).m(e.t)
	d.token = tok["access_token"].(string)
	return d
}

// claim enrolls a device and has u claim it; returns the device and frame id.
func (e *env) claim(u *user) (*device, string) {
	e.t.Helper()
	d := e.enroll(1280, 800)
	code := e.expect(req{method: "POST", path: "/v1/device/code", token: d.token}, 200).m(e.t)["code"].(string)
	res := e.expect(req{method: "POST", path: "/v1/frames/redeem", token: u.token, body: map[string]any{"code": code}}, 200).m(e.t)
	if res["status"] != "claimed" {
		e.t.Fatalf("redeem status %v", res["status"])
	}
	return d, res["frame"].(map[string]any)["id"].(string)
}

// upload does a tus creation + PATCH and returns the upload id.
func (e *env) upload(u *user, data []byte, filename, mime string) string {
	e.t.Helper()
	sum := sha256.Sum256(data)
	meta := fmt.Sprintf("filename %s,filetype %s,sha256 %s",
		base64.StdEncoding.EncodeToString([]byte(filename)), base64.StdEncoding.EncodeToString([]byte(mime)),
		base64.StdEncoding.EncodeToString([]byte(hex.EncodeToString(sum[:]))))
	res := e.expect(req{method: "POST", path: "/v1/uploads", token: u.token, header: map[string]string{
		"Tus-Resumable": "1.0.0", "Upload-Length": fmt.Sprint(len(data)), "Upload-Metadata": meta,
	}}, 201)
	loc := res.Header.Get("Location")
	if !strings.HasPrefix(loc, e.url+"/v1/uploads/") {
		e.t.Fatalf("location %q", loc)
	}
	// Two chunks, to exercise resumption.
	half := len(data) / 2
	for _, chunk := range []struct {
		off  int
		data []byte
	}{{0, data[:half]}, {half, data[half:]}} {
		e.expect(req{method: "PATCH", path: loc, token: u.token, raw: chunk.data, header: map[string]string{
			"Tus-Resumable": "1.0.0", "Upload-Offset": fmt.Sprint(chunk.off), "Content-Type": "application/offset+octet-stream",
		}}, 204)
	}
	head := e.expect(req{method: "HEAD", path: loc, token: u.token, header: map[string]string{"Tus-Resumable": "1.0.0"}}, 200)
	if head.Header.Get("Upload-Offset") != fmt.Sprint(len(data)) {
		e.t.Fatalf("HEAD Upload-Offset %q", head.Header.Get("Upload-Offset"))
	}
	return strings.TrimPrefix(loc, e.url+"/v1/uploads/")
}

// ---------------------------------------------------------------- websockets

type wsConn struct {
	t    *testing.T
	c    *websocket.Conn
	msgs chan map[string]any
	done chan struct{}
	err  error
}

func (e *env) dialWS(path, bearer string) *wsConn {
	e.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	opts := &websocket.DialOptions{}
	if bearer != "" {
		opts.HTTPHeader = http.Header{"Authorization": {"Bearer " + bearer}}
	}
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(e.url, "http")+path, opts)
	if err != nil {
		e.t.Fatalf("dial %s: %v", path, err)
	}
	w := &wsConn{t: e.t, c: c, msgs: make(chan map[string]any, 100), done: make(chan struct{})}
	go func() {
		defer close(w.done)
		for {
			_, b, err := c.Read(context.Background())
			if err != nil {
				w.err = err
				return
			}
			var m map[string]any
			if json.Unmarshal(b, &m) == nil {
				w.msgs <- m
			}
		}
	}()
	e.t.Cleanup(func() { _ = c.Close(websocket.StatusNormalClosure, "") })
	return w
}

// await returns the next message of the given type, skipping others.
func (w *wsConn) await(typ string, timeout time.Duration) map[string]any {
	w.t.Helper()
	return w.awaitWhere(typ, timeout, func(map[string]any) bool { return true })
}

// awaitWhere returns the next message of the given type matching pred.
func (w *wsConn) awaitWhere(typ string, timeout time.Duration, pred func(map[string]any) bool) map[string]any {
	w.t.Helper()
	deadline := time.After(timeout)
	for {
		select {
		case m := <-w.msgs:
			if m["type"] == typ && pred(m) {
				return m
			}
		case <-w.done:
			// The reader may have queued messages (e.g. "revoked") just before the close frame;
			// select picks randomly between ready cases, so drain them before giving up.
		drain:
			for {
				select {
				case m := <-w.msgs:
					if m["type"] == typ && pred(m) {
						return m
					}
				default:
					break drain
				}
			}
			w.t.Fatalf("websocket closed while waiting for %q: %v", typ, w.err)
		case <-deadline:
			w.t.Fatalf("timed out waiting for websocket message %q", typ)
		}
	}
}

func (w *wsConn) send(v any) {
	w.t.Helper()
	b, _ := json.Marshal(v)
	if err := w.c.Write(context.Background(), websocket.MessageText, b); err != nil {
		w.t.Fatal(err)
	}
}

// eventually polls fn until it returns true.
func eventually(t *testing.T, timeout time.Duration, what string, fn func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if fn() {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func dig(m map[string]any, path ...string) any {
	var cur any = m
	for _, p := range path {
		mm, ok := cur.(map[string]any)
		if !ok {
			return nil
		}
		cur = mm[p]
	}
	return cur
}
