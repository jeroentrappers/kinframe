package media

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"os"
)

// Lossless metadata stripping for prepared photo masters. Privacy is never
// delegated to clients: even when an upload claims to be a prepared master, the
// server rewrites the container without EXIF, XMP, IPTC, comments or trailing
// data (Motion Photo videos, maker blobs). The compressed image data and the ICC
// profile are copied byte for byte, so nothing is re-encoded.

// StripFile writes src without metadata to dst. mime is the sniffed type.
func StripFile(src, dst, mime string) error {
	b, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	var out []byte
	switch mime {
	case "image/jpeg":
		out, err = StripJPEG(b)
	case "image/webp":
		out, err = StripWebP(b)
	default:
		return fmt.Errorf("strip %s: %w", mime, ErrUnsupported)
	}
	if err != nil {
		return err
	}
	return os.WriteFile(dst, out, 0o644)
}

// StripJPEG keeps only the segments needed to decode the image (plus JFIF,
// ICC and Adobe colour markers) and drops everything after EOI.
func StripJPEG(b []byte) ([]byte, error) {
	if len(b) < 4 || b[0] != 0xFF || b[1] != 0xD8 {
		return nil, fmt.Errorf("jpeg: no SOI: %w", ErrCorrupt)
	}
	out := make([]byte, 0, len(b))
	out = append(out, 0xFF, 0xD8)
	i := 2
	for {
		// Skip fill bytes before a marker.
		for i < len(b) && b[i] == 0xFF && i+1 < len(b) && b[i+1] == 0xFF {
			i++
		}
		if i+2 > len(b) || b[i] != 0xFF {
			return nil, fmt.Errorf("jpeg: bad marker at %d: %w", i, ErrCorrupt)
		}
		m := b[i+1]
		if m == 0xD9 { // EOI: anything after it (e.g. a Motion Photo video) is dropped
			return append(out, 0xFF, 0xD9), nil
		}
		if i+4 > len(b) {
			return nil, fmt.Errorf("jpeg: truncated at %d: %w", i, ErrCorrupt)
		}
		if m == 0x01 || (m >= 0xD0 && m <= 0xD7) { // standalone markers
			out = append(out, b[i], m)
			i += 2
			continue
		}
		n := int(binary.BigEndian.Uint16(b[i+2:]))
		end := i + 2 + n
		if n < 2 || end > len(b) {
			return nil, fmt.Errorf("jpeg: segment overruns file: %w", ErrCorrupt)
		}
		seg := b[i:end]
		if keepJPEGSegment(m, seg[4:]) {
			out = append(out, seg...)
		}
		i = end
		if m == 0xDA { // SOS: copy entropy-coded data up to the next real marker
			j := i
			for j+1 < len(b) {
				if b[j] == 0xFF && b[j+1] != 0x00 && !(b[j+1] >= 0xD0 && b[j+1] <= 0xD7) && b[j+1] != 0xFF {
					break
				}
				j++
			}
			if j+1 >= len(b) {
				return nil, fmt.Errorf("jpeg: truncated scan: %w", ErrCorrupt)
			}
			out = append(out, b[i:j]...)
			i = j
		}
	}
}

func keepJPEGSegment(marker byte, payload []byte) bool {
	switch {
	case marker == 0xE0: // APP0 JFIF/JFXX
		return bytes.HasPrefix(payload, []byte("JFIF\x00"))
	case marker == 0xE2: // APP2: keep the ICC profile only (not MPF etc.)
		return bytes.HasPrefix(payload, []byte("ICC_PROFILE\x00"))
	case marker == 0xEE: // APP14 Adobe: colour transform, needed to decode correctly
		return bytes.HasPrefix(payload, []byte("Adobe"))
	case marker >= 0xE0 && marker <= 0xEF: // other APPn: EXIF, XMP, IPTC, maker data
		return false
	case marker == 0xFE: // COM
		return false
	}
	return true // DQT, DHT, SOFn, DRI, SOS, DNL, ...
}

// StripWebP rewrites a RIFF WebP without EXIF and XMP chunks (and the matching
// VP8X flags). Animated WebP is rejected (masters are stills).
func StripWebP(b []byte) ([]byte, error) {
	if len(b) < 20 || string(b[0:4]) != "RIFF" || string(b[8:12]) != "WEBP" {
		return nil, fmt.Errorf("webp: bad header: %w", ErrCorrupt)
	}
	riffEnd := 8 + int(binary.LittleEndian.Uint32(b[4:8]))
	if riffEnd > len(b) {
		return nil, fmt.Errorf("webp: truncated: %w", ErrCorrupt)
	}
	body := []byte{}
	vp8xAt := -1
	hasImage := false
	for i := 12; i+8 <= riffEnd; {
		id := string(b[i : i+4])
		n := int(binary.LittleEndian.Uint32(b[i+4 : i+8]))
		end := i + 8 + n + n%2
		if i+8+n > riffEnd {
			return nil, fmt.Errorf("webp: chunk %q overruns file: %w", id, ErrCorrupt)
		}
		end = min(end, riffEnd)
		switch id {
		case "EXIF", "XMP ":
			// dropped
		case "ANIM", "ANMF":
			return nil, fmt.Errorf("webp: animated: %w", ErrUnsupported)
		case "VP8X":
			vp8xAt = len(body)
			body = append(body, b[i:end]...)
		default:
			if id == "VP8 " || id == "VP8L" {
				hasImage = true
			}
			body = append(body, b[i:end]...)
		}
		i = end
	}
	if !hasImage {
		return nil, fmt.Errorf("webp: no image data: %w", ErrCorrupt)
	}
	if vp8xAt >= 0 && len(body) >= vp8xAt+9 {
		// VP8X flags: bit 3 EXIF, bit 2 XMP (RFC 9649 §2.7).
		body[vp8xAt+8] &^= 0x08 | 0x04
	}
	out := make([]byte, 0, 12+len(body))
	out = append(out, "RIFF"...)
	out = binary.LittleEndian.AppendUint32(out, uint32(4+len(body)))
	out = append(out, "WEBP"...)
	return append(out, body...), nil
}
