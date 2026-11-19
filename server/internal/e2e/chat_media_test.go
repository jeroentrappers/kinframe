package e2e

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/config"
	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

// Chat media (README §5.19): photos and videos as items served to the conversation, voice messages through
// the media pipeline, "Put on a frame", and link previews.

func makeAudio(t *testing.T, seconds float64, codec string) []byte {
	t.Helper()
	ext, args := "webm", []string{"-c:a", "libopus", "-b:a", "24k"}
	switch codec {
	case "aac":
		ext, args = "m4a", []string{"-c:a", "aac", "-ac", "1", "-b:a", "64k"}
	case "aac-stereo":
		ext, args = "m4a", []string{"-c:a", "aac", "-ac", "2", "-b:a", "160k"}
	}
	out := filepath.Join(t.TempDir(), "voice."+ext)
	cmd := append([]string{"-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
		fmt.Sprintf("sine=frequency=330:duration=%g", seconds), "-af", "volume='if(lt(t,1),0.2,1)':eval=frame"}, args...)
	cmd = append(cmd, out)
	if b, err := exec.Command(ffmpegBin(), cmd...).CombinedOutput(); err != nil {
		t.Skipf("cannot make %s audio: %v %s", codec, err, b)
	}
	b, err := os.ReadFile(out)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func ffprobeBin() string {
	if b := os.Getenv("KINFRAME_FFPROBE"); b != "" {
		return b
	}
	return "ffprobe"
}

// waitMessage polls a message until pred holds.
func (e *env) waitMessage(u *user, conv, id string, what string, pred func(map[string]any) bool) map[string]any {
	e.t.Helper()
	var m map[string]any
	eventually(e.t, 60*time.Second, what, func() bool {
		m = e.get("/v1/conversations/"+conv+"/messages/"+id, u.token).m(e.t)
		return pred(m)
	})
	return m
}

func TestChatVoiceMessages(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	anna, ben, carl := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carl@x.org", "Carl")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	if e.get("/.well-known/kinframe", "").m(t)["voice_messages"] != true {
		t.Fatal("voice messages off with ffmpeg present")
	}
	dm := e.direct(anna, ben, 201)
	ws := e.dialWS("/v1/ws?access_token="+ben.token, "")

	for _, codec := range []string{"opus", "aac", "aac-stereo"} {
		t.Run(codec, func(t *testing.T) {
			up := e.upload(anna, makeAudio(t, 3, codec), "voice.bin", "application/octet-stream")
			m := e.expect(req{method: "POST", path: "/v1/conversations/" + dm + "/messages", token: anna.token,
				body: map[string]any{"voice": map[string]any{"upload_id": up, "duration_ms": 3000}}}, 201).m(t)
			if dig(m, "voice", "status") != "processing" || len(m["items"].([]any)) != 0 {
				t.Fatalf("voice message: %v", m)
			}
			id := m["id"].(string)
			ev := ws.awaitWhere("message.updated", 60*time.Second, func(ev map[string]any) bool {
				return dig(ev, "message", "id") == id && dig(ev, "message", "voice", "status") == "ready"
			})
			v := dig(ev, "message", "voice").(map[string]any)
			if d := v["duration_ms"].(float64); d < 2900 || d > 3200 {
				t.Fatalf("duration %v", d)
			}
			wf := v["waveform"].([]any)
			if len(wf) != 64 {
				t.Fatalf("waveform has %d points", len(wf))
			}
			if wf[0].(float64) >= wf[40].(float64) || wf[40].(float64) < 90 {
				t.Fatalf("waveform doesn't follow the loudness: %v", wf)
			}
			// The master: AAC-LC mono 48 kHz in MP4, served to the members only (Range works).
			itemID := v["item_id"].(string)
			r := e.expect(req{method: "GET", path: "/v1/items/" + itemID + "/media/master", token: ben.token}, 302)
			loc := r.Header.Get("Location")
			full := e.get(loc, "")
			if ct := full.Header.Get("Content-Type"); ct != "audio/mp4" {
				t.Fatalf("master content type %q", ct)
			}
			part := e.expect(req{method: "GET", path: loc, header: map[string]string{"Range": "bytes=0-99"}}, 206)
			if len(part.body) != 100 {
				t.Fatalf("range: %d bytes", len(part.body))
			}
			path := filepath.Join(t.TempDir(), "master.m4a")
			if err := os.WriteFile(path, full.body, 0o600); err != nil {
				t.Fatal(err)
			}
			out, err := exec.Command(ffprobeBin(), "-v", "error", "-select_streams", "a:0", "-show_entries",
				"stream=codec_name,profile,channels,sample_rate", "-of", "default=nw=1", path).CombinedOutput()
			if err != nil {
				t.Fatalf("ffprobe: %v %s", err, out)
			}
			probe := string(out)
			if !strings.Contains(probe, "codec_name=aac") || !strings.Contains(probe, "channels=1") || !strings.Contains(probe, "profile=LC") {
				t.Fatalf("master: %s", probe)
			}
			if codec != "aac" && !strings.Contains(probe, "sample_rate=48000") {
				t.Fatalf("transcoded master: %s", probe)
			}
			if r := e.do(req{method: "GET", path: "/v1/items/" + itemID + "/media/master", token: carl.token}); r.StatusCode != 404 {
				t.Fatalf("outsider gets the recording: %d", r.StatusCode)
			}
		})
	}

	// Limits: at least 1 s, at most 300 s, no photos alongside, an actual recording.
	short := e.upload(anna, makeAudio(t, 0.4, "opus"), "s.webm", "audio/webm")
	if r := e.do(req{method: "POST", path: "/v1/conversations/" + dm + "/messages", token: anna.token, body: map[string]any{"voice": map[string]any{"upload_id": short}}}); r.StatusCode != 422 || r.code() != "validation_failed" {
		t.Fatalf("too short: %d %s", r.StatusCode, r.body)
	}
	long := e.upload(anna, makeAudio(t, 301, "opus"), "l.webm", "audio/webm")
	if r := e.do(req{method: "POST", path: "/v1/conversations/" + dm + "/messages", token: anna.token, body: map[string]any{"voice": map[string]any{"upload_id": long}}}); r.StatusCode != 422 || r.code() != "audio_too_long" {
		t.Fatalf("too long: %d %s", r.StatusCode, r.body)
	}
	photo := e.photo(anna, 1)
	voice := e.upload(anna, makeAudio(t, 2, "opus"), "v.webm", "audio/webm")
	if r := e.do(req{method: "POST", path: "/v1/conversations/" + dm + "/messages", token: anna.token,
		body: map[string]any{"voice": map[string]any{"upload_id": voice}, "items": []any{map[string]any{"upload_id": photo}}}}); r.StatusCode != 422 {
		t.Fatalf("voice with photos: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/conversations/" + dm + "/messages", token: anna.token, body: map[string]any{"voice": map[string]any{"upload_id": photo}}}); r.StatusCode != 422 || r.code() != "unsupported_media" {
		t.Fatalf("a photo as voice: %d %s", r.StatusCode, r.body)
	}
	// A voice message with a caption, and its push text.
	m := e.expect(req{method: "POST", path: "/v1/conversations/" + dm + "/messages", token: anna.token,
		body: map[string]any{"voice": map[string]any{"upload_id": voice}, "body": "for you"}}, 201).m(t)
	if m["body"] != "for you" || m["voice"] == nil {
		t.Fatalf("voice with text: %v", m)
	}
}

