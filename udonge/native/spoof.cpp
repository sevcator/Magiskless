#include "spoof.hpp"
#include "config.hpp"

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
#include <string>

#define LOGD(...) ((void)0)
#define LOGE(...) ((void)0)

namespace cloak {

static void set_str(JNIEnv *env, jclass cls, const char *field, const std::string &val) {
    if (!cls) return;
    jfieldID fid = env->GetStaticFieldID(cls, field, "Ljava/lang/String;");
    if (!fid) { env->ExceptionClear(); return; }
    jstring s = env->NewStringUTF(val.c_str());
    env->SetStaticObjectField(cls, fid, s);
    env->DeleteLocalRef(s);
}

static void set_int(JNIEnv *env, jclass cls, const char *field, int val) {
    if (!cls) return;
    jfieldID fid = env->GetStaticFieldID(cls, field, "I");
    if (!fid) { env->ExceptionClear(); return; }
    env->SetStaticIntField(cls, fid, val);
}

void spoof_build(JNIEnv *env, const Config &cfg) {
    if (!env || cfg.gms_build.empty()) return;

    jclass build = env->FindClass("android/os/Build");
    if (!build) { env->ExceptionClear(); return; }
    jclass ver = env->FindClass("android/os/Build$VERSION");
    if (!ver) env->ExceptionClear();

    int n = 0;
    for (const auto &kv : cfg.gms_build) {
        const std::string &k = kv.first;
        const std::string &v = kv.second;
        if (k == "SECURITY_PATCH" || k == "INCREMENTAL" || k == "RELEASE") {
            set_str(env, ver, k.c_str(), v);
        } else if (k == "DEVICE_INITIAL_SDK_INT") {
            set_int(env, ver, "DEVICE_INITIAL_SDK_INT", atoi(v.c_str()));
        } else {
            set_str(env, build, k.c_str(), v);
        }
        ++n;
    }
    env->ExceptionClear();
    LOGD("spoofed %d Build fields for Play certification", n);
}

// Read a file into a byte vector. Returns empty vector on error.
static std::vector<uint8_t> read_file_bytes(const std::string &path) {
    int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return {};
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0) { ::close(fd); return {}; }
    std::vector<uint8_t> buf((size_t)st.st_size);
    ssize_t total = 0;
    while (total < st.st_size) {
        ssize_t n = ::read(fd, buf.data() + total, (size_t)(st.st_size - total));
        if (n <= 0) { ::close(fd); return {}; }
        total += n;
    }
    ::close(fd);
    return buf;
}

