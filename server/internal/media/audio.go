package media

// Voice messages (README §5.19): one master per recording, AAC-LC mono 48 kHz 64 kbit/s in MP4 (audio/mp4),
// copied when the upload already is AAC mono at most 96 kbit/s in MP4, else transcoded; plus 64 loudness values
// for the waveform.

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"os/exec"
	"strconv"
	"strings"
)

// Audio is the media kind of voice recordings (never placed on frames).
const Audio Kind = "audio"

// Voice message limits.
const (
	MinVoiceMS       = 1000
	MaxVoiceBytes    = 20 << 20
	WaveformPoints   = 64
	voiceBitrate     = 64000
	maxCopyBitrate   = 96000
	voiceSampleRate  = 48000
	waveformRate     = 8000
	voiceMasterMime  = "audio/mp4"
	maxProbeOverhead = 2 // seconds past the limit a transcode keeps, to tell "too long" apart
)

// ErrAudioTooLong is a recording over the length limit.
var ErrAudioTooLong = errors.New("audio too long")

// SniffAudio accepts the containers voice recorders produce: MP4/M4A (AAC), WebM/Matroska and Ogg (Opus).
func SniffAudio(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	head := make([]byte, 64)
	n, _ := io.ReadFull(f, head)
	b := head[:n]
	switch {
	case bytes.HasPrefix(b, []byte("OggS")):
		return "audio/ogg", nil
	case bytes.HasPrefix(b, []byte{0x1A, 0x45, 0xDF, 0xA3}):
		return "audio/webm", nil
	case len(b) >= 12 && bytes.Equal(b[4:8], []byte("ftyp")):
		return "audio/mp4", nil
	}
	return "", ErrUnsupported
}

// AudioInfo is what ffprobe (and, when the container doesn't say, a decode) tells about a recording.
type AudioInfo struct {
	DurationMS   int
	Codec        string
	Profile      string
	Channels     int
	SampleRate   int
	Bitrate      int
	VideoStreams int
	MP4          bool // ISO-BMFF container
}

type audioProbeJSON struct {
	Streams []struct {
		CodecType   string         `json:"codec_type"`
		CodecName   string         `json:"codec_name"`
		Profile     string         `json:"profile"`
		Channels    int            `json:"channels"`
		SampleRate  string         `json:"sample_rate"`
		BitRate     string         `json:"bit_rate"`
		Duration    string         `json:"duration"`
		Disposition map[string]int `json:"disposition"`
	} `json:"streams"`
	Format struct {
		FormatName string `json:"format_name"`
		Duration   string `json:"duration"`
	} `json:"format"`
}

// ProbeAudio reads a recording's audio stream. A file without an audio stream is ErrUnsupported; one with a
// real video stream too (not cover art) as well. Containers without a duration (MediaRecorder WebM) are
// measured by decoding.
func (t Tools) ProbeAudio(ctx context.Context, path string) (AudioInfo, error) {
	out, err := run(ctx, t.bin(t.FFprobe, "ffprobe"), "-v", "error", "-print_format", "json", "-show_format", "-show_streams", path)
	if err != nil {
		return AudioInfo{}, err
	}
	info, err := parseAudioProbe(out)
	if err != nil {
		return AudioInfo{}, err
	}
	if info.DurationMS <= 0 {
		if info.DurationMS, err = t.decodeDuration(ctx, path); err != nil {
			return AudioInfo{}, err
		}
	}
	return info, nil
}

func parseAudioProbe(b []byte) (AudioInfo, error) {
	var p audioProbeJSON
	if err := json.Unmarshal(b, &p); err != nil {
		return AudioInfo{}, fmt.Errorf("ffprobe output: %w", err)
	}
	var info AudioInfo
	found := false
	for _, s := range p.Streams {
		switch s.CodecType {
		case "audio":
			if found {
				continue
			}
			found = true
			info.Codec, info.Profile, info.Channels = s.CodecName, s.Profile, s.Channels
			info.SampleRate, _ = strconv.Atoi(s.SampleRate)
			info.Bitrate, _ = strconv.Atoi(s.BitRate)
			if d, err := strconv.ParseFloat(s.Duration, 64); err == nil && d > 0 {
				info.DurationMS = int(d * 1000)
			}
		case "video":
			if s.Disposition["attached_pic"] != 1 {
				info.VideoStreams++
			}
		}
	}
	if !found || info.VideoStreams > 0 {
		return AudioInfo{}, ErrUnsupported
	}
	info.MP4 = strings.Contains(p.Format.FormatName, "mp4") || strings.Contains(p.Format.FormatName, "m4a")
	if d, err := strconv.ParseFloat(p.Format.Duration, 64); err == nil && d > 0 && info.DurationMS <= 0 {
		info.DurationMS = int(d * 1000)
	}
	return info, nil
}

