package dev.nextrounlock.fpsunlock;

import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * Module configuration and profile resolution.
 *
 * <p>Resolution order (first hit wins, and the winner is always logged so there is
 * never ambiguity about what actually ran):
 * <ol>
 *   <li>{@code /data/local/tmp/fpsunlock.json} — adb-pushed override, best effort.
 *       SELinux denies this on some ROMs; we log and fall through rather than fail.</li>
 *   <li>{@code assets/config.json} inside the module APK, read as a zip from the
 *       module path handed to us by {@code initZygote}.</li>
 *   <li>Compiled-in defaults, so a corrupt or missing config can never brick the
 *       hooked process.</li>
 * </ol>
 *
 * <p>No network access anywhere in this class. The reference module we based the
 * shape on fetched its profile from GitHub at class-init time, which is a syscall
 * inside a process that is being inspected by anti-cheat and fails offline. Not
 * repeated here.
 */
public final class Config {

    /** adb-pushable override. */
    private static final String OVERRIDE_PATH = "/data/local/tmp/fpsunlock.json";

    public final String activeProfileName;
    public final Profile.Tier tier;
    public final boolean refreshEnabled;
    public final float refreshHz;
    public final boolean forceWindowRate;
    public final boolean nativeEnabled;
    public final boolean probeEnabled;

    /** package → profile name, or {@code null} for "explicitly do not touch". */
    private final Map<String, String> packageMap;
    private final Map<String, Profile> profiles;

    /** Human-readable description of where this config came from. Logged at startup. */
    public final String source;

    private Config(String source,
                   String activeProfileName,
                   Profile.Tier tier,
                   boolean refreshEnabled,
                   float refreshHz,
                   boolean forceWindowRate,
                   boolean nativeEnabled,
                   boolean probeEnabled,
                   Map<String, String> packageMap,
                   Map<String, Profile> profiles) {
        this.source = source;
        this.activeProfileName = activeProfileName;
        this.tier = tier;
        this.refreshEnabled = refreshEnabled;
        this.refreshHz = refreshHz;
        this.forceWindowRate = forceWindowRate;
        this.nativeEnabled = nativeEnabled;
        this.probeEnabled = probeEnabled;
        this.packageMap = packageMap;
        this.profiles = profiles;
    }

    // ---- resolution -------------------------------------------------------

    /** True when this package is in scope. Checked before any hook is installed. */
    public boolean isTarget(String pkg) {
        return pkg != null && packageMap.containsKey(pkg);
    }

    /**
     * Profile for a package, or {@code null} if the package is explicitly excluded.
     * Falls back to the active profile if the package maps to a name we don't know —
     * that is a config typo, and doing something defensible beats doing nothing.
     */
    public Profile profileFor(String pkg) {
        String name = packageMap.get(pkg);
        if (name == null) {
            return null;
        }
        Profile p = profiles.get(name);
        if (p == null) {
            XLog.w("config: package " + pkg + " -> unknown profile '" + name
                    + "', falling back to " + activeProfileName);
            p = profiles.get(activeProfileName);
        }
        return p;
    }

    /**
     * @param modulePath path to the module APK, from
     *                   {@code IXposedHookZygoteInit.StartupParam#modulePath}.
     *                   May be {@code null} if called outside zygote init, in which
     *                   case the bundled assets are skipped.
     */
    public static Config load(String modulePath) {
        // 1. adb override
        Config c = tryLoadFile(OVERRIDE_PATH, null);
        if (c != null) {
            return c;
        }

        // 2. bundled asset
        if (modulePath != null) {
            c = tryLoadFile(null, modulePath);
            if (c != null) {
                return c;
            }
        }

        // 3. compiled-in
        XLog.w("config: no config found, using compiled defaults");
        return defaults("compiled-defaults");
    }

