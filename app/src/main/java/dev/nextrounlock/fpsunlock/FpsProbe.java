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

    private static boolean started;

    private FpsProbe() {
    }

    public static void start(final Context ctx) {
        if (started || ctx == null) {
            return;
        }
        started = true;

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    tail(ctx);
                } catch (Throwable e) {
                    XLog.w("fpsprobe: stopped :: " + XLog.describe(e));
                }
            }
        }, "fpsunlock-fpsprobe");
        t.setDaemon(true);
        t.start();
    }

    private static void tail(Context ctx) {
        File external = ctx.getExternalFilesDir(null);
        if (external == null) {
            XLog.w("fpsprobe: no external files dir");
            return;
        }

        // getExternalFilesDir gives .../Android/data/<pkg>/files; the log lives under
        // .../files/UE4Game/... so walk up to the package dir and rebuild the path.
        File pkgDir = external.getParentFile();
        if (pkgDir == null) {
            XLog.w("fpsprobe: cannot resolve package dir");
            return;
        }
        File log = new File(pkgDir.getAbsolutePath() + LOG_RELATIVE);
        if (!log.canRead()) {
            XLog.w("fpsprobe: log not readable at " + log.getAbsolutePath());
            return;
        }

        XLog.i("fpsprobe: watching " + log.getName());

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
            String chunk = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            int lastNl = chunk.lastIndexOf('\n');
            if (lastNl < 0) {
                return pos;   // partial line; wait for more
            }
            for (String line : chunk.substring(0, lastNl).split("\n")) {
                emit(line);
            }
            return pos + lastNl + 1;
        }
    }

    /** Re-emit only lines that carry FPS/graphics decisions. */
    private static void emit(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        if (line.contains("InitRealSupportFPS")
                || line.contains("GetMaxFPSLevel")
                || line.contains("SetFPS")
                || line.contains("MaxFPSInMatch")
                || line.contains("ExpandFPS")
                || line.contains("maxSupportFPS")
                || line.contains("bluehole120")
                || line.contains("DoClickFPS")
                || line.contains("InitFPSFTSwitch")) {
            XLog.d("fpsprobe", "GAME " + line);
        }
    }
}
