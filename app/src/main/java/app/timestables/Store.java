package app.timestables;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import app.timestables.core.Card;
import app.timestables.core.Deck;
import app.timestables.core.Json;
import app.timestables.core.Settings;
import app.timestables.core.Srs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;

/**
 * App-private persistence: progress and settings as JSON files (AtomicFile, so a power cut mid-write
 * keeps the previous copy), small flags in SharedPreferences. Survives reboots and app updates.
 */
public final class Store {
    private static final String PROGRESS = "progress.json";
    private static final String SETTINGS = "settings.json";
    private static final String KEY_PIN = "pin_hash";
    private static final String KEY_MAINTENANCE_UNTIL = "maintenance_until";
    private static final String KEY_NEXT_WAKE = "next_wake";
    private static final String KEY_PIN_FAILS = "pin_fails";
    private static final String KEY_PIN_LOCKED_UNTIL = "pin_locked_until";

    private static Store instance;

    private final Context app;
    private final SharedPreferences prefs;
    private Deck deck;
    private Settings settings;

    public static synchronized Store get(Context context) {
        if (instance == null) instance = new Store(context.getApplicationContext());
        return instance;
    }

    private Store(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("state", Context.MODE_PRIVATE);
    }

    public synchronized Deck deck() {
        if (deck == null) {
            String text = read(PROGRESS);
            try {
                deck = text == null ? new Deck() : Json.deckFromJson(text);
            } catch (Exception e) {
                quarantine(PROGRESS, e);
                deck = new Deck();
            }
        }
        return deck;
    }

    public synchronized Settings settings() {
        if (settings == null) {
            String text = read(SETTINGS);
            try {
                settings = text == null ? new Settings() : Json.settingsFromJson(text);
            } catch (Exception e) {
                quarantine(SETTINGS, e);
                settings = new Settings();
            }
        }
        return settings;
    }

    public synchronized List<Card> activeCards() {
        Settings s = settings();
        return deck().active(s.tables, s.maxFactor);
    }

    public Srs srs() {
        return new Srs(ZoneId.systemDefault());
    }

    public synchronized void saveDeck() {
        write(PROGRESS, Json.deckToJson(deck()));
    }

    public synchronized void saveSettings() {
        write(SETTINGS, Json.settingsToJson(settings()));
    }

    public synchronized void resetProgress() {
        deck().reset();
        saveDeck();
        AppLog.log(app, "progress reset by parent");
    }

    public String pinHash() {
        return prefs.getString(KEY_PIN, null);
    }

    public void setPinHash(String hash) {
        prefs.edit().putString(KEY_PIN, hash).putInt(KEY_PIN_FAILS, 0).commit();
    }

    /** Wrong-PIN throttling: after 3 misses, lock out for a minute (doubling each further miss). */
    public long pinLockedUntil() {
        return prefs.getLong(KEY_PIN_LOCKED_UNTIL, 0);
    }

    public void recordPinResult(boolean ok, long now) {
        if (ok) {
            prefs.edit().putInt(KEY_PIN_FAILS, 0).putLong(KEY_PIN_LOCKED_UNTIL, 0).apply();
            return;
        }
        int fails = prefs.getInt(KEY_PIN_FAILS, 0) + 1;
        long lock = fails >= 3 ? now + 60_000L * (1L << Math.min(fails - 3, 6)) : 0;
        prefs.edit().putInt(KEY_PIN_FAILS, fails).putLong(KEY_PIN_LOCKED_UNTIL, lock).apply();
    }

    public long maintenanceUntil() {
        return prefs.getLong(KEY_MAINTENANCE_UNTIL, 0);
    }

    public void setMaintenanceUntil(long until) {
        prefs.edit().putLong(KEY_MAINTENANCE_UNTIL, until).commit();
    }

    public boolean inMaintenance(long now) {
        return maintenanceUntil() > now;
    }

    public long nextWake() {
        return prefs.getLong(KEY_NEXT_WAKE, 0);
    }

    public void setNextWake(long at) {
        prefs.edit().putLong(KEY_NEXT_WAKE, at).apply();
    }

    private AtomicFile file(String name) {
        return new AtomicFile(new File(app.getFilesDir(), name));
    }

    private String read(String name) {
        AtomicFile f = file(name);
        if (!f.getBaseFile().exists()) return null;
        try {
            return new String(f.readFully(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AppLog.log(app, "read " + name + " failed: " + e);
            return null;
        }
    }

    private void write(String name, String text) {
        AtomicFile f = file(name);
        FileOutputStream out = null;
        try {
            out = f.startWrite();
            out.write(text.getBytes(StandardCharsets.UTF_8));
            f.finishWrite(out);
        } catch (IOException e) {
            if (out != null) f.failWrite(out);
            AppLog.log(app, "write " + name + " failed: " + e);
        }
    }

    /** Keep an unreadable file for inspection instead of silently overwriting it. */
    private void quarantine(String name, Exception e) {
        File base = file(name).getBaseFile();
        File copy = new File(base.getPath() + ".corrupt-" + System.currentTimeMillis());
        boolean moved = base.renameTo(copy);
        AppLog.log(app, "could not parse " + name + " (" + e + "); moved=" + moved + " to " + copy.getName());
    }
}
