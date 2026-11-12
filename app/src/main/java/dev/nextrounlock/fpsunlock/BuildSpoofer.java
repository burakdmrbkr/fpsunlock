package dev.nextrounlock.fpsunlock;

import android.os.Build;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Overwrites {@code android.os.Build} static fields in the target process.
 *
 * <p>Why this works, from the 4.7.0 client Lua: the entire FPS gate is
 * {@code gameInstance:GetDeviceMaxFPSByDeviceLevel(quality)} — a native call whose
 * device lookup resolves from these fields. Patch the fields before the engine
 * initialises and the native table returns 120.
 *
 * <h3>Ordering</h3>
 * {@code Build.<clinit>} populates every field from {@code SystemProperties} on
 * first touch. If we write a field before that runs, the class initialiser
 * overwrites us. So the first thing {@link #apply} does is force the class into
 * its initialised state, then write.
 *
 * <h3>Known limitation</h3>
 * Writing a {@code static final String} via reflection changes the field, not any
 * constant the JIT inlined at <em>its</em> compile time. Callers compiled against
 * the public SDK stub read the field, so this is fine in practice. The probe mode
 * exists partly to prove it on-device rather than assume it.
 */
public final class BuildSpoofer {

    private static boolean buildClassReady = false;

    private BuildSpoofer() {
    }

    /**
     * @return number of fields successfully written
     */
    public static int apply(Profile profile, Profile.Tier tier) {
        ensureBuildInitialised();

        Map<String, String> fields = profile.buildFields(tier);
        int ok = 0;
        int failed = 0;

        for (Map.Entry<String, String> e : fields.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            try {
                Field f = Build.class.getDeclaredField(key);
                f.setAccessible(true);
                f.set(null, value);
                f.setAccessible(false);
                ok++;
                XLog.d("build:" + key, "build." + key + " = " + value);
            } catch (NoSuchFieldException nsfe) {
                // Expected on older API levels for SOC_* (API 31+). Not an error.
                XLog.d("build:" + key, "build." + key + " absent on this API level, skipped");
                failed++;
            } catch (Throwable t) {
                // A SecurityException or IllegalAccessException here means the ROM or
                // ART is refusing the write. Surface it loudly: the spoof silently
                // not applying is the worst failure mode.
                XLog.w("build." + key + " write failed: " + XLog.describe(t));
                failed++;
            }
        }

        XLog.d("build", "build spoof: " + ok + " written, " + failed + " skipped");
        return ok;
    }

    /**
     * Forces {@code Build.<clinit>} to run so our writes survive.
     *
     * <p>Anything that touches a {@code Build} field would do this implicitly, but
     * we may be the first caller in this process, and we are writing fields the
     * engine has not read yet.
     */
    private static void ensureBuildInitialised() {
        if (buildClassReady) {
            return;
        }
        try {
            // Any field read triggers static init.
            String ignored = Build.MODEL;
            if (ignored == null) {
                // Unreachable in practice; silences the unused-variable lint.
                XLog.d("build", "build.MODEL was null before spoof");
            }
            buildClassReady = true;
        } catch (Throwable t) {
            XLog.w("build init probe failed: " + XLog.describe(t));
        }
    }

    /** Current values, for the self-test dump. Never throws. */
    public static String snapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("MANUFACTURER=").append(safe(Build.MANUFACTURER))
          .append(" BRAND=").append(safe(Build.BRAND))
          .append(" MODEL=").append(safe(Build.MODEL))
          .append(" DEVICE=").append(safe(Build.DEVICE))
          .append(" PRODUCT=").append(safe(Build.PRODUCT));
        try {
            sb.append(" SOC_MODEL=").append(safe(Build.SOC_MODEL));
        } catch (Throwable ignored) {
            sb.append(" SOC_MODEL=<absent>");
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "<null>" : s;
    }
}
