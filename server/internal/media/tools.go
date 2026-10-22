package media

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Tools wraps the external binaries. Zero values default to names on $PATH.
type Tools struct {
	Vips       string
	VipsHeader string
	FFmpeg     string
	FFprobe    string
}

func (t Tools) bin(name, def string) string {
	if name == "" {
		return def
	}
	return name
}

// Missing lists binaries that cannot be found.
func (t Tools) Missing() []string {
	var out []string
	for _, b := range []string{t.bin(t.Vips, "vips"), t.bin(t.VipsHeader, "vipsheader"), t.bin(t.FFmpeg, "ffmpeg"), t.bin(t.FFprobe, "ffprobe")} {
		if _, err := exec.LookPath(b); err != nil {
			out = append(out, b)
		}
	}
	return out
}

// toolError carries a tool's stderr so failures are diagnosable in logs.
type toolError struct {
	tool   string
	err    error
	stderr string
}

func (e *toolError) Error() string {
	s := strings.TrimSpace(e.stderr)
	if len(s) > 600 {
		s = s[len(s)-600:]
	}
	return fmt.Sprintf("%s: %v: %s", e.tool, e.err, s)
}

func (e *toolError) Unwrap() error { return ErrCorrupt }

func run(ctx context.Context, name string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	cmd.Env = append(os.Environ(), "VIPS_WARNING=0")
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		var ee *exec.ExitError
		if !errors.As(err, &ee) {
			// Binary missing or not executable: infrastructure, not corrupt media.
			return nil, fmt.Errorf("%s: %w", name, err)
		}
		return nil, &toolError{tool: filepath.Base(name), err: err, stderr: stderr.String()}
	}
	return stdout.Bytes(), nil
}

// ImageInfo is what vipsheader tells us.
type ImageInfo struct {
	Size       Size
	CapturedAt *time.Time
}

// ImageHeader reads dimensions (as stored, before autorotation) and EXIF capture time.
func (t Tools) ImageHeader(ctx context.Context, path string) (ImageInfo, error) {
	out, err := run(ctx, t.bin(t.VipsHeader, "vipsheader"), "-a", path)
	if err != nil {
		return ImageInfo{}, err
	}
	return parseHeader(string(out)), nil
}

func parseHeader(out string) ImageInfo {
	var info ImageInfo
	fields := map[string]string{}
	for _, line := range strings.Split(out, "\n") {
		k, v, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		fields[strings.TrimSpace(k)] = strings.TrimSpace(v)
	}
	info.Size.W, _ = strconv.Atoi(fields["width"])
	info.Size.H, _ = strconv.Atoi(fields["height"])
	if o, _ := strconv.Atoi(fields["orientation"]); o >= 5 && o <= 8 {
		info.Size.W, info.Size.H = info.Size.H, info.Size.W
	}
	for _, k := range []string{"exif-ifd2-DateTimeOriginal", "exif-ifd2-DateTimeDigitized", "exif-ifd0-DateTime"} {
		if ts := parseExifTime(fields[k], fields[strings.Replace(k, "DateTime", "OffsetTime", 1)]); ts != nil {
			info.CapturedAt = ts
			break
		}
	}
	return info
}

// parseExifTime parses "2023:05:01 12:00:00 (2023:05:01 12:00:00, ASCII, 20 components, 20 bytes)".
// EXIF times without an offset are local wall-clock times in an unknown zone;
// they are stored as if UTC so frames show the wall-clock time the photo was taken.
func parseExifTime(v, offset string) *time.Time {
	if len(v) < 19 {
		return nil
	}
	loc := time.UTC
	if off := strings.Fields(offset); len(off) > 0 {
		if t, err := time.Parse("-07:00", off[0]); err == nil {
			_, secs := t.Zone()
			loc = time.FixedZone("", secs)
		}
	}
	ts, err := time.ParseInLocation("2006:01:02 15:04:05", v[:19], loc)
	if err != nil || ts.Year() < 1900 {
		return nil
	}
	ts = ts.UTC()
	return &ts
}

