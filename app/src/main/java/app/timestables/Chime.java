package app.timestables;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * Friendly synthesised tones on the alarm stream (so they sound even when media volume is 0 and
 * aren't affected by silent mode). Only happy sounds: no buzzers.
 */
public final class Chime {
    private static final int RATE = 22050;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final double C5 = 523.25, D5 = 587.33, E5 = 659.25, G5 = 783.99, A5 = 880.0, C6 = 1046.5,
            E6 = 1318.51, G6 = 1567.98;

    private Chime() {}

    /** Wake-up call: rising arpeggio, played at the configured alarm volume. */
    public static void wake(Context context) {
        int volume = applyAlarmVolume(context);
        AppLog.log(context, "beep: wake chime, alarm volume " + volume);
        play(new double[] {C5, E5, G5, C6, 0, G5, C6}, 0.16, 1.0f);
    }

    public static void good() {
        play(new double[] {E5, G5}, 0.12, 0.45f);
    }

    public static void easy() {
        play(new double[] {C5, E5, G5, C6}, 0.09, 0.45f);
    }

    /** Again is still encouraging: a soft "try again" two-note lift. */
    public static void again() {
        play(new double[] {C5, D5}, 0.14, 0.3f);
    }

    public static void done() {
        play(new double[] {G5, A5, C6, 0, C6, G5, C6}, 0.12, 0.6f);
    }

    /** Celebration: a longer, brighter fanfare. */
    public static void fanfare() {
        play(new double[] {C5, E5, G5, C6, 0, G5, C6, 0, E6, G6, E6, C6}, 0.11, 0.7f);
    }

    /** A wake-up while she's already holding it (reading her book): the call, softly. */
    public static void nudge() {
        play(new double[] {C5, E5, G5, C6}, 0.12, 0.35f);
    }

    /** A wax seal cracking open: a quick sparkle up high. */
    public static void shimmer() {
        play(new double[] {G5, C6, E6, G6, C6 * 2}, 0.06, 0.45f);
    }

    /** A riddle card turning over. */
    public static void flip() {
        play(new double[] {E6, C6}, 0.07, 0.35f);
    }

    /** Tapping a card whose answer comes tomorrow: a tiny friendly ping, nothing like a buzzer. */
    public static void tick() {
        play(new double[] {G6}, 0.05, 0.25f);
    }

    /** Sets the alarm stream to the parent's chosen percentage; returns the index applied. */
    public static int applyAlarmVolume(Context context) {
        AudioManager am = context.getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        int pct = Store.get(context).settings().volumePercent;
        int index = Math.max(pct > 0 ? 1 : 0, Math.round(max * pct / 100f));
        am.setStreamVolume(AudioManager.STREAM_ALARM, index, 0);
        return am.getStreamVolume(AudioManager.STREAM_ALARM);
    }

    /** Plays a sequence of notes (0 = rest), each {@code noteSeconds} long, with a soft bell envelope. */
    private static void play(double[] notes, double noteSeconds, float gain) {
        int perNote = (int) (RATE * noteSeconds);
        int tail = RATE / 4;
        short[] pcm = new short[perNote * notes.length + tail];
        for (int n = 0; n < notes.length; n++) {
            if (notes[n] <= 0) continue;
            int start = n * perNote;
            int length = Math.min(perNote * 3, pcm.length - start);
            for (int i = 0; i < length; i++) {
                double t = (double) i / RATE;
                double env = Math.min(1.0, i / (RATE * 0.005)) * Math.exp(-t * 7.0);
                double s = Math.sin(2 * Math.PI * notes[n] * t) + 0.25 * Math.sin(4 * Math.PI * notes[n] * t);
                int v = pcm[start + i] + (int) (s * env * 9000);
                pcm[start + i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
            }
        }

        final AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        track.write(pcm, 0, pcm.length);
        track.setVolume(gain);
        track.play();
        long millis = pcm.length * 1000L / RATE + 200;
        MAIN.postDelayed(track::release, millis);
    }
}
