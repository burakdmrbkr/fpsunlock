package dev.nextrounlock.fpsunlock;

import android.graphics.Point;
import android.util.DisplayMetrics;
import android.view.Display;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Display-mode and screen-geometry spoof.
 *
 * <p>Two distinct jobs, easy to conflate:
 *
 * <ol>
 *   <li><b>Refresh rate</b> — makes {@code Display} report 120 Hz so anything that
 *       reads the panel rate sees a 120 Hz panel. Cheap, and the reference modules
 *       only ever hooked {@code getRefreshRate}. We cover the full surface,
 *       including the supported-modes list, because a consumer that checks
 *       "is 120 in the mode list" is not fooled by the current rate alone.</li>
 *   <li><b>Screen geometry</b> — {@code data_device_os.lua} puts
 *       {@code PhysicalScreenWidth/Height} in the identity payload it ships to the
 *       server on match join ({@code ingame.lua:598}). A 1220×2712 panel claiming to
 *       be an {@code SM-S928B} (1440×3120) is the cheapest possible contradiction,
 *       so L1.5 rewrites the reported size too.</li>
 * </ol>
 *
 * <p><b>Deliberately not hooked:</b> {@code Surface.setFrameRate},
 * {@code Window#setFrameRate}, and the frame-rate-override APIs. Those change
 * compositor scheduling rather than the game's own limiter, and misusing them is the
 * fastest route to a black screen. The game's limiter is {@code t.MaxFPS}, driven by
 * its own native device table — not by what the display reports.
 */
@SuppressWarnings({"deprecation", "DiscouragedPrivateApi"})
public final class DisplaySpoofer {

    private static final String HZ_KEY = "display:hz";
    private static final String MODE_KEY = "display:mode";

    private DisplaySpoofer() {
    }

    /**
     * Installs refresh-rate hooks.
     *
     * @param targetHz the rate to report
     */
    public static void applyRefreshRate(final float targetHz) {
        if (targetHz <= 0f) {
            XLog.w("display: refusing to spoof non-positive refresh rate " + targetHz);
            return;
        }

        // --- getRefreshRate() -> float -------------------------------------
        hook("getRefreshRate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                param.setResult(targetHz);
                XLog.d(HZ_KEY, "Display.getRefreshRate -> " + targetHz);
            }
        });

        // --- getMode() -> Display.Mode -------------------------------------
        // Some consumers read the mode object rather than the float.
        hook("getMode", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object original = param.getResult();
                Object patched = withRefreshRate(original, targetHz);
                if (patched != null) {
                    param.setResult(patched);
                    XLog.d(MODE_KEY, "Display.getMode -> " + targetHz + "Hz");
                }
            }
        });

        // --- getSupportedModes() -> Display.Mode[] -------------------------
        // Highest-value hook in this class for "is 120 supported" checks.
        hook("getSupportedModes", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object[] modes = (Object[]) param.getResult();
                if (modes == null || modes.length == 0) {
                    return;
                }
                Object best = modes[0];
                float bestHz = refreshRateOf(best);
                for (Object m : modes) {
                    float hz = refreshRateOf(m);
                    if (hz > bestHz) {
                        bestHz = hz;
                        best = m;
                    }
                }
                // Only touch the top mode if the panel does not already reach target.
                if (bestHz + 0.5f >= targetHz) {
                    return;
                }
                Object upgraded = withRefreshRate(best, targetHz);
                if (upgraded != null) {
                    param.setResult(new Object[]{upgraded});
                    XLog.d(MODE_KEY, "getSupportedModes -> single " + targetHz + "Hz mode");
                }
            }
        });

        // --- getSupportedRefreshRates() -> float[] (hidden) ----------------
        // Exists on API 21+ but is not public surface. Guarded.
        try {
            XposedHelpers.findAndHookMethod(Display.class, "getSupportedRefreshRates",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(new float[]{targetHz});
                        }
                    });
            XLog.d(HZ_KEY, "hooked getSupportedRefreshRates");
        } catch (Throwable t) {
            XLog.d(HZ_KEY, "getSupportedRefreshRates not hookable: " + XLog.describe(t));
        }
    }

    /**
     * Installs screen-geometry hooks so the reported physical panel size matches the
     * spoofed device. Only called at L1.5+ when the profile carries a screen block.
     */
    public static void applyScreenGeometry(final int width, final int height, final int density) {
        // getRealSize(Point) and getSize(Point) leave the result in an out-parameter,
        // so we must mutate the Point after the original has filled it.
        hookPointMutation("getRealSize", width, height);
        hookPointMutation("getSize", width, height);

        // getMetrics / getRealMetrics carry widthPixels/heightPixels/densityDpi.
        hookMetrics("getMetrics", width, height, density);
        hookMetrics("getRealMetrics", width, height, density);

        // getDisplayInfo is an out-parameter call on API 29+: DisplayInfo is passed in
        // and mutated, there is no return value. Hooking "this#getDisplayInfo()" fails.
        try {
            XposedBridge.hookAllMethods(Display.class, "getDisplayInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        if (arg != null && arg.getClass().getSimpleName().equals("DisplayInfo")) {
                            mutateDisplayInfo(arg, width, height, density);
                            return;
                        }
                    }
                }
            });
            XLog.d("display:geometry", "getDisplayInfo hooked (out-param form)");
        } catch (Throwable t) {
            XLog.d("display:geometry", "getDisplayInfo not hookable: " + XLog.describe(t));
        }

        XLog.d("display:geometry",
                "screen geometry spoof armed: " + width + "x" + height + "@" + density);
    }

    private static void mutateDisplayInfo(Object info, int width, int height, int density) {
        setFieldIfPresent(info, "logicalWidth", width);
        setFieldIfPresent(info, "logicalHeight", height);
        setFieldIfPresent(info, "appWidth", width);
        setFieldIfPresent(info, "appHeight", height);
        setFieldIfPresent(info, "largestNominalAppWidth", width);
        setFieldIfPresent(info, "largestNominalAppHeight", height);
        setFieldIfPresent(info, "densityDpi", density);
    }

    // ---- helpers ----------------------------------------------------------

    private static void hookPointMutation(String method, final int w, final int h) {
        try {
            // Two overloads exist: getRealSize(Point) and (pre-API17) getRealSize(Point,
            // boolean). Hook every overload and mutate whichever Point we got.
            XposedBridge.hookAllMethods(Display.class, method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        if (arg instanceof Point) {
                            Point p = (Point) arg;
                            p.x = w;
                            p.y = h;
                            return;
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XLog.d("display:geometry", method + " not hookable: " + XLog.describe(t));
        }
    }

    private static void hookMetrics(String method, final int w, final int h, final int density) {
        try {
            XposedBridge.hookAllMethods(Display.class, method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        if (arg instanceof DisplayMetrics) {
                            DisplayMetrics dm = (DisplayMetrics) arg;
                            dm.widthPixels = w;
                            dm.heightPixels = h;
                            dm.densityDpi = density;
                            dm.density = density / 160f;
                            dm.scaledDensity = dm.density;
                            dm.xdpi = density;
                            dm.ydpi = density;
                            return;
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XLog.d("display:geometry", method + " not hookable: " + XLog.describe(t));
        }
    }

    private static void hook(String method, XC_MethodHook cb) {
        try {
            XposedBridge.hookAllMethods(Display.class, method, cb);
            XLog.d("display", "hooked Display." + method);
        } catch (Throwable t) {
            XLog.w("Display." + method + " hook failed: " + XLog.describe(t));
        }
    }

    /** Reads {@code refreshRate} off a {@code Display.Mode} without hard-casting. */
    private static float refreshRateOf(Object mode) {
        if (mode == null) {
            return 0f;
        }
        try {
            Field f = mode.getClass().getDeclaredField("mRefreshRate");
            f.setAccessible(true);
            return f.getFloat(mode);
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    /**
     * Produces a {@code Display.Mode}-shaped object reporting {@code targetHz}.
     *
     * <p>Two paths, because the clean one is {@code @hide}:
     * <ol>
     *   <li>Construct a fresh mode via the hidden 4-arg constructor. Preferred — the
     *       object is internally consistent.</li>
     *   <li>Fall back to mutating {@code mRefreshRate} on the original. Works on ROMs
     *       where the constructor has moved, at the cost of a mode whose internal
     *       state was built for a different rate.</li>
     * </ol>
     * Returns {@code null} if both fail, in which case the caller leaves the original.
     */
    private static Object withRefreshRate(Object original, float targetHz) {
        if (original == null) {
            return null;
        }
        if (Math.abs(refreshRateOf(original) - targetHz) < 0.5f) {
            return original;   // already what we want
        }

        int w = intFieldOr(original, "mWidth", 0);
        int h = intFieldOr(original, "mHeight", 0);

        // Path 1: hidden constructor Display.Mode(int width, int height, int id, float rate)
        try {
            Constructor<?> ctor = original.getClass()
                    .getDeclaredConstructor(int.class, int.class, int.class, float.class);
            ctor.setAccessible(true);
            int id = intFieldOr(original, "mModeId", 0);
            Object fresh = ctor.newInstance(w, h, id, targetHz);
            XLog.d(MODE_KEY, "constructed Display.Mode " + w + "x" + h + "@" + targetHz);
            return fresh;
        } catch (Throwable ignored) {
            // fall through
        }

        // Path 2: mutate the original's refreshRate field in place.
        try {
            Field f = original.getClass().getDeclaredField("mRefreshRate");
            f.setAccessible(true);
            f.setFloat(original, targetHz);
            XLog.d(MODE_KEY, "mutated mRefreshRate on existing Display.Mode -> " + targetHz);
            return original;
        } catch (Throwable t) {
            XLog.w("unable to build Display.Mode at " + targetHz + "Hz: " + XLog.describe(t));
            return null;
        }
    }

    private static int intFieldOr(Object o, String field, int fallback) {
        try {
            Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.getInt(o);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void setFieldIfPresent(Object target, String name, int value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(target, value);
        } catch (Throwable ignored) {
            // Field renames across API levels are expected; not worth logging per call.
        }
    }
}
