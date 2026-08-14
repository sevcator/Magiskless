#!/system/bin/sh

umask 077
root=/data/adb/udonge
runtime=$root/runtime
state=$root/state

mkdir -p "$state"
chmod 700 "$root" "$state"
rm -f "$state/vbmeta_hash" "$state/pif_urls.conf"

for name in targets.conf props.conf pif.conf keybox_urls.conf; do
    if [ ! -f "$state/$name" ] || { [ "$name" = keybox_urls.conf ] && ! grep -q '^https://' "$state/$name"; }; then
        cp "$runtime/defaults/$name" "$state/$name"
        chmod 600 "$state/$name"
    fi
done

sync_vbmeta_digest() {
    [ "$(wc -c < "$state/boot_hash.bin" 2>/dev/null)" = 32 ] || return 1
    digest="$(od -An -tx1 -v "$state/boot_hash.bin" 2>/dev/null | tr -d ' \n')"
    [ "${#digest}" = 64 ] || return 1
    temp="$state/.props.$$"
    sed '/^ro\.boot\.vbmeta\.digest=/d' "$state/props.conf" 2>/dev/null > "$temp"
    printf 'ro.boot.vbmeta.digest=%s\n' "$digest" >> "$temp"
    chmod 600 "$temp"
    mv -f "$temp" "$state/props.conf"
}

sync_vbmeta_digest || true
chmod 600 "$state/.certified" "$state/.keybox-checked" 2>/dev/null || true

if grep -qF 'google/tegu_beta/tegu:CANARY/ZP11.260618.005/15760424' "$state/pif.conf"; then
    cp "$runtime/defaults/pif.conf" "$state/pif.conf"
    chmod 600 "$state/pif.conf"
fi
