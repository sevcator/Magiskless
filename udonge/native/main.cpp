#include <sys/types.h>
#include "zygisk.hpp"
#include "config.hpp"
#include "hooks.hpp"
#include "spoof.hpp"

#include <cerrno>
#include <cstdint>
#include <string>
#include <sys/socket.h>
#include <unistd.h>

using zygisk::Api;
using zygisk::AppSpecializeArgs;

namespace {

bool xwrite(int fd, const void *buf, size_t len) {
    auto *cursor = static_cast<const uint8_t *>(buf);
    while (len) {
        ssize_t count = write(fd, cursor, len);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        cursor += count;
        len -= static_cast<size_t>(count);
    }
    return true;
}

bool xread(int fd, void *buf, size_t len) {
    auto *cursor = static_cast<uint8_t *>(buf);
    while (len) {
        ssize_t count = read(fd, cursor, len);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return false;
        cursor += count;
        len -= static_cast<size_t>(count);
    }
    return true;
}

bool write_str(int fd, const std::string &value) {
    uint32_t size = static_cast<uint32_t>(value.size());
    return xwrite(fd, &size, sizeof(size)) && xwrite(fd, value.data(), size);
}

bool read_str(int fd, std::string &value) {
    uint32_t size = 0;
    if (!xread(fd, &size, sizeof(size)) || size > (16u << 20)) return false;
    value.resize(size);
    return size == 0 || xread(fd, value.data(), size);
}

#ifndef UDONGE_ROOT
#define UDONGE_ROOT "/data/adb/udonge"
#endif

const char *CONF_DIR = UDONGE_ROOT "/state";
std::string base_package(const std::string &process_name) {
    size_t separator = process_name.find(':');
    return process_name.substr(0, separator);
}

bool is_candidate(const std::string &package_name) {
    const std::string name = base_package(package_name);
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
        if (name == candidate) return true;
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
        is_gms_unstable_ = false;
        keep_loaded_ = false;

        std::string package_name = jstr(args->nice_name);
        if (package_name.empty()) return;
        std::string package = base_package(package_name);
        is_gms_unstable_ = package_name == "com.google.android.gms.unstable";
        if (!is_gms_unstable_ && !is_candidate(package_name)) return;
        if (!fetch_config(package)) return;

        if (is_gms_unstable_) return;
        if (cfg_.shouldStealth(package)) {
            api_->setOption(zygisk::FORCE_DENYLIST_UNMOUNT);
            return;
        }
        if (cfg_.shouldCloak(package)) {
            cloak_ = true;
            keep_loaded_ = true;
            api_->setOption(zygisk::FORCE_DENYLIST_UNMOUNT);
        }
    }

    void postAppSpecialize(const AppSpecializeArgs *) override {
        if (is_gms_unstable_) {
            cloak::spoof_build(env_, cfg_);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (cloak_) {
            cloak::install_hooks(api_, &cfg_);
            cloak::spoof_display(env_, cfg_);
        }
        if (!keep_loaded_) api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    cloak::Config cfg_;
    bool cloak_ = false;
    bool is_gms_unstable_ = false;
    bool keep_loaded_ = false;

    std::string jstr(jstring value) {
        if (!value) return {};
        const char *chars = env_->GetStringUTFChars(value, nullptr);
        std::string result = chars ? chars : "";
        if (chars) env_->ReleaseStringUTFChars(value, chars);
        return result;
    }

    bool fetch_config(const std::string &package) {
        int fd = api_->connectCompanion();
        std::string targets;
        std::string props;
        std::string pif;
        if (fd >= 0) {
            uint8_t request = 1;
            bool ok = xwrite(fd, &request, 1)
                && read_str(fd, targets)
                && read_str(fd, props)
                && read_str(fd, pif);
            close(fd);
            if (!ok) {
                targets.clear();
                props.clear();
                pif.clear();
            }
        }
        if (targets.empty()) {
            targets = cloak::read_file(std::string(CONF_DIR) + "/targets.conf");
            props = cloak::read_file(std::string(CONF_DIR) + "/props.conf");
            pif = cloak::read_file(std::string(CONF_DIR) + "/pif.conf");
        }
        cfg_ = cloak::parse_config(targets, props, pif);
        if (is_gms_unstable_) return !cfg_.gms_build.empty();
        if (!cfg_.shouldCloak(package) && !cfg_.shouldStealth(package)) {
            cfg_.packages.insert(package);
        }
        return true;
    }
};

static void companion_handler(int client) {
    uint8_t request = 0;
    if (!xread(client, &request, 1)) return;
    std::string targets = cloak::read_file(std::string(CONF_DIR) + "/targets.conf");
    std::string props = cloak::read_file(std::string(CONF_DIR) + "/props.conf");
    std::string pif = cloak::read_file(std::string(CONF_DIR) + "/pif.conf");
    write_str(client, targets);
    write_str(client, props);
    write_str(client, pif);
}

REGISTER_ZYGISK_MODULE(UdongeModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