// decodeDuration decodes the audio to nothing and reads how far it got.
func (t Tools) decodeDuration(ctx context.Context, path string) (int, error) {
	out, err := run(ctx, t.bin(t.FFmpeg, "ffmpeg"), "-nostdin", "-v", "error", "-i", path, "-map", "0:a:0",
		"-f", "null", "-progress", "pipe:1", "-")
	if err != nil {
		return 0, err
	}
	ms := 0
	sc := bufio.NewScanner(bytes.NewReader(out))
	for sc.Scan() {
		k, v, ok := strings.Cut(sc.Text(), "=")
		if ok && (k == "out_time_us" || k == "out_time_ms") { // out_time_ms is microseconds too (ffmpeg quirk)
			if us, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64); err == nil && us > 0 {
				ms = int(us / 1000)
			}
		}
	}
	if ms <= 0 {
		return 0, fmt.Errorf("no audio decoded: %w", ErrCorrupt)
	}
	return ms, nil
}

// CopyableVoice reports whether a recording can be the master as it is (stream copy into MP4).
func CopyableVoice(info AudioInfo) bool {
	return info.Codec == "aac" && (info.Profile == "" || info.Profile == "LC") && info.Channels == 1 &&
		info.Bitrate > 0 && info.Bitrate <= maxCopyBitrate
}

// VoiceMaster writes the master of a recording to dst (.m4a) and returns what the master measures. maxMS is
// the length limit: longer recordings are ErrAudioTooLong (the minimum length is checked by the caller).
func (t Tools) VoiceMaster(ctx context.Context, src, dst string, maxMS int) (AudioInfo, error) {
	info, err := t.ProbeAudio(ctx, src)
	if err != nil {
		return AudioInfo{}, err
	}
	if maxMS > 0 && info.DurationMS > maxMS {
		return AudioInfo{}, ErrAudioTooLong
	}
	args := []string{"-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", src, "-map", "0:a:0", "-vn", "-sn", "-dn",
		"-map_metadata", "-1", "-map_chapters", "-1"}
	if maxMS > 0 {
		// Belt and braces when the container lied about its length.
		args = append(args, "-t", strconv.Itoa(maxMS/1000+maxProbeOverhead))
	}
	if CopyableVoice(info) {
		args = append(args, "-c:a", "copy")
	} else {
		args = append(args, "-c:a", "aac", "-profile:a", "aac_low", "-ac", "1", "-ar", strconv.Itoa(voiceSampleRate),
			"-b:a", strconv.Itoa(voiceBitrate))
	}
	args = append(args, "-movflags", "+faststart", "-f", "mp4", dst)
	if _, err := run(ctx, t.bin(t.FFmpeg, "ffmpeg"), args...); err != nil {
		return AudioInfo{}, err
	}
	out, err := t.ProbeAudio(ctx, dst)
	if err != nil {
		return AudioInfo{}, err
	}
	if maxMS > 0 && out.DurationMS > maxMS {
		return AudioInfo{}, ErrAudioTooLong
	}
	return out, nil
}

// VoiceMasterMime is the content type of voice masters.
func VoiceMasterMime() string { return voiceMasterMime }

// Waveform returns WaveformPoints loudness values 0–100 over the recording (RMS per slice, scaled so the
// loudest slice is 100; silence stays 0).
func (t Tools) Waveform(ctx context.Context, path string) ([]int, error) {
	cmd := exec.CommandContext(ctx, t.bin(t.FFmpeg, "ffmpeg"), "-nostdin", "-v", "error", "-i", path, "-map", "0:a:0",
		"-ac", "1", "-ar", strconv.Itoa(waveformRate), "-f", "s16le", "-acodec", "pcm_s16le", "-")
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, &toolError{tool: "ffmpeg", err: err, stderr: stderr.String()}
	}
	return WaveformOf(stdout.Bytes()), nil
}

// WaveformOf computes the waveform of signed 16-bit little-endian mono samples.
func WaveformOf(pcm []byte) []int {
	n := len(pcm) / 2
	out := make([]int, WaveformPoints)
	if n == 0 {
		return out
	}
	rms := make([]float64, WaveformPoints)
	peak := 0.0
	for i := 0; i < WaveformPoints; i++ {
		from, to := i*n/WaveformPoints, (i+1)*n/WaveformPoints
		if to <= from {
			to = min(from+1, n)
		}
		sum := 0.0
		for j := from; j < to; j++ {
			v := float64(int16(binary.LittleEndian.Uint16(pcm[2*j:]))) / 32768
			sum += v * v
		}
		rms[i] = math.Sqrt(sum / float64(to-from))
		peak = math.Max(peak, rms[i])
	}
	if peak < 1e-4 {
		return out
	}
	for i, v := range rms {
		out[i] = int(math.Round(100 * v / peak))
	}
	return out
}
