package media

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"time"
)

// Client-side preparation (README §5.3 "Prepared uploads"). A client may
// declare an upload a prepared master. The server verifies instead of trusting:
// a conforming upload is stored as the master without re-encoding (metadata is
// still stripped server-side); anything else takes the normal pipeline.

// Prepared upload profiles (tus Upload-Metadata key "prepared").
const (
	PreparedPhotoV1 = "photo-master-v1"
	PreparedVideoV1 = "video-master-v1"
)

// ValidPrepared reports whether v is a known profile.
func ValidPrepared(v string) bool { return v == PreparedPhotoV1 || v == PreparedVideoV1 }

// Limits for prepared masters.
const (
	// MaxPreparedPhotoBytes bounds a prepared photo master (a 2560 px WebP q90 is
	// typically 0.5–3 MB; noisy JPEGs can be larger).
	MaxPreparedPhotoBytes = 16 << 20
	// MaxPreparedVideoBitrate bounds the total bitrate of a prepared video master.
	MaxPreparedVideoBitrate = 20_000_000
	// MaxH264Level is the highest accepted H.264 level (4.1: 1080p at 30 fps).
	MaxH264Level = 41
	// MaxMotionMS bounds a Live/Motion Photo clip; longer clips are cut.
	MaxMotionMS = 10_000
)

// Declared is what the client says it produced (send item fields).
type Declared struct {
	Width, Height, DurationMS *int
}

// PreparedPhoto is a verified prepared photo master.
type PreparedPhoto struct {
	Size       Size
	Mime       string
	CapturedAt *time.Time
}

// Rejection explains why a prepared upload did not conform; the upload then
// takes the normal pipeline. It is not a media error.
type Rejection string

func (r Rejection) Error() string { return "not a conforming prepared master: " + string(r) }

func reject(format string, a ...any) error { return Rejection(fmt.Sprintf(format, a...)) }

// RejectionReason returns the reason if err is a Rejection.
func RejectionReason(err error) (string, bool) {
	var r Rejection
	if errors.As(err, &r) {
		return string(r), true
	}
	return "", false
}

// VerifyPreparedPhoto checks src against photo-master-v1 and, if it conforms,
// writes a metadata-free copy to dst (same bytes for the image data). A
// Rejection means "use the normal pipeline"; other errors are infrastructure or
// media errors.
func (t Tools) VerifyPreparedPhoto(ctx context.Context, src, dst string, decl Declared) (*PreparedPhoto, error) {
	_, mime, err := Sniff(src)
	if err != nil {
		return nil, reject("not a photo")
	}
	if mime != "image/webp" && mime != "image/jpeg" {
		return nil, reject("format %s", mime)
	}
	st, err := os.Stat(src)
	if err != nil {
		return nil, err
	}
	if st.Size() > MaxPreparedPhotoBytes {
		return nil, reject("%d bytes", st.Size())
	}
	out, err := run(ctx, t.bin(t.VipsHeader, "vipsheader"), "-a", src)
	if err != nil {
		return nil, reject("header unreadable")
	}
	fields := headerFields(string(out))
	w, _ := strconv.Atoi(fields["width"])
	h, _ := strconv.Atoi(fields["height"])
	size := Size{w, h}
	switch {
	case w <= 0 || h <= 0:
		return nil, reject("no dimensions")
	case size.Long() > MasterPhotoEdge:
		return nil, reject("%dx%d exceeds %d px", w, h, MasterPhotoEdge)
	case fields["orientation"] != "" && fields["orientation"] != "1":
		return nil, reject("orientation %s not applied", fields["orientation"])
	case fields["format"] != "uchar":
		return nil, reject("sample format %s", fields["format"])
	case fields["interpretation"] != "srgb" && fields["interpretation"] != "b-w":
		return nil, reject("colour space %s", fields["interpretation"])
	case fields["n-pages"] != "" && fields["n-pages"] != "1":
		return nil, reject("%s pages", fields["n-pages"])
	}
	if decl.Width != nil && decl.Height != nil && (*decl.Width != w || *decl.Height != h) {
		return nil, reject("declared %dx%d, file is %dx%d", *decl.Width, *decl.Height, w, h)
	}
	// Decode it completely: a truncated or broken file must not become a master.
	if _, err := run(ctx, t.bin(t.Vips, "vips"), "avg", src+"[fail_on=truncated]"); err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, reject("does not decode")
	}
	info := parseHeader(string(out))
	if err := StripFile(src, dst, mime); err != nil {
		if errors.Is(err, ErrUnsupported) || errors.Is(err, ErrCorrupt) {
			return nil, reject("%v", err)
		}
		return nil, err
	}
	// Belt and braces: the written master must carry no metadata at all.
	if chk, err := run(ctx, t.bin(t.VipsHeader, "vipsheader"), "-a", dst); err != nil {
		return nil, reject("stripped copy unreadable")
	} else if f := headerFields(string(chk)); f["exif-data"] != "" || f["xmp-data"] != "" || f["iptc-data"] != "" {
		return nil, fmt.Errorf("metadata survived stripping: %w", ErrCorrupt)
	}
	return &PreparedPhoto{Size: size, Mime: mime, CapturedAt: info.CapturedAt}, nil
}