func TestChatPhotosAndPutOnFrame(t *testing.T) {
	requireMedia(t)
	e := newEnv(t)
	anna, ben, carl := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben"), e.user("carl@x.org", "Carl")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	e.joinFeed(anna, carl, feed)
	dBen, benFrame := e.claim(ben)
	_, annaFrame := e.claim(anna)
	g := e.group(anna, "Trio", ben, carl)
	wsBen := e.dialWS("/v1/ws?access_token="+ben.token, "")

	up1, up2 := e.photo(anna, 1), e.photo(anna, 2)
	m := e.expect(req{method: "POST", path: "/v1/conversations/" + g + "/messages", token: anna.token,
		body: map[string]any{"items": []any{map[string]any{"upload_id": up1, "caption": "beach"}, map[string]any{"upload_id": up2}}}}, 201).m(t)
	items := m["items"].([]any)
	if len(items) != 2 || items[0].(map[string]any)["status"] != "processing" || items[0].(map[string]any)["caption"] != "beach" {
		t.Fatalf("message items: %v", items)
	}
	ev := wsBen.awaitWhere("message.created", 10*time.Second, func(ev map[string]any) bool { return dig(ev, "message", "id") == m["id"] })
	if len(dig(ev, "message").(map[string]any)["items"].([]any)) != 2 {
		t.Fatalf("message.created: %v", ev)
	}
	mid := m["id"].(string)
	m = e.waitMessage(ben, g, mid, "chat photos ready", func(m map[string]any) bool {
		for _, it := range m["items"].([]any) {
			if it.(map[string]any)["status"] != "ready" || it.(map[string]any)["thumb_url"] == nil {
				return false
			}
		}
		return true
	})
	item := m["items"].([]any)[0].(map[string]any)["id"].(string)
	// Served to the conversation's members, not in anyone's sent history.
	if b := e.itemMedia(ben, item, "thumb_256"); len(b) == 0 {
		t.Fatal("no thumbnail for a member")
	}
	outsider := e.user("zed@x.org", "Zed")
	if r := e.do(req{method: "GET", path: "/v1/items/" + item + "/media/master", token: outsider.token}); r.StatusCode != 404 {
		t.Fatalf("outsider gets a chat photo: %d", r.StatusCode)
	}
	if sends := e.get("/v1/sends", anna.token).m(t)["sends"].([]any); len(sends) != 0 {
		t.Fatalf("chat photos in sent history: %v", sends)
	}

	// Ben puts Anna's photos on his frame: placed like a share, not to families.
	if r := e.do(req{method: "POST", path: "/v1/conversations/" + g + "/messages/" + mid + "/send", token: ben.token,
		body: map[string]any{"frame_ids": []string{benFrame}, "feed_ids": []string{feed}}}); r.StatusCode != 403 {
		t.Fatalf("other people's photos to a family: %d %s", r.StatusCode, r.body)
	}
	if r := e.do(req{method: "POST", path: "/v1/conversations/" + g + "/messages/" + mid + "/send", token: ben.token,
		body: map[string]any{"frame_ids": []string{annaFrame}}}); r.StatusCode != 404 && r.StatusCode != 403 {
		t.Fatalf("to a frame Ben isn't on: %d", r.StatusCode)
	}
	sd := e.expect(req{method: "POST", path: "/v1/conversations/" + g + "/messages/" + mid + "/send", token: ben.token,
		body: map[string]any{"frame_ids": []string{benFrame}, "item_ids": []string{item}}}, 202).m(t)
	if len(sd["items"].([]any)) != 1 || len(sd["frames"].([]any)) != 1 {
		t.Fatalf("Ben's put-on-frame send: %v", sd)
	}
	pl := dig(sd["frames"].([]any)[0].(map[string]any)).(map[string]any)["placements"].([]any)[0].(map[string]any)
	if pl["shared_by_name"] != "Ben" {
		t.Fatalf("placement credit: %v", pl)
	}
	sys := e.messages(anna, g, "?limit=1")[0]
	if dig(sys, "system", "type") != "put_on_frame" || dig(sys, "system", "person", "user_id") != ben.id ||
		dig(sys, "system", "item_count") != float64(1) || dig(sys, "system", "source_message_id") != mid {
		t.Fatalf("system message: %v", sys)
	}
	if names := dig(sys, "system", "frame_names").([]any); len(names) != 1 {
		t.Fatalf("frame names: %v", names)
	}
	// The frame's reaction shows under the original message.
	var placement string
	eventually(t, 30*time.Second, "placement ready", func() bool {
		placement, _ = e.scalar(`SELECT id::text FROM placements WHERE item_id = $1 AND frame_id = $2`, item, benFrame).(string)
		return placement != ""
	})
	e.expect(req{method: "PUT", path: "/v1/device/placements/" + placement + "/reaction", token: dBen.token, body: map[string]any{"reaction": "love"}}, 204)
	ev = wsBen.awaitWhere("message.updated", 10*time.Second, func(ev map[string]any) bool {
		return dig(ev, "message", "id") == mid && dig(ev, "message", "frame_activity") != nil
	})
	fa := dig(ev, "message", "frame_activity").([]any)[0].(map[string]any)
	if fa["reaction"] != "love" || fa["frame_id"] != benFrame || fa["item_id"] != item {
		t.Fatalf("frame activity: %v", fa)
	}

	// Anna puts her own photos on her frame and in the family: a normal send, with a frame post.
	sd = e.expect(req{method: "POST", path: "/v1/conversations/" + g + "/messages/" + mid + "/send", token: anna.token,
		body: map[string]any{"frame_ids": []string{annaFrame}, "feed_ids": []string{feed}}}, 202).m(t)
	if len(sd["items"].([]any)) != 2 {
		t.Fatalf("Anna's send: %v", sd)
	}
	if n := e.scalar(`SELECT count(*) FROM frame_posts WHERE send_id = $1`, sd["id"]); n != int64(1) {
		t.Fatalf("frame posts: %v", n)
	}
	if n := e.scalar(`SELECT count(*) FROM feed_posts WHERE send_id = $1 AND feed_id = $2`, sd["id"], feed); n != int64(1) {
		t.Fatalf("feed posts: %v", n)
	}
	if sends := e.get("/v1/sends", anna.token).m(t)["sends"].([]any); len(sends) != 1 {
		t.Fatalf("Anna's sent history: %d", len(sends))
	}
	// A member of the family can't post there.
	e.expect(req{method: "PATCH", path: "/v1/feeds/" + feed + "/members/" + carl.id, token: anna.token, body: map[string]any{"role": "member"}}, 200)
	cm := e.expect(req{method: "POST", path: "/v1/conversations/" + g + "/messages", token: carl.token,
		body: map[string]any{"items": []any{map[string]any{"upload_id": e.photo(carl, 3)}}}}, 201).m(t)
	if r := e.do(req{method: "POST", path: "/v1/conversations/" + g + "/messages/" + cm["id"].(string) + "/send", token: carl.token,
		body: map[string]any{"frame_ids": []string{}, "feed_ids": []string{feed}}}); r.StatusCode != 403 || r.code() != "missing_permission" {
		t.Fatalf("member posts to the family: %d %s", r.StatusCode, r.body)
	}

	// Deleting a message: its photos are no longer shared with the chat; what was put on a frame stays there.
	solo := e.expect(req{method: "POST", path: "/v1/conversations/" + g + "/messages", token: anna.token,
		body: map[string]any{"items": []any{map[string]any{"upload_id": e.photo(anna, 4)}}}}, 201).m(t)
	soloItem := solo["items"].([]any)[0].(map[string]any)["id"].(string)
	e.waitMessage(carl, g, solo["id"].(string), "solo photo ready", func(m map[string]any) bool {
		its := m["items"].([]any)
		return len(its) == 1 && its[0].(map[string]any)["thumb_url"] != nil
	})
	e.expect(req{method: "GET", path: "/v1/items/" + soloItem + "/media/thumb_256", token: carl.token}, 302)
	e.expect(req{method: "DELETE", path: "/v1/conversations/" + g + "/messages/" + solo["id"].(string), token: anna.token}, 204)
	if r := e.do(req{method: "GET", path: "/v1/items/" + soloItem + "/media/thumb_256", token: carl.token}); r.StatusCode != 404 {
		t.Fatalf("deleted message's photo: %d", r.StatusCode)
	}
	e.expect(req{method: "DELETE", path: "/v1/conversations/" + g + "/messages/" + mid, token: anna.token}, 204)
	if n := e.scalar(`SELECT count(*) FROM placements WHERE item_id = $1 AND removed_at IS NULL`, item); n != int64(2) {
		t.Fatalf("placements after delete: %v", n)
	}
}

