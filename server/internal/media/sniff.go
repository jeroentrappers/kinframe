// Package media turns uploads into kinframe renditions using the libvips and
// ffmpeg command-line tools.
package media

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"os"
)

type Kind string

const (
	Photo Kind = "photo"
	Video Kind = "video"
)

// ErrUnsupported means the file is not a supported photo or video format.
var ErrUnsupported = errors.New("unsupported media")

// ErrCorrupt means the file looks supported but could not be decoded.
var ErrCorrupt = errors.New("corrupt media")

// Sniff classifies a file by its magic bytes (never by name or client-supplied type).
func Sniff(path string) (Kind, string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", "", err
	}
	defer f.Close()
	head := make([]byte, 4096)
	n, err := io.ReadFull(f, head)
	if err != nil && !errors.Is(err, io.ErrUnexpectedEOF) {
		if errors.Is(err, io.EOF) {
			return "", "", ErrUnsupported
		}
		return "", "", err
	}
	kind, mime := SniffBytes(head[:n])
	if kind == "" {
		return "", "", ErrUnsupported
	}
	return kind, mime, nil
}

// SniffBytes classifies the first bytes of a file.
func SniffBytes(b []byte) (Kind, string) {
	switch {
	case bytes.HasPrefix(b, []byte{0xFF, 0xD8, 0xFF}):
		return Photo, "image/jpeg"
	case bytes.HasPrefix(b, []byte("\x89PNG\r\n\x1a\n")):
		return Photo, "image/png"
	case bytes.HasPrefix(b, []byte("GIF87a")), bytes.HasPrefix(b, []byte("GIF89a")):
		return Photo, "image/gif"
	case len(b) >= 12 && bytes.Equal(b[0:4], []byte("RIFF")) && bytes.Equal(b[8:12], []byte("WEBP")):
		return Photo, "image/webp"
	case bytes.HasPrefix(b, []byte{0x1A, 0x45, 0xDF, 0xA3}):
		if bytes.Contains(b[:min(len(b), 64)], []byte("webm")) {
			return Video, "video/webm"
		}
		return Video, "video/x-matroska"
	case len(b) >= 12 && bytes.Equal(b[4:8], []byte("ftyp")):
		return sniffFtyp(b)
	}
	return "", ""
}

// sniffFtyp inspects an ISO-BMFF ftyp box: major brand plus compatible brands.
func sniffFtyp(b []byte) (Kind, string) {
	size := int(binary.BigEndian.Uint32(b[0:4]))
	if size < 16 || size > len(b) {
		size = min(len(b), 64)
	}
	brands := map[string]bool{string(b[8:12]): true}
	for i := 16; i+4 <= size; i += 4 {
		brands[string(b[i:i+4])] = true
	}
	major := string(b[8:12])
	switch {
	case major == "avif" || major == "avis":
		return Photo, "image/avif"
	case major == "heic" || major == "heix" || major == "heim" || major == "heis" || major == "hevc" || major == "hevx":
		return Photo, "image/heic"
	case major == "mif1" || major == "msf1":
		if brands["avif"] || brands["avis"] {
			return Photo, "image/avif"
		}
		return Photo, "image/heif"
	case major == "qt  ":
		return Video, "video/quicktime"
	case major == "3gp4" || major == "3gp5" || major == "3gp6" || major == "3g2a" || major == "3gg6":
		return Video, "video/3gpp"
	}
	for _, br := range []string{"isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "dash", "MSNV", "XAVC"} {
		if brands[br] {
			return Video, "video/mp4"
		}
	}
	return "", ""
}