func headerFields(out string) map[string]string {
	fields := map[string]string{}
	for _, line := range strings.Split(out, "\n") {
		k, v, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		fields[strings.TrimSpace(k)] = strings.TrimSpace(v)
	}
	return fields
}

// h264Profiles are accepted for masters and for direct reuse on frames.
var h264Profiles = map[string]bool{"High": true, "Main": true, "Constrained Baseline": true, "Baseline": true}

// CheckPreparedVideo verifies probe output against video-master-v1: MP4,
// H.264 High/Main (or Baseline) at level ≤ 4.1, 4:2:0, ≤ 1080p without a
// rotation flag, ≤ 60 fps, AAC (≤ 2 channels) or no audio, ≤ maxMS, sane
// bitrate. Faststart is not required: the server remuxes every prepared master
// (to strip metadata), which writes the index first anyway.
func CheckPreparedVideo(info VideoInfo, maxMS int, decl Declared) error {
	switch {
	case !strings.Contains(info.Format, "mp4") || info.MajorBrand == "qt" || strings.HasPrefix(info.MajorBrand, "3g"):
		return reject("container %s/%s", info.Format, info.MajorBrand)
	case info.VideoStreams != 1:
		return reject("%d video streams", info.VideoStreams)
	case info.VideoCodec != "h264":
		return reject("codec %s", info.VideoCodec)
	case !h264Profiles[info.Profile]:
		return reject("profile %s", info.Profile)
	case info.Level <= 0 || info.Level > MaxH264Level:
		return reject("level %d", info.Level)
	case info.PixFmt != "yuv420p" && info.PixFmt != "yuvj420p":
		return reject("pixel format %s", info.PixFmt)
	case info.Rotation != 0:
		return reject("rotation %d", info.Rotation)
	case FitWithin(info.Size, MasterVideoBox, true) != info.Size:
		return reject("%dx%d exceeds 1080p", info.Size.W, info.Size.H)
	case info.FPS <= 0 || info.FPS > 60.5:
		return reject("%.2f fps", info.FPS)
	case info.AudioStreams > 1:
		return reject("%d audio streams", info.AudioStreams)
	case info.HasAudio && info.AudioCodec != "aac":
		return reject("audio %s", info.AudioCodec)
	case info.HasAudio && info.AudioChans > 2:
		return reject("%d audio channels", info.AudioChans)
	case info.DurationMS <= 0 || (maxMS > 0 && info.DurationMS > maxMS):
		return reject("duration %d ms", info.DurationMS)
	case info.TotalBitrate > MaxPreparedVideoBitrate || info.VideoBitrate > MaxPreparedVideoBitrate:
		return reject("bitrate %d", max(info.TotalBitrate, info.VideoBitrate))
	}
	if decl.Width != nil && decl.Height != nil && (Size{*decl.Width, *decl.Height}) != info.Size {
		return reject("declared %dx%d, file is %dx%d", *decl.Width, *decl.Height, info.Size.W, info.Size.H)
	}
	if decl.DurationMS != nil && abs(*decl.DurationMS-info.DurationMS) > 500 {
		return reject("declared %d ms, file is %d ms", *decl.DurationMS, info.DurationMS)
	}
	return nil
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// RemuxVideo copies the first video (and, if includeAudio, the first audio)
// stream into a fresh MP4 without any container or stream metadata (GPS,
// device, creation time) and with the index first (faststart). No re-encode.
func (t Tools) RemuxVideo(ctx context.Context, src string, includeAudio bool, dst string) (VideoInfo, error) {
	args := []string{"-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-i", src, "-map", "0:v:0"}
	if includeAudio {
		args = append(args, "-map", "0:a:0?")
	}
	args = append(args, "-c", "copy", "-sn", "-dn", "-map_metadata", "-1", "-map_metadata:s:v", "-1",
		"-map_metadata:s:a", "-1", "-map_chapters", "-1", "-fflags", "+bitexact", "-flags:v", "+bitexact",
		"-flags:a", "+bitexact", "-movflags", "+faststart", "-f", "mp4", dst)
	if _, err := run(ctx, t.bin(t.FFmpeg, "ffmpeg"), args...); err != nil {
		return VideoInfo{}, err
	}
	return t.ProbeVideo(ctx, dst)
}

// ReusableAsRendition reports whether a master can be served to a frame as is:
// it already meets the frame video rendition profile (README §5.3) for the
// device's decode box — H.264 ≤ level 4.1, within the box, ≤ 30 fps, bitrate
// within the rendition budget, AAC or no audio, faststart.
func ReusableAsRendition(info VideoInfo, decode Size, faststart bool) bool {
	budget := VideoBitrate(info.Size)
	vbr := info.VideoBitrate
	if vbr == 0 {
		vbr = info.TotalBitrate
	}
	return faststart &&
		info.VideoCodec == "h264" && h264Profiles[info.Profile] && info.Level > 0 && info.Level <= MaxH264Level &&
		(info.PixFmt == "yuv420p" || info.PixFmt == "yuvj420p") && info.Rotation == 0 &&
		FitWithin(info.Size, decode, true) == info.Size &&
		info.FPS > 0 && info.FPS <= 30.5 &&
		vbr > 0 && vbr <= budget+budget/10 &&
		(!info.HasAudio || (info.AudioCodec == "aac" && info.AudioChans <= 2 && info.AudioBitrate <= 192_000)) &&
		info.AudioStreams <= 1 && info.OtherStreams == 0
}

// Faststart reports whether an MP4's moov box precedes its mdat box.
func Faststart(path string) (bool, error) {
	f, err := os.Open(path)
	if err != nil {
		return false, err
	}
	defer f.Close()
	var off int64
	hdr := make([]byte, 16)
	for {
		if _, err := f.ReadAt(hdr[:8], off); err != nil {
			if errors.Is(err, io.EOF) {
				return false, nil
			}
			return false, err
		}
		size := int64(binary.BigEndian.Uint32(hdr[:4]))
		typ := string(hdr[4:8])
		switch typ {
		case "moov":
			return true, nil
		case "mdat":
			return false, nil
		}
		if size == 1 {
			if _, err := f.ReadAt(hdr[8:16], off+8); err != nil {
				return false, nil
			}
			size = int64(binary.BigEndian.Uint64(hdr[8:16]))
		}
		if size < 8 {
			return false, nil
		}
		off += size
	}
}
