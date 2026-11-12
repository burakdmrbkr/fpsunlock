package dev.nextrounlock.fpsunlock;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One spoofable device identity.
 *
 * <p>Field coverage is tiered on purpose — see PLAN.md §2.2.1. The short version of
 * why, from the 4.7.0 client Lua:
 *
 * <ul>
 *   <li>{@code GSC_FPS:InitRealSupportFPS} asks
 *       {@code gameInstance:GetDeviceMaxFPSByDeviceLevel(quality)} — native, and it
 *       resolves the device from {@code Build} fields. {@link #L1} is what feeds it.
 *       This is the whole unlock.</li>
 *   <li>{@code data_device_os.lua} assembles ~50 identity fields and
 *       {@code ingame.lua:598} ships them to the server on match join. Screen size is
 *       in that payload, so {@link #L1_5} exists to stop a 1080p panel claiming to be
 *       a 1440p one.</li>
 *   <li>{@code Client.GetLowerSOCModelAboveAndroid12} reads SOC identity → {@link #L2}.</li>
 *   <li>Nothing in the script layer reads {@code FINGERPRINT}. {@link #L3} is purely
 *       for native/ACE consistency and costs the most.</li>
 * </ul>
 *
 * <p>Deliberately absent at every tier: {@code VERSION.*}, {@code SDK_INT},
 * {@code SERIAL}, IMEI/ANDROID_ID, GPU strings, reported RAM, {@code /proc/*}.
 * Spoofing RAM or GPU makes the engine pick render paths the real hardware cannot
 * honour — that is not an unlock, it is a stutter generator.
 */
public final class Profile {

    /** Progressive disclosure of the identity surface. */
    public enum Tier {
        /** Model/manufacturer/brand/device/product. The unlock. */
        L1,
        /** L1 + screen geometry. Removes the cheapest server-side contradiction. */
        L1_5,
        /** L1.5 + SOC identity. Matches the 4.4+ chipset rule directly. */
        L2,
        /** L2 + hardware/board/fingerprint. Native/ACE consistency only. */
        L3;

        /** True when this tier includes everything up to and including {@code floor}. */
        public boolean atLeast(Tier floor) {
            return this.ordinal() >= floor.ordinal();
        }

        public static Tier parse(String raw) {
            if (raw == null) {
                return L1;
            }
            switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "L1":
                    return L1;
                case "L1.5":
                case "L15":
                    return L1_5;
                case "L2":
                    return L2;
                case "L3":
                    return L3;
                default:
                    return L1;
            }
        }
    }

    public final String name;

    // L1
    public final String manufacturer;
    public final String brand;
    public final String model;
    public final String device;
    public final String product;

    // L1.5 (nullable = not spoofed)
    public final Integer screenWidth;
    public final Integer screenHeight;
    public final Integer screenDensity;

    // L2
    public final String socModel;
    public final String socManufacturer;

    // L3
    public final String hardware;
    public final String board;
    public final String fingerprint;

    private Profile(Builder b) {
        this.name = b.name;
        this.manufacturer = b.manufacturer;
        this.brand = b.brand;
        this.model = b.model;
        this.device = b.device;
        this.product = b.product;
        this.screenWidth = b.screenWidth;
        this.screenHeight = b.screenHeight;
        this.screenDensity = b.screenDensity;
        this.socModel = b.socModel;
        this.socManufacturer = b.socManufacturer;
        this.hardware = b.hardware;
        this.board = b.board;
        this.fingerprint = b.fingerprint;
    }

    /**
     * {@code Build} static fields to overwrite, for the given tier.
     *
     * <p>Ordering matters: {@code BRAND}/{@code MANUFACTURER} are written before
     * {@code FINGERPRINT} so a fingerprint composed from them stays coherent if a
     * future tier derives one.
     */
    public Map<String, String> buildFields(Tier tier) {
        // LinkedHashMap: BuildSpoofer iterates in insertion order.
        Map<String, String> out = new LinkedHashMap<>();

        putIfPresent(out, "MANUFACTURER", manufacturer);
        putIfPresent(out, "BRAND", brand);
        putIfPresent(out, "MODEL", model);
        putIfPresent(out, "DEVICE", device);
        putIfPresent(out, "PRODUCT", product);

        if (tier.atLeast(Tier.L2)) {
            putIfPresent(out, "SOC_MODEL", socModel);
            putIfPresent(out, "SOC_MANUFACTURER", socManufacturer);
        }

        if (tier.atLeast(Tier.L3)) {
            putIfPresent(out, "HARDWARE", hardware);
            putIfPresent(out, "BOARD", board);
            putIfPresent(out, "FINGERPRINT", fingerprint);
        }

        return out;
    }

    /** True when this profile carries enough data to spoof the screen at this tier. */
    public boolean hasScreen(Tier tier) {
        return tier.atLeast(Tier.L1_5)
                && screenWidth != null && screenHeight != null && screenDensity != null;
    }

    private static void putIfPresent(Map<String, String> m, String key, String value) {
        if (value != null && !value.isEmpty()) {
            m.put(key, value);
        }
    }

    /** Pre-rendered for log lines; must never throw. */
    public String summary(Tier tier) {
        StringBuilder sb = new StringBuilder();
        sb.append(name == null ? "?" : name)
          .append(" tier=").append(tier)
          .append(" model=").append(model)
          .append(" make=").append(manufacturer);
        if (tier.atLeast(Tier.L1_5) && hasScreen(tier)) {
            sb.append(" screen=").append(screenWidth).append('x').append(screenHeight)
              .append('@').append(screenDensity);
        }
        if (tier.atLeast(Tier.L2)) {
            sb.append(" soc=").append(socModel);
        }
        return sb.toString();
    }

    /**
     * Flat key/value array handed to the native layer.
     *
     * <p>Format: {@code {"ro.product.model", "<value>", "ro.product.manufacturer", ...}}
     * — property name first, value second. Native side applies its own allowlist, so
     * anything it does not recognise is ignored rather than trusted.
     *
     * <p>{@code FINGERPRINT} is only included at L3, matching the Java tiering.
     */
    public String[] nativeConfig() {
        java.util.List<String> kv = new java.util.ArrayList<>();
        add(kv, "ro.product.model", model);
        add(kv, "ro.product.manufacturer", manufacturer);
        add(kv, "ro.product.brand", brand);
        add(kv, "ro.product.device", device);
        add(kv, "ro.product.name", product);
        add(kv, "ro.soc.model", socModel);
        add(kv, "ro.soc.manufacturer", socManufacturer);

        // FINGERPRINT is L3-only; the caller decides tier, so gate on presence only.
        add(kv, "ro.build.fingerprint", fingerprint);

        return kv.toArray(new String[0]);
    }

    private static void add(java.util.List<String> kv, String key, String value) {
        if (value != null && !value.isEmpty()) {
            kv.add(key);
            kv.add(value);
        }
    }

    /** Structural sanity check — catches a hand-edited config before it reaches the game. */
    public String validate() {
        if (name == null || name.trim().isEmpty()) {
            return "profile has no name";
        }
        if (isEmpty(model)) {
            return "profile '" + name + "' has no model";
        }
        if (isEmpty(manufacturer)) {
            return "profile '" + name + "' has no manufacturer";
        }
        // A model string with whitespace is a config typo that silently never matches.
        if (model.contains(" ")) {
            return "profile '" + name + "' model contains a space: '" + model + "'";
        }
        return null;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String manufacturer, brand, model, device, product;
        private Integer screenWidth, screenHeight, screenDensity;
        private String socModel, socManufacturer;
        private String hardware, board, fingerprint;

        private Builder(String name) {
            this.name = name;
        }

        public Builder l1(String manufacturer, String brand, String model, String device, String product) {
            this.manufacturer = manufacturer;
            this.brand = brand;
            this.model = model;
            this.device = device;
            this.product = product;
            return this;
        }

        public Builder screen(Integer w, Integer h, Integer density) {
            this.screenWidth = w;
            this.screenHeight = h;
            this.screenDensity = density;
            return this;
        }

        public Builder soc(String model, String manufacturer) {
            this.socModel = model;
            this.socManufacturer = manufacturer;
            return this;
        }

        public Builder l3(String hardware, String board, String fingerprint) {
            this.hardware = hardware;
            this.board = board;
            this.fingerprint = fingerprint;
            return this;
        }

        public Profile build() {
            return new Profile(this);
        }
    }
}
