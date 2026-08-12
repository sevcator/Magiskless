#!/system/bin/sh

root=/data/adb/udonge
runtime=$root/runtime
state=$root/state

mkdir -p "$state"
chmod 700 "$root" "$state"

for name in targets.conf props.conf pif.conf pif_urls.conf keybox_urls.conf; do
    [ -f "$state/$name" ] || cp "$runtime/defaults/$name" "$state/$name"
    chmod 600 "$state/$name"
done

digest="$(getprop ro.boot.vbmeta.digest 2>/dev/null)"
if [ -n "$digest" ]; then
    printf '%s\n' "$digest" > "$state/vbmeta_hash"
    chmod 600 "$state/vbmeta_hash"
fi
