// Package mediatest builds media fixtures for tests.
package mediatest

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/color"
	"image/jpeg"
)

// JPEG returns a w×h gradient JPEG. If orientation > 0 or dateTimeOriginal is
// set, an EXIF block is added; withGPS adds a GPS IFD so stripping can be tested.
func JPEG(w, h int, orientation uint16, dateTimeOriginal string, withGPS bool) []byte {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			// Red marks the top-left quadrant so orientation handling is checkable.
			c := color.RGBA{uint8(x * 255 / w), uint8(y * 255 / h), 128, 255}
			if x < w/2 && y < h/2 {
				c = color.RGBA{230, 20, 20, 255}
			}
			img.Set(x, y, c)
		}
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 85}); err != nil {
		panic(err)
	}
	b := buf.Bytes()
	if orientation == 0 && dateTimeOriginal == "" && !withGPS {
		return b
	}
	app1 := exifSegment(orientation, dateTimeOriginal, withGPS)
	out := append([]byte{}, b[:2]...)
	out = append(out, app1...)
	return append(out, b[2:]...)
}

type entry struct {
	tag, typ uint16
	count    uint32
	value    [4]byte
}

func exifSegment(orientation uint16, dto string, withGPS bool) []byte {
	le := binary.LittleEndian
	short := func(v uint16) (b [4]byte) { le.PutUint16(b[:], v); return }
	long := func(v uint32) (b [4]byte) { le.PutUint32(b[:], v); return }

	if dto == "" {
		dto = "2021:06:15 10:30:00"
	}
	// Layout: header(8) IFD0(2+3*12+4=42) ExifIFD(2+12+4=18) string(20) GPS IFD(2+2*12+4=30)
	const ifd0 = 8
	const exifIFD = ifd0 + 42
	const str = exifIFD + 18
	const gpsIFD = str + 20

	ifd0Entries := []entry{}
	if orientation == 0 {
		orientation = 1
	}
	ifd0Entries = append(ifd0Entries, entry{0x0112, 3, 1, short(orientation)})
	ifd0Entries = append(ifd0Entries, entry{0x8769, 4, 1, long(exifIFD)})
	if withGPS {
		ifd0Entries = append(ifd0Entries, entry{0x8825, 4, 1, long(gpsIFD)})
	} else {
		// Keep the layout fixed: a harmless Software tag (inline ASCII "kf\0").
		ifd0Entries = append(ifd0Entries, entry{0x0131, 2, 3, [4]byte{'k', 'f', 0, 0}})
	}

	var t bytes.Buffer
	t.WriteString("II*\x00")
	_ = binary.Write(&t, le, uint32(ifd0))
	writeIFD := func(es []entry) {
		_ = binary.Write(&t, le, uint16(len(es)))
		for _, e := range es {
			_ = binary.Write(&t, le, e.tag)
			_ = binary.Write(&t, le, e.typ)
			_ = binary.Write(&t, le, e.count)
			t.Write(e.value[:])
		}
		_ = binary.Write(&t, le, uint32(0))
	}
	// Tags must be sorted; Software(0x131) sorts before 0x8769, so reorder.
	if !withGPS {
		ifd0Entries = []entry{ifd0Entries[0], ifd0Entries[2], ifd0Entries[1]}
	}
	writeIFD(ifd0Entries)
	writeIFD([]entry{{0x9003, 2, 20, long(str)}})
	s := make([]byte, 20)
	copy(s, dto)
	t.Write(s)
	writeIFD([]entry{
		{0x0000, 1, 4, [4]byte{2, 2, 0, 0}},
		{0x0001, 2, 2, [4]byte{'N', 0, 0, 0}},
	})

	payload := append([]byte("Exif\x00\x00"), t.Bytes()...)
	seg := []byte{0xFF, 0xE1, 0, 0}
	binary.BigEndian.PutUint16(seg[2:], uint16(len(payload)+2))
	return append(seg, payload...)
}