    private static Config tryLoadFile(String path, String apkPath) {
        InputStream in = null;
        try {
            String json;
            String source;
            if (path != null) {
                File f = new File(path);
                if (!f.canRead()) {
                    XLog.d("cfg", "config: " + path + " not readable, skipping");
                    return null;
                }
                in = new FileInputStream(f);
                json = readAll(in);
                source = path;
            } else {
                // Read assets/config.json straight out of the APK zip. Doing this
                // avoids needing a Context in a foreign process.
                java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkPath);
                java.util.zip.ZipEntry entry = zip.getEntry("assets/config.json");
                if (entry == null) {
                    zip.close();
                    XLog.d("cfg", "config: no assets/config.json in " + apkPath);
                    return null;
                }
                in = zip.getInputStream(entry);
                json = readAll(in);
                source = "apk:assets/config.json";
            }
            return parse(json, source);
        } catch (Throwable t) {
            XLog.w("config: failed to load from " + (path != null ? path : apkPath)
                    + " :: " + XLog.describe(t));
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing useful to do; the data was already read.
            }
        }
    }

    private static Config parse(String json, String source) throws org.json.JSONException {
        JSONObject root = new JSONObject(json);

        String active = root.optString("active", "S24U");
        Profile.Tier tier = Profile.Tier.parse(root.optString("tier", "L1"));

        JSONObject refresh = root.optJSONObject("refresh");
        boolean refreshEnabled = refresh != null && refresh.optBoolean("enabled", true);
        float refreshHz = refresh != null ? (float) refresh.optDouble("hz", 120.0) : 120f;
        boolean forceWindow = refresh != null && refresh.optBoolean("forceWindow", false);

        JSONObject nativeObj = root.optJSONObject("native");
        boolean nativeEnabled = nativeObj != null && nativeObj.optBoolean("enabled", false);

        boolean probe = root.optBoolean("probe", false);

        // profiles
        Map<String, Profile> profiles = new LinkedHashMap<>();
        JSONObject profileObj = root.optJSONObject("profiles");
        if (profileObj != null) {
            java.util.Iterator<String> keys = profileObj.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONObject p = profileObj.optJSONObject(name);
                if (p == null) {
                    continue;
                }
                Profile.Builder b = Profile.builder(name)
                        .l1(
                                p.optString("manufacturer", null),
                                p.optString("brand", null),
                                p.optString("model", null),
                                p.optString("device", null),
                                p.optString("product", null));

                JSONObject screen = p.optJSONObject("screen");
                if (screen != null) {
                    b.screen(
                            optIntOrNull(screen, "width"),
                            optIntOrNull(screen, "height"),
                            optIntOrNull(screen, "density"));
                }

                String socModel = p.optString("soc_model", null);
                if (socModel != null && !socModel.isEmpty()) {
                    b.soc(socModel, p.optString("soc_manufacturer", null));
                }

                b.l3(
                        p.optString("hardware", null),
                        p.optString("board", null),
                        p.optString("fingerprint", null));

                Profile built = b.build();
                String problem = built.validate();
                if (problem != null) {
                    XLog.w("config: rejecting profile — " + problem);
                    continue;
                }
                profiles.put(name, built);
            }
        }

        // package map
        Map<String, String> pkgMap = new HashMap<>();
        JSONObject packages = root.optJSONObject("packages");
        if (packages != null) {
            java.util.Iterator<String> keys = packages.keys();
            while (keys.hasNext()) {
                String pkg = keys.next();
                if (packages.isNull(pkg)) {
                    pkgMap.put(pkg, null);   // explicit exclusion
                } else {
                    pkgMap.put(pkg, packages.optString(pkg, null));
                }
            }
        }

        if (profiles.isEmpty()) {
            XLog.w("config: no valid profiles in " + source + ", using compiled defaults");
            return defaults(source + " (no valid profiles)");
        }
        if (!profiles.containsKey(active)) {
            String first = profiles.keySet().iterator().next();
            XLog.w("config: active profile '" + active + "' not found, using '" + first + "'");
            active = first;
        }

        return new Config(source, active, tier, refreshEnabled, refreshHz, forceWindow,
                nativeEnabled, probe, pkgMap, profiles);
    }

    private static Integer optIntOrNull(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) {
            return null;
        }
        int v = o.optInt(key, -1);
        return v > 0 ? v : null;
    }

    // ---- compiled defaults ------------------------------------------------

    /**
     * Compiled-in fallback. Mirrors assets/config.json so behaviour is identical
     * whether or not the asset was readable. Keep the two in sync.
     */
    public static Config defaults(String source) {
        Map<String, Profile> profiles = new LinkedHashMap<>();

        Profile s24u = Profile.builder("S24U")
                .l1("samsung", "samsung", "SM-S928B", "e3q", "e3qxxx")
                .screen(1440, 3120, 450)
                .soc("SM8650", "QTI")
                .l3("qcom", "pineapple",
                        "samsung/e3qxxx/e3q:14/UP1A.231005.007/S928BXXU1AXBA:user/release-keys")
                .build();

        Profile tabS9u = Profile.builder("SM_X910")
                .l1("samsung", "samsung", "SM-X910", "gts9uwifi", "gts9uwifixx")
                .screen(1848, 2960, 320)
                .soc("SM8550", "QTI")
                .l3("qcom", "kalama",
                        "samsung/gts9uwifixx/gts9uwifi:14/UP1A.231005.007/X910XXU1AXBA:user/release-keys")
                .build();

        Profile rog8 = Profile.builder("ROG8")
                .l1("asus", "asus", "ASUS_AI2401", "AI2401", "WW_AI2401")
                .screen(1080, 2400, 395)
                .soc("SM8650", "QTI")
                .l3("qcom", "pineapple",
                        "asus/WW_AI2401/AI2401:14/UP1A.231005.007/WW_AI2401_33.0210.0210.200:user/release-keys")
                .build();

        Profile redmagic = Profile.builder("REDMAGIC9P")
                .l1("nubia", "nubia", "NX769J", "NX769J", "NX769J")
                .screen(1116, 2480, 387)
                .soc("SM8650", "QTI")
                .l3("qcom", "pineapple",
                        "nubia/NX769J/NX769J:14/UP1A.231005.007/OS9.0.14:user/release-keys")
                .build();

        profiles.put("S24U", s24u);
        profiles.put("SM_X910", tabS9u);
        profiles.put("ROG8", rog8);
        profiles.put("REDMAGIC9P", redmagic);

        Map<String, String> pkgMap = new HashMap<>();
        pkgMap.put("com.tencent.ig", "S24U");
        pkgMap.put("com.pubg.imobile", "S24U");
        pkgMap.put("com.pubg.krmobile", "S24U");
        pkgMap.put("com.rekoo.pubgm", "S24U");
        pkgMap.put("com.vng.pubgmobile", "S24U");
        pkgMap.put("com.tencent.tmgp.pubgmhd", "S24U");
        pkgMap.put("com.pubg.newstate", "S24U");
        pkgMap.put("com.tencent.igfit", "S24U");
        pkgMap.put("com.aida64", "S24U");
        pkgMap.put("ru.andr7e.deviceinfohw", "S24U");

        return new Config(source, "S24U", Profile.Tier.L2,
                true, 120f, false, false, false,
                pkgMap, profiles);
    }

    /** Diagnostics for the startup log line. */
    public String describe() {
        return "source=" + source
                + " active=" + activeProfileName
                + " tier=" + tier
                + " refresh=" + (refreshEnabled ? refreshHz + "Hz" : "off")
                + " native=" + nativeEnabled
                + " probe=" + probeEnabled
                + " profiles=" + profiles.keySet();
    }

    public Map<String, String> packageMapView() {
        return Collections.unmodifiableMap(packageMap);
    }
}
