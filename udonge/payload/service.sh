#!/system/bin/sh

root=/data/adb/udonge
runtime=$root/runtime
state=$root/state
tee_state=$root/tee-state

boot_wait=0
until [ "$(getprop sys.boot_completed)" = 1 ]; do
    [ "$boot_wait" -ge 90 ] && exit 0
    sleep 2
    boot_wait=$((boot_wait + 1))
done

[ -f "$state/disabled" ] && exit 0

ms --denylist rm com.google.android.gms >/dev/null 2>&1
ms --denylist rm com.android.vending >/dev/null 2>&1
ms --denylist add com.eltavine.duckdetector >/dev/null 2>&1

refresh_keybox() {
    local urls marker now last work best score candidate count checked size
    urls="$state/keybox_urls.conf"
    [ -s "$urls" ] || return 0
    marker="$state/.keybox-checked"
    now="$(date +%s 2>/dev/null)"
    last="$(cat "$marker" 2>/dev/null)"
    if [ ! -f "$state/.keybox-refresh" ] && [ -n "$now" ] && [ -n "$last" ]; then
        [ $((now - last)) -lt 86400 ] && return 0
    fi

    work="$root/keybox-check"
    rm -rf "$work"
    mkdir -p "$work" "$tee_state"
    trap 'rm -rf "$work"' EXIT INT TERM
    best=0
    checked=0
    while IFS= read -r candidate; do
        case "$candidate" in
            https://*) ;;
            *) continue ;;
        esac
        checked=$((checked + 1))
        [ "$checked" -le 16 ] || break
        score="$work/candidate.xml"
        wget -q -T 12 -O "$score" "$candidate" >/dev/null 2>&1 || continue
        size="$(wc -c < "$score" 2>/dev/null)"
        [ -n "$size" ] && [ "$size" -le 262144 ] || continue
        grep -q '<Keybox' "$score" || continue
        grep -q -- '-----BEGIN CERTIFICATE-----' "$score" || continue
        grep -q -- '-----END CERTIFICATE-----' "$score" || continue
        count="$(grep -c -- '-----BEGIN CERTIFICATE-----' "$score")"
        [ "$count" -gt "$best" ] 2>/dev/null || continue
        cp "$score" "$work/best.xml"
        best="$count"
    done < "$urls"
    if [ "$best" -gt 0 ] && [ -s "$work/best.xml" ]; then
        cp "$work/best.xml" "$tee_state/keybox.xml"
        chmod 600 "$tee_state/keybox.xml"
    fi
    [ -n "$now" ] && printf '%s\n' "$now" > "$marker"
    rm -f "$state/.keybox-refresh"
    rm -rf "$work"
    trap - EXIT INT TERM
}

start_tee() {
    local sdk abi source run next target patch version current
    sdk="$(getprop ro.build.version.sdk 2>/dev/null)"
    [ "$sdk" -ge 29 ] 2>/dev/null || return 0
    abi="$(getprop ro.product.cpu.abi 2>/dev/null)"
    source="$runtime/tee/$abi"
    [ -d "$source" ] || return 0
    [ -f "$source/libTEESimulator.so" ] || return 0
    [ -f "$source/inject" ] || return 0
    [ -f "$source/supervisor" ] || return 0
    [ -f "$runtime/tee/classes.dex" ] || return 0
    [ -f "$runtime/tee/daemon" ] || return 0

    run="$root/tee-runtime"
    version="$(cat "$runtime/version" 2>/dev/null)"
    current="$(cat "$run/.version" 2>/dev/null)"
    if [ "$version" != "$current" ] || [ ! -x "$run/supervisor" ]; then
        next="$root/tee-runtime.new"
        rm -rf "$next"
        mkdir -p "$next" "$tee_state"
        cp "$source/libTEESimulator.so" "$next/"
        [ ! -f "$source/libcertgen.so" ] || cp "$source/libcertgen.so" "$next/"
        cp "$source/inject" "$next/inject"
        cp "$source/supervisor" "$next/supervisor"
        cp "$runtime/tee/classes.dex" "$next/classes.dex"
        cp "$runtime/tee/daemon" "$next/daemon"
        chmod 700 "$next/inject" "$next/supervisor" "$next/daemon"
        printf '%s\n' "$version" > "$next/.version"
        rm -rf "$run"
        mv "$next" "$run"
    else
        mkdir -p "$tee_state"
    fi

    [ -f "$tee_state/keybox.xml" ] || cp "$runtime/defaults/keybox.xml" "$tee_state/keybox.xml"
    target="$tee_state/target.txt"
    if [ ! -f "$target" ]; then
        printf 'com.android.vending\ncom.google.android.gms\ncom.eltavine.duckdetector\n' > "$target"
    elif ! grep -qxF com.eltavine.duckdetector "$target"; then
        printf 'com.eltavine.duckdetector\n' >> "$target"
    fi
    if [ ! -f "$tee_state/security_patch.txt" ]; then
        patch="$(sed -n 's/^SECURITY_PATCH=//p' "$state/pif.conf" | head -n 1)"
        [ -z "$patch" ] || printf 'system=%s\n' "$patch" > "$tee_state/security_patch.txt"
    fi
    [ -f "$tee_state/hbk" ] || head -c 32 /dev/urandom > "$tee_state/hbk"
    chmod 600 "$tee_state"/* 2>/dev/null

    pgrep -f "$run/daemon" >/dev/null 2>&1 && return 0
    (cd "$run" && exec ./supervisor ./daemon "$tee_state" </dev/null >/dev/null 2>&1) &
}

refresh_keybox
start_tee

if [ -f "$state/pif.conf" ] && [ ! -f "$state/.certified" ]; then
    am force-stop com.google.android.gms >/dev/null 2>&1
    am broadcast -a android.server.checkin.CHECKIN >/dev/null 2>&1
    touch "$state/.certified"
fi
