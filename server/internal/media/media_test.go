package media

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jeroentrappers/kinframe/server/internal/media/mediatest"
)

func TestSniffBytes(t *testing.T) {
	ftyp := func(major string, compat ...string) []byte {
		b := []byte{0, 0, 0, byte(16 + 4*len(compat))}
		b = append(b, "ftyp"+major+"\x00\x00\x00\x00"...)
		for _, c := range compat {
			b = append(b, c...)
		}
		return b
	}
	tests := []struct {
		name string
		in   []byte
		kind Kind
		mime string
	}{
		{"jpeg", []byte{0xFF, 0xD8, 0xFF, 0xE0}, Photo, "image/jpeg"},
		{"png", []byte("\x89PNG\r\n\x1a\n...."), Photo, "image/png"},
		{"gif", []byte("GIF89a...."), Photo, "image/gif"},
		{"webp", []byte("RIFF\x00\x00\x00\x00WEBPVP8 "), Photo, "image/webp"},
		{"heic", ftyp("heic", "mif1", "heic"), Photo, "image/heic"},
		{"heif mif1", ftyp("mif1", "heic"), Photo, "image/heif"},
		{"avif", ftyp("avif", "mif1"), Photo, "image/avif"},
		{"avif via mif1", ftyp("mif1", "avif"), Photo, "image/avif"},
		{"mov", ftyp("qt  ", "qt  "), Video, "video/quicktime"},
		{"mp4", ftyp("isom", "isom", "avc1"), Video, "video/mp4"},
		{"mp4 compat only", ftyp("XXXX", "mp42"), Video, "video/mp4"},
		{"3gp", ftyp("3gp4", "isom"), Video, "video/3gpp"},
		{"webm", []byte("\x1a\x45\xdf\xa3\x9f\x42\x86\x81\x01\x42\x82\x84webm"), Video, "video/webm"},
		{"mkv", []byte("\x1a\x45\xdf\xa3\x9f\x42\x86\x81\x01\x42\x82\x88matroska"), Video, "video/x-matroska"},
		{"text", []byte("hello world"), "", ""},
		{"pdf", []byte("%PDF-1.7"), "", ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			k, m := SniffBytes(tt.in)
			if k != tt.kind || m != tt.mime {
				t.Errorf("got %q %q, want %q %q", k, m, tt.kind, tt.mime)
			}
		})
	}
}

func TestGeometry(t *testing.T) {
	tests := []struct {
		name string
		got  Size
		want Size
	}{
		{"photo landscape downscale", PhotoRenditionSize(Size{2560, 1920}, 1280), Size{1707, 1280}},
		{"photo portrait downscale", PhotoRenditionSize(Size{1920, 2560}, 1280), Size{1280, 1707}},
		{"photo never upscale", PhotoRenditionSize(Size{1024, 768}, 1280), Size{1024, 768}},
		{"video 4k to 1080p", FitWithin(Size{3840, 2160}, MasterVideoBox, true), Size{1920, 1080}},
		{"video portrait 4k", FitWithin(Size{2160, 3840}, MasterVideoBox, true), Size{1080, 1920}},
		{"video small untouched", FitWithin(Size{640, 360}, MasterVideoBox, true), Size{640, 360}},
		{"video odd rounded even", FitWithin(Size{1281, 721}, Size{1920, 1080}, true), Size{1280, 720}},
		{"video decode box 720p", FitWithin(Size{1920, 1080}, Size{1280, 720}, true), Size{1280, 720}},
		{"video ultrawide", FitWithin(Size{3000, 1000}, Size{1920, 1080}, true), Size{1920, 640}},
	}
	for _, tt := range tests {
		if tt.got != tt.want {
			t.Errorf("%s: got %v want %v", tt.name, tt.got, tt.want)
		}
	}
	if VideoBitrate(Size{1920, 1080}) != 8_000_000 || VideoBitrate(Size{1280, 720}) != 3_686_400 {
		t.Error("bitrate")
	}
	for _, tt := range []struct {
		size         Size
		n            int
		fx, fy       float64
		wantL, wantT int
	}{
		{Size{682, 512}, 512, 0.5, 0.5, 85, 0},
		{Size{682, 512}, 512, 0, 0, 0, 0},
		{Size{682, 512}, 512, 1, 1, 170, 0},
		{Size{512, 900}, 512, 0.5, 0.1, 0, 0},
		{Size{512, 900}, 512, 0.5, 0.9, 0, 388},
	} {
		l, top := CropOrigin(tt.size, tt.n, tt.fx, tt.fy)
		if l != tt.wantL || top != tt.wantT {
			t.Errorf("CropOrigin(%v,%v,%v) = %d,%d", tt.size, tt.fx, tt.fy, l, top)
		}
	}
	if PhotoVariant(Size{800, 1280}) != "frame_1280x800" || VideoVariant(Size{1280, 800}, Size{1080, 1920}) != "frame_1280x800_v1920x1080" {
		t.Error("variant names")
	}
}