// VideoInfo is what ffprobe tells us.
type VideoInfo struct {
	Size       Size // display size, rotation applied
	DurationMS int
	FPS        float64
	HasAudio   bool
	CapturedAt *time.Time
}

// ProbeVideo runs ffprobe.
func (t Tools) ProbeVideo(ctx context.Context, path string) (VideoInfo, error) {
	out, err := run(ctx, t.bin(t.FFprobe, "ffprobe"), "-v", "error", "-print_format", "json", "-show_format", "-show_streams", path)
	if err != nil {
		return VideoInfo{}, err
	}
	return parseProbe(out)
}

type probeJSON struct {
	Streams []struct {
		CodecType    string            `json:"codec_type"`
		Width        int               `json:"width"`
		Height       int               `json:"height"`
		AvgFrameRate string            `json:"avg_frame_rate"`
		RFrameRate   string            `json:"r_frame_rate"`
		Duration     string            `json:"duration"`
		Tags         map[string]string `json:"tags"`
		Disposition  map[string]int    `json:"disposition"`
		SideData     []struct {
			Rotation float64 `json:"rotation"`
		} `json:"side_data_list"`
	} `json:"streams"`
	Format struct {
		Duration string            `json:"duration"`
		Tags     map[string]string `json:"tags"`
	} `json:"format"`
}

func parseProbe(b []byte) (VideoInfo, error) {
	var p probeJSON
	if err := json.Unmarshal(b, &p); err != nil {
		return VideoInfo{}, fmt.Errorf("ffprobe output: %w", err)
	}
	var info VideoInfo
	found := false
	for _, s := range p.Streams {
		switch s.CodecType {
		case "video":
			if found || s.Disposition["attached_pic"] == 1 || s.Width == 0 {
				continue
			}
			found = true
			info.Size = Size{s.Width, s.Height}
			rot := 0.0
			for _, sd := range s.SideData {
				rot = sd.Rotation
			}
			if r, err := strconv.ParseFloat(s.Tags["rotate"], 64); err == nil {
				rot = r
			}
			if int(math.Abs(rot))%180 == 90 {
				info.Size.W, info.Size.H = info.Size.H, info.Size.W
			}
			info.FPS = parseRate(s.AvgFrameRate)
			if info.FPS == 0 {
				info.FPS = parseRate(s.RFrameRate)
			}
			if d, err := strconv.ParseFloat(s.Duration, 64); err == nil {
				info.DurationMS = int(d * 1000)
			}
		case "audio":
			info.HasAudio = true
		}
	}
	if !found {
		return VideoInfo{}, ErrUnsupported
	}
	if d, err := strconv.ParseFloat(p.Format.Duration, 64); err == nil && d > 0 {
		info.DurationMS = int(d * 1000)
	}
	for _, k := range []string{"com.apple.quicktime.creationdate", "creation_time"} {
		if v := p.Format.Tags[k]; v != "" {
			if ts, err := time.Parse(time.RFC3339Nano, v); err == nil && ts.Year() > 1970 {
				ts = ts.UTC()
				info.CapturedAt = &ts
				break
			}
			if ts, err := time.Parse("2006-01-02T15:04:05-0700", v); err == nil && ts.Year() > 1970 {
				ts = ts.UTC()
				info.CapturedAt = &ts
				break
			}
		}
	}
	return info, nil
}

func parseRate(s string) float64 {
	num, den, ok := strings.Cut(s, "/")
	n, err := strconv.ParseFloat(num, 64)
	if err != nil {
		return 0
	}
	if !ok {
		return n
	}
	d, err := strconv.ParseFloat(den, 64)
	if err != nil || d == 0 {
		return 0
	}
	return n / d
}

// FileDigest returns the hex sha256 and size of a file.
func FileDigest(path string) (string, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}
