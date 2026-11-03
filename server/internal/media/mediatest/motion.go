package mediatest

import (
	"encoding/binary"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

// MotionStyle selects how a synthetic Motion Photo describes its video.
type MotionStyle int

const (
	// MotionPhotoV1 uses GCamera:MotionPhoto with a Container:Directory.
	MotionPhotoV1 MotionStyle = iota
	// MicroVideo uses the legacy GCamera:MicroVideoOffset.
	MicroVideo
)

// MotionPhoto builds an Android Motion Photo: the JPEG with an XMP APP1
// segment describing the trailing video, followed by the MP4 bytes.
func MotionPhoto(jpg, mp4 []byte, style MotionStyle) []byte {
	var desc string
	switch style {
	case MicroVideo:
		desc = fmt.Sprintf(`<rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="%d"
    GCamera:MicroVideoPresentationTimestampUs="1500000"/>`, len(mp4))
	default:
		desc = fmt.Sprintf(`<rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
    GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="1500000">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
     <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="%d" Item:Padding="0"/></rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>`, len(mp4))
	}
	xmp := `<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">` +
		desc + `</rdf:RDF></x:xmpmeta><?xpacket end="w"?>`
	payload := append([]byte("http://ns.adobe.com/xap/1.0/\x00"), xmp...)
	seg := []byte{0xFF, 0xE1, 0, 0}
	binary.BigEndian.PutUint16(seg[2:], uint16(len(payload)+2))
	out := append([]byte{}, jpg[:2]...)
	out = append(out, seg...)
	out = append(out, payload...)
	out = append(out, jpg[2:]...)
	return append(out, mp4...)
}

// Clip encodes a test clip with ffmpeg. extra are output options (codec,
// profile, metadata...); the file name's extension picks the container.
func Clip(ffmpeg, dir, name string, w, h, fps int, seconds float64, audio bool, extra ...string) ([]byte, error) {
	out := filepath.Join(dir, name)
	args := []string{"-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", fmt.Sprintf("testsrc2=size=%dx%d:rate=%d:duration=%g", w, h, fps, seconds)}
	if audio {
		args = append(args, "-f", "lavfi", "-i", fmt.Sprintf("sine=frequency=440:duration=%g", seconds), "-shortest", "-c:a", "aac", "-b:a", "96k")
	}
	args = append(args, "-pix_fmt", "yuv420p")
	args = append(args, extra...)
	args = append(args, out)
	if b, err := exec.Command(ffmpeg, args...).CombinedOutput(); err != nil {
		return nil, fmt.Errorf("%v: %s", err, b)
	}
	return os.ReadFile(out)
}

// PreparedH264 are ffmpeg options for a clip that conforms to video-master-v1
// and to the frame rendition profile at its size (H.264 High@4.0, ~1.5 Mbps).
var PreparedH264 = []string{"-c:v", "libx264", "-preset", "ultrafast", "-profile:v", "high", "-level:v", "4.0",
	"-b:v", "1500k", "-maxrate", "1800k", "-bufsize", "3000k", "-movflags", "+faststart"}
