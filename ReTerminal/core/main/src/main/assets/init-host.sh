ALPINE_DIR=$PREFIX/local/alpine
ALPINE_ARCHIVE=$PREFIX/files/alpine.tar.gz

[ ! -f "$ALPINE_ARCHIVE" ] && ALPINE_ARCHIVE=$PREFIX/files/alpine.tar

mkdir -p $ALPINE_DIR

if [ -z "$(ls -A "$ALPINE_DIR" | grep -vE '^(root|tmp)$')" ]; then
    tar -xf "$ALPINE_ARCHIVE" -C "$ALPINE_DIR"
fi

FIPS_COMPAT_FILE="$PREFIX/local/sysctl_crypto_fips_enabled"
[ ! -f "$FIPS_COMPAT_FILE" ] && {
    mkdir -p "$PREFIX/local"
    printf '0\n' > "$FIPS_COMPAT_FILE"
}

if [ -n "$OMNIBOT_HOST_WORKSPACE" ]; then
    mkdir -p "$OMNIBOT_HOST_WORKSPACE"
    mkdir -p "$ALPINE_DIR/workspace"
fi

if [ -n "$OMNIBOT_MT_STORAGE_HOST" ] && [ -d "$OMNIBOT_MT_STORAGE_HOST" ]; then
    mkdir -p "$ALPINE_DIR/mnt/mt" "$ALPINE_DIR/mt"
fi

mkdir -p "$PREFIX/local/bin" "$PREFIX/local/lib"

install_runtime_file() {
    src="$1"
    dest="$2"
    mode="$3"
    [ -e "$src" ] || return 0
    tmp="${dest}.$$"
    rm -f "$tmp"
    cp "$src" "$tmp" && chmod "$mode" "$tmp" && mv -f "$tmp" "$dest"
}

install_runtime_file "$PREFIX/files/proot" "$PREFIX/local/bin/proot" 755

for sofile in "$PREFIX/files/"*.so.2; do
    [ -e "$sofile" ] || continue
    dest="$PREFIX/local/lib/$(basename "$sofile")"
    install_runtime_file "$sofile" "$dest" 644
done


ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b $PREFIX/local/stat:/proc/stat"
ARGS="$ARGS -b $PREFIX/local/vmstat:/proc/vmstat"
ARGS="$ARGS -b $FIPS_COMPAT_FILE:/proc/.sysctl_crypto_fips_enabled"

if [ -n "$OMNIBOT_HOST_WORKSPACE" ]; then
  ARGS="$ARGS -b $OMNIBOT_HOST_WORKSPACE:/workspace"
fi

if [ -n "$OMNIBOT_MT_STORAGE_HOST" ] && [ -d "$OMNIBOT_MT_STORAGE_HOST" ]; then
  ARGS="$ARGS -b $OMNIBOT_MT_STORAGE_HOST:/mnt/mt"
  ARGS="$ARGS -b $OMNIBOT_MT_STORAGE_HOST:/mt"
fi

if [ -e "/proc/self/fd" ]; then
  ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi

if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi

if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi

if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi


ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b /sys"

if [ ! -d "$PREFIX/local/alpine/tmp" ]; then
 mkdir -p "$PREFIX/local/alpine/tmp"
 chmod 1777 "$PREFIX/local/alpine/tmp"
fi
ARGS="$ARGS -b $PREFIX/local/alpine/tmp:/dev/shm"

ARGS="$ARGS -r $PREFIX/local/alpine"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

$LINKER $PREFIX/local/bin/proot $ARGS sh $PREFIX/local/bin/init "$@"
