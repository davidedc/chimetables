package app.timestables;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import app.timestables.core.Deck;

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

/**
 * Optional celebration stickers from the APK's assets/stickers/ folder, which the build fills from
 * the git-ignored family-assets/stickers/ (personal photos never go into the repository). The
 * pictures directly in that folder are the first set: collecting all of them unlocks riddles and the
 * extra stickers in stickers/more/. Lines in stickers/phrases.txt map a file to what is shown under
 * it: {@code wow.png: Wow!! Amazing!} or {@code more/cap.png: Cool!}. With no stickers the
 * celebration uses emoji.
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
    private static final String MORE = "more";
    private static final List<String> DEFAULT_PHRASES = Arrays.asList("Superstar!", "You did it!", "Amazing work!");
    private static final Random RANDOM = new Random();

    private Stickers() {}

    /** The first set (unlocks riddles when complete). */
    static List<String> starter(Context context) {
        return list(context, DIR, "");
    }

    /** The extra stickers that join after the unlock, named "more/…". */
    static List<String> more(Context context) {
        return list(context, DIR + "/" + MORE, MORE + "/");
    }

    private static List<String> list(Context context, String dir, String prefix) {
        List<String> names = new ArrayList<>();
        try {
            String[] files = context.getAssets().list(dir);
            if (files != null) {
                for (String f : files) {
                    String lower = f.toLowerCase(java.util.Locale.ROOT);
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".webp")) names.add(prefix + f);
                }
            }
        } catch (IOException ignored) {
            // no stickers bundled
        }
        Collections.sort(names);
        return names;
    }

    /** A sticker for a celebration: a new one when one is allowed today (see {@link Deck#stickerPool}). */
    static Sticker pick(Context context, Deck deck, long today, int everyDays) {
        List<String> pool = deck.stickerPool(starter(context), more(context), today, everyDays);
        if (pool.isEmpty()) return null;
        String name = pool.get(RANDOM.nextInt(pool.size()));
        Bitmap bitmap = load(context, name, 1);
        if (bitmap == null) return null;
        return new Sticker(name, bitmap, phrase(context, name));
    }

    /** Decodes a sticker, {@code sample} times smaller in each direction (1 = full size); null if unreadable. */
    static Bitmap load(Context context, String name, int sample) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        try (InputStream in = context.getAssets().open(DIR + "/" + name)) {
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException e) {
            AppLog.log(context, "sticker " + name + " unreadable: " + e);
            return null;
        }
    }

    static String phrase(Context context, String name) {
        String phrase = phrases(context).get(name);
        return phrase != null ? phrase : DEFAULT_PHRASES.get(RANDOM.nextInt(DEFAULT_PHRASES.size()));
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
