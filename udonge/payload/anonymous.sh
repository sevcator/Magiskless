#!/system/bin/sh
# ============================================================
#  Reisenless — anonymous mode engine
#  Commands: enable | disable | boot-fw | boot | randomize | fw-start | fw-stop | status
#
#  What it does when enabled:
#    - Cuts the device off from Google (iptables/ip6tables, by app UID
#      + Google-owned CIDRs — NOT via DNS, NOT via hosts)
#    - Re-randomizes the whole device identity on EVERY boot:
#      fingerprint / Build fields (via udonge pif.conf + props.conf),
#      serial + IMEI props (global resetprop + per-app), Android ID
#    - Downloads the real-device database from GitHub (TheFreeman193/PIFS
#      collection zip), caches it, falls back to the bundled snapshot
#    - Clears target-app data so no cached static IDs (Firebase IID,
#      advertising ID, cached device fingerprint) survive a rotation
# ============================================================
umask 077
root=/data/adb/udonge
runtime=$root/runtime
state=$root/state

FLAG="$state/anonymous"
CLEAR_KNOB="$state/anonymous_clear_data"
CHAIN="rs_anon"
LOG="$state/anonymous.log"
LOCK="$state/.anonymous.lock"

DB_URL="https://codeload.github.com/TheFreeman193/PIFS/zip/refs/heads/main"
DB_ZIP="$state/pifs_collection.zip"
DB_DIR="$state/pifs_json"               # extracted JSON/ tree
DB_TS="$state/device_db.ts"
DB_MAX_AGE=1209600                      # refresh the cache every 14 days
BUNDLED_DB="$runtime/defaults/device_db.pifs"

# canonical candidate line (same 13 fields as the bundled database):
# brand|manufacturer|model|product|device|fingerprint|release|id|incremental|type|tags|security_patch|first_api

