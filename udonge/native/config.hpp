#pragma once
#include <string>
#include <unordered_set>
#include <unordered_map>
#include <vector>

namespace cloak {

// Parsed /data/adb/udonge/state/*.conf
struct Config {
    std::unordered_set<std::string> packages;
    std::unordered_set<std::string> stealth_packages;
    std::unordered_map<std::string, std::string> props;
    std::unordered_map<std::string, std::string> gms_build;
    // ROM keywords from rom_keywords.conf (e.g. "lineage", "protonaosp").
    // Used to dynamically block ROM-specific file paths and properties.
    std::vector<std::string> rom_keywords;

    bool shouldCloak(const std::string &pkg) const {
        return packages.count(pkg) != 0;
    }

    // Stealth: inject for FORCE_DENYLIST_UNMOUNT but DLCLOSE before native libs load.
    bool shouldStealth(const std::string &pkg) const {
        return stealth_packages.count(pkg) != 0;
    }

};

// Parse config from the raw text of the four state files (companion sends these to
// the app process, which cannot read /data/adb itself). Never throws.
Config parse_config(const std::string &targets_text, const std::string &props_text,
                    const std::string &pif_text, const std::string &rom_keywords_text);

// Read a whole file into a string ("" on failure). Used by the root companion.
std::string read_file(const std::string &path);

} // namespace cloak
