package app.timestables;

import android.content.Context;

import app.timestables.core.Riddle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The riddles, in the order she gets them: assets/riddles.txt, with any of the family's own from the
 * git-ignored family-assets/riddles-family.txt mixed in (one after every four).
 */
final class RiddleBank {
    private static List<Riddle> cached;

    private RiddleBank() {}

    static synchronized List<Riddle> get(Context context) {
        if (cached == null) cached = Riddle.mix(read(context, "riddles.txt"), read(context, "riddles-family.txt"), 4);
        return cached;
    }

    private static List<Riddle> read(Context context, String name) {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return Riddle.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }
}