func TestParseHeader(t *testing.T) {
	out := `x.jpg: 4000x3000 uchar, 3 bands, srgb, jpegload
width: 4000
height: 3000
orientation: 6
exif-ifd2-DateTimeOriginal: 2023:05:01 12:34:56 (2023:05:01 12:34:56, ASCII, 20 components, 20 bytes)
exif-ifd2-OffsetTimeOriginal: +02:00 (+02:00, ASCII, 7 components, 7 bytes)
`
	info := parseHeader(out)
	if info.Size != (Size{3000, 4000}) {
		t.Errorf("size %v", info.Size)
	}
	want := time.Date(2023, 5, 1, 10, 34, 56, 0, time.UTC)
	if info.CapturedAt == nil || !info.CapturedAt.Equal(want) {
		t.Errorf("captured %v", info.CapturedAt)
	}
	if parseHeader("width: 10\nheight: 5\nexif-ifd2-DateTimeOriginal: 0000:00:00 00:00:00").CapturedAt != nil {
		t.Error("zero date should be ignored")
	}
}

func TestParseProbe(t *testing.T) {
	js := `{"streams":[
	 {"codec_type":"video","width":1920,"height":1080,"avg_frame_rate":"60000/1001","duration":"12.5",
	  "side_data_list":[{"rotation":-90}]},
	 {"codec_type":"audio"},
	 {"codec_type":"video","width":320,"height":240,"disposition":{"attached_pic":1}}],
	 "format":{"duration":"12.512","tags":{"creation_time":"2024-02-03T04:05:06.000000Z"}}}`
	info, err := parseProbe([]byte(js))
	if err != nil {
		t.Fatal(err)
	}
	if info.Size != (Size{1080, 1920}) || !info.HasAudio || info.DurationMS != 12512 || info.FPS < 59.9 || info.FPS > 60 {
		t.Errorf("info %+v", info)
	}
	if info.CapturedAt == nil || info.CapturedAt.Year() != 2024 {
		t.Errorf("captured %v", info.CapturedAt)
	}
	if _, err := parseProbe([]byte(`{"streams":[{"codec_type":"audio"}],"format":{}}`)); err != ErrUnsupported {
		t.Errorf("audio-only: %v", err)
	}
}

// --- integration tests with the real binaries -------------------------------

func toolsOrSkip(t *testing.T) Tools {
	t.Helper()
	tools := Tools{
		Vips: os.Getenv("KINFRAME_VIPS"), VipsHeader: os.Getenv("KINFRAME_VIPSHEADER"),
		FFmpeg: os.Getenv("KINFRAME_FFMPEG"), FFprobe: os.Getenv("KINFRAME_FFPROBE"),
	}
	if missing := tools.Missing(); len(missing) > 0 {
		if os.Getenv("KINFRAME_REQUIRE_MEDIA_TOOLS") != "" {
			t.Fatalf("media tools missing: %v", missing)
		}
		t.Skipf("media tools missing: %v (run make test-docker)", missing)
	}
	return tools
}

func TestPhotoPipeline(t *testing.T) {
	tools := toolsOrSkip(t)
	ctx := context.Background()
	dir := t.TempDir()
	src := filepath.Join(dir, "in.jpg")
	// 4000x3000 stored, orientation 6 = rotate 90° CW for display -> 3000x4000.
	if err := os.WriteFile(src, mediatest.JPEG(4000, 3000, 6, "2021:06:15 10:30:00", true), 0o644); err != nil {
		t.Fatal(err)
	}
	master := filepath.Join(dir, "master.webp")
	size, captured, err := tools.PhotoMaster(ctx, src, master)
	if err != nil {
		t.Fatal(err)
	}
	if size != (Size{1920, 2560}) {
		t.Errorf("master size %v, want 1920x2560 (rotated, long edge 2560)", size)
	}
	if captured == nil || !captured.Equal(time.Date(2021, 6, 15, 10, 30, 0, 0, time.UTC)) {
		t.Errorf("captured %v", captured)
	}
	if k, m, _ := Sniff(master); k != Photo || m != "image/webp" {
		t.Errorf("master type %s %s", k, m)
	}
	out, _ := exec.Command(tools.bin(tools.VipsHeader, "vipsheader"), "-a", master).Output()
	if strings.Contains(string(out), "exif-") || strings.Contains(strings.ToLower(string(out)), "gps") {
		t.Errorf("metadata not stripped:\n%s", out)
	}

	rend := filepath.Join(dir, "frame.webp")
	rs, err := tools.PhotoRendition(ctx, master, size, 1280, rend)
	if err != nil {
		t.Fatal(err)
	}
	if rs != (Size{1280, 1707}) {
		t.Errorf("rendition %v", rs)
	}

	for _, n := range ThumbSizes {
		th := filepath.Join(dir, "thumb.webp")
		if err := tools.Thumb(ctx, master, size, n, 0.5, 0.2, th); err != nil {
			t.Fatal(err)
		}
		h, err := tools.ImageHeader(ctx, th)
		if err != nil || h.Size != (Size{n, n}) {
			t.Errorf("thumb %d: %v %v", n, h.Size, err)
		}
	}
}

