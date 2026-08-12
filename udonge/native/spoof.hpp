#pragma once
#include <jni.h>
#include "config.hpp"
#include <string>

namespace cloak {

// Overwrite android.os.Build / Build.VERSION fields in the current process with
// the certified-device values from cfg.gms_build (via JNI SetStaticObjectField).
void spoof_build(JNIEnv *env, const Config &cfg);

// Overwrite only Build.DISPLAY using the DISPLAY (or ID) value from cfg.gms_build.
// Targeted variant for cloaked non-GMS apps — avoids changing MODEL/BRAND/etc.
// which could break payment apps that validate device identity.
void spoof_display(JNIEnv *env, const Config &cfg);

// Load the DEX hook (classes.dex) into the GMS process and call
// EntryPoint.init() to install the KeyStore attestation intercept.
// This blocks hardware attestation chains from reaching DroidGuard,
// forcing a software/basic evaluation path for Play Integrity.
void load_dex(JNIEnv *env, const std::string &dex_path, const std::string &pif_json);

} // namespace cloak
