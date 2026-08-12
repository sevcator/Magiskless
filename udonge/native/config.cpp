#include "config.hpp"

#include <cstdio>
#include <string>

namespace cloak {

static std::string trim(const std::string &s) {
    size_t a = s.find_first_not_of(" \t\r\n");
    if (a == std::string::npos) return "";
    size_t b = s.find_last_not_of(" \t\r\n");
    return s.substr(a, b - a + 1);
}

// Read a whole file into a string. Returns "" on failure.
std::string read_file(const std::string &path) {
    FILE *f = fopen(path.c_str(), "re");
    if (!f) return "";
    std::string out;
    char buf[4096];
    size_t n;
    while ((n = fread(buf, 1, sizeof buf, f)) > 0) out.append(buf, n);
    fclose(f);
    return out;
}

// Iterate lines, skipping blanks and '#' comments; call fn(trimmed_line).
template <class F>
static void for_each_line(const std::string &text, F fn) {
    size_t i = 0;
    while (i < text.size()) {
        size_t nl = text.find('\n', i);
        std::string line = text.substr(i, nl == std::string::npos ? std::string::npos : nl - i);
        i = (nl == std::string::npos) ? text.size() : nl + 1;
        line = trim(line);
        if (line.empty() || line[0] == '#') continue;
        fn(line);
    }
}

Config parse_config(const std::string &targets_text, const std::string &props_text,
                    const std::string &pif_text) {
    Config cfg;

    for_each_line(targets_text, [&](const std::string &line) {
        size_t eq = line.find('=');
        if (eq != std::string::npos && trim(line.substr(0, eq)) == "mode") {
            std::string v = trim(line.substr(eq + 1));
            cfg.mode = (v == "blacklist") ? Config::BLACKLIST : Config::WHITELIST;
        } else if (line.rfind("stealth:", 0) == 0) {
            cfg.stealth_packages.insert(line.substr(8));
        } else {
            cfg.packages.insert(line);   // a bare package name
        }
    });

    for_each_line(props_text, [&](const std::string &line) {
        size_t eq = line.find('=');
        if (eq == std::string::npos) return;
        std::string k = trim(line.substr(0, eq));
        std::string v = trim(line.substr(eq + 1));
        if (!k.empty()) cfg.props[k] = v;
    });

    // pif.conf: Build field name = value (FINGERPRINT, MODEL, SECURITY_PATCH, ...)
    for_each_line(pif_text, [&](const std::string &line) {
        size_t eq = line.find('=');
        if (eq == std::string::npos) return;
        std::string k = trim(line.substr(0, eq));
        std::string v = trim(line.substr(eq + 1));
        if (!k.empty()) cfg.gms_build[k] = v;
    });

    return cfg;
}

} // namespace cloak
