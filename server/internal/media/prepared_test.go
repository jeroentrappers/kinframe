package media

import (
	"bytes"
	"context"
	"encoding/binary"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

func writeFile(t *testing.T, dir, name string, b []byte) string {
	t.Helper()
	p := filepath.Join(dir, name)
	if err := os.WriteFile(p, b, 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

func header(t *testing.T, tools Tools, path string) map[string]string {
	t.Helper()
	out, err := run(context.Background(), tools.bin(tools.VipsHeader, "vipsheader"), "-a", path)
	if err != nil {
		t.Fatal(err)
	}
	return headerFields(string(out))
}

func TestStripJPEG(t *testing.T) {
	src := mediatest.JPEG(640, 480, 1, "2021:06:15 10:30:00", true)
	withTrailer := append(append([]byte{}, src...), []byte("....ftypisom trailing video bytes")...)
	out, err := StripJPEG(withTrailer)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(out, []byte("Exif\x00\x00")) || bytes.Contains(out, []byte("trailing")) {
		t.Fatal("EXIF or trailing data survived")
	}
	if !bytes.HasSuffix(out, []byte{0xFF, 0xD9}) || len(out) >= len(src) {
		t.Fatalf("stripped %d bytes from %d", len(out), len(src))
	}
	// The entropy-coded data is untouched: everything after the EXIF segment is identical.
	exifLen := int(binary.BigEndian.Uint16(src[4:6])) + 2
	if !bytes.Equal(out[2:], src[2+exifLen:]) {
		t.Fatal("image data changed")
	}
	if _, err := StripJPEG(src[:200]); err == nil {
		t.Fatal("truncated JPEG accepted")
	}
}

func TestStripWebP(t *testing.T) {
	tools := toolsOrSkip(t)
	dir := t.TempDir()
	jpg := writeFile(t, dir, "in.jpg", mediatest.JPEG(320, 200, 1, "2021:06:15 10:30:00", true))
	// vips keeps EXIF unless told to strip: a WebP with an EXIF chunk.
	webpWithExif := filepath.Join(dir, "exif.webp")
	if _, err := run(context.Background(), tools.bin(tools.Vips, "vips"), "copy", jpg, webpWithExif+"[Q=90]"); err != nil {
		t.Fatal(err)
	}
	if header(t, tools, webpWithExif)["exif-data"] == "" {
		t.Fatal("fixture has no EXIF")
	}
	b, _ := os.ReadFile(webpWithExif)
	out, err := StripWebP(b)
	if err != nil {
		t.Fatal(err)
	}
	stripped := writeFile(t, dir, "out.webp", out)
	h := header(t, tools, stripped)
	if h["exif-data"] != "" || h["width"] != "320" || bytes.Contains(out, []byte("EXIF")) {
		t.Fatalf("stripped webp: %v", h)
	}
	if _, err := StripWebP([]byte("RIFF\x10\x00\x00\x00WEBPANIM\x00\x00\x00\x00")); err == nil {
		t.Fatal("animated/garbage webp accepted")
	}
}

func TestVerifyPreparedPhoto(t *testing.T) {
	tools := toolsOrSkip(t)
	ctx := context.Background()
	dir := t.TempDir()
	webpOf := func(name string, w, h int) string {
		src := writeFile(t, dir, name+".jpg", mediatest.JPEG(w, h, 0, "", false))
		dst := filepath.Join(dir, name+".webp")
		if _, err := run(ctx, tools.bin(tools.Vips, "vips"), "copy", src, dst+"[Q=90]"); err != nil {
			t.Fatal(err)
		}
		return dst
	}
	w, h := 2560, 1440
	declW, declH := w, h
	ok := webpOf("ok", w, h)

	t.Run("conforming webp", func(t *testing.T) {
		dst := filepath.Join(dir, "m1.webp")
		pp, err := tools.VerifyPreparedPhoto(ctx, ok, dst, Declared{Width: &declW, Height: &declH})
		if err != nil {
			t.Fatal(err)
		}
		if pp.Size != (Size{w, h}) || pp.Mime != "image/webp" {
			t.Fatalf("%+v", pp)
		}
		if hd := header(t, tools, dst); hd["exif-data"] != "" {
			t.Fatal("metadata kept")
		}
	})
	t.Run("gps jpeg is stripped, capture time read", func(t *testing.T) {
		src := writeFile(t, dir, "gps.jpg", mediatest.JPEG(1200, 900, 1, "2019:07:01 08:09:10", true))
		dst := filepath.Join(dir, "m2.jpg")
		pp, err := tools.VerifyPreparedPhoto(ctx, src, dst, Declared{})
		if err != nil {
			t.Fatal(err)
		}
		if pp.CapturedAt == nil || pp.CapturedAt.Format("2006-01-02T15:04:05") != "2019-07-01T08:09:10" {
			t.Fatalf("captured %v", pp.CapturedAt)
		}
		hd := header(t, tools, dst)
		for k := range hd {
			if strings.HasPrefix(k, "exif-") || strings.Contains(k, "gps") {
				t.Fatalf("metadata %s survived", k)
			}
		}
	})
	for _, tc := range []struct {
		name string
		src  func() string
		decl Declared
		want string
	}{
		{"too large", func() string { return webpOf("big", 3000, 2000) }, Declared{}, "exceeds"},
		{"orientation not applied", func() string {
			return writeFile(t, dir, "rot.jpg", mediatest.JPEG(800, 600, 6, "", false))
		}, Declared{}, "orientation"},
		{"declared size mismatch", func() string { return ok }, Declared{Width: ptrInt(100), Height: ptrInt(100)}, "declared"},
		{"truncated", func() string {
			b := mediatest.JPEG(800, 600, 0, "", false)
			return writeFile(t, dir, "trunc.jpg", b[:len(b)/2])
		}, Declared{}, "decode"},
		{"png", func() string {
			src := writeFile(t, dir, "p.jpg", mediatest.JPEG(100, 100, 0, "", false))
			dst := filepath.Join(dir, "p.png")
			if _, err := run(ctx, tools.bin(tools.Vips, "vips"), "copy", src, dst); err != nil {
				t.Fatal(err)
			}
			return dst
		}, Declared{}, "format"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := tools.VerifyPreparedPhoto(ctx, tc.src(), filepath.Join(dir, "x"), tc.decl)
			reason, rejected := RejectionReason(err)
			if !rejected || !strings.Contains(reason, tc.want) {
				t.Fatalf("got %v, want rejection containing %q", err, tc.want)
			}
		})
	}
}

func ptrInt(v int) *int { return &v }

func TestPreparedVideo(t *testing.T) {
	tools := toolsOrSkip(t)
	ctx := context.Background()
	dir := t.TempDir()
	ff := tools.bin(tools.FFmpeg, "ffmpeg")
	clip := func(name string, w, h, fps int, extra ...string) string {
		if _, err := mediatest.Clip(ff, dir, name, w, h, fps, 3, true, extra...); err != nil {
			t.Skipf("cannot build clip: %v", err)
		}
		return filepath.Join(dir, name)
	}
	good := clip("good.mp4", 1280, 720, 30, append(mediatest.PreparedH264, "-metadata", "location=+50.8503+004.3517/",
		"-metadata", "creation_time=2022-08-09T10:11:12Z")...)
	info, err := tools.ProbeVideo(ctx, good)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckPreparedVideo(info, 300_000, Declared{}); err != nil {
		t.Fatalf("conforming clip rejected: %v (%+v)", err, info)
	}
	if info.CapturedAt == nil {
		t.Fatal("capture time not probed")
	}
	if err := CheckPreparedVideo(info, 2000, Declared{}); err == nil {
		t.Fatal("too long accepted")
	}
	if err := CheckPreparedVideo(info, 300_000, Declared{DurationMS: ptrInt(9000)}); err == nil {
		t.Fatal("duration mismatch accepted")
	}

	// Remux: no metadata (GPS location, creation time), faststart, same streams, no re-encode.
	out := filepath.Join(dir, "master.mp4")
	mi, err := tools.RemuxVideo(ctx, good, true, out)
	if err != nil {
		t.Fatal(err)
	}
	probe, _ := run(ctx, tools.bin(tools.FFprobe, "ffprobe"), "-v", "error", "-show_format", "-show_streams", out)
	for _, leak := range []string{"TAG:location", "50.8503", "creation_time"} {
		if strings.Contains(string(probe), leak) {
			t.Fatalf("remux kept %s:\n%s", leak, probe)
		}
	}
	if fs, _ := Faststart(out); !fs || mi.VideoCodec != "h264" || !mi.HasAudio || mi.Size != info.Size {
		t.Fatalf("remuxed %+v faststart %v", mi, fs)
	}
	if !ReusableAsRendition(mi, Size{1920, 1080}, true) {
		t.Fatalf("720p30 at 1.5 Mbps should be reusable: %+v", mi)
	}
	if ReusableAsRendition(mi, Size{1024, 600}, true) {
		t.Fatal("larger than the decode box but reusable")
	}
	muted, err := tools.RemuxVideo(ctx, good, false, filepath.Join(dir, "muted.mp4"))
	if err != nil || muted.HasAudio {
		t.Fatalf("muted remux %+v %v", muted, err)
	}

	for _, tc := range []struct {
		name string
		src  func() string
		want string
	}{
		{"hevc", func() string {
			return clip("hevc.mp4", 640, 360, 30, "-c:v", "libx265", "-tag:v", "hvc1", "-x265-params", "log-level=error")
		}, "codec"},
		{"1440p", func() string { return clip("big.mp4", 2560, 1440, 30, mediatest.PreparedH264...) }, "1080p"},
		{"mov", func() string { return clip("q.mov", 640, 360, 30, mediatest.PreparedH264...) }, "container"},
		{"level 5.1", func() string {
			return clip("l51.mp4", 640, 360, 30, "-c:v", "libx264", "-preset", "ultrafast", "-level:v", "5.1")
		}, "level"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			info, err := tools.ProbeVideo(ctx, tc.src())
			if err != nil {
				t.Fatal(err)
			}
			reason, rejected := RejectionReason(CheckPreparedVideo(info, 300_000, Declared{}))
			if !rejected || !strings.Contains(reason, tc.want) {
				t.Fatalf("got %q, want rejection containing %q", reason, tc.want)
			}
		})
	}

	// 60 fps is a fine master but not a frame rendition.
	fast := clip("fast.mp4", 1280, 720, 60, mediatest.PreparedH264...)
	fi, _ := tools.ProbeVideo(ctx, fast)
	if CheckPreparedVideo(fi, 300_000, Declared{}) != nil || ReusableAsRendition(fi, Size{1920, 1080}, true) {
		t.Fatalf("60 fps: %+v", fi)
	}
}

func TestFindMotionVideo(t *testing.T) {
	tools := toolsOrSkip(t)
	dir := t.TempDir()
	mp4, err := mediatest.Clip(tools.bin(tools.FFmpeg, "ffmpeg"), dir, "m.mp4", 320, 240, 30, 1.5, false, mediatest.PreparedH264...)
	if err != nil {
		t.Skipf("clip: %v", err)
	}
	jpg := mediatest.JPEG(400, 300, 1, "2023:01:02 03:04:05", false)
	for _, style := range []mediatest.MotionStyle{mediatest.MotionPhotoV1, mediatest.MicroVideo} {
		mp := writeFile(t, dir, "mp.jpg", mediatest.MotionPhoto(jpg, mp4, style))
		r, ok, err := FindMotionVideo(mp)
		if err != nil || !ok {
			t.Fatalf("style %d: not found: %v", style, err)
		}
		if r.Len() != int64(len(mp4)) {
			t.Fatalf("style %d: range %+v, want %d bytes", style, r, len(mp4))
		}
		out := filepath.Join(dir, "out.mp4")
		if err := ExtractRange(mp, r, out); err != nil {
			t.Fatal(err)
		}
		if b, _ := os.ReadFile(out); !bytes.Equal(b, mp4) {
			t.Fatal("extracted bytes differ")
		}
	}
	// Offsets that are a little off (padding counted): found by scanning.
	bad := mediatest.MotionPhoto(jpg, mp4, mediatest.MicroVideo)
	bad = append(bad, make([]byte, 7)...)
	if r, ok, _ := FindMotionVideo(writeFile(t, dir, "pad.jpg", bad)); !ok || r.Len() != int64(len(mp4)) {
		t.Fatalf("padded: %+v %v", r, ok)
	}
	// Plain photos are not motion photos, even with an MP4 glued on but no XMP flag.
	if _, ok, _ := FindMotionVideo(writeFile(t, dir, "plain.jpg", jpg)); ok {
		t.Fatal("plain JPEG detected as motion photo")
	}
	if _, ok, _ := FindMotionVideo(writeFile(t, dir, "glued.jpg", append(append([]byte{}, jpg...), mp4...))); ok {
		t.Fatal("unflagged JPEG detected as motion photo")
	}
	// HEIC Motion Photo: the video sits in a top-level mpvd box.
	heic := []byte{0, 0, 0, 24}
	heic = append(heic, "ftypheic\x00\x00\x00\x00mif1heic"...)
	heic = binary.BigEndian.AppendUint32(heic, 12)
	heic = append(heic, "meta\x00\x00\x00\x00"...)
	heic = binary.BigEndian.AppendUint32(heic, uint32(8+len(mp4)))
	heic = append(heic, "mpvd"...)
	heic = append(heic, mp4...)
	r, ok, err := FindMotionVideo(writeFile(t, dir, "mp.heic", heic))
	if err != nil || !ok || r.Len() != int64(len(mp4)) || r.Start != 44 {
		t.Fatalf("heic mpvd: %+v %v %v", r, ok, err)
	}
}
