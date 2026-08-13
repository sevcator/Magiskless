#####################################################################
#   AVD MagiskInit Setup
#####################################################################
#
# Support API level: 23 - 36
#
# With an emulator booted and accessible via ADB, usage:
# ./build.py avd_patch path/to/booted/avd-image/ramdisk.img
#
# The purpose of this script is to patch AVD ramdisk.img and do a
# full integration test of magiskinit under several circumstances.
# After patching ramdisk.img, close the emulator, then select
# "Cold Boot Now" in AVD Manager to force a full reboot.
#
#####################################################################
# AVD Init Configurations:
#
# rootfs w/o early mount: API 23 - 25
# rootfs with early mount: API 26 - 27
# Legacy system-as-root: API 28
# 2 stage init: API 29 - 35
#####################################################################

if [ ! -f /system/build.prop ]; then
  # Running on PC
  echo 'please run `./build.py avd_patch` instead of directly executing the script!'
  exit 1
fi

cd /data/local/tmp
chmod 755 busybox

if [ -z "$FIRST_STAGE" ]; then
  export FIRST_STAGE=1
  export ASH_STANDALONE=1
  # Re-exec script with busybox
  exec ./busybox sh $0 "$@"
fi

TARGET_FILE="$1"
OUTPUT_FILE="$1.magisk"

if echo "$TARGET_FILE" | grep -q 'ramdisk'; then
  IS_RAMDISK=true
else
  IS_RAMDISK=false
fi

# Extract files from APK
unzip -oj magisk.apk 'assets/util_functions.sh' 'assets/stub.apk' 'assets/udonge.bin'
. ./util_functions.sh

api_level_arch_detect

unzip -oj magisk.apk "lib/$ABI/*" -x "lib/$ABI/libbusybox.so"
for file in lib*.so; do
  chmod 755 $file
  mv "$file" "${file:3:${#file}-6}"
done

if $IS_RAMDISK; then
  ./mboot decompress "$TARGET_FILE" ramdisk.cpio
else
  ./mboot unpack "$TARGET_FILE"
fi
cp ramdisk.cpio ramdisk.cpio.orig

export KEEPVERITY=true
export KEEPFORCEENCRYPT=true

echo "KEEPVERITY=$KEEPVERITY" > config
echo "KEEPFORCEENCRYPT=$KEEPFORCEENCRYPT" >> config
echo "PREINITDEVICE=$(./$MAIN_BIN_NAME --preinit-device)" >> config
# For API 28, we also manually disable SystemAsRoot
# Explicitly override skip_initramfs by setting RECOVERYMODE=true
[ $API = "28" ] && echo 'RECOVERYMODE=true' >> config
cat config

[ -f "$MAIN_BIN_NAME" ] && mv "$MAIN_BIN_NAME" ms
./mboot compress=xz ms ms.xz
./mboot compress=xz stub.apk stub.xz
./mboot compress=xz init-ld init-ld.xz
./mboot compress=xz udonge.bin udonge.xz

./mboot cpio ramdisk.cpio \
"add 0750 init minit" \
"mkdir 0750 overlay.d" \
"mkdir 0750 overlay.d/sbin" \
"add 0644 overlay.d/sbin/ms.xz ms.xz" \
"add 0644 overlay.d/sbin/stub.xz stub.xz" \
"add 0644 overlay.d/sbin/init-ld.xz init-ld.xz" \
"add 0600 overlay.d/sbin/udonge.xz udonge.xz" \
"patch" \
"backup ramdisk.cpio.orig" \
"mkdir 000 .backup" \
"add 000 .backup/.cfg config"

rm -f ramdisk.cpio.orig config *.xz
if $IS_RAMDISK; then
  ./mboot compress=gzip ramdisk.cpio "$OUTPUT_FILE"
else
  ./mboot repack "$TARGET_FILE" "$OUTPUT_FILE"
  ./mboot cleanup
fi
