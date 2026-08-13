#!/system/bin/sh

umask 077
root=/data/adb/udonge
runtime=$root/runtime
state=$root/state

mkdir -p "$state"
chmod 700 "$root" "$state"
rm -f "$state/vbmeta_hash" "$state/pif_urls.conf"

for name in targets.conf props.conf pif.conf keybox_urls.conf; do
    if [ ! -f "$state/$name" ]; then
        cp "$runtime/defaults/$name" "$state/$name"
        chmod 600 "$state/$name"
    fi
done
