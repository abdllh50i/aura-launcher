#!/system/bin/sh
# ROM workshop (runs inside the Android emulator, as root):
#   - takes a copy of the stock system image,
#   - checks it, grows the filesystem into the free space of its logical partition,
#   - loop-mounts it read-write, applies the Aura overlay (/data/local/tmp/ws/rom), and checks it again.
# Output: /data/local/tmp/ws/aura-system.img (same size as the stock image).

WS=/data/local/tmp/ws
SRC=$WS/rom
IMG=$WS/aura-system.img
MNT=$WS/mnt
PARTITION_BYTES=1835290624  # size of the system logical partition inside super (must never change)
PARTITION_BLOCKS=448069     # = PARTITION_BYTES / 4096
STAMP=200901010300          # keep the firmware's fixed timestamp on everything we touch
CTX=u:object_r:system_file:s0

LOOP=""
cleanup() { umount $MNT 2>/dev/null; [ -n "$LOOP" ] && losetup -d $LOOP 2>/dev/null; LOOP=""; }
fail() { echo "FAIL: $*"; cleanup; exit 1; }
step() { echo; echo "== $*"; }

setenforce 0
umount $MNT 2>/dev/null
rm -f $IMG

step "copy stock image"
cp $WS/system.img $IMG || fail "copy"

step "check (read-only), then grow filesystem to $PARTITION_BLOCKS blocks"
# NB: never `e2fsck -y` here: it would "optimise" directories and rewrite thousands of blocks for no reason.
e2fsck -fn $IMG > $WS/fsck_pre.log 2>&1 || fail "stock image is not clean"
tail -1 $WS/fsck_pre.log
resize2fs -f $IMG $PARTITION_BLOCKS > $WS/resize.log 2>&1 || { cat $WS/resize.log; fail "resize2fs failed"; }
tail -2 $WS/resize.log
[ "$(stat -c %s $IMG)" = "$PARTITION_BYTES" ] || fail "image size changed: $(stat -c %s $IMG) != $PARTITION_BYTES"
tune2fs -l $IMG | grep -q "^Block count: *$PARTITION_BLOCKS\$" || fail "filesystem block count is not $PARTITION_BLOCKS after the resize"
e2fsck -fn $IMG > $WS/fsck_resized.log 2>&1 || fail "fsck after resize"
tail -1 $WS/fsck_resized.log

step "mount read-write"
mkdir -p $MNT
LOOP=$(losetup -f --show $IMG) || fail "losetup"
mount -t ext4 -o rw,noatime $LOOP $MNT || fail "mount"
R=$MNT/system

# put_file SRC DEST MODE OWNER:GROUP
put_file() {
    d=$(dirname "$2")
    [ -d "$d" ] || { mkdir -p "$d" && chmod 755 "$d" && chown 0:0 "$d" && chcon $CTX "$d" && touch -t $STAMP "$d"; } || fail "mkdir $d"
    cat "$1" > "$2" || fail "write $2"
    chmod "$3" "$2"; chown "${4:-0:0}" "$2"; chcon $CTX "$2"; touch -t $STAMP "$2"
    echo "  + $2"
}

step "install files"
put_file $SRC/system/priv-app/Aura/Aura.apk              $R/priv-app/Aura/Aura.apk                 644
put_file $SRC/system/etc/permissions/privapp-permissions-aura.xml $R/etc/permissions/privapp-permissions-aura.xml 644
put_file $SRC/system/etc/init/aura.rc                     $R/etc/init/aura.rc                       644
put_file $SRC/system/bin/aura-prepare.sh                  $R/bin/aura-prepare.sh                    755 0:2000
[ -f $SRC/system/media/bootanimation.zip ] && put_file $SRC/system/media/bootanimation.zip $R/media/bootanimation.zip 644
[ -f $SRC/system/media/bootanimation.zip ] && put_file $SRC/system/media/bootanimation.zip $R/config/app/bootanimation.zip 644
touch -t $STAMP $R/priv-app $R/etc/permissions $R/etc/init $R/bin $R/media $R/config/app

step "patch NWD default config (same logic the car runs at boot)"
sh $SRC/system/bin/aura-prepare.sh --image $MNT
for f in AppConfig.xml TaskWhitelist.xml OneClearConfig.xml FastDexOpt.xml; do
    rm -f $R/config/app/$f.pre-aura          # no backup copies inside the ROM
    touch -t $STAMP $R/config/app/$f
done

step "build.prop: default home app + ROM version"
bp=$R/build.prop
sed 's/^persist\.nwd\.launcher\.default=.*/persist.nwd.launcher.default=com.abdllh.aura/' $bp > $WS/bp.new || fail "sed"
grep -q '^persist.nwd.launcher.default=com.abdllh.aura$' $WS/bp.new || fail "launcher prop not replaced"
grep -q '^ro.aura.rom.version=' $WS/bp.new || printf '\n# Aura ROM\nro.aura.rom.version=%s\n' "$(cat $SRC/ROM_VERSION)" >> $WS/bp.new
cat $WS/bp.new > $bp || fail "write build.prop"
touch -t $STAMP $bp
rm -f $WS/bp.new
grep -n -e 'launcher.default' -e 'aura' $bp

step "verify what we wrote"
ls -lZ $R/priv-app/Aura $R/etc/init/aura.rc $R/etc/permissions/privapp-permissions-aura.xml $R/bin/aura-prepare.sh
grep -n 'abdllh' $R/config/app/AppConfig.xml $R/config/app/TaskWhitelist.xml $R/config/app/OneClearConfig.xml $R/config/app/FastDexOpt.xml

step "unmount, final filesystem check"
sync
umount $MNT || fail "umount"
losetup -d $LOOP 2>/dev/null; LOOP=""
e2fsck -fn $IMG > $WS/fsck_final.log 2>&1; rc=$?
echo "final e2fsck rc=$rc"; tail -3 $WS/fsck_final.log
[ $rc = 0 ] || fail "final fsck not clean"
[ "$(stat -c %s $IMG)" = "$PARTITION_BYTES" ] || fail "final image size wrong"
ls -l $IMG
sha256sum $IMG
echo "DONE"