// Try InMemoryDexClassLoader (API 26+). No temp file remains on disk.
static jobject try_inmemory_dex(JNIEnv *env, const std::vector<uint8_t> &dex_bytes, jobject sysCL) {
    if (dex_bytes.empty()) return nullptr;
    jclass imcl = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (!imcl || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }

    // Constructor: InMemoryDexClassLoader(ByteBuffer dexBuffer, ClassLoader parent)
    jmethodID ctor = env->GetMethodID(imcl, "<init>",
        "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    if (!ctor || env->ExceptionCheck()) { env->ExceptionClear(); env->DeleteLocalRef(imcl); return nullptr; }

    // NewDirectByteBuffer wraps native memory; InMemoryDexClassLoader copies it during construction.
    jobject bb = env->NewDirectByteBuffer((void *)dex_bytes.data(), (jlong)dex_bytes.size());
    if (!bb || env->ExceptionCheck()) { env->ExceptionClear(); env->DeleteLocalRef(imcl); return nullptr; }

    jobject loader = env->NewObject(imcl, ctor, bb, sysCL);
    env->DeleteLocalRef(bb);
    env->DeleteLocalRef(imcl);
    if (env->ExceptionCheck() || !loader) { env->ExceptionClear(); return nullptr; }
    return loader;
}

// Fall back to DexClassLoader (path-based, requires temp file).
static jobject try_dexclassloader(JNIEnv *env, const std::string &dex_path, jobject sysCL) {
    jclass dcl = env->FindClass("dalvik/system/DexClassLoader");
    if (!dcl || env->ExceptionCheck()) {
        env->ExceptionClear();
        dcl = env->FindClass("dalvik/system/PathClassLoader");
        if (!dcl || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    }
    jmethodID ctor = env->GetMethodID(dcl, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (!ctor || env->ExceptionCheck()) { env->ExceptionClear(); env->DeleteLocalRef(dcl); return nullptr; }

    jstring jp = env->NewStringUTF(dex_path.c_str());
    jobject loader = env->NewObject(dcl, ctor, jp, nullptr, nullptr, sysCL);
    env->DeleteLocalRef(jp);
    env->DeleteLocalRef(dcl);
    if (env->ExceptionCheck() || !loader) { env->ExceptionClear(); return nullptr; }
    return loader;
}

void spoof_display(JNIEnv *env, const Config &cfg) {
    if (!env) return;
    // Use DISPLAY key if present, fall back to ID (same value on stock Pixel user builds).
    auto it = cfg.gms_build.find("DISPLAY");
    if (it == cfg.gms_build.end() || it->second.empty())
        it = cfg.gms_build.find("ID");
    if (it == cfg.gms_build.end() || it->second.empty()) return;
    jclass build = env->FindClass("android/os/Build");
    if (!build) { env->ExceptionClear(); return; }
    set_str(env, build, "DISPLAY", it->second);
    env->ExceptionClear();
    LOGD("spoofed Build.DISPLAY = %s", it->second.c_str());
}

void load_dex(JNIEnv *env, const std::string &dex_path, const std::string &pif_json) {
    if (!env || dex_path.empty()) return;

    // Get system class loader
    jclass clClass = env->FindClass("java/lang/ClassLoader");
    if (!clClass) { env->ExceptionClear(); LOGE("ClassLoader not found"); return; }
    jmethodID getSysCL = env->GetStaticMethodID(clClass, "getSystemClassLoader",
                                                 "()Ljava/lang/ClassLoader;");
    if (!getSysCL) { env->ExceptionClear(); LOGE("getSystemClassLoader not found"); return; }
    jobject sysCL = env->CallStaticObjectMethod(clClass, getSysCL);
    if (!sysCL || env->ExceptionCheck()) { env->ExceptionClear(); LOGE("sysCL null"); return; }

    // Read DEX bytes once — used by InMemoryDexClassLoader; also validates file exists.
    auto dex_bytes = read_file_bytes(dex_path);
    if (dex_bytes.empty()) {
        LOGE("DEX not found or empty: %s", dex_path.c_str());
        return;
    }

    // 1st choice: InMemoryDexClassLoader (API 26+, no disk artifact)
    jobject dexLoader = try_inmemory_dex(env, dex_bytes, sysCL);
    bool used_inmemory = (dexLoader != nullptr);

    // 2nd choice: DexClassLoader (needs temp file on disk)
    if (!dexLoader) {
        dexLoader = try_dexclassloader(env, dex_path, sysCL);
    }

    // The temp file is no longer needed — delete it now regardless of which loader won.
    if (::unlink(dex_path.c_str()) != 0 && errno != ENOENT)
        LOGD("unlink %s: %s", dex_path.c_str(), strerror(errno));

    if (!dexLoader) {
        LOGE("No DEX class loader available");
        env->DeleteLocalRef(sysCL);
        return;
    }

    LOGD("DEX loader: %s", used_inmemory ? "InMemoryDexClassLoader" : "DexClassLoader");

    // Resolve loadClass on the loader object
    jmethodID loadClass = env->GetMethodID(clClass, "loadClass",
        "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!loadClass) { env->ExceptionClear(); LOGE("loadClass not found"); goto cleanup; }

    {
        jstring className = env->NewStringUTF("io.sevcator.udonge.EntryPoint");
        jclass entryClass = (jclass)env->CallObjectMethod(dexLoader, loadClass, className);
        env->DeleteLocalRef(className);
        if (env->ExceptionCheck() || !entryClass) {
            env->ExceptionClear();
            LOGE("EntryPoint class not found in DEX");
            goto cleanup;
        }

        jmethodID initMethod = env->GetStaticMethodID(entryClass, "init", "(Ljava/lang/String;)V");
        if (!initMethod) { env->ExceptionClear(); LOGE("EntryPoint.init() not found"); }
        else {
            jstring jJson = env->NewStringUTF(pif_json.c_str());
            env->CallStaticVoidMethod(entryClass, initMethod, jJson);
            if (env->ExceptionCheck()) { env->ExceptionClear(); LOGE("EntryPoint.init() threw"); }
            else LOGD("DEX keystore hook loaded successfully");
            env->DeleteLocalRef(jJson);
        }
        env->DeleteLocalRef(entryClass);
    }

cleanup:
    env->DeleteLocalRef(dexLoader);
    env->DeleteLocalRef(sysCL);
}

} // namespace cloak
