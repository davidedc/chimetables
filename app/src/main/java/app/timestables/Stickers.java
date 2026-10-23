package app.timestables;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Optional celebration stickers from the APK's assets/stickers/ folder, which the build fills from
 * the git-ignored family-assets/stickers/ (personal photos never go into the repository). Lines in
 * stickers/phrases.txt map a file to what is shown under it: {@code salish_wow.png: Wow!! Amazing!}.
 * With no stickers the celebration uses emoji.
 */
final class Stickers {
    static final class Sticker {
        final String name;
        final Bitmap bitmap;
        final String phrase;

        Sticker(String name, Bitmap bitmap, String phrase) {
            this.name = name;
            this.bitmap = bitmap;
            this.phrase = phrase;
        }
    }

    private static final String DIR = "stickers";
    private static final List<String> DEFAULT_PHRASES = Arrays.asList("Superstar!", "You did it!", "Amazing work!");
    private static final Random RANDOM = new Random();

    private Stickers() {}

    static List<String> all(Context context) {
        List<String> names = new ArrayList<>();
        try {
            String[] files = context.getAssets().list(DIR);
            if (files != null) {
                for (String f : files) {
                    String lower = f.toLowerCase(java.util.Locale.ROOT);
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".webp")) names.add(f);
                }
            }
        } catch (IOException ignored) {
            // no stickers bundled
        }
        Collections.sort(names);
        return names;
    }

    /** A sticker she hasn't collected yet if possible, else any; null if none are bundled. */
    static Sticker pick(Context context, Set<String> collected) {
        List<String> names = all(context);
        if (names.isEmpty()) return null;
        List<String> fresh = new ArrayList<>(names);
        fresh.removeAll(collected);
        List<String> pool = fresh.isEmpty() ? names : fresh;
        String name = pool.get(RANDOM.nextInt(pool.size()));
        try (InputStream in = context.getAssets().open(DIR + "/" + name)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null) return null;
            String phrase = phrases(context).get(name);
            if (phrase == null) phrase = DEFAULT_PHRASES.get(RANDOM.nextInt(DEFAULT_PHRASES.size()));
            return new Sticker(name, bitmap, phrase);
        } catch (IOException e) {
            AppLog.log(context, "sticker " + name + " unreadable: " + e);
            return null;
        }
    }

    private static Map<String, String> phrases(Context context) {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                context.getAssets().open(DIR + "/phrases.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                int colon = line.indexOf(':');
                if (line.isEmpty() || line.startsWith("#") || colon < 1) continue;
                map.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        } catch (IOException ignored) {
            // no phrases file: defaults are used
        }
        return map;
    }
}
