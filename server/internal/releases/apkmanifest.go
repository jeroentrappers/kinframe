package releases

import (
	"archive/zip"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"strconv"
	"unicode/utf16"
)

// Manifest is what the mirror needs from an APK's AndroidManifest.xml.
type Manifest struct {
	Package     string
	VersionCode int
	VersionName string
	MinSDK      int
}

// Android framework attribute resource IDs (android.R.attr). aapt2 can strip
// attribute names, so attributes are matched by ID first, by name second.
const (
	attrVersionCode   = 0x0101021b
	attrVersionName   = 0x0101021c
	attrMinSdkVersion = 0x0101020c
)

// Binary XML chunk types (frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h).
const (
	chunkStringPool   = 0x0001
	chunkXML          = 0x0003
	chunkXMLStartElem = 0x0102
	chunkXMLResMap    = 0x0180
	typeString        = 0x03
	typeIntDec        = 0x10
	typeIntHex        = 0x11
	utf8Flag          = 1 << 8
	maxManifestBytes  = 4 << 20
)

// ReadAPKManifest opens an APK (a zip file) and parses its binary manifest.
func ReadAPKManifest(path string) (*Manifest, error) {
	zr, err := zip.OpenReader(path)
	if err != nil {
		return nil, fmt.Errorf("not an APK: %w", err)
	}
	defer zr.Close()
	for _, f := range zr.File {
		if f.Name != "AndroidManifest.xml" {
			continue
		}
		if f.UncompressedSize64 > maxManifestBytes {
			return nil, errors.New("AndroidManifest.xml is too large")
		}
		rc, err := f.Open()
		if err != nil {
			return nil, err
		}
		defer rc.Close()
		b, err := io.ReadAll(io.LimitReader(rc, maxManifestBytes+1))
		if err != nil {
			return nil, err
		}
		return ParseBinaryManifest(b)
	}
	return nil, errors.New("APK has no AndroidManifest.xml")
}

// ParseBinaryManifest reads package, versionCode, versionName and
// minSdkVersion from a compiled (binary XML) AndroidManifest.xml.
func ParseBinaryManifest(b []byte) (*Manifest, error) {
	le := binary.LittleEndian
	if len(b) < 8 || le.Uint16(b) != chunkXML {
		return nil, errors.New("not a binary XML manifest")
	}
	var (
		strs   []string
		resMap []uint32
		m      Manifest
		seen   bool
	)
	off := int(le.Uint16(b[2:]))
	for off+8 <= len(b) {
		typ := le.Uint16(b[off:])
		hdr := int(le.Uint16(b[off+2:]))
		size := int(le.Uint32(b[off+4:]))
		if size < 8 || off+size > len(b) || hdr > size {
			return nil, fmt.Errorf("corrupt chunk at %d", off)
		}
		chunk := b[off : off+size]
		switch typ {
		case chunkStringPool:
			var err error
			if strs, err = parseStringPool(chunk); err != nil {
				return nil, err
			}
		case chunkXMLResMap:
			for p := hdr; p+4 <= size; p += 4 {
				resMap = append(resMap, le.Uint32(chunk[p:]))
			}
		case chunkXMLStartElem:
			if hdr < 16 || size < hdr+20 {
				return nil, errors.New("corrupt start element")
			}
			ext := chunk[hdr:]
			name := str(strs, le.Uint32(ext[4:]))
			attrStart, attrSize, attrCount := int(le.Uint16(ext[8:])), int(le.Uint16(ext[10:])), int(le.Uint16(ext[12:]))
			if attrSize < 20 || hdr+attrStart+attrSize*attrCount > size {
				return nil, errors.New("corrupt attributes")
			}
			for i := 0; i < attrCount; i++ {
				a := ext[attrStart+i*attrSize:]
				nameIdx := le.Uint32(a[4:])
				rawIdx := le.Uint32(a[8:])
				dataType := a[15]
				data := le.Uint32(a[16:])
				var id uint32
				if int(nameIdx) < len(resMap) {
					id = resMap[nameIdx]
				}
				aname := str(strs, nameIdx)
				value := func() string {
					if dataType == typeString {
						return str(strs, data)
					}
					if rawIdx != 0xffffffff {
						return str(strs, rawIdx)
					}
					return ""
				}
				intValue := func() int {
					if dataType == typeIntDec || dataType == typeIntHex {
						return int(int32(data))
					}
					n, _ := strconv.Atoi(value())
					return n
				}
				switch {
				case name == "manifest" && aname == "package" && id == 0:
					m.Package = value()
				case name == "manifest" && (id == attrVersionCode || (id == 0 && aname == "versionCode")):
					m.VersionCode = intValue()
				case name == "manifest" && (id == attrVersionName || (id == 0 && aname == "versionName")):
					m.VersionName = value()
				case name == "uses-sdk" && (id == attrMinSdkVersion || (id == 0 && aname == "minSdkVersion")):
					m.MinSDK = intValue()
				}
			}
			if name == "manifest" {
				seen = true
			}
		}
		off += size
	}
	if !seen || m.Package == "" {
		return nil, errors.New("manifest element or package missing")
	}
	if m.MinSDK == 0 {
		m.MinSDK = 1 // Android's default when uses-sdk is absent
	}
	return &m, nil
}

func str(strs []string, i uint32) string {
	if int(i) < len(strs) {
		return strs[i]
	}
	return ""
}

func parseStringPool(c []byte) ([]string, error) {
	le := binary.LittleEndian
	if len(c) < 28 {
		return nil, errors.New("corrupt string pool")
	}
	hdr := int(le.Uint16(c[2:]))
	count := int(le.Uint32(c[8:]))
	flags := le.Uint32(c[16:])
	start := int(le.Uint32(c[20:]))
	if count > len(c)/4 || hdr+count*4 > len(c) || start > len(c) {
		return nil, errors.New("corrupt string pool header")
	}
	out := make([]string, count)
	for i := range count {
		p := start + int(le.Uint32(c[hdr+i*4:]))
		if p >= len(c) {
			return nil, errors.New("string offset out of range")
		}
		if flags&utf8Flag != 0 {
			_, n := utf8Len(c[p:]) // length in UTF-16 units, unused
			p += n
			l, n := utf8Len(c[p:])
			p += n
			if p+l > len(c) {
				return nil, errors.New("string out of range")
			}
			out[i] = string(c[p : p+l])
		} else {
			if p+2 > len(c) {
				return nil, errors.New("string out of range")
			}
			l := int(le.Uint16(c[p:]))
			p += 2
			if l&0x8000 != 0 {
				if p+2 > len(c) {
					return nil, errors.New("string out of range")
				}
				l = (l&0x7fff)<<16 | int(le.Uint16(c[p:]))
				p += 2
			}
			if p+2*l > len(c) {
				return nil, errors.New("string out of range")
			}
			u := make([]uint16, l)
			for j := range l {
				u[j] = le.Uint16(c[p+2*j:])
			}
			out[i] = string(utf16.Decode(u))
		}
	}
	return out, nil
}

func utf8Len(b []byte) (int, int) {
	if len(b) == 0 {
		return 0, 0
	}
	if b[0]&0x80 != 0 && len(b) > 1 {
		return int(b[0]&0x7f)<<8 | int(b[1]), 2
	}
	return int(b[0]), 1
}
