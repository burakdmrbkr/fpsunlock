package dev.nextrounlock.fpsunlock;

import android.os.Build;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Module entry point. Resolved by name from {@code assets/xposed_init}.
 *
 * <p>Runs twice per target process lifecycle, by design:
 * <ul>
 *   <li>{@link #initZygote} — zygote fork, before the app is specialised. We only
 *       stash {@code modulePath} here so {@link Config} can read the bundled asset
 *       without needing a {@code Context}.</li>
 *   <li>{@link #handleLoadPackage} — inside the target process, before its
 *       {@code Application.onCreate} and well before the engine initialises. This is
 *       where everything is applied.</li>
 * </ul>
 *
 * <p><b>Intervention budget.</b> This module changes device identity strings and
 * display-reported values. It does not touch memory of any loaded native library,
 * does not patch files, does not write to any partition, and does not intercept
 * anything the anti-cheat uses to detect tampering. That is the whole "just enough"
 * argument — see PLAN.md §1.
 */
public final class HookEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String SELF_PACKAGE = "dev.nextrounlock.fpsunlock";

    /** Set in {@link #initZygote}; read in {@link #handleLoadPackage}. */
    private static volatile String modulePath;

    @Override
    public void initZygote(StartupParam startupParam) {
        modulePath = startupParam.modulePath;
        XLog.i("zygote init, module=" + modulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            dispatch(lpparam);
        } catch (Throwable t) {
            // Never let a module bug take down the target process. A crashed game is a
            // worse outcome than an unapplied spoof, and it looks like a module failure
            // rather than a game bug, which wastes everyone's debugging time.
            XLog.e("unhandled failure in " + lpparam.packageName
                    + " — module disabled for this process", t);
        }
    }

    private void dispatch(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null) {
            return;
        }

        // LSPosed scopes us to our own package too, which is how the self-test runs.
        if (SELF_PACKAGE.equals(pkg)) {
            reportSelf(lpparam);
            return;
        }

        Config config = Config.load(modulePath);

        if (!config.isTarget(pkg)) {
            // Not in scope. Silent — this fires for every package LSPosed scopes us to,
            // and logging here would be noise.
            return;
        }

        Profile profile = config.profileFor(pkg);
        if (profile == null) {
            XLog.i(pkg + ": explicitly excluded by config, no hooks installed");
            return;
        }

        XLog.i(pkg + ": applying " + profile.summary(config.tier)
                + " | " + config.describe());

        // 1. Identity. This is the unlock: the native FPS table reads these.
        int written = BuildSpoofer.apply(profile, config.tier);
        if (written == 0) {
            XLog.w(pkg + ": NO Build fields were written — the spoof will not work");
        }

        // 2. Display refresh rate.
        if (config.refreshEnabled) {
            DisplaySpoofer.applyRefreshRate(config.refreshHz);
            if (config.forceWindowRate) {
                forceWindowRefreshRate(config.refreshHz);
            }
        }

        // 3. Screen geometry (L1.5+). Keeps the server-side identity payload coherent.
        if (profile.hasScreen(config.tier)) {
            DisplaySpoofer.applyScreenGeometry(
                    profile.screenWidth, profile.screenHeight, profile.screenDensity);
        }

        // 4. Probe mode.
        if (config.probeEnabled) {
            Probe.install(pkg);
        }

        // 4b. Surface the game's own FPS decisions. On by default: the game logs them
        // to a UID-private file, not logcat, so without this there is no way to
        // confirm which native FPS value the device table actually returned.
        if (config.fpsProbeEnabled) {
            try {
                FpsProbe.start(currentApplication());
            } catch (Throwable t) {
                XLog.d("fpsprobe", "probe unavailable: " + XLog.describe(t));
            }
        }

        // 5. Native prop layer (phase 3). Off unless explicitly enabled.
        if (config.nativeEnabled) {
            try {
                NativeLoader.loadAndHook(modulePath, profile);
            } catch (Throwable t) {
                XLog.w("native layer failed, continuing Java-only: " + XLog.describe(t));
            }
        }

        XLog.i(pkg + ": ready — Build=" + BuildSpoofer.snapshot());
    }

    /**
     * Sets {@code WindowManager.LayoutParams#preferredRefreshRate} on added windows.
     *
     * <p>Opt-in only ({@code refresh.forceWindow}). Some devices keep the panel at
     * 60 Hz for an app unless the window asks otherwise, and the game does not ask.
     * This helps real frame pacing; it does not unlock anything.
     */
    private void forceWindowRefreshRate(final float hz) {
        try {
            Class<?> lpClass = XposedHelpers.findClass(
                    "android.view.WindowManager$LayoutParams", null);
            final java.lang.reflect.Field pref =
                    lpClass.getDeclaredField("preferredRefreshRate");
            pref.setAccessible(true);

            Class<?> wmi = XposedHelpers.findClass("android.view.WindowManagerImpl", null);
            XposedBridge.hookAllMethods(wmi, "addView", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        if (arg != null && arg.getClass().getName()
                                .equals("android.view.WindowManager$LayoutParams")) {
                            try {
                                pref.setFloat(arg, hz);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            });
            XLog.d("display:window", "preferredRefreshRate=" + hz + " armed");
        } catch (Throwable t) {
            XLog.d("display:window", "window rate forcing unavailable: " + XLog.describe(t));
        }
    }

    /**
     * Resolves the host process's Application without requiring a Context hook.
     * Used by {@link FpsProbe}, which needs the game's own UID to read its log file.
     */
    private static android.content.Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return (android.content.Context) app;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Self-test: when scoped to our own package, dump the live values so you can
     * confirm the module is loaded and see what the device actually reports.
     */
    private void reportSelf(XC_LoadPackage.LoadPackageParam lpparam) {        Config config = Config.load(modulePath);
        XLog.i("=== self-test ===");
        XLog.i("process=" + lpparam.processName + " pid=" + android.os.Process.myPid());
        XLog.i("device=" + BuildSpoofer.snapshot());
        XLog.i("android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT
                + " abi=" + Build.SUPPORTED_ABIS[0]);
        XLog.i("config: " + config.describe());
        XLog.i("=== end self-test ===");
    }
}