log() { echo "[anonymous] $(date '+%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null; }

# ---------- randomness ----------
rand_hex() { od -An -tx1 -N"$1" /dev/urandom 2>/dev/null | tr -d ' \n'; }
gen_serial() { rand_hex 8 | tr 'a-f' 'A-F'; }
gen_android_id() { rand_hex 8; }

luhn_digit() {  # luhn_digit <14-digit base> -> GSMA check digit
    local base="$1" sum=0 i=0 len pos d
    len=${#base}
    i=$len
    while [ "$i" -ge 1 ]; do
        d="${base:$((i-1)):1}"
        pos=$(( len - i + 1 ))
        if [ $(( pos % 2 )) -eq 1 ]; then
            d=$(( d * 2 ))
            [ "$d" -gt 9 ] && d=$(( d - 9 ))
        fi
        sum=$(( sum + d ))
        i=$(( i - 1 ))
    done
    echo $(( (10 - (sum % 10)) % 10 ))
}

gen_imei() {  # Luhn-valid IMEI, real 35-xxxxxx TAC allocation
    local tac="35" serial="" base cd i=0
    while [ "$i" -lt 6 ]; do tac="${tac}$((RANDOM % 10))"; i=$((i+1)); done
    i=0
    while [ "$i" -lt 6 ]; do serial="${serial}$((RANDOM % 10))"; i=$((i+1)); done
    base="${tac}${serial}"
    cd=$(luhn_digit "$base")
    echo "${base}${cd}"
}

# ---------- firewall: cut Google off (iptables, not DNS) ----------
# Google app UIDs are resolved live from the package manager, so new
# Google packages are covered automatically. The CIDR list only covers
# ranges unambiguously owned by Google services (search/GMS/core) —
# GCP cloud ranges are deliberately NOT blocked so that third-party
# apps hosted on Google Cloud keep working.
GOOGLE_V4="8.8.4.0/24 8.8.8.0/24 8.34.208.0/20 8.35.192.0/20 \
23.236.48.0/20 23.251.128.0/19 64.15.112.0/20 64.233.160.0/19 \
66.102.0.0/20 66.249.64.0/19 70.32.128.0/19 72.14.192.0/18 \
74.125.0.0/16 108.177.0.0/17 142.250.0.0/15 142.251.0.0/16 \
172.217.0.0/16 172.253.0.0/16 173.194.0.0/16 192.178.0.0/15 \
193.186.4.0/24 209.85.128.0/17 216.58.192.0/19 216.239.32.0/19"
GOOGLE_V6="2001:4860::/32 2404:6800::/32 2607:f8b0::/32 2800:3f0::/32 \
2a00:1450::/32 2c0f:fb50::/32"

google_uids() {
    pm list packages -U 2>/dev/null | \
        grep -E 'package:(com\.google\.|com\.android\.vending|com\.android\.gms)' | \
        sed -n 's/.*uid:\([0-9][0-9]*\)$/\1/p' | sort -un
}

fw_stop() {
    local t
    for t in iptables ip6tables; do
        $t -D OUTPUT -j "$CHAIN" >/dev/null 2>&1
        $t -F "$CHAIN"    >/dev/null 2>&1
        $t -X "$CHAIN"    >/dev/null 2>&1
    done
}

fw_cidr_rules() {
    local cidr t
    for cidr in $GOOGLE_V4; do
        iptables -A "$CHAIN" -d "$cidr" -j REJECT >/dev/null 2>&1
    done
    for cidr in $GOOGLE_V6; do
        ip6tables -A "$CHAIN" -d "$cidr" -j REJECT >/dev/null 2>&1
    done
    # block DoT/DoH toward Google DNS specifically
    for t in iptables ip6tables; do
        $t -A "$CHAIN" -p tcp --dport 853 -d 8.8.8.8 -j REJECT >/dev/null 2>&1
        $t -A "$CHAIN" -p tcp --dport 853 -d 8.8.4.4 -j REJECT >/dev/null 2>&1
        $t -A "$CHAIN" -p tcp --dport 443 -d 8.8.8.8 -j REJECT >/dev/null 2>&1
        $t -A "$CHAIN" -p tcp --dport 443 -d 8.8.4.4 -j REJECT >/dev/null 2>&1
    done
}

fw_uid_rules() {
    local uid
    for uid in $(google_uids); do
        iptables  -A "$CHAIN" -m owner --uid-owner "$uid" -j REJECT >/dev/null 2>&1
        ip6tables -A "$CHAIN" -m owner --uid-owner "$uid" -j REJECT >/dev/null 2>&1
    done
}

fw_hook() {
    iptables  -I OUTPUT -j "$CHAIN" >/dev/null 2>&1
    ip6tables -I OUTPUT -j "$CHAIN" >/dev/null 2>&1
}

fw_newchain() {
    local t
    fw_stop
    for t in iptables ip6tables; do $t -N "$CHAIN" >/dev/null 2>&1; done
}

# full firewall: uid rules + cidr rules (requires the package manager up)
fw_start() {
    fw_newchain
    fw_uid_rules
    fw_cidr_rules
    fw_hook
    log "firewall up (uids: $(google_uids | tr '\n' ' '))"
}

# early firewall for post-fs-data: cidr only — the package manager is
# not up yet at that stage, so no uid resolution happens here
fw_start_early() {
    fw_newchain
    fw_cidr_rules
    fw_hook
    log "early firewall up (cidr only)"
}

# ---------- device database (PIFS collection zip) ----------
db_refresh() {  # download the collection unless the cache is fresh
    local now age size
    now=$(date +%s 2>/dev/null)
    age=$(cat "$DB_TS" 2>/dev/null)
    if [ -d "$DB_DIR/JSON" ] && [ -n "$now" ] && [ -n "$age" ]; then
        [ $((now - age)) -lt "$DB_MAX_AGE" ] && return 0
    fi
    if wget -q -T 60 -O "$DB_ZIP.new" "$DB_URL" 2>/dev/null; then
        size=$(wc -c < "$DB_ZIP.new" 2>/dev/null)
        if [ -n "$size" ] && [ "$size" -gt 1048576 ]; then
            rm -rf "${DB_DIR}.new"
            mkdir -p "${DB_DIR}.new"
            if unzip -q "$DB_ZIP.new" 'JSON/*' -d "${DB_DIR}.new" 2>/dev/null && \
               [ -n "$(find "${DB_DIR}.new" -type f -name '*.json' 2>/dev/null | head -n 1)" ]; then
                rm -rf "$DB_DIR"
                mv -f "${DB_DIR}.new" "$DB_DIR"
                mv -f "$DB_ZIP.new" "$DB_ZIP"
                [ -n "$now" ] && echo "$now" > "$DB_TS"
                log "pifs collection downloaded ($size bytes)"
                return 0
            fi
            rm -rf "${DB_DIR}.new"
        fi
    fi
    rm -f "$DB_ZIP.new"
    log "pifs download failed, using cache/bundled"
    return 1
}

# Convert pif-style per-device JSON files into the canonical 13-field
# candidate lines. One awk pass over batches of files; FNR==1 marks a
# new file, starred prop keys are ignored, missing fields are derived
# from the fingerprint itself.
pifs_to_candidates() {  # pifs_to_candidates <dir>...
    find "$@" -type f -name '*.json' -exec awk '
        FNR == 1 {
            if (fp != "") flush()
            fp = br = manu = mo = pr = de = idv = incv = ty = tg = sp = ""
        }
        {
            line = $0
            while (match(line, /"[A-Za-z_*][A-Za-z0-9_.]*"[ \t]*:[ \t]*"[^"]*"/)) {
                kv = substr(line, RSTART, RLENGTH)
                line = substr(line, RSTART + RLENGTH)
                if (match(kv, /^"[^"]*"/)) {
                    k = substr(kv, 2, RLENGTH - 2)
                    rest = substr(kv, RSTART + RLENGTH)
                    if (match(rest, /"[^"]*"$/)) {
                        v = substr(rest, RSTART + 1, RLENGTH - 2)
                        store(k, v)
                    }
                }
            }
        }
        END { if (fp != "") flush() }
        function store(k, v) {
            if (k == "FINGERPRINT") fp = v
            else if (k == "BRAND") br = v
            else if (k == "MANUFACTURER") manu = v
            else if (k == "MODEL") mo = v
            else if (k == "PRODUCT") pr = v
            else if (k == "DEVICE") de = v
            else if (k == "ID" || k == "BUILD_ID") { if (idv == "") idv = v }
            else if (k == "INCREMENTAL") incv = v
            else if (k == "TYPE") ty = v
            else if (k == "TAGS") tg = v
            else if (k == "SECURITY_PATCH") sp = v
        }
        function flush() {
            if (fp !~ /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+:[0-9]/) return
            split(fp, a, ":"); split(a[1], b, "/"); split(a[2], c, "/")
            if (br == "") br = b[1]
            if (pr == "") pr = b[2]
            if (de == "") de = b[3]
            if (mo == "") mo = de
            if (manu == "") manu = br
            print br "|" manu "|" mo "|" pr "|" de "|" fp "|" c[1] "|" idv "|" incv "|" ty "|" tg "|" sp "|"
        }
    ' {} + 2>/dev/null
}

build_candidates() {  # emit all candidate lines into $state/.anon_candidates
    local abilist d
    : > "$state/.anon_candidates"

    # 1) bundled snapshot (offline-safe, always present)
    [ -s "$BUNDLED_DB" ] && grep -v '^#' "$BUNDLED_DB" >> "$state/.anon_candidates" 2>/dev/null

    # 2) live PIFS collection if downloaded; ABI-matched dir preferred so
    #    SUPPORTED_ABIS never contradicts the spoofed profile
    if [ -d "$DB_DIR/JSON" ]; then
        abilist=$(getprop ro.product.cpu.abilist 2>/dev/null)
        if [ -n "$abilist" ] && [ -d "$DB_DIR/JSON/$abilist" ]; then
            pifs_to_candidates "$DB_DIR/JSON/$abilist" >> "$state/.anon_candidates"
        else
            for d in $(find "$DB_DIR/JSON" -mindepth 1 -maxdepth 1 -type d -name 'arm64*' 2>/dev/null | head -n 2); do
                pifs_to_candidates "$d" >> "$state/.anon_candidates"
            done
        fi
    fi

    # dedupe by fingerprint
    awk -F'|' '!seen[$6]++' "$state/.anon_candidates" > "$state/.anon_uniq" 2>/dev/null
    [ -s "$state/.anon_uniq" ] && mv -f "$state/.anon_uniq" "$state/.anon_candidates"
    rm -f "$state/.anon_uniq"
}

# ---------- pick a profile and rewrite the identity ----------
# Selection prefers profiles whose release matches the real one so the
# fingerprint never contradicts the (unspoofed) Build.VERSION.
sdk_to_release() {
    case "$1" in
        29) echo "10";; 30) echo "11";; 31) echo "12";; 32) echo "12.1";;
        33) echo "13";;  34) echo "14";; 35) echo "15";; 36) echo "16";;
        *)  echo "";;
    esac
}

