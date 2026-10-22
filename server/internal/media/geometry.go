package media

import "math"

// Size is a pixel size.
type Size struct{ W, H int }

func (s Size) Long() int  { return max(s.W, s.H) }
func (s Size) Short() int { return min(s.W, s.H) }

// MasterPhotoEdge is the maximum long edge of a stored photo master.
const MasterPhotoEdge = 2560

// MasterVideoBox is the maximum stored video master size (1080p, either orientation).
var MasterVideoBox = Size{1920, 1080}

// DefaultDecodeBox is assumed when a device does not report max_video_decode.
var DefaultDecodeBox = Size{1920, 1080}

// FitWithin scales src down (never up) so its long edge fits box.Long() and its
// short edge fits box.Short(), regardless of orientation. Results are rounded to
// even numbers when even is true (H.264 4:2:0 needs even dimensions).
func FitWithin(src, box Size, even bool) Size {
	scale := math.Min(1, math.Min(float64(box.Long())/float64(src.Long()), float64(box.Short())/float64(src.Short())))
	return scaled(src, scale, even)
}

// PhotoRenditionSize returns the frame rendition size for a photo: the short
// edge equals the frame's long screen edge, never upscaled.
func PhotoRenditionSize(src Size, screenLong int) Size {
	scale := math.Min(1, float64(screenLong)/float64(src.Short()))
	return scaled(src, scale, false)
}

func scaled(src Size, scale float64, even bool) Size {
	w := int(math.Round(float64(src.W) * scale))
	h := int(math.Round(float64(src.H) * scale))
	if even {
		w -= w % 2
		h -= h % 2
	}
	return Size{max(w, 2), max(h, 2)}
}

// VideoBitrate is w·h·4 bits per second, capped at 8 Mbps (README §5.3).
func VideoBitrate(s Size) int {
	return min(s.W*s.H*4, 8_000_000)
}

// CropOrigin returns the top-left corner of an n×n square inside size, centred
// on the focal point (0..1) and clamped to the image.
func CropOrigin(size Size, n int, fx, fy float64) (int, int) {
	clamp := func(v, hi int) int { return max(0, min(v, hi)) }
	left := clamp(int(math.Round(fx*float64(size.W)-float64(n)/2)), size.W-n)
	top := clamp(int(math.Round(fy*float64(size.H)-float64(n)/2)), size.H-n)
	return left, top
}

// PhotoVariant names the frame rendition for a screen ("frame_1280x800").
func PhotoVariant(screen Size) string {
	return "frame_" + itoa(screen.Long()) + "x" + itoa(screen.Short())
}

// VideoVariant names the frame video rendition; it depends on the decode box too.
func VideoVariant(screen, decode Size) string {
	return PhotoVariant(screen) + "_v" + itoa(decode.Long()) + "x" + itoa(decode.Short())
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	var b [20]byte
	n := len(b)
	for i > 0 {
		n--
		b[n] = byte('0' + i%10)
		i /= 10
	}
	return string(b[n:])
}
