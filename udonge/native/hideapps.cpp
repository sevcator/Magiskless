#include "hideapps.hpp"

#include <android/log.h>

namespace hideapps {
namespace {

bool clear_exception(JNIEnv *env, const char *stage) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    __android_log_print(ANDROID_LOG_WARN, "ReisenlessHideApps", "JNI failure at %s", stage);
    return true;
}

void exempt_hidden_apis(JNIEnv *env) {
    jclass vm_class = env->FindClass("dalvik/system/VMRuntime");
    if (!vm_class) {
        clear_exception(env, "VMRuntime class");
        return;
    }
    jmethodID get_runtime = env->GetStaticMethodID(
            vm_class, "getRuntime", "()Ldalvik/system/VMRuntime;");
    jmethodID set_exemptions = env->GetMethodID(
            vm_class, "setHiddenApiExemptions", "([Ljava/lang/String;)V");
    if (!get_runtime || !set_exemptions) {
        clear_exception(env, "hidden API methods");
        return;
    }
    if (clear_exception(env, "hidden API methods")) return;

    jobject runtime = env->CallStaticObjectMethod(vm_class, get_runtime);
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray prefixes = env->NewObjectArray(1, string_class, nullptr);
    jstring all = env->NewStringUTF("L");
    env->SetObjectArrayElement(prefixes, 0, all);
    env->CallVoidMethod(runtime, set_exemptions, prefixes);
    clear_exception(env, "hidden API exemptions");
}

} // namespace

bool install(JNIEnv *env, const std::string &caller, const std::string &rule,
             const std::string &dex) {
    if (!env || caller.empty() || rule.empty() || dex.empty()) return false;
    exempt_hidden_apis(env);

    jclass activity_thread = env->FindClass("android/app/ActivityThread");
    jfieldID pm_field = activity_thread
            ? env->GetStaticFieldID(activity_thread, "sPackageManager",
                                    "Landroid/content/pm/IPackageManager;")
            : nullptr;
    jobject original = pm_field ? env->GetStaticObjectField(activity_thread, pm_field) : nullptr;
    if (!original && activity_thread) {
        env->ExceptionClear();
        jmethodID get_package_manager = env->GetStaticMethodID(
                activity_thread, "getPackageManager",
                "()Landroid/content/pm/IPackageManager;");
        if (get_package_manager) {
            original = env->CallStaticObjectMethod(activity_thread, get_package_manager);
        }
    }
    if (!original) {
        clear_exception(env, "ActivityThread.sPackageManager");
        return false;
    }
    if (clear_exception(env, "ActivityThread.sPackageManager")) return false;

    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(dex.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(dex.size()),
                            reinterpret_cast<const jbyte *>(dex.data()));

    jclass byte_buffer = env->FindClass("java/nio/ByteBuffer");
    jmethodID wrap = byte_buffer
            ? env->GetStaticMethodID(byte_buffer, "wrap", "([B)Ljava/nio/ByteBuffer;")
            : nullptr;
    jobject buffer = wrap ? env->CallStaticObjectMethod(byte_buffer, wrap, bytes) : nullptr;

    jclass class_loader = env->FindClass("java/lang/ClassLoader");
    jmethodID get_system = class_loader
            ? env->GetStaticMethodID(class_loader, "getSystemClassLoader",
                                     "()Ljava/lang/ClassLoader;")
            : nullptr;
    jobject parent = get_system ? env->CallStaticObjectMethod(class_loader, get_system) : nullptr;

    jclass memory_loader = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    jmethodID loader_ctor = memory_loader
            ? env->GetMethodID(memory_loader, "<init>",
                               "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V")
            : nullptr;
    jobject loader = loader_ctor
            ? env->NewObject(memory_loader, loader_ctor, buffer, parent)
            : nullptr;
    if (!loader) {
        clear_exception(env, "InMemoryDexClassLoader");
        return false;
    }
    if (clear_exception(env, "InMemoryDexClassLoader")) return false;

    jmethodID load_class = env->GetMethodID(
            class_loader, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring class_name = env->NewStringUTF(
            "com.topjohnwu.reisenless.hideapps.PackageManagerProxy");
    auto proxy_class = static_cast<jclass>(
            env->CallObjectMethod(loader, load_class, class_name));
    if (!proxy_class) {
        clear_exception(env, "PackageManagerProxy class");
        return false;
    }
    if (clear_exception(env, "PackageManagerProxy class")) return false;

    jmethodID wrap_proxy = env->GetStaticMethodID(
            proxy_class, "wrap",
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;");
    jstring caller_string = env->NewStringUTF(caller.c_str());
    jstring rule_string = env->NewStringUTF(rule.c_str());
    jobject proxy = wrap_proxy
            ? env->CallStaticObjectMethod(proxy_class, wrap_proxy, original,
                                          caller_string, rule_string)
            : nullptr;
    if (!proxy) {
        clear_exception(env, "PackageManagerProxy.wrap");
        return false;
    }
    if (clear_exception(env, "PackageManagerProxy.wrap")) return false;

    env->SetStaticObjectField(activity_thread, pm_field, proxy);
    return !clear_exception(env, "install proxy");
}

} // namespace hideapps