randomize_identity() {
    local sdk rel total pick fp brand manu model prod dev id inc typ tags sp_val
    sdk=$(getprop ro.build.version.sdk 2>/dev/null)
    rel=$(sdk_to_release "$sdk")

    # capture the device's real fingerprint once (resetprop does not
    # survive reboots, so at this point of every boot props are still
    # real) — the picker must never re-select the real device
    if [ ! -f "$state/anonymous_real_fp" ]; then
        getprop ro.build.fingerprint > "$state/anonymous_real_fp" 2>/dev/null
    fi
    local realfp
    realfp=$(cat "$state/anonymous_real_fp" 2>/dev/null)

    build_candidates
    total=$(grep -c . "$state/.anon_candidates" 2>/dev/null)
    if [ -z "$total" ] || [ "$total" -lt 1 ]; then
        log "no candidate profiles — keeping previous identity"
        echo "!! no candidate profiles found — keeping previous identity"
        rm -f "$state/.anon_candidates"
        return 1
    fi

    # filter by matching release when possible
    if [ -n "$rel" ]; then
        awk -F'|' -v r="$rel" '$7 == r' "$state/.anon_candidates" > "$state/.anon_filtered" 2>/dev/null
        [ -s "$state/.anon_filtered" ] && mv -f "$state/.anon_filtered" "$state/.anon_candidates"
        rm -f "$state/.anon_filtered"
        total=$(grep -c . "$state/.anon_candidates" 2>/dev/null)
    fi

    # pick a random candidate, avoiding the real and previous fingerprints
    local last tries=0 line_no
    last=$(cat "$state/anonymous_last_fp" 2>/dev/null)
    while :; do
        line_no=$(( (RANDOM % total) + 1 ))
        pick=$(sed -n "${line_no}p" "$state/.anon_candidates")
        tries=$((tries+1))
        [ -n "$pick" ] || { [ "$tries" -gt 10 ] && break; continue; }
        fp=$(printf '%s' "$pick" | cut -d'|' -f6)
        { [ "$fp" != "$last" ] && [ "$fp" != "$realfp" ]; } && break
        [ "$tries" -gt 10 ] && break
    done
    rm -f "$state/.anon_candidates"
    [ -n "$fp" ] || { log "profile pick failed"; echo "!! profile pick failed"; return 1; }

    brand=$(printf '%s' "$pick" | cut -d'|' -f1)
    manu=$(printf  '%s' "$pick" | cut -d'|' -f2)
    model=$(printf '%s' "$pick" | cut -d'|' -f3)
    prod=$(printf  '%s' "$pick" | cut -d'|' -f4)
    dev=$(printf   '%s' "$pick" | cut -d'|' -f5)
    id=$(printf    '%s' "$pick" | cut -d'|' -f8)
    inc=$(printf   '%s' "$pick" | cut -d'|' -f9)
    typ=$(printf   '%s' "$pick" | cut -d'|' -f10)
    tags=$(printf  '%s' "$pick" | cut -d'|' -f11)
    sp_val=$(printf '%s' "$pick" | cut -d'|' -f12)

    # security patch: derive from the build id date when missing
    # (BP1A.250405.007 -> 2025-04-05)
    if [ -z "$sp_val" ]; then
        local d yy mm dd
        d=$(printf '%s' "$id" | grep -oE '[0-9]{6}' 2>/dev/null | head -n1)
        if [ -n "$d" ]; then
            yy=${d%????}; mm=${d#??}; mm=${mm%??}; dd=${d#????}
            sp_val="20${yy}-${mm}-${dd}"
        fi
    fi

    local ser im1 im2 aid
    ser=$(gen_serial); im1=$(gen_imei); im2=$(gen_imei); aid=$(gen_android_id)

    # ---- udonge per-app spoof (Build fields for cloak targets + GMS) ----
    {
        echo "FINGERPRINT=$fp"
        echo "BRAND=$brand"
        echo "MANUFACTURER=$manu"
        echo "MODEL=$model"
        echo "PRODUCT=$prod"
        echo "DEVICE=$dev"
        echo "ID=$id"
        echo "INCREMENTAL=$inc"
        echo "TYPE=${typ:-user}"
        echo "TAGS=${tags:-release-keys}"
        [ -n "$sp_val" ] && echo "SECURITY_PATCH=$sp_val"
    } > "$state/pif.conf"

    # ---- per-app prop overrides (SystemProperties hook reads this) ----
    {
        echo "ro.serialno=$ser"
        echo "ro.boot.serialno=$ser"
        echo "ro.boot.bootserialno=$ser"
        echo "persist.radio.serialno=$ser"
        echo "persist.sys.serialno=$ser"
        echo "persist.sys.zerotouch_serialno=$ser"
        echo "ro.ril.oem.imei=$im1"
        echo "ro.ril.oem.imei2=$im2"
        echo "ro.ril.oem.sno=$ser"
        echo "ro.ril.miui.imei0=$im1"
        echo "ro.ril.miui.imei1=$im2"
        echo "persist.radio.imei=$im1"
        echo "persist.radio.imei2=$im2"
        echo "ro.gsm.imei=$im1"
        echo "ro.boot.imei=$im1"
        echo "ro.boot.imei2=$im2"
        echo "ro.debuggable=0"
        echo "ro.secure=1"
        echo "ro.kernel.qemu=0"
    } > "$state/props.conf"

    # ---- global layer: serial/imei system-wide + fresh Android ID ----
    if command -v resetprop >/dev/null 2>&1; then
        resetprop -n ro.serialno "$ser"
        resetprop -n ro.boot.serialno "$ser"
        resetprop -n ro.boot.bootserialno "$ser"
        resetprop -n persist.radio.serialno "$ser"
        resetprop -n persist.sys.serialno "$ser"
        resetprop -n ro.ril.oem.imei "$im1"
        resetprop -n ro.ril.oem.imei2 "$im2"
        resetprop -n persist.radio.imei "$im1"
        resetprop -n ro.debuggable 0
        resetprop -n ro.secure 1
    fi
    command -v settings >/dev/null 2>&1 && settings put secure android_id "$aid" 2>/dev/null

    echo "$fp" > "$state/anonymous_last_fp"

    # ---- close static values: wipe cached IDs inside target apps ----
    if [ -f "$CLEAR_KNOB" ] && [ -s "$state/targets.conf" ]; then
        grep -v '^#' "$state/targets.conf" 2>/dev/null | grep -v '^stealth:' | \
        while IFS= read -r pkg; do
            [ -n "$pkg" ] || continue
            am force-stop "$pkg" >/dev/null 2>&1
            pm clear --user 0 "$pkg" >/dev/null 2>&1
        done
        log "target app data cleared"
    fi

    # GMS holds the old fingerprint; restart it so nothing stale leaks
    am force-stop com.google.android.gms >/dev/null 2>&1

    log "identity randomized: $manu $model serial=$ser android_id=$aid"
    echo "identity randomized: $manu $model"
    echo "  fingerprint : $fp"
    echo "  serial/imei : $ser / $im1"
    echo "  android_id  : $aid"
    return 0
}

# ---------- boot worker ----------
anon_boot_bg() {
    fw_start
    db_refresh
    randomize_identity
    rmdir "$LOCK" 2>/dev/null
}

cmd_boot() {
    [ -f "$FLAG" ] || exit 0
    if mkdir "$LOCK" 2>/dev/null; then
        anon_boot_bg &
        echo $! > "$LOCK/pid"
    else
        # stale lock from a killed boot run? verify the owner still lives
        local opid
        opid=$(cat "$LOCK/pid" 2>/dev/null)
        if [ -n "$opid" ] && ! kill -0 "$opid" 2>/dev/null; then
            rm -rf "$LOCK"
            if mkdir "$LOCK" 2>/dev/null; then
                anon_boot_bg &
                echo $! > "$LOCK/pid"
            fi
        fi
    fi
}

# ---------- commands ----------
cmd_status() {
    if [ -f "$FLAG" ]; then
        echo "anonymous mode: ENABLED"
        iptables -nL "$CHAIN" >/dev/null 2>&1 && echo "firewall: UP ($(iptables -nL "$CHAIN" 2>/dev/null | grep -c .) rules)" || echo "firewall: DOWN"
        echo "last fingerprint: $(cat "$state/anonymous_last_fp" 2>/dev/null || echo none)"
        echo "data wipe knob: $([ -f "$CLEAR_KNOB" ] && echo on || echo off)"
    else
        echo "anonymous mode: DISABLED"
    fi
}

cmd_enable() {
    mkdir -p "$state"
    : > "$FLAG"
    : > "$CLEAR_KNOB"
    fw_start
    db_refresh
    randomize_identity
    log "anonymous mode enabled"
    echo "anonymous mode enabled — identity randomized, Google cut off."
}

cmd_disable() {
    rm -f "$FLAG" "$CLEAR_KNOB" "$state/anonymous_real_fp"
    fw_stop
    # restore the stock udonge config so the cloak engine goes back to
    # its static profile; spoofed serial/imei props revert on next reboot
    cp -f "$runtime/defaults/pif.conf" "$state/pif.conf" 2>/dev/null
    : > "$state/props.conf" 2>/dev/null
    am force-stop com.google.android.gms >/dev/null 2>&1
    log "anonymous mode disabled"
    echo "anonymous mode disabled — Google unblocked, stock profile restored."
}

case "$1" in
    enable)    cmd_enable ;;
    disable)   cmd_disable ;;
    fw-start)  fw_start ;;
    fw-stop)   fw_stop ;;
    boot-fw)   [ -f "$FLAG" ] && fw_start_early ;;
    randomize) randomize_identity ;;
    status)    cmd_status ;;
    boot)      cmd_boot ;;
    *) echo "usage: $0 enable|disable|boot|boot-fw|randomize|fw-start|fw-stop|status" ;;
esac
exit 0
