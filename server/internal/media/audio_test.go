package media

import (
	"context"
	"encoding/binary"
	"errors"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestWaveformOf(t *testing.T) {
	if wf := WaveformOf(nil); len(wf) != WaveformPoints || wf[0] != 0 {
		t.Fatalf("empty: %v", wf)
	}
	// A quiet first half and a loud second half.
	n := 6400
	pcm := make([]byte, 2*n)
	for i := 0; i < n; i++ {
		amp := 1000.0
		if i >= n/2 {
			amp = 20000
		}
		binary.LittleEndian.PutUint16(pcm[2*i:], uint16(int16(amp*math.Sin(float64(i)/5))))
	}
	wf := WaveformOf(pcm)
	if len(wf) != WaveformPoints {
		t.Fatalf("%d points", len(wf))
	}
	for i, v := range wf {
		if v < 0 || v > 100 {
			t.Fatalf("point %d out of range: %d", i, v)
		}
	}
	if wf[10] > 10 || wf[50] < 95 {
		t.Fatalf("loudness not followed: %v", wf)
	}
	// Silence stays flat zero.
	if wf := WaveformOf(make([]byte, 2000)); wf[5] != 0 {
		t.Fatalf("silence: %v", wf)
	}
}

func TestCopyableVoice(t *testing.T) {
	for _, tt := range []struct {
		info AudioInfo
		want bool
	}{
		{AudioInfo{Codec: "aac", Profile: "LC", Channels: 1, Bitrate: 64000}, true},
		{AudioInfo{Codec: "aac", Profile: "LC", Channels: 1, Bitrate: 96000}, true},
		{AudioInfo{Codec: "aac", Profile: "LC", Channels: 1, Bitrate: 128000}, false},
		{AudioInfo{Codec: "aac", Profile: "LC", Channels: 2, Bitrate: 64000}, false},
		{AudioInfo{Codec: "aac", Profile: "HE-AAC", Channels: 1, Bitrate: 32000}, false},
		{AudioInfo{Codec: "opus", Channels: 1, Bitrate: 32000}, false},
		{AudioInfo{Codec: "aac", Profile: "LC", Channels: 1}, false}, // unknown bitrate
	} {
		if got := CopyableVoice(tt.info); got != tt.want {
			t.Errorf("%+v: %v", tt.info, got)
		}
	}
}

func TestParseAudioProbe(t *testing.T) {
	info, err := parseAudioProbe([]byte(`{"streams":[{"codec_type":"video","codec_name":"mjpeg","disposition":{"attached_pic":1}},
		{"codec_type":"audio","codec_name":"aac","profile":"LC","channels":1,"sample_rate":"48000","bit_rate":"64000","duration":"3.5"}],
		"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"3.5"}}`))
	if err != nil || info.DurationMS != 3500 || info.Codec != "aac" || info.SampleRate != 48000 || !info.MP4 {
		t.Fatalf("m4a with cover art: %+v %v", info, err)
	}
	if _, err := parseAudioProbe([]byte(`{"streams":[{"codec_type":"video","codec_name":"h264","width":640}],"format":{}}`)); !errors.Is(err, ErrUnsupported) {
		t.Fatalf("video only: %v", err)
	}
	if _, err := parseAudioProbe([]byte(`{"streams":[{"codec_type":"video","codec_name":"h264","width":640},{"codec_type":"audio","codec_name":"aac"}],"format":{}}`)); !errors.Is(err, ErrUnsupported) {
		t.Fatalf("a video with sound: %v", err)
	}
}

func voiceFixture(t *testing.T, tools Tools, seconds string, args ...string) string {
	t.Helper()
	out := filepath.Join(t.TempDir(), "in."+args[len(args)-1])
	cmd := append([]string{"-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds}, args[:len(args)-1]...)
	cmd = append(cmd, out)
	if b, err := exec.Command(tools.bin(tools.FFmpeg, "ffmpeg"), cmd...).CombinedOutput(); err != nil {
		t.Skipf("cannot create audio fixture: %v %s", err, b)
	}
	return out
}

func TestVoiceMaster(t *testing.T) {
	tools := toolsOrSkip(t)
	ctx := context.Background()
	for _, tt := range []struct {
		name string
		args []string
		mime string
	}{
		{"opus webm", []string{"-c:a", "libopus", "-b:a", "24k", "webm"}, "audio/webm"},
		{"opus ogg", []string{"-c:a", "libopus", "-b:a", "24k", "ogg"}, "audio/ogg"},
		{"aac mono (copied)", []string{"-c:a", "aac", "-ac", "1", "-b:a", "64k", "m4a"}, "audio/mp4"},
		{"aac stereo", []string{"-c:a", "aac", "-ac", "2", "-b:a", "192k", "m4a"}, "audio/mp4"},
	} {
		t.Run(tt.name, func(t *testing.T) {
			src := voiceFixture(t, tools, "2", tt.args...)
			if mime, err := SniffAudio(src); err != nil || mime != tt.mime {
				t.Fatalf("sniff: %q %v", mime, err)
			}
			dst := filepath.Join(t.TempDir(), "master.m4a")
			info, err := tools.VoiceMaster(ctx, src, dst, 300_000)
			if err != nil {
				t.Fatal(err)
			}
			if info.Codec != "aac" || info.Channels != 1 || info.DurationMS < 1900 || info.DurationMS > 2200 || !info.MP4 {
				t.Fatalf("master: %+v", info)
			}
			if tt.name != "aac mono (copied)" && (info.SampleRate != 48000 || info.Bitrate > 80000) {
				t.Fatalf("transcoded master: %+v", info)
			}
			wf, err := tools.Waveform(ctx, dst)
			if err != nil || len(wf) != WaveformPoints || wf[32] < 80 {
				t.Fatalf("waveform: %v %v", wf, err)
			}
		})
	}
	long := voiceFixture(t, tools, "5", "-c:a", "libopus", "-b:a", "16k", "webm")
	if _, err := tools.VoiceMaster(ctx, long, filepath.Join(t.TempDir(), "m.m4a"), 3000); !errors.Is(err, ErrAudioTooLong) {
		t.Fatalf("too long: %v", err)
	}
	junk := filepath.Join(t.TempDir(), "junk")
	_ = os.WriteFile(junk, []byte("OggS but not really"), 0o600)
	if _, err := tools.ProbeAudio(ctx, junk); err == nil {
		t.Fatal("junk probed fine")
	}
}
