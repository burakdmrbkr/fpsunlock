# fpsunlock is loaded by name from assets/xposed_init, so the entry class and
# everything it reaches must survive minification.
-keep class dev.nextrounlock.fpsunlock.** { *; }

# Xposed API is compileOnly; references resolve against the framework at runtime.
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

# Native methods are matched by exact name.
-keepclasseswithmembernames class * {
    native <methods>;
}