func TestPhotoSmallNotUpscaled(t *testing.T) {
	tools := toolsOrSkip(t)
	ctx := context.Background()
	dir := t.TempDir()
	src := filepath.Join(dir, "in.jpg")
	_ = os.WriteFile(src, mediatest.JPEG(640, 480, 0, "", false), 0o644)
	master := filepath.Join(dir, "m.webp")
	size, captured, err := tools.PhotoMaster(ctx, src, master)
	if err != nil {
		t.Fatal(err)
	}
	if size != (Size{640, 480}) || captured != nil {
		t.Errorf("size %v captured %v", size, captured)
	}
	rs, err := tools.PhotoRendition(ctx, master, size, 1280, filepath.Join(dir, "r.webp"))
	if err != nil || rs != size {
		t.Errorf("rendition %v %v", rs, err)
	}
}

func TestCorruptPhoto(t *testing.T) {
	tools := toolsOrSkip(t)
	dir := t.TempDir()
	src := filepath.Join(dir, "in.jpg")
	b := mediatest.JPEG(200, 200, 0, "", false)
	_ = os.WriteFile(src, b[:200], 0o644)
	_, _, err := tools.PhotoMaster(context.Background(), src, filepath.Join(dir, "m.webp"))
	if err == nil || !IsMediaError(err) {
		t.Fatalf("want media error, got %v", err)
	}
}

func makeVideo(t *testing.T, tools Tools, dir string, w, h int, seconds int, codec string) string {
	t.Helper()
	ext := ".mp4"
	if codec == "hevc" {
		ext = ".mov"
	}
	out := filepath.Join(dir, "in"+ext)
	args := []string{"-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=" + itoa(w) + "x" + itoa(h) + ":rate=60:duration=" + itoa(seconds),
		"-f", "lavfi", "-i", "sine=frequency=440:duration=" + itoa(seconds),
		"-shortest", "-pix_fmt", "yuv420p", "-c:a", "aac",
		"-metadata", "creation_time=2022-08-09T10:11:12Z"}
	switch codec {
	case "hevc":
		args = append(args, "-c:v", "libx265", "-tag:v", "hvc1", "-x265-params", "log-level=error")
	default:
		args = append(args, "-c:v", "libx264", "-preset", "ultrafast")
	}
	args = append(args, out)
	if b, err := exec.Command(tools.bin(tools.FFmpeg, "ffmpeg"), args...).CombinedOutput(); err != nil {
		t.Skipf("cannot create %s fixture: %v %s", codec, err, b)
	}
	return out
}

func TestVideoPipeline(t *testing.T) {
	tools := toolsOrSkip(t)
	ctx := context.Background()
	for _, tc := range []struct {
		name  string
		codec string
		w, h  int
	}{
		{"h264 1440p", "h264", 2560, 1440},
		{"hevc mov", "hevc", 1280, 720},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			src := makeVideo(t, tools, dir, tc.w, tc.h, 4, tc.codec)
			info, err := tools.ProbeVideo(ctx, src)
			if err != nil {
				t.Fatal(err)
			}
			if info.DurationMS < 3900 || !info.HasAudio || info.CapturedAt == nil {
				t.Fatalf("probe %+v", info)
			}
			master := filepath.Join(dir, "master.mp4")
			mi, err := tools.VideoMaster(ctx, src, info, &Trim{StartMS: 1000, EndMS: 3000}, true, master)
			if err != nil {
				t.Fatal(err)
			}
			want := FitWithin(Size{tc.w, tc.h}, MasterVideoBox, true)
			if mi.Size != want || mi.DurationMS < 1800 || mi.DurationMS > 2300 || !mi.HasAudio || mi.FPS > 60.5 {
				t.Errorf("master %+v want size %v ~2s", mi, want)
			}
			rend := filepath.Join(dir, "frame.mp4")
			rs, err := tools.VideoRendition(ctx, master, mi, Size{1280, 720}, rend)
			if err != nil {
				t.Fatal(err)
			}
			ri, err := tools.ProbeVideo(ctx, rend)
			if err != nil || ri.Size != rs || ri.FPS > 30.5 || rs.Long() > 1280 {
				t.Errorf("rendition %+v %v %v", ri, rs, err)
			}
			poster := filepath.Join(dir, "poster.webp")
			if err := tools.Poster(ctx, master, mi.DurationMS, poster); err != nil {
				t.Fatal(err)
			}
			if k, _, _ := Sniff(poster); k != Photo {
				t.Error("poster not an image")
			}
			// No audio requested.
			noAudio := filepath.Join(dir, "na.mp4")
			na, err := tools.VideoMaster(ctx, src, info, nil, false, noAudio)
			if err != nil || na.HasAudio {
				t.Errorf("include_audio=false: %+v %v", na, err)
			}
		})
	}
}
