package dev.nextrounlock.fpsunlock;

import android.content.Context;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Surfaces the game's own FPS decisions in logcat.
 *
 * <p>Why this exists: the game logs its graphics reasoning through UE4's Lua
 * {@code printf}, which goes to
 * {@code .../Saved/Logs/ShadowTrackerExtra.log} — <b>not</b> to logcat. Shipping
 * builds additionally gate most of it behind {@code bWriteLog}. That file is
 * {@code 0600} and owned by the game's UID, so an external {@code adb pull} is
 * denied even with a shell UID.
 *
 * <p>We are already inside the game's process, under its UID, so we can tail the
 * file and re-emit the interesting lines as {@code fpsunlock} logcat entries. This
 * turns "trust me, it unlocked" into an observable number, and it is the only
 * reliable way to see {@code maxSupportFPS} / {@code ExpandFPS} without a debug build.
 *
 * <p>Read-only: opens the file for reading, never writes to it, never truncates it.
 */
public final class FpsProbe {

    private static final String LOG_RELATIVE =
            "/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/Logs/ShadowTrackerExtra.log";

    /** Lines newer than this many bytes from EOF are considered "recent". */
    private static final int TAIL_BYTES = 256 * 1024;

    /**
     * The game obfuscates its UE4 log with a constant XOR. Verified by decoding a
     * known prefix: {@code "J#!#'?  ? \""} XOR 0x11 = {@code "[2026.11.13-03"},
     * and {@code "L]~vR}xt e_te"} XOR 0x11 = {@code "]LogClientNet"}.
     *
     * <p>Without this the file is unreadable and every line looks like binary noise,
     * which is exactly what made the first probe attempt produce nothing.
     */
    private static final byte LOG_XOR_KEY = 0x11;

    private static boolean started;

    private FpsProbe() {
    }

