package dev.kinframe.mictest;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Detects whether a microphone is physically fitted.
 *
 * Phase 1 records ambient sound. Phase 2 records while the device's own speaker plays a 1 kHz tone.
 * A working mic shows much more 1 kHz energy in phase 2. A missing mic reads flat or as noise in both phases.
 * Results go to logcat (tag "MicTest") and to the screen. Raw recordings are saved as WAV in the app's external files dir.
 */
public class MicTestActivity extends Activity {
    private static final String TAG = "MicTest";
    private static final int RATE = 16000;
    private static final int SECONDS = 3;
    private static final double TONE_HZ = 1000.0;

    private TextView text;
    private final StringBuilder out = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        text = new TextView(this);
        text.setTextSize(22);
        text.setPadding(40, 40, 40, 40);
        setContentView(text);
        report("kinframe mic test: recording...");
        new Thread(this::runTest).start();
    }

    private void runTest() {
        try {
            short[] ambient = record(null);
            Stats a = analyze(ambient);
            report("ambient: " + a);
            save("ambient.wav", ambient);

            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int previousVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            short[] withTone;
            try {
                withTone = record(startTone(am));
            } finally {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0);
            }
            Stats t = analyze(withTone);
            report("with 1 kHz tone: " + t);
            save("tone.wav", withTone);

            String verdict;
            if (a.peak == 0 && t.peak == 0) {
                verdict = "NO MIC: all samples are zero";
            } else if (t.tone > 20 * Math.max(a.tone, 1e-3) && t.tone > 50) {
                verdict = "MIC PRESENT: speaker tone picked up (" + Math.round(t.tone / Math.max(a.tone, 1e-3)) + "x)";
            } else if (a.ac < 4 && t.ac < 4) {
                // No sample-to-sample movement: only DC/offset drift, nothing in the audio band.
                verdict = "NO MIC: input carries no audio, only DC drift";
            } else {
                verdict = "INCONCLUSIVE: signal present but tone not detected (noise floor or disconnected input)";
            }
            report("RESULT " + verdict);
        } catch (Exception e) {
            Log.e(TAG, "test failed", e);
            report("RESULT ERROR " + e);
        }
    }

    private short[] record(AudioTrack playing) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioRecord failed to initialize");
        }
        short[] buf = new short[RATE * SECONDS];
        rec.startRecording();
        int n = 0;
        while (n < buf.length) {
            int r = rec.read(buf, n, buf.length - n);
            if (r < 0) throw new IllegalStateException("AudioRecord.read error " + r);
            n += r;
        }
        rec.stop();
        rec.release();
        if (playing != null) {
            playing.stop();
            playing.release();
        }
        return buf;
    }

    private AudioTrack startTone(AudioManager am) {
        int samples = RATE * (SECONDS + 1);
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            pcm[i] = (short) (Math.sin(2 * Math.PI * TONE_HZ * i / RATE) * 12000);
        }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 2 / 3, 0);
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, samples * 2, AudioTrack.MODE_STATIC);
        track.write(pcm, 0, samples);
        track.play();
        return track;
    }

    static final class Stats {
        double mean, rms, ac, tone;
        int peak, zeros, distinct;

        @Override
        public String toString() {
            return String.format("mean=%.1f rms=%.1f ac=%.1f peak=%d zeros=%d distinct=%d tone1k=%.1f",
                    mean, rms, ac, peak, zeros, distinct, tone);
        }
    }

    private static Stats analyze(short[] s) {
        Stats st = new Stats();
        // Skip the first 0.25 s: input often settles after start.
        int from = RATE / 4;
        double sum = 0;
        boolean[] seen = new boolean[65536];
        for (int i = from; i < s.length; i++) {
            sum += s[i];
            if (s[i] == 0) st.zeros++;
            st.peak = Math.max(st.peak, Math.abs((int) s[i]));
            if (!seen[s[i] & 0xffff]) {
                seen[s[i] & 0xffff] = true;
                st.distinct++;
            }
        }
        int n = s.length - from;
        st.mean = sum / n;
        double sq = 0;
        for (int i = from; i < s.length; i++) {
            double d = s[i] - st.mean;
            sq += d * d;
        }
        st.rms = Math.sqrt(sq / n);
        // RMS of the first difference: a crude high-pass that ignores DC and slow drift.
        double dsq = 0;
        for (int i = from + 1; i < s.length; i++) {
            double d = s[i] - s[i - 1];
            dsq += d * d;
        }
        st.ac = Math.sqrt(dsq / (n - 1));
        // Goertzel magnitude at the tone frequency, normalized per sample.
        double k = 2 * Math.cos(2 * Math.PI * TONE_HZ / RATE), s1 = 0, s2 = 0;
        for (int i = from; i < s.length; i++) {
            double s0 = (s[i] - st.mean) + k * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        st.tone = Math.sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) / n * 2;
        return st;
    }

    private void save(String name, short[] pcm) {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length * 2).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(pcm.length * 2);
        for (short v : pcm) b.putShort(v);
        try (FileOutputStream f = new FileOutputStream(new File(dir, name))) {
            f.write(b.array());
        } catch (IOException e) {
            Log.w(TAG, "could not save " + name, e);
        }
    }

    private void report(String line) {
        Log.i(TAG, line);
        runOnUiThread(() -> {
            out.append(line).append('\n');
            text.setText(out);
        });
    }
}
