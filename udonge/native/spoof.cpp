#include "spoof.hpp"
#include "config.hpp"

#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <fcntl.h>
#include <string>
#include <unistd.h>

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
    }
    env->ExceptionClear();
    if (ver) env->DeleteLocalRef(ver);
    env->DeleteLocalRef(build);
}

static jobject try_inmemory_dex(JNIEnv *env, const std::string &dex_data, jobject sys_cl) {
    if (dex_data.empty()) return nullptr;
    jclass loader_class = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (!loader_class || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    jmethodID ctor = env->GetMethodID(
        loader_class, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    if (!ctor || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(loader_class);
        return nullptr;
    }
    jclass buffer_class = env->FindClass("java/nio/ByteBuffer");
    jmethodID allocate = buffer_class ? env->GetStaticMethodID(
        buffer_class, "allocateDirect", "(I)Ljava/nio/ByteBuffer;") : nullptr;
    jobject buffer = allocate ? env->CallStaticObjectMethod(
        buffer_class, allocate, static_cast<jint>(dex_data.size())) : nullptr;
    void *buffer_data = buffer ? env->GetDirectBufferAddress(buffer) : nullptr;
    if (buffer_class) env->DeleteLocalRef(buffer_class);
    if (!buffer || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(loader_class);
        return nullptr;
    }
    if (!buffer_data) {
        env->DeleteLocalRef(buffer);
        env->DeleteLocalRef(loader_class);
        return nullptr;
    }
    memcpy(buffer_data, dex_data.data(), dex_data.size());
    jobject loader = env->NewObject(loader_class, ctor, buffer, sys_cl);
    env->DeleteLocalRef(buffer);
    env->DeleteLocalRef(loader_class);
    if (env->ExceptionCheck() || !loader) { env->ExceptionClear(); return nullptr; }
    return loader;
}

static std::string code_cache_dir(JNIEnv *env) {
    jclass thread = env->FindClass("android/app/ActivityThread");
    if (!thread || env->ExceptionCheck()) { env->ExceptionClear(); return {}; }
    jmethodID current = env->GetStaticMethodID(
        thread, "currentApplication", "()Landroid/app/Application;");
    if (!current || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(thread);
        return {};
    }
    jobject app = env->CallStaticObjectMethod(thread, current);
    env->DeleteLocalRef(thread);
    if (!app || env->ExceptionCheck()) { env->ExceptionClear(); return {}; }

    jclass context = env->GetObjectClass(app);
    jmethodID get_dir = context ? env->GetMethodID(
        context, "getCodeCacheDir", "()Ljava/io/File;") : nullptr;
    jobject file = get_dir ? env->CallObjectMethod(app, get_dir) : nullptr;
    env->DeleteLocalRef(app);
    if (context) env->DeleteLocalRef(context);
    if (!file || env->ExceptionCheck()) { env->ExceptionClear(); return {}; }

    jclass file_class = env->GetObjectClass(file);
    jmethodID get_path = file_class ? env->GetMethodID(
        file_class, "getAbsolutePath", "()Ljava/lang/String;") : nullptr;
    auto path = get_path ? static_cast<jstring>(env->CallObjectMethod(file, get_path)) : nullptr;
    env->DeleteLocalRef(file);
    if (file_class) env->DeleteLocalRef(file_class);
    if (!path || env->ExceptionCheck()) { env->ExceptionClear(); return {}; }

    const char *chars = env->GetStringUTFChars(path, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(path, chars);
    env->DeleteLocalRef(path);
    return result;
}

static std::string write_private_dex(const std::string &dir, const std::string &data) {
    if (dir.empty() || data.empty()) return {};
    std::string path = dir + "/.udonge.dex";
    int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return {};
    size_t written = 0;
    while (written < data.size()) {
        ssize_t n = ::write(fd, data.data() + written, data.size() - written);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) {
            ::close(fd);
            ::unlink(path.c_str());
            return {};
        }
        written += static_cast<size_t>(n);
    }
    ::close(fd);
    return path;
}

static jobject try_dexclassloader(JNIEnv *env, const std::string &dex_path,
                                  const std::string &optimized_dir, jobject sys_cl) {
    jclass loader_class = env->FindClass("dalvik/system/DexClassLoader");
    if (!loader_class || env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    jmethodID ctor = env->GetMethodID(
        loader_class, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (!ctor || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(loader_class);
        return nullptr;
    }
    jstring path = env->NewStringUTF(dex_path.c_str());
    jstring output = env->NewStringUTF(optimized_dir.c_str());
    jobject loader = env->NewObject(loader_class, ctor, path, output, nullptr, sys_cl);
    env->DeleteLocalRef(path);
    env->DeleteLocalRef(output);
    env->DeleteLocalRef(loader_class);
    if (env->ExceptionCheck() || !loader) { env->ExceptionClear(); return nullptr; }
    return loader;
}

void spoof_display(JNIEnv *env, const Config &cfg) {
    if (!env) return;
    auto it = cfg.gms_build.find("DISPLAY");
    if (it == cfg.gms_build.end() || it->second.empty()) it = cfg.gms_build.find("ID");
    if (it == cfg.gms_build.end() || it->second.empty()) return;
    jclass build = env->FindClass("android/os/Build");
    if (!build) { env->ExceptionClear(); return; }
    set_str(env, build, "DISPLAY", it->second);
    env->ExceptionClear();
    env->DeleteLocalRef(build);
}

void load_dex(JNIEnv *env, const std::string &dex_data) {
    if (!env || dex_data.empty()) return;
    jclass class_loader = env->FindClass("java/lang/ClassLoader");
    if (!class_loader) { env->ExceptionClear(); return; }
    jmethodID get_system = env->GetStaticMethodID(
        class_loader, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    if (!get_system) {
        env->ExceptionClear();
        env->DeleteLocalRef(class_loader);
        return;
    }
    jobject system_loader = env->CallStaticObjectMethod(class_loader, get_system);
    if (!system_loader || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(class_loader);
        return;
    }

    jobject dex_loader = try_inmemory_dex(env, dex_data, system_loader);
    if (!dex_loader) {
        std::string cache = code_cache_dir(env);
        std::string path = write_private_dex(cache, dex_data);
        if (!path.empty()) {
            dex_loader = try_dexclassloader(env, path, cache, system_loader);
            ::unlink(path.c_str());
        }
    }
    if (!dex_loader) {
        env->DeleteLocalRef(system_loader);
        env->DeleteLocalRef(class_loader);
        return;
    }

    jmethodID load_class = env->GetMethodID(
        class_loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (load_class) {
        jstring name = env->NewStringUTF("io.sevcator.udonge.EntryPoint");
        auto entry = static_cast<jclass>(env->CallObjectMethod(dex_loader, load_class, name));
        env->DeleteLocalRef(name);
        if (!env->ExceptionCheck() && entry) {
            jmethodID init = env->GetStaticMethodID(entry, "init", "()V");
            if (init) env->CallStaticVoidMethod(entry, init);
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(entry);
        } else {
            env->ExceptionClear();
        }
    } else {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(dex_loader);
    env->DeleteLocalRef(system_loader);
    env->DeleteLocalRef(class_loader);
}

} // namespace cloak
