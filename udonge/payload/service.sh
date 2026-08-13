#!/system/bin/sh

root=/data/adb/udonge
runtime=$root/runtime
state=$root/state
tee_state=$root/tee-state
lock=$root/.service-lock
work=$root/keybox-check
boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"

process_is_current() {
    local pid expected_start expected_boot current_start
    pid="$1"
    expected_start="$2"
    expected_boot="$3"
    [ -n "$pid" ] && [ -n "$expected_start" ] && [ "$expected_boot" = "$boot_id" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    current_start="$(awk '{print $22}' "/proc/$pid/stat" 2>/dev/null)"
    [ -n "$current_start" ] && [ "$current_start" = "$expected_start" ]
}

if ! mkdir "$lock" 2>/dev/null; then
    owner="$(cat "$lock/pid" 2>/dev/null)"
    owner_start="$(cat "$lock/start" 2>/dev/null)"
    owner_boot="$(cat "$lock/boot" 2>/dev/null)"
    process_is_current "$owner" "$owner_start" "$owner_boot" && exit 0
    rm -rf "$lock"
    mkdir "$lock" 2>/dev/null || exit 0
fi
printf '%s\n' "$$" > "$lock/pid"
awk '{print $22}' "/proc/$$/stat" > "$lock/start" 2>/dev/null
printf '%s\n' "$boot_id" > "$lock/boot"
cleanup() {
    rm -rf "$work" "$root/tee-runtime.new" "$lock"
}
trap cleanup EXIT INT TERM

boot_wait=0
until [ "$(getprop sys.boot_completed)" = 1 ]; do
    [ "$boot_wait" -ge 90 ] && exit 0
    sleep 2
    boot_wait=$((boot_wait + 1))
done

[ -f "$state/disabled" ] && exit 0

refresh_keybox() {
    local urls marker now last best score candidate count checked size temp
    urls="$state/keybox_urls.conf"
    [ -s "$urls" ] || return 0
    marker="$state/.keybox-checked"
    now="$(date +%s 2>/dev/null)"
    last="$(cat "$marker" 2>/dev/null)"
    if [ ! -f "$state/.keybox-refresh" ] && [ -n "$now" ] && [ -n "$last" ]; then
        [ $((now - last)) -lt 86400 ] && return 0
    fi

    rm -rf "$work"
    mkdir -p "$work" "$tee_state"
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
        grep -q '<NumberOfKeyboxes>' "$score" || continue
        grep -q '<Keybox' "$score" || continue
        grep -Eq -- '-----BEGIN (EC |RSA )?PRIVATE KEY-----' "$score" || continue
        grep -q 'AndroidAttestation' "$score" || continue
        grep -q -- '-----BEGIN CERTIFICATE-----' "$score" || continue
        grep -q -- '-----END CERTIFICATE-----' "$score" || continue
        count="$(grep -c -- '-----BEGIN CERTIFICATE-----' "$score")"
        [ "$count" -gt "$best" ] 2>/dev/null || continue
        cp "$score" "$work/best.xml"
        best="$count"
    done < "$urls"
    if [ "$best" -gt 0 ] && [ -s "$work/best.xml" ]; then
        temp="$tee_state/.keybox.$$"
        cp "$work/best.xml" "$temp" && chmod 600 "$temp" && mv -f "$temp" "$tee_state/keybox.xml"
        rm -f "$temp"
    fi
    [ -n "$now" ] && printf '%s\n' "$now" > "$marker"
    rm -f "$state/.keybox-refresh"
    rm -rf "$work"
}

start_tee() {
    local sdk abi source run next old target patch version current pid pid_start pid_boot
    sdk="$(getprop ro.build.version.sdk 2>/dev/null)"
    [ "$sdk" -ge 29 ] 2>/dev/null || return 0
    abi="$(getprop ro.product.cpu.abi 2>/dev/null)"
    source="$runtime/tee/$abi"
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
        old="$root/tee-runtime.old"
        rm -rf "$next" "$old"
        mkdir -p "$next" "$tee_state"
        cp "$source/libTEESimulator.so" "$next/" || return 0
        [ ! -f "$source/libcertgen.so" ] || cp "$source/libcertgen.so" "$next/"
        cp "$source/inject" "$next/inject" || return 0
        cp "$source/supervisor" "$next/supervisor" || return 0
        cp "$runtime/tee/classes.dex" "$next/classes.dex" || return 0
        cp "$runtime/tee/daemon" "$next/daemon" || return 0
        chmod 700 "$next/inject" "$next/supervisor" "$next/daemon"
        printf '%s\n' "$version" > "$next/.version"
        [ ! -d "$run" ] || mv "$run" "$old"
        if mv "$next" "$run"; then
            rm -rf "$old"
        else
            [ ! -d "$old" ] || mv "$old" "$run"
            return 0
        fi
    else
        mkdir -p "$tee_state"
    fi

    if [ ! -f "$tee_state/keybox.xml" ]; then
        cp "$runtime/defaults/keybox.xml" "$tee_state/keybox.xml"
        chmod 600 "$tee_state/keybox.xml"
    fi
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

    pid="$(cat "$run/.pid" 2>/dev/null)"
    pid_start="$(cat "$run/.pid-start" 2>/dev/null)"
    pid_boot="$(cat "$run/.pid-boot" 2>/dev/null)"
    process_is_current "$pid" "$pid_start" "$pid_boot" && return 0
    rm -f "$run/.pid" "$run/.pid-start" "$run/.pid-boot"
    (cd "$run" && exec ./supervisor ./daemon "$tee_state" </dev/null >/dev/null 2>&1) &
    pid="$!"
    printf '%s\n' "$pid" > "$run/.pid"
    awk '{print $22}' "/proc/$pid/stat" > "$run/.pid-start" 2>/dev/null
    printf '%s\n' "$boot_id" > "$run/.pid-boot"
}

refresh_keybox
start_tee

version="$(cat "$runtime/version" 2>/dev/null)"
certified="$(cat "$state/.certified" 2>/dev/null)"
if [ -f "$state/pif.conf" ] && [ "$version" != "$certified" ]; then
    am force-stop com.google.android.gms >/dev/null 2>&1
    am broadcast -a android.server.checkin.CHECKIN >/dev/null 2>&1
    printf '%s\n' "$version" > "$state/.certified"
fi
