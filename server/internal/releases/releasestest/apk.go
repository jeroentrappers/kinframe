// Package releasestest builds fake frame APKs (a zip with a compiled binary
// AndroidManifest.xml) for tests of the release mirror and the update API.
package releasestest

import (
	"archive/zip"
	"bytes"
	"encoding/binary"
	"unicode/utf16"
)

// axmlAttr is one attribute for the test encoder. Exactly one of str/num is used.
type axmlAttr struct {
	name  string
	resID uint32 // 0 = no android resource ID
	str   string
	num   int
	isNum bool
}

type axmlElem struct {
	name  string
	attrs []axmlAttr
}

// buildAXML encodes a minimal binary XML document (UTF-16 string pool, resource map, start elements) the way aapt2
// lays it out: strings with resource IDs come first so the resource map indexes them.
func buildAXML(elems []axmlElem) []byte {
	le := binary.LittleEndian
	var strs []string
	var ids []uint32
	index := map[string]int{}
	add := func(s string) uint32 {
		if i, ok := index[s]; ok {
			return uint32(i)
		}
		index[s] = len(strs)
		strs = append(strs, s)
		return uint32(len(strs) - 1)
	}
	for _, e := range elems {
		for _, a := range e.attrs {
			if a.resID != 0 {
				if _, ok := index[a.name]; !ok {
					add(a.name)
					ids = append(ids, a.resID)
				}
			}
		}
	}
	var body bytes.Buffer
	for _, e := range elems {
		var attrs bytes.Buffer
		for _, a := range e.attrs {
			n := add(a.name)
			raw := uint32(0xffffffff)
			typ := byte(0x10)
			data := uint32(a.num)
			if !a.isNum {
				raw = add(a.str)
				typ = 0x03
				data = raw
			}
			for _, v := range []uint32{0xffffffff, n, raw} {
				_ = binary.Write(&attrs, le, v)
			}
			_ = binary.Write(&attrs, le, uint16(8))
			attrs.WriteByte(0)
			attrs.WriteByte(typ)
			_ = binary.Write(&attrs, le, data)
		}
		name := add(e.name)
		ext := new(bytes.Buffer)
		for _, v := range []uint32{0xffffffff, name} {
			_ = binary.Write(ext, le, v)
		}
		for _, v := range []uint16{20, 20, uint16(len(e.attrs)), 0, 0, 0} {
			_ = binary.Write(ext, le, v)
		}
		size := 16 + ext.Len() + attrs.Len()
		for _, v := range []any{uint16(0x0102), uint16(16), uint32(size), uint32(1), uint32(0xffffffff)} {
			_ = binary.Write(&body, le, v)
		}
		body.Write(ext.Bytes())
		body.Write(attrs.Bytes())
	}
	// String pool (UTF-16).
	var data bytes.Buffer
	offsets := make([]uint32, len(strs))
	for i, s := range strs {
		offsets[i] = uint32(data.Len())
		u := utf16.Encode([]rune(s))
		_ = binary.Write(&data, le, uint16(len(u)))
		for _, c := range u {
			_ = binary.Write(&data, le, c)
		}
		_ = binary.Write(&data, le, uint16(0))
	}
	for data.Len()%4 != 0 {
		data.WriteByte(0)
	}
	var pool bytes.Buffer
	start := 28 + 4*len(strs)
	for _, v := range []any{uint16(0x0001), uint16(28), uint32(start + data.Len()), uint32(len(strs)), uint32(0), uint32(0), uint32(start), uint32(0)} {
		_ = binary.Write(&pool, le, v)
	}
	for _, o := range offsets {
		_ = binary.Write(&pool, le, o)
	}
	pool.Write(data.Bytes())
	var rm bytes.Buffer
	for _, v := range []any{uint16(0x0180), uint16(8), uint32(8 + 4*len(ids))} {
		_ = binary.Write(&rm, le, v)
	}
	for _, id := range ids {
		_ = binary.Write(&rm, le, id)
	}
	var out bytes.Buffer
	total := 8 + pool.Len() + rm.Len() + body.Len()
	for _, v := range []any{uint16(0x0003), uint16(8), uint32(total)} {
		_ = binary.Write(&out, le, v)
	}
	out.Write(pool.Bytes())
	out.Write(rm.Bytes())
	out.Write(body.Bytes())
	return out.Bytes()
}

// Manifest encodes a binary AndroidManifest.xml with these values.
func Manifest(pkg, name string, code, minSDK int) []byte {
	return buildAXML([]axmlElem{
		{name: "manifest", attrs: []axmlAttr{
			{name: "versionCode", resID: 0x0101021b, num: code, isNum: true},
			{name: "versionName", resID: 0x0101021c, str: name},
			{name: "package", str: pkg},
		}},
		{name: "uses-sdk", attrs: []axmlAttr{
			{name: "minSdkVersion", resID: 0x0101020c, num: minSDK, isNum: true},
			{name: "targetSdkVersion", resID: 0x01010270, num: 36, isNum: true},
		}},
		{name: "application"},
	})
}

// APK returns a zip with a binary manifest plus filler, standing in for an APK.
func APK(pkg, name string, code, minSDK int) []byte {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	w, _ := zw.Create("AndroidManifest.xml")
	_, _ = w.Write(Manifest(pkg, name, code, minSDK))
	w, _ = zw.Create("classes.dex")
	_, _ = w.Write(bytes.Repeat([]byte(name), 1000))
	_ = zw.Close()
	return buf.Bytes()
}
