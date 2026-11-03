package media

import (
	"bytes"
	"encoding/binary"
	"io"
	"os"
	"regexp"
	"strconv"
)

// Motion Photos (Android) carry a short MP4 inside the still: appended after a
// JPEG (Google "MotionPhoto"/legacy "MicroVideo" XMP, Samsung trailers) or in a
// top-level `mpvd` box of a HEIC. The XMP says where it starts:
//
//	GCamera:MotionPhoto="1" + Container:Directory items, the MotionPhoto item's
//	Item:Length counting from the end of the file, or
//	GCamera:MicroVideo="1" GCamera:MicroVideoOffset="<bytes from the end>".
//
// FindMotionVideo never trusts the offsets blindly: the located range must start
// with an MP4 ftyp box, and the end is taken from the MP4's own box structure.

// MotionRange is the byte range of an embedded video.
type MotionRange struct{ Start, End int64 }

func (r MotionRange) Len() int64 { return r.End - r.Start }

// maxXMPScan bounds how much of the file is searched for the XMP packet.
const maxXMPScan = 1 << 20

var (
	reMotionFlag  = regexp.MustCompile(`(?:MotionPhoto|MicroVideo)\s*=\s*"1"|<\w+:(?:MotionPhoto|MicroVideo)>\s*1\s*<`)
	reMicroOffset = regexp.MustCompile(`MicroVideoOffset\s*=\s*"(\d+)"|<\w+:MicroVideoOffset>\s*(\d+)\s*<`)
	reItem        = regexp.MustCompile(`<\w+:Item\b[^>]*>`)
	reSemantic    = regexp.MustCompile(`\w+:Semantic\s*=\s*"MotionPhoto"`)
	reLength      = regexp.MustCompile(`\w+:Length\s*=\s*"(\d+)"`)
)

// FindMotionVideo locates an embedded Motion Photo video in a JPEG or HEIC.
// ok is false for ordinary photos.
func FindMotionVideo(path string) (MotionRange, bool, error) {
	f, err := os.Open(path)
	if err != nil {
		return MotionRange{}, false, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return MotionRange{}, false, err
	}
	size := st.Size()
	head := make([]byte, min(size, maxXMPScan))
	if _, err := io.ReadFull(f, head); err != nil {
		return MotionRange{}, false, err
	}
	_, mime := SniffBytes(head)
	if mime != "image/jpeg" && mime != "image/heic" && mime != "image/heif" {
		return MotionRange{}, false, nil
	}
	if mime != "image/jpeg" {
		if r, ok := findMpvd(f, size); ok {
			return r, true, nil
		}
	}
	var xmp []byte
	if i := bytes.Index(head, []byte("<x:xmpmeta")); i >= 0 {
		xmp = head[i:]
		if j := bytes.Index(xmp, []byte("</x:xmpmeta>")); j >= 0 {
			xmp = xmp[:j]
		}
	}
	flagged := xmp != nil && reMotionFlag.Match(xmp)
	samsung := bytes.Contains(head, []byte("MotionPhoto_Data")) || bytes.Contains(tail(f, size), []byte("MotionPhoto_Data"))
	if !flagged && !samsung {
		return MotionRange{}, false, nil
	}
	var candidates []int64
	if flagged {
		if m := reMicroOffset.FindSubmatch(xmp); m != nil {
			candidates = append(candidates, size-atoi64(m[1], m[2]))
		}
		for _, item := range reItem.FindAll(xmp, -1) {
			if !reSemantic.Match(item) {
				continue
			}
			if m := reLength.FindSubmatch(item); m != nil {
				candidates = append(candidates, size-atoi64(m[1]))
			}
		}
	}
	for _, start := range candidates {
		if r, ok := mp4At(f, start, size); ok {
			return r, true, nil
		}
	}
	// Offsets missing or off by a few bytes (some OEMs count padding): scan the
	// tail for an MP4 ftyp box.
	if r, ok := scanForMP4(f, size); ok {
		return r, true, nil
	}
	return MotionRange{}, false, nil
}

func atoi64(parts ...[]byte) int64 {
	for _, p := range parts {
		if len(p) > 0 {
			v, _ := strconv.ParseInt(string(p), 10, 64)
			return v
		}
	}
	return 0
}

