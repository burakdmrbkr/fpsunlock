package dev.nextrounlock.fpsunlock;

import android.os.Build;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Probe mode — observe, never substitute.
 *
 * <p>Enabled with {@code "probe": true} in config. Installs pass-through hooks that
 * log what the game actually reads, then return the original value untouched.
 *
 * <p>The point is to replace inference with observation. PLAN.md §2.2 was derived
 * by reading 27k Lua files and reasoning about the native boundary; this mode
 * answers the question directly on the device. Concretely it tells us:
 * <ul>
 *   <li>whether the game's Java layer or the engine's JNI reads {@code Build} fields,
 *       and which ones — if only {@code MODEL} is read, the rest of L1 is unnecessary</li>
 *   <li>whether {@code Display.getSupportedModes} is consulted at all — if not, that
 *       hook in {@link DisplaySpoofer} is dead weight</li>
 *   <li>whether the process touches {@code SystemProperties} for device identity,
 *       which is the signal that the phase-3 native layer is worth enabling</li>
 * </ul>
 *
 * <p>Cost: logcat volume. Mitigated by {@link XLog}'s per-key budget.
 */
public final class Probe {

    private Probe() {
    }

    public static void install(String pkg) {
        XLog.i(pkg + ": probe mode ON — logging reads, not substituting");

        hookDisplay();
        hookProperties();
        hookGraphics();
        hookBuildFields();
    }

    // ---- Display ----------------------------------------------------------

    private static void hookDisplay() {
        String[] methods = {
                "getRefreshRate", "getMode", "getSupportedModes",
                "getSupportedRefreshRates", "getSize", "getRealSize", "getDisplayInfo"
        };
        for (String m : methods) {
            try {
                XposedBridge.hookAllMethods(android.view.Display.class, m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        XLog.d("probe:display",
                                "Display." + m + " -> " + brief(param.getResult())
                                        + " caller=" + callerOf(1));
                    }
                });
            } catch (Throwable ignored) {
                // Method absent on this API level; nothing to probe.
            }
        }
    }

    // ---- System properties ------------------------------------------------

    private static void hookProperties() {
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", null);

            XposedBridge.hookAllMethods(sp, "get", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    String key = (String) param.args[0];
                    if (isDeviceKey(key)) {
                        XLog.d("probe:prop", "PROBE prop " + key + " = " + param.getResult()
                                + " caller=" + callerOf(2));
                    }
                }
            });
        } catch (Throwable t) {
            XLog.d("probe", "SystemProperties probe unavailable: " + XLog.describe(t));
        }
    }

    /** Filters the property firehose down to device-identity keys. */
    private static boolean isDeviceKey(String key) {
        if (key == null) {
            return false;
        }
        return key.startsWith("ro.product.")
                || key.startsWith("ro.soc.")
                || key.startsWith("ro.board.")
                || key.startsWith("ro.hardware")
                || key.startsWith("ro.build.fingerprint")
                || key.startsWith("ro.build.version.")
                || key.equals("ro.debuggable")
                || key.equals("ro.secure");
    }

    // ---- GLES -------------------------------------------------------------

    private static void hookGraphics() {
        try {
            Class<?> gles = XposedHelpers.findClass("android.opengl.GLES20", null);
            XposedBridge.hookAllMethods(gles, "glGetString", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    XLog.d("probe:gl", "glGetString(" + param.args[0] + ") -> "
                            + brief(param.getResult()));
                }
            });
        } catch (Throwable t) {
            XLog.d("probe", "GLES20 probe unavailable: " + XLog.describe(t));
        }
    }

    // ---- Build field reads ------------------------------------------------
    //
    // Build fields are plain static finals; ART does not offer read-hook points for
    // them without instrumentation. Instead we snapshot at attach time, which is
    // enough to answer "did the spoof land before the game looked".

    private static void hookBuildFields() {
        XLog.d("probe:build", "PROBE build snapshot: " + BuildSpoofer.snapshot());
        try {
            XLog.d("probe:build", "PROBE build extra: SOC_MANUFACTURER=" + Build.SOC_MANUFACTURER
                    + " HARDWARE=" + Build.HARDWARE
                    + " BOARD=" + Build.BOARD
                    + " FINGERPRINT=" + Build.FINGERPRINT);
        } catch (Throwable ignored) {
        }
    }

    // ---- utilities --------------------------------------------------------

    /** Compact result rendering — arrays are the common case and are huge. */
    private static String brief(Object o) {
        if (o == null) {
            return "null";
        }
        if (o.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(o);
            if (n == 0) {
                return "[]";
            }
            Object first = java.lang.reflect.Array.get(o, 0);
            return o.getClass().getSimpleName() + "[" + n + "] first="
                    + (first == null ? "null" : first.getClass().getSimpleName());
        }
        String s = String.valueOf(o);
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    /**
     * Best-effort caller class. Walks past Xposed and our own frames. Used to
     * distinguish "the game's Java read this" from "the engine's JNI read this" —
     * which is the single most useful bit of information this probe produces.
     */
    private static String callerOf(int extraSkip) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        int skip = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.startsWith("dev.nextrounlock.fpsunlock")
                    || cn.startsWith("de.robv.android.xposed")
                    || cn.startsWith("java.lang.Thread")) {
                skip++;
                continue;
            }
            if (extraSkip > 0) {
                extraSkip--;
                continue;
            }
            return cn + "#" + e.getMethodName();
        }
        return "?";
    }
}
