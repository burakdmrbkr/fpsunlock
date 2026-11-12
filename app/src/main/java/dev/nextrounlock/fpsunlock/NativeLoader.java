package dev.nextrounlock.fpsunlock;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads the optional native property-hook layer.
 *
 * <p>Ships <b>disabled</b> ({@code native.enabled=false}). Its only job is to make
 * {@code __system_property_get} agree with the {@code Build} fields we overwrite, so
 * that native-side inspection sees the same device the UI does. It unlocks nothing —
 * the unlock is entirely {@link BuildSpoofer}.
 *
 * <p>An LSPosed module's {@code .so} is not on the target's library path, so
 * {@code System.loadLibrary("propshield")} cannot resolve it. We extract into the
 * target app's own private cache and {@code System.load} by absolute path. No
 * partition is written and nothing persists outside that app's sandbox.
 */
public final class NativeLoader {

    private static final String LIB_NAME = "libpropshield.so";
    private static boolean attempted;

    private NativeLoader() {
    }

    /**
     * Extracts, loads and configures the native layer.
     *
     * <p>Every failure is non-fatal: the Java spoof is the feature, this is
     * defence-in-depth on top of it.
     */
    public static void loadAndHook(String apkPath, Profile profile) {
        if (attempted) {
            return;
        }
        attempted = true;

        if (apkPath == null) {
            XLog.w("native: modulePath unknown, cannot extract " + LIB_NAME);
            return;
        }

        String abi = android.os.Build.SUPPORTED_ABIS[0];
        String entryName = "lib/" + abi + "/" + LIB_NAME;

        try {
            File lib = extract(apkPath, entryName);
            if (lib == null) {
                XLog.w("native: " + entryName + " absent from module APK "
                        + "(build with -Pnative=true)");
                return;
            }
            System.load(lib.getAbsolutePath());
            XLog.i("native: loaded " + lib.getAbsolutePath());

            // Flat String[] ABI: passing an enum or POJO across JNI needs mangled
            // signatures and breaks the moment the Java type is renamed.
            nativeConfigure(profile.nativeConfig());
            XLog.i("native: configured " + profile.nativeConfig().length / 2 + " prop overrides");
        } catch (Throwable t) {
            XLog.w("native: load failed, Java-only behaviour retained: " + XLog.describe(t));
        }
    }

    /** Extracts the library into this process's private cache. Returns null if absent. */
    private static File extract(String apkPath, String entryName) throws IOException {
        Context ctx = currentApplication();
        if (ctx == null) {
            XLog.w("native: no application context, cannot resolve cache dir");
            return null;
        }

        File dir = new File(ctx.getCacheDir(), "fpsunlock");
        File out = new File(dir, LIB_NAME);
        if (out.exists() && out.length() > 0) {
            return out;
        }

        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            if (!dir.mkdirs() && !dir.isDirectory()) {
                XLog.w("native: cannot create " + dir);
                return null;
            }
            try (InputStream in = zip.getInputStream(entry);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
        }

        // Best effort; ignored if the filesystem disagrees.
        out.setReadable(true, true);
        out.setWritable(true, true);
        out.setExecutable(true, true);
        return out;
    }

    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return (Context) at.getMethod("currentApplication").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Native side applies its own allowlist; unrecognised keys are ignored, not trusted. */
    private static native void nativeConfigure(String[] keyValuePairs);
}