func tail(f *os.File, size int64) []byte {
	n := min(size, 64<<10)
	b := make([]byte, n)
	_, _ = f.ReadAt(b, size-n)
	return b
}

var mp4Brands = map[string]bool{"isom": true, "iso2": true, "iso4": true, "iso5": true, "iso6": true, "mp41": true, "mp42": true, "avc1": true, "qt  ": true, "M4V ": true}

// mp4At checks for an MP4 starting at start and returns its extent, walking
// the top-level boxes (ftyp, moov, mdat, free, ...).
func mp4At(f *os.File, start, size int64) (MotionRange, bool) {
	if start <= 0 || start+16 > size {
		return MotionRange{}, false
	}
	hdr := make([]byte, 16)
	if _, err := f.ReadAt(hdr, start); err != nil {
		return MotionRange{}, false
	}
	boxSize := int64(binary.BigEndian.Uint32(hdr[:4]))
	if string(hdr[4:8]) != "ftyp" || boxSize < 16 || boxSize > 256 || !mp4Brands[string(hdr[8:12])] {
		return MotionRange{}, false
	}
	off := start
	sawMoov, sawMdat := false, false
	for off+8 <= size {
		if _, err := f.ReadAt(hdr[:8], off); err != nil {
			break
		}
		n := int64(binary.BigEndian.Uint32(hdr[:4]))
		typ := string(hdr[4:8])
		if n == 1 {
			if _, err := f.ReadAt(hdr[8:16], off+8); err != nil {
				break
			}
			n = int64(binary.BigEndian.Uint64(hdr[8:16]))
		} else if n == 0 {
			n = size - off
		}
		if n < 8 || off+n > size || !isBoxType(typ) {
			break
		}
		switch typ {
		case "moov":
			sawMoov = true
		case "mdat":
			sawMdat = true
		}
		off += n
	}
	if !sawMoov || !sawMdat {
		return MotionRange{}, false
	}
	return MotionRange{Start: start, End: off}, true
}

func isBoxType(t string) bool {
	for i := 0; i < 4; i++ {
		c := t[i]
		if c < 0x20 || c > 0x7e {
			return false
		}
	}
	return true
}

// scanForMP4 looks for the last plausible MP4 ftyp box in the second half of
// the file (the still comes first).
func scanForMP4(f *os.File, size int64) (MotionRange, bool) {
	const window = 8 << 20
	from := max(int64(0), size-window)
	b := make([]byte, size-from)
	if _, err := f.ReadAt(b, from); err != nil && err != io.EOF {
		return MotionRange{}, false
	}
	for i := len(b); ; {
		j := bytes.LastIndex(b[:i], []byte("ftyp"))
		if j < 4 {
			return MotionRange{}, false
		}
		if r, ok := mp4At(f, from+int64(j-4), size); ok && r.Start > 0 {
			return r, true
		}
		i = j
	}
}

// findMpvd returns the payload of a top-level `mpvd` box (HEIC Motion Photo).
func findMpvd(f *os.File, size int64) (MotionRange, bool) {
	hdr := make([]byte, 16)
	var off int64
	for off+8 <= size {
		if _, err := f.ReadAt(hdr[:8], off); err != nil {
			return MotionRange{}, false
		}
		n := int64(binary.BigEndian.Uint32(hdr[:4]))
		hl := int64(8)
		if n == 1 {
			if _, err := f.ReadAt(hdr[8:16], off+8); err != nil {
				return MotionRange{}, false
			}
			n, hl = int64(binary.BigEndian.Uint64(hdr[8:16])), 16
		} else if n == 0 {
			n = size - off
		}
		if n < hl || off+n > size {
			return MotionRange{}, false
		}
		if string(hdr[4:8]) == "mpvd" {
			if r, ok := mp4At(f, off+hl, off+n); ok {
				return r, true
			}
			return MotionRange{}, false
		}
		off += n
	}
	return MotionRange{}, false
}

// ExtractRange copies [r.Start, r.End) of src to dst.
func ExtractRange(src string, r MotionRange, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o640)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, io.NewSectionReader(in, r.Start, r.Len())); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}