    /**
     * Starts the tailer. Safe to call before the process has an Application:
     * {@code handleLoadPackage} runs before {@code Application.onCreate}, so the
     * context is resolved on a background thread that retries instead of bailing out.
     */
    public static void start() {
        if (started) {
            return;
        }
        started = true;

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = waitForContext();
                    if (ctx == null) {
                        XLog.w("fpsprobe: no Application after 30s, log tailing unavailable");
                        return;
                    }
                    tail(ctx);
                } catch (Throwable e) {
                    XLog.w("fpsprobe: stopped :: " + XLog.describe(e));
                }
            }
        }, "fpsunlock-fpsprobe");
        t.setDaemon(true);
        t.start();
    }

    /** Polls for the host Application. Returns null after ~30 s. */
    private static Context waitForContext() throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            Context ctx = currentApplication();
            if (ctx != null) {
                XLog.d("fpsprobe", "fpsprobe: application resolved after " + (i * 200) + "ms");
                return ctx;
            }
            Thread.sleep(200);
        }
        return null;
    }

    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return (Context) at.getMethod("currentApplication").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Locates the UE4 log. Tries the external-files dir first, then the app's own
     * data dir (the game may be on internal storage), and reports what it finds.
     */
    private static File resolveLog(Context ctx) {
        File external = ctx.getExternalFilesDir(null);
        if (external == null) {
            XLog.w("fpsprobe: no external files dir");
            return null;
        }
        File pkgDir = external.getParentFile();
        if (pkgDir == null) {
            XLog.w("fpsprobe: cannot resolve package dir");
            return null;
        }

        File candidate = new File(pkgDir.getAbsolutePath() + LOG_RELATIVE);
        if (candidate.canRead()) {
            return candidate;
        }

        XLog.w("fpsprobe: not readable: " + candidate.getAbsolutePath()
                + " (exists=" + candidate.exists() + ")");

        // Fall back: search the Saved/Logs directory for a .log we can read.
        File logsDir = candidate.getParentFile();
        if (logsDir != null && logsDir.isDirectory()) {
            File[] files = logsDir.listFiles();
            if (files != null) {
                File newest = null;
                for (File f : files) {
                    if (f.isFile() && f.canRead() && f.getName().endsWith(".log")) {
                        if (newest == null || f.lastModified() > newest.lastModified()) {
                            newest = f;
                        }
                    }
                }
                if (newest != null) {
                    XLog.i("fpsprobe: falling back to " + newest.getName());
                    return newest;
                }
            }
        }
        return null;
    }

    /**
     * Emits a handful of raw lines so the on-disk format is visible without a
     * root shell. Cheap, one-shot, and the only way to tune {@link #emit}.
     */
    private static void dumpSample(File log) {
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - 8192);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            int n = raf.read(buf);
            if (n <= 0) {
                XLog.w("fpsprobe: log is empty");
                return;
            }
            String[] lines = new String(buf, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1).split("\n");
            int shown = 0;
            for (String line : lines) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                XLog.d("fpsprobe:sample", "RAW " + xor(line));
                if (++shown >= 6) {
                    break;
                }
            }
        } catch (Throwable e) {
            XLog.w("fpsprobe: sample failed :: " + XLog.describe(e));
        }

        scanForFps(log);
    }

    /**
     * One-shot full-file scan for every FPS / render-setting line.
     *
     * <p>This exists because the interesting lines are emitted when the graphics
     * menu initialises, which may be long before or after the tail window. Scanning
     * the whole file once gives the complete picture: what the device table returned
     * per quality, what {@code SetFPS} resolved to, and whether the expand path was
     * open.
     */
    private static void scanForFps(File log) {
        int hits = 0;
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        new java.io.FileInputStream(log),
                        java.nio.charset.StandardCharsets.ISO_8859_1))) {
            String raw;
            while ((raw = br.readLine()) != null) {
                String line = xor(raw);
                if (line.isEmpty()) {
                    continue;
                }
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                boolean interesting =
                        lower.contains("fps")
                                || lower.contains("framerate")
                                || lower.contains("frame rate")
                                || lower.contains("maxfps")
                                || lower.contains("devicelevel")
                                || lower.contains("renderquality")
                                || lower.contains("get applying");
                if (interesting) {
                    hits++;
                    XLog.d("fpsprobe:scan", "SCAN " + line);
                }
            }
        } catch (Throwable e) {
            XLog.w("fpsprobe: scan failed after " + hits + " hits :: " + XLog.describe(e));
            return;
        }
        XLog.i("fpsprobe: full-file scan complete, " + hits + " FPS/render lines");
    }

    private static void tail(Context ctx) {
        File log = resolveLog(ctx);
        if (log == null) {
            return;
        }

        XLog.i("fpsprobe: watching " + log.getAbsolutePath());

        // One-shot raw sample so the actual log format is visible in logcat.
        dumpSample(log);

        long pos = Math.max(0, log.length() - TAIL_BYTES);
        while (true) {
            try {
                long len = log.length();
                if (len < pos) {
                    pos = 0;   // file was rotated
                }
                if (len > pos) {
                    pos = drain(log, pos, len);
                }
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable e) {
                XLog.d("fpsprobe", "fpsprobe read error: " + XLog.describe(e));
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static long drain(File log, long pos, long len) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            raf.seek(pos);
            byte[] buf = new byte[(int) Math.min(len - pos, TAIL_BYTES)];
            int n = raf.read(buf);
            if (n <= 0) {
                return pos;
            }
            String chunk = new String(buf, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1);
            int lastNl = chunk.lastIndexOf('\n');
            if (lastNl < 0) {
                return pos;   // partial line; wait for more
            }
            for (String line : chunk.substring(0, lastNl).split("\n")) {
                emit(xor(line));
            }
            return pos + lastNl + 1;
        }
    }

    /** Applies the log's XOR obfuscation. Symmetric: same call encodes and decodes. */
    private static String xor(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        for (int i = 0; i < b.length; i++) {
            b[i] ^= LOG_XOR_KEY;
        }
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Re-emit only lines that carry FPS/graphics decisions. */
    private static void emit(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (line.contains("InitRealSupportFPS")
                || line.contains("GetMaxFPSLevel")
                || line.contains("SetFPS")
                || line.contains("MaxFPSInMatch")
                || line.contains("ExpandFPS")
                || line.contains("maxSupportFPS")
                || line.contains("bluehole")
                || line.contains("DoClickFPS")
                || line.contains("InitFPSFTSwitch")
                || line.contains("SettingGraphics")
                // wider net: any line mentioning fps/framerate plus a quality word
                || (lower.contains("fps") && (lower.contains("quality") || lower.contains("support")))) {
            XLog.d("fpsprobe", "GAME " + line);
        }
    }
}
