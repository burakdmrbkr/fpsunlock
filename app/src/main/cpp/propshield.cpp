/*
 * libpropshield — optional native property hook (phase 3, disabled by default).
 *
 * Purpose: make __system_property_get / __system_property_read_callback agree with
 * the Build fields the Java layer overwrites, so the anti-cheat's view of the device
 * matches the game's view. This layer does NOT unlock anything — the unlock is
 * entirely the Build field overwrite in BuildSpoofer.java.
 *
 * Design notes:
 *  - Allowlist-only. A key that is not in our table passes straight through, so this
 *    cannot accidentally lie about something we did not intend to change.
 *  - No filesystem writes, no partition writes, nothing persistent. The hook lives
 *    only as long as the target process.
 *  - PLT hooking via bytehook/AAPM. Inline hooking the two functions directly is
 *    possible but more fragile across Android versions.
 */
#include <jni.h>
#include <string>
#include <unordered_map>
#include <mutex>

#include "bytehook.h"

#define LOG_TAG "fpsunlock-nat"

#if defined(__ANDROID__)
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) ((void)0)
#define LOGW(...) ((void)0)
#endif

namespace {

/** property name -> spoofed value. Guarded by g_mutex. */
std::unordered_map<std::string, std::string> g_overrides;
std::mutex g_mutex;
bool g_installed = false;

/**
 * Keys we are willing to rewrite. Anything else is passed through untouched,
 * even if the Java layer handed it to us.
 */
bool is_allowed_key(const char* name) {
    if (name == nullptr) return false;
    static const char* kAllowed[] = {
        "ro.product.model",
        "ro.product.manufacturer",
        "ro.product.brand",
        "ro.product.device",
        "ro.product.name",
        "ro.soc.model",
        "ro.soc.manufacturer",
        "ro.build.fingerprint",   // only ever supplied at L3
    };
    for (const char* k : kAllowed) {
        if (strcmp(name, k) == 0) return true;
    }
    return false;
}

/** Returns a spoofed value, or nullptr if this key is not overridden. */
const std::string* lookup(const char* name) {
    if (name == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_overrides.find(name);
    if (it == g_overrides.end()) return nullptr;
    return &it->second;
}

// --- hooks ---------------------------------------------------------------

int (*g_orig_get)(const char*, char*) = nullptr;
int (*g_orig_read_callback)(const char*, void (*)(void*, const char*, const char*, uint32_t), void*) = nullptr;

/** __system_property_get(const char* name, char* value) -> int length */
int hook_get(const char* name, char* value) {
    const std::string* v = lookup(name);
    if (v != nullptr) {
        size_t len = v->size();
        // PROP_VALUE_MAX is 92 bytes; never overflow the caller's buffer.
        if (len > 91) len = 91;
        if (value != nullptr) {
            memcpy(value, v->c_str(), len);
            value[len] = '\0';
        }
        return static_cast<int>(len);
    }
    return g_orig_get(name, value);
}

void hook_read_callback(const char* name,
                        void (*callback)(void*, const char*, const char*, uint32_t),
                        void* cookie) {
    const std::string* v = lookup(name);
    if (v != nullptr && callback != nullptr) {
        callback(cookie, name, v->c_str(), static_cast<uint32_t>(v->size()));
        return;
    }
    g_orig_read_callback(name, callback, cookie);
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_dev_nextrounlock_fpsunlock_NativeLoader_nativeConfigure(
        JNIEnv* env, jclass /*clazz*/, jobjectArray pairs) {
    if (pairs == nullptr) return;

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_overrides.clear();

        jsize n = env->GetArrayLength(pairs);
        // Pairs are [key, value, key, value, ...]
        for (jsize i = 0; i + 1 < n; i += 2) {
            jstring jk = static_cast<jstring>(env->GetObjectArrayElement(pairs, i));
            jstring jv = static_cast<jstring>(env->GetObjectArrayElement(pairs, i + 1));
            if (jk == nullptr || jv == nullptr) {
                if (jk) env->DeleteLocalRef(jk);
                if (jv) env->DeleteLocalRef(jv);
                continue;
            }
            const char* ck = env->GetStringUTFChars(jk, nullptr);
            const char* cv = env->GetStringUTFChars(jv, nullptr);
            if (ck != nullptr && cv != nullptr && is_allowed_key(ck)) {
                g_overrides[std::string(ck)] = std::string(cv);
                LOGI("override %s", ck);
            }
            if (ck) env->ReleaseStringUTFChars(jk, ck);
            if (cv) env->ReleaseStringUTFChars(jv, cv);
            env->DeleteLocalRef(jk);
            env->DeleteLocalRef(jv);
        }
    }

    if (g_installed) {
        LOGI("hooks already installed, values refreshed (%zu keys)", g_overrides.size());
        return;
    }

    // Install PLT hooks. We do not enumerate the app's own libraries: bytehook's
    // default is to hook every loaded ELF that imports these symbols, which covers
    // the game libraries, ACE, and the engine without us naming them.
    bytehook_stub_t s1 = bytehook_hook_all(
            nullptr, "__system_property_get",
            reinterpret_cast<void*>(hook_get),
            nullptr, nullptr);
    if (s1 == nullptr) {
        LOGW("failed to hook __system_property_get");
    }

    bytehook_stub_t s2 = bytehook_hook_all(
            nullptr, "__system_property_read_callback",
            reinterpret_cast<void*>(hook_read_callback),
            nullptr, nullptr);
    if (s2 == nullptr) {
        LOGW("failed to hook __system_property_read_callback (may not be imported)");
    }

    g_installed = true;
    LOGI("prop hooks installed (%zu overrides)", g_overrides.size());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_nextrounlock_fpsunlock_NativeLoader_nativeVersion(JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF("propshield/1");
}

/*
 * Bytehook requires a JNI_OnLoad so it can capture the JavaVM and cache its own
 * internals. Without it, bytehook_init() is lazy and the first hook from a
 * non-main thread can race.
 */
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    LOGI("JNI_OnLoad");
    return JNI_VERSION_1_6;
}
