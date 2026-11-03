package media

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

// Quality settings (product spec §8, README §5.3).
const (
	MasterPhotoQ    = 90
	RenditionPhotoQ = 85
	ThumbQ          = 75
	PosterQ         = 85
)

// ThumbSizes are the square thumbnail sizes.
var ThumbSizes = []int{256, 512}

func webp(path string, q int) string { return fmt.Sprintf("%s[Q=%d,strip]", path, q) }

// PhotoMaster writes a WebP master (≤2560 px long edge, EXIF orientation applied,
// all metadata stripped). It returns the master size and the EXIF capture time.
func (t Tools) PhotoMaster(ctx context.Context, src, dst string) (Size, *time.Time, error) {
	hdr, err := t.ImageHeader(ctx, src)
	if err != nil {
		return Size{}, nil, err
	}
	// vips thumbnail autorotates, converts to sRGB and only loads the first page/frame.
	if _, err := run(ctx, t.bin(t.Vips, "vips"), "thumbnail", src, webp(dst, MasterPhotoQ),
		strconv.Itoa(MasterPhotoEdge), "--height", strconv.Itoa(MasterPhotoEdge), "--size", "down"); err != nil {
		return Size{}, nil, err
	}
	out, err := t.ImageHeader(ctx, dst)
	if err != nil {
		return Size{}, nil, err
	}
	return out.Size, hdr.CapturedAt, nil
}

// PhotoRendition writes the frame-sized WebP for a screen long edge. If the
// master is already a small enough WebP it is copied rather than re-encoded
// (prepared JPEG masters are always re-encoded to WebP).
func (t Tools) PhotoRendition(ctx context.Context, master string, masterSize Size, screenLong int, dst string) (Size, error) {
	target := PhotoRenditionSize(masterSize, screenLong)
	if _, mime, _ := Sniff(master); target == masterSize && mime == "image/webp" {
		return masterSize, copyFile(master, dst)
	}
	if _, err := run(ctx, t.bin(t.Vips, "vips"), "thumbnail", master, webp(dst, RenditionPhotoQ),
		strconv.Itoa(target.W), "--height", strconv.Itoa(target.H), "--size", "down"); err != nil {
		return Size{}, err
	}
	hdr, err := t.ImageHeader(ctx, dst)
	return hdr.Size, err
}

// ResizeImage writes img scaled to fit within box as WebP (used for frame-sized posters).
func (t Tools) ResizeImage(ctx context.Context, src string, box Size, q int, dst string) error {
	_, err := run(ctx, t.bin(t.Vips, "vips"), "thumbnail", src, webp(dst, q),
		strconv.Itoa(box.W), "--height", strconv.Itoa(box.H), "--size", "down")
	return err
}

// Thumb writes an n×n WebP square centred on the focal point (fx, fy in 0..1).
func (t Tools) Thumb(ctx context.Context, src string, srcSize Size, n int, fx, fy float64, dst string) error {
	tmp := dst + ".tmp.v"
	defer os.Remove(tmp)
	scale := float64(n) / float64(srcSize.Short())
	if _, err := run(ctx, t.bin(t.Vips, "vips"), "resize", src, tmp, strconv.FormatFloat(scale, 'f', 6, 64)); err != nil {
		return err
	}
	hdr, err := t.ImageHeader(ctx, tmp)
	if err != nil {
		return err
	}
	side := min(n, hdr.Size.Short())
	left, top := CropOrigin(hdr.Size, side, fx, fy)
	_, err = run(ctx, t.bin(t.Vips, "vips"), "extract_area", tmp, webp(dst, ThumbQ),
		strconv.Itoa(left), strconv.Itoa(top), strconv.Itoa(side), strconv.Itoa(side))
	return err
}

// Trim selects part of a video.
type Trim struct{ StartMS, EndMS int }

