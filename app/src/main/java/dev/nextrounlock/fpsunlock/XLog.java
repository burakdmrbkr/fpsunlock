package dev.nextrounlock.fpsunlock;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * Single logging choke point.
 *
 * <p>Two destinations, deliberately:
 * <ul>
 *   <li>{@code XposedBridge.log} — lands in the LSPosed log, survives force-stop, and
 *       is what {@code scripts/verify.ps1} greps. Lifecycle events only.</li>
 *   <li>{@code Log.d} under {@link #TAG} — logcat. Per-hook detail that would otherwise
 *       flood the LSPosed log.</li>
 * </ul>
 *
 * <p>Rate limiting exists because probe mode logs every property read and the game
 * reads thousands per frame during load. Unbounded logging there is a measurable
 * frame-time regression — self-defeating at 120 fps.
 */
public final class XLog {

    public static final String TAG = "fpsunlock";

    /** Above this many lines for one key, go quiet until the next process. */
    private static final int HOT_KEY_LIMIT = 40;

    private static final java.util.HashMap<String, int[]> COUNTERS = new java.util.HashMap<>();

    private XLog() {
    }

    /** Lifecycle events. Always emitted, to both destinations. */
    public static void i(String msg) {
        Log.i(TAG, msg);
        try {
            XposedBridge.log("[" + TAG + "] " + msg);
        } catch (Throwable ignored) {
            // XposedBridge is absent when this class is exercised off-device.
        }
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
        try {
            XposedBridge.log("[" + TAG + "] WARN " + msg);
        } catch (Throwable ignored) {
        }
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        try {
            XposedBridge.log("[" + TAG + "] ERROR " + msg + " :: " + describe(t));
        } catch (Throwable ignored) {
        }
    }

    /**
     * High-volume detail line. Reaches logcat only, and only until the per-key budget
     * is exhausted. Pass a stable {@code key} so one hot call site cannot drown out
     * a cold one.
     */
    public static void d(String key, String msg) {
        if (allow(key)) {
            Log.d(TAG, msg);
        }
    }

    private static boolean allow(String key) {
        synchronized (COUNTERS) {
            int[] box = COUNTERS.get(key);
            if (box == null) {
                COUNTERS.put(key, new int[]{1});
                return true;
            }
            if (box[0] >= HOT_KEY_LIMIT) {
                return false;
            }
            box[0]++;
            return true;
        }
    }

    /** Compact throwable rendering — no stack traces in the Xposed log. */
    public static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
