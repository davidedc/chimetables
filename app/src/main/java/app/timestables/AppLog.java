package app.timestables;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Append-only diagnostic log in the app's external files dir, so it can be pulled with adb
 * (`/sdcard/Android/data/app.timestables/files/app.log`) even from a non-debuggable build.
 * Holds wake/alarm/policy events only; no progress data or PIN.
 */
public final class AppLog {
    private static final String TAG = "TimesTables";
    private static final long MAX_BYTES = 512 * 1024;

    private AppLog() {}

    /**
     * Appends one review as a JSON line to reviews.jsonl (same folder as app.log). The full answer
     * history, kept so a scheduler such as FSRS could later be fitted to this child's own data.
     */
    public static synchronized void review(Context context, String jsonLine) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return;
        try (FileWriter w = new FileWriter(new File(dir, "reviews.jsonl"), true)) {
            w.write(jsonLine + "\n");
        } catch (IOException e) {
            Log.w(TAG, "review log write failed", e);
        }
    }

    public static synchronized void log(Context context, String message) {
        Log.i(TAG, message);
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return;
        File file = new File(dir, "app.log");
        if (file.length() > MAX_BYTES) {
            file.renameTo(new File(dir, "app.log.1"));
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.UK).format(new Date());
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(stamp + " " + message + "\n");
        } catch (IOException e) {
            Log.w(TAG, "log write failed", e);
        }
    }
}