// VideoMaster writes the H.264/AAC master (≤1080p, faststart, metadata stripped).
func (t Tools) VideoMaster(ctx context.Context, src string, info VideoInfo, trim *Trim, includeAudio bool, dst string) (VideoInfo, error) {
	size := FitWithin(info.Size, MasterVideoBox, true)
	args := []string{"-hide_banner", "-nostdin", "-y", "-loglevel", "error"}
	if trim != nil && trim.StartMS > 0 {
		args = append(args, "-ss", ms(trim.StartMS))
	}
	args = append(args, "-i", src)
	if trim != nil && trim.EndMS > 0 {
		args = append(args, "-t", ms(trim.EndMS-trim.StartMS))
	}
	vf := fmt.Sprintf("scale=%d:%d:flags=lanczos", size.W, size.H)
	if info.FPS > 60 {
		vf += ",fps=60"
	}
	args = append(args, "-map", "0:v:0", "-vf", vf+",format=yuv420p",
		"-c:v", "libx264", "-preset", "fast", "-crf", "20", "-profile:v", "high")
	if includeAudio && info.HasAudio {
		args = append(args, "-map", "0:a:0", "-c:a", "aac", "-b:a", "128k", "-ac", "2")
	} else {
		args = append(args, "-an")
	}
	args = append(args, "-sn", "-dn", "-map_metadata", "-1", "-movflags", "+faststart", "-f", "mp4", dst)
	if _, err := run(ctx, t.bin(t.FFmpeg, "ffmpeg"), args...); err != nil {
		return VideoInfo{}, err
	}
	out, err := t.ProbeVideo(ctx, dst)
	if err != nil {
		return VideoInfo{}, err
	}
	out.CapturedAt = info.CapturedAt
	return out, nil
}

// VideoRendition writes a frame video within the decode box: H.264 High@4.1,
// ≤30 fps, bitrate w·h·4 capped at 8 Mbps, AAC-LC 96 kbps, faststart.
func (t Tools) VideoRendition(ctx context.Context, master string, info VideoInfo, decode Size, dst string) (Size, error) {
	size := FitWithin(info.Size, decode, true)
	vf := fmt.Sprintf("scale=%d:%d:flags=lanczos", size.W, size.H)
	if info.FPS > 30.5 || info.FPS == 0 {
		vf += ",fps=30"
	}
	br := VideoBitrate(size)
	args := []string{"-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-i", master,
		"-map", "0:v:0", "-vf", vf + ",format=yuv420p",
		"-c:v", "libx264", "-preset", "fast", "-profile:v", "high", "-level:v", "4.1",
		"-b:v", strconv.Itoa(br), "-maxrate", strconv.Itoa(br), "-bufsize", strconv.Itoa(2 * br)}
	if info.HasAudio {
		args = append(args, "-map", "0:a:0", "-c:a", "aac", "-profile:a", "aac_low", "-b:a", "96k", "-ac", "2")
	} else {
		args = append(args, "-an")
	}
	args = append(args, "-sn", "-dn", "-map_metadata", "-1", "-movflags", "+faststart", "-f", "mp4", dst)
	if _, err := run(ctx, t.bin(t.FFmpeg, "ffmpeg"), args...); err != nil {
		return Size{}, err
	}
	return size, nil
}

// Poster writes a WebP still from the video (1 s in, or the middle of short clips).
func (t Tools) Poster(ctx context.Context, video string, durationMS int, dst string) error {
	at := min(1000, durationMS/2)
	png := dst + ".png"
	defer os.Remove(png)
	if _, err := run(ctx, t.bin(t.FFmpeg, "ffmpeg"), "-hide_banner", "-nostdin", "-y", "-loglevel", "error",
		"-ss", ms(at), "-i", video, "-frames:v", "1", "-update", "1", png); err != nil {
		return err
	}
	if _, err := os.Stat(png); err != nil {
		return fmt.Errorf("poster: no frame extracted: %w", ErrCorrupt)
	}
	_, err := run(ctx, t.bin(t.Vips, "vips"), "copy", png, webp(dst, PosterQ))
	return err
}

func ms(v int) string { return strconv.FormatFloat(float64(v)/1000, 'f', 3, 64) }

func copyFile(src, dst string) error {
	b, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	return os.WriteFile(dst, b, 0o644)
}

// IsMediaError reports whether err is the media's fault (unsupported or corrupt)
// rather than an infrastructure problem worth retrying.
func IsMediaError(err error) bool {
	return errors.Is(err, ErrCorrupt) || errors.Is(err, ErrUnsupported)
}
