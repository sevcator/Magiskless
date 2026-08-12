#include <sys/types.h>   // dev_t / ino_t used by zygisk.hpp
#include "zygisk.hpp"
#include "config.hpp"
#include "hooks.hpp"
#include "spoof.hpp"
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <cstdint>
#include <string>

#define LOGD(...) ((void)0)
#define LOGE(...) ((void)0)

using zygisk::Api;
using zygisk::AppSpecializeArgs;

namespace {

// ---- length-prefixed socket helpers ----
bool xwrite(int fd, const void *buf, size_t len) {
    auto *p = static_cast<const uint8_t *>(buf);
    while (len) {
        ssize_t n = write(fd, p, len);
        if (n <= 0) return false;
        p += n; len -= n;
    }
    return true;
}
bool xread(int fd, void *buf, size_t len) {
    auto *p = static_cast<uint8_t *>(buf);
    while (len) {
        ssize_t n = read(fd, p, len);
        if (n <= 0) return false;
        p += n; len -= n;
    }
    return true;
}
bool write_str(int fd, const std::string &s) {
    uint32_t n = (uint32_t) s.size();
    return xwrite(fd, &n, sizeof n) && xwrite(fd, s.data(), n);
}
bool read_str(int fd, std::string &out) {
    uint32_t n = 0;
    if (!xread(fd, &n, sizeof n)) return false;
    if (n > (16u << 20)) return false;  // 16 MiB sanity cap
    out.resize(n);
    return n == 0 || xread(fd, out.data(), n);
}

#ifndef UDONGE_ROOT
#define UDONGE_ROOT "/data/adb/udonge"
#endif

const char *CONF_DIR = UDONGE_ROOT "/state";

bool is_candidate(const std::string &pkg) {
    static const char *const packages[] = {
        "com.eltavine.duckdetector", "ru.nspk.mirpay", "ru.nspk.sbpay",
        "ru.sberbankmobile", "com.idamob.tinkoff.android",
        "ru.vtb24.mobilebanking.android", "ru.alfabank.mobile.android",
        "ru.gazprombank.android.mobilebank.app", "ru.raiffeisennews",
        "ru.rosbank.android", "ru.mkb.mobile", "ru.rshb.dbo",
        "ru.letobank.Prometheus", "com.openbank", "ru.sovcombank.halva",
        "com.sovcombank.club", "ru.yoo.money", "com.yandex.bank",
        "ru.ozon.fintech.finance", "com.qiwi.wallet",
        "com.axlebolt.standoff2",
    };
    for (const char *candidate : packages) {
        if (pkg == candidate) return true;
    }
    return false;
}

} // namespace

class UdongeModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        cloak_ = false;
        spoofGms_ = false;
        isGmsUnstable_ = false;
        isVending_ = false;
        std::string pkg = jstr(args->nice_name);
        if (pkg.empty()) { dontUnload_ = false; return; }

        bool isGms = pkg.rfind("com.google.android.gms", 0) == 0;
        isGmsUnstable_ = (pkg == "com.google.android.gms.unstable");
        isVending_ = (pkg == "com.android.vending");
        if (!isGms && !isVending_ && !is_candidate(pkg)) {
            dontUnload_ = false;
            return;
        }
        if (!fetch_config(isGmsUnstable_)) { dontUnload_ = false; return; }

        if (isGms && !cfg_.gms_build.empty()) {
            spoofGms_ = true;
        }

        // Cloaking (PLT file/path hooks + denylist unmount):
        // - For GMS: ONLY apply to .unstable (DroidGuard process).
        //   Other GMS sub-processes (persistent/chimera/ui/…) have hundreds of
        //   native libs; full PLT patching destabilises them → GPS/Play crashes.
        // - For all other apps: normal shouldCloak() logic.
        bool shouldCloak = isGms ? (isGmsUnstable_ && cfg_.shouldCloak(pkg))
                                 : cfg_.shouldCloak(pkg);
        bool shouldStealth = !isGms && cfg_.shouldStealth(pkg);

        if (isGmsUnstable_) {
            // gms.unstable (DroidGuard): spoof Build + load PIF DEX (self-contained).
            // dontUnload_=false → DLCLOSE_MODULE_LIBRARY after postAppSpecialize →
            // no pthread_create hook → libzygisk.so destructor doesn't crash.
            // We do NOT install PLT filesystem hooks here (would dangle after dlclose).
            cloak_ = false;
            dontUnload_ = false;
            LOGD("pif gms.unstable");
        } else if (isVending_) {
            // android.vending (Play Store): passthrough for same reason as gms.unstable.
            // On this ROM any module that stays loaded (dontUnload_=true) causes Zygisk
            // to install a pthread_create cleanup hook; when JIT initializes ~500ms in,
            // that hook fires → dlclose(libzygisk.so) → buggy destructor at 0x569a8 →
            // SIGSEGV. Shamiko + Magisk denylist namespace-unmount handle root hiding.
            cloak_ = false;
            dontUnload_ = false;
            LOGD("passthrough com.android.vending");
        } else if (shouldStealth) {
            // Stealth: inject to apply FORCE_DENYLIST_UNMOUNT (hides /product/bin/su),
            // but DLCLOSE immediately after postAppSpecialize so our .so is unmapped
            // from /proc/self/maps before libgp.so's JNI_OnLoad runs.
            // No PLT hooks installed — libgp.so's GOT stays clean.
            dontUnload_ = false;  // → DLCLOSE_MODULE_LIBRARY in postAppSpecialize
            cloak_ = false;
            api_->setOption(zygisk::FORCE_DENYLIST_UNMOUNT);
            LOGD("stealth %s", pkg.c_str());
        } else if (shouldCloak) {
            cloak_ = true;
            dontUnload_ = true;
            // Magisk unmounts its overlay bind mounts (incl. /product/bin/su)
            // from this process's isolated namespace. Happens between pre and post
            // AppSpecialize — exactly when unshare(CLONE_NEWNS) creates the isolation.
            api_->setOption(zygisk::FORCE_DENYLIST_UNMOUNT);
            LOGD("%s %s", spoofGms_ ? "certify+cloak" : "cloaking", pkg.c_str());
        } else if (spoofGms_) {
            // Non-unstable GMS: Build spoof only — no hooks, no denylist unmount.
            dontUnload_ = true;
            LOGD("spoof-only %s", pkg.c_str());
        }
    }

    void postAppSpecialize(const AppSpecializeArgs *) override {
        if (isGmsUnstable_) {
            // Self-contained PIF: spoof Build fields + load DEX keystore hook.
            // No filesystem PLT hooks (would dangle after DLCLOSE_MODULE_LIBRARY).
            // We do NOT use FORCE_DENYLIST_UNMOUNT so spoof_build() is safe here.
            if (!cfg_.gms_build.empty()) {
                cloak::spoof_build(env_, cfg_);
            }
            if (!dexPath_.empty()) {
                cloak::load_dex(env_, dexPath_, cfg_.pif_json());
            }
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (spoofGms_ && !isGmsUnstable_) {
            cloak::spoof_build(env_, cfg_);
        }
        if (cloak_) {
            // FORCE_DENYLIST_UNMOUNT (set in preAppSpecialize) already asked Magisk
            // to unmount its overlays. Install PLT hooks to catch any remaining
            // libc-routed accesses that survived the namespace cleanup.
            cloak::install_hooks(api_, &cfg_);
            if (!spoofGms_) {
                // Build.DISPLAY is a cached Java field — not reachable via property hooks.
                // Spoof it for all cloaked non-GMS apps so "lineage_enchilada-user..."
                // doesn't leak. Only touches DISPLAY; leaves MODEL/BRAND/etc. alone
                // to avoid breaking payment apps that verify registered device identity.
                cloak::spoof_display(env_, cfg_);
            }
        }
        if (!dontUnload_) {
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        }
    }

private:
    Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    cloak::Config cfg_;     // lives for the process; hooks hold a pointer to it
    std::string dexPath_;   // path to classes.dex (from companion)
    bool cloak_ = false;
    bool spoofGms_ = false;
    bool isGmsUnstable_ = false;
    bool isVending_ = false;
    bool dontUnload_ = false;

    std::string jstr(jstring s) {
        if (!s) return "";
        const char *c = env_->GetStringUTFChars(s, nullptr);
        std::string r = c ? c : "";
        if (c) env_->ReleaseStringUTFChars(s, c);
        return r;
    }

    // Ask the root companion for the config files and parse them.
    bool fetch_config(bool include_dex) {
        int fd = api_->connectCompanion();
        if (fd < 0) { LOGE("companion connect failed"); return false; }
        uint8_t req = include_dex ? 2 : 1;
        std::string targets, props, pif, dex_path;
        bool ok = xwrite(fd, &req, 1) &&
                  read_str(fd, targets) &&
                  read_str(fd, props) &&
                  read_str(fd, pif) &&
                  read_str(fd, dex_path);
        close(fd);
        if (!ok) { LOGE("companion read failed"); return false; }
        cfg_ = cloak::parse_config(targets, props, pif);
        dexPath_ = dex_path;
        return true;
    }
};

// ---- root companion: serves the config files to app processes ----
static const char *RUNTIME_DIR = UDONGE_ROOT "/runtime";

static std::string find_dex_path() {
    // After FORCE_DENYLIST_UNMOUNT, /data/adb/modules/ is hidden from the app
    // process. Copy the DEX to /data/local/tmp where it remains visible.
    std::string src = std::string(RUNTIME_DIR) + "/classes.dex";
    std::string dst = "/data/local/tmp/udonge_classes.dex";
    struct stat st;
    if (stat(src.c_str(), &st) != 0) return "";
    // Always copy (module update may have changed the DEX)
    std::string data = cloak::read_file(src);
    if (data.empty()) return "";
    FILE *f = fopen(dst.c_str(), "we");
    if (!f) return "";
    fwrite(data.data(), 1, data.size(), f);
    fclose(f);
    chmod(dst.c_str(), 0644);
    return dst;
}

static void companion_handler(int client) {
    uint8_t req = 0;
    if (!xread(client, &req, 1)) return;

    std::string targets  = cloak::read_file(std::string(CONF_DIR) + "/targets.conf");
    std::string props    = cloak::read_file(std::string(CONF_DIR) + "/props.conf");
    std::string pif      = cloak::read_file(std::string(CONF_DIR) + "/pif.conf");
    std::string dex_path = req == 2 ? find_dex_path() : "";
    write_str(client, targets);
    write_str(client, props);
    write_str(client, pif);
    write_str(client, dex_path);
}

REGISTER_ZYGISK_MODULE(UdongeModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