func TestChatLinkPreviews(t *testing.T) {
	requireMedia(t)
	img := mediatest.JPEG(1200, 630, 0, "", false)
	var pageHits int
	site := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/post":
			pageHits++
			if r.Header.Get("User-Agent") != "kinframe-linkpreview/1" {
				t.Errorf("user agent %q", r.Header.Get("User-Agent"))
			}
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			fmt.Fprint(w, `<html><head><title>Fallback</title>
				<meta property="og:title" content="Summer at the lake">
				<meta property="og:description" content="Photos &amp; stories">
				<meta property="og:site_name" content="Lake Blog">
				<meta property="og:image" content="/img.jpg"></head><body>hi</body></html>`)
		case "/img.jpg":
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write(img)
		case "/plain":
			w.Header().Set("Content-Type", "application/pdf")
			fmt.Fprint(w, "%PDF")
		default:
			http.NotFound(w, r)
		}
	}))
	defer site.Close()

	off := newEnv(t)
	u := off.user("o@x.org", "O")
	if off.get("/.well-known/kinframe", "").m(t)["link_previews"] != false {
		t.Fatal("link previews on by default")
	}
	if r := off.do(req{method: "POST", path: "/v1/link-previews", token: u.token, body: map[string]any{"url": site.URL + "/post"}}); r.StatusCode != 503 {
		t.Fatalf("previews while off: %d", r.StatusCode)
	}

	e := newEnv(t, func(c *config.Config) { c.LinkPreviews = true })
	anna, ben := e.user("anna@x.org", "Anna"), e.user("ben@x.org", "Ben")
	feed := e.createFeed(anna, map[string]any{"name": "Peeters"})
	e.joinFeed(anna, ben, feed)
	dm := e.direct(anna, ben, 201)
	if e.get("/.well-known/kinframe", "").m(t)["link_previews"] != true {
		t.Fatal("link previews off with KINFRAME_LINK_PREVIEWS")
	}
	link := site.URL + "/post"
	m := e.say(anna, dm, "Look: "+link+".")
	if dig(m, "link_preview", "status") != "pending" || dig(m, "link_preview", "url") != link {
		t.Fatalf("pending preview: %v", m["link_preview"])
	}
	m = e.waitMessage(ben, dm, m["id"].(string), "preview fetched", func(m map[string]any) bool { return dig(m, "link_preview", "status") == "ok" })
	lp := m["link_preview"].(map[string]any)
	if lp["title"] != "Summer at the lake" || lp["description"] != "Photos & stories" || lp["site_name"] != "Lake Blog" {
		t.Fatalf("preview: %v", lp)
	}
	if lp["image_url"] == nil || lp["image_width"].(float64) > 512 || lp["image_height"].(float64) > 512 {
		t.Fatalf("preview image: %v", lp)
	}
	if ct := e.get(lp["image_url"].(string), "").Header.Get("Content-Type"); ct != "image/webp" {
		t.Fatalf("preview image type %q", ct)
	}
	// Cached per URL: the composer's preview and a second message don't fetch again.
	p := e.expect(req{method: "POST", path: "/v1/link-previews", token: ben.token, body: map[string]any{"url": link}}, 200).m(t)
	if p["status"] != "ok" || p["title"] != "Summer at the lake" {
		t.Fatalf("composer preview: %v", p)
	}
	if m := e.say(ben, dm, link); dig(m, "link_preview", "status") != "ok" {
		t.Fatalf("cached preview: %v", m["link_preview"])
	}
	if pageHits != 1 {
		t.Fatalf("page fetched %d times", pageHits)
	}
	// Opting out per message, failures and non-links.
	if m := e.expect(req{method: "POST", path: "/v1/conversations/" + dm + "/messages", token: anna.token,
		body: map[string]any{"body": link, "link_preview": false}}, 201).m(t); m["link_preview"] != nil {
		t.Fatalf("preview despite link_preview false: %v", m["link_preview"])
	}
	if p := e.expect(req{method: "POST", path: "/v1/link-previews", token: ben.token, body: map[string]any{"url": site.URL + "/plain"}}, 200).m(t); p["status"] != "failed" {
		t.Fatalf("non-HTML preview: %v", p)
	}
	if p := e.expect(req{method: "POST", path: "/v1/link-previews", token: ben.token, body: map[string]any{"url": site.URL + "/missing"}}, 200).m(t); p["status"] != "failed" {
		t.Fatalf("404 preview: %v", p)
	}
	if r := e.do(req{method: "POST", path: "/v1/link-previews", token: ben.token, body: map[string]any{"url": "ftp://example.org/x"}}); r.StatusCode != 422 {
		t.Fatalf("ftp link: %d", r.StatusCode)
	}
}
