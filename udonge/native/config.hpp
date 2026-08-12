#pragma once
#include <string>
#include <unordered_set>
#include <unordered_map>

namespace cloak {

// Parsed /data/adb/udonge/state/*.conf
struct Config {
    enum Mode { WHITELIST, BLACKLIST };
    Mode mode = WHITELIST;
    std::unordered_set<std::string> packages;         // targets.conf list
    std::unordered_set<std::string> stealth_packages; // targets.conf stealth: entries
    std::unordered_map<std::string, std::string> props;      // props.conf overrides
    std::unordered_map<std::string, std::string> gms_build;  // pif.conf -> Build fields

    bool shouldCloak(const std::string &pkg) const {
        bool listed = packages.count(pkg) != 0;
        return mode == WHITELIST ? listed : !listed;
    }

    // Stealth: inject for FORCE_DENYLIST_UNMOUNT but DLCLOSE before native libs load.
    bool shouldStealth(const std::string &pkg) const {
        return stealth_packages.count(pkg) != 0;
    }

    // Serialize gms_build to a simple JSON string for the DEX entry point.
    std::string pif_json() const {
        std::string j = "{";
        bool first = true;
        for (const auto &kv : gms_build) {
            if (!first) j += ",";
            j += "\"" + kv.first + "\":\"" + kv.second + "\"";
            first = false;
        }
        j += "}";
        return j;
    }
};

// Parse config from the raw text of the three files (companion sends these to
// the app process, which cannot read /data/adb itself). Never throws.
Config parse_config(const std::string &targets_text, const std::string &props_text,
                    const std::string &pif_text);

// Read a whole file into a string ("" on failure). Used by the root companion.
std::string read_file(const std::string &path);

} // namespace cloak
