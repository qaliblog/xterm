# Determine rootfs file and directory from environment or defaults
# Following termos exact flow
ROOTFS_FILE="${ROOTFS_FILE:-alpine.tar.gz}"
ROOTFS_DIR="${ROOTFS_DIR:-alpine}"

# If ROOTFS_DIR is not set, infer from ROOTFS_FILE
if [ -z "$ROOTFS_DIR" ] || [ "$ROOTFS_DIR" = "alpine" ]; then
    if [ "$ROOTFS_FILE" = "ubuntu.tar.gz" ]; then
        ROOTFS_DIR="ubuntu"
    elif [ "$ROOTFS_FILE" != "alpine.tar.gz" ]; then
        # For custom rootfs, use filename without extension
        ROOTFS_DIR=$(echo "$ROOTFS_FILE" | sed 's/\.tar\.gz$//' | sed 's/\.tar$//' | tr '[:upper:]' '[:lower:]' | tr ' ' '_')
    fi
fi

# ROOTFS_DIR_PATH should be relative to the local directory
ROOTFS_DIR_PATH="$PREFIX/local/$ROOTFS_DIR"

# Redundant check for extraction - TermuxInstaller handles this now
# but we keep a minimal check to be safe
if [ ! -d "$ROOTFS_DIR_PATH" ]; then
    echo "Error: rootfs directory not found at $ROOTFS_DIR_PATH"
    exit 1
fi

# Use proot from local/bin
PROOT_BINARY="$PREFIX/local/bin/proot"
if [ ! -f "$PROOT_BINARY" ]; then
    # Fallback to files/bin/proot if not in local/bin
    PROOT_BINARY="$PREFIX/files/bin/proot"
fi

if [ ! -f "$PROOT_BINARY" ]; then
    echo "Error: proot binary not found"
    exit 1
fi

ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

# Standard Android system binds
for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  ARGS="$ARGS -b ${system_mnt}"
 fi
done

ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b /sys"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /data"

# Bind app directories
ARGS="$ARGS -b $PREFIX"
# Bind individual variants to fix path resolution
packageName=$(basename "$PREFIX")
for variant in "/data/data/$packageName" "/data/user/0/$packageName"; do
    if [ -d "$variant" ]; then
        ARGS="$ARGS -b $variant"
    fi
done

# Create fake stat/vmstat files to avoid proot warnings/errors
mkdir -p "$PREFIX/local" 2>/dev/null || true
if [ ! -f "$PREFIX/local/stat" ]; then
    echo "cpu  0 0 0 0 0 0 0 0 0 0" > "$PREFIX/local/stat" 2>/dev/null || true
fi
if [ ! -f "$PREFIX/local/vmstat" ]; then
    echo "nr_free_pages 0" > "$PREFIX/local/vmstat" 2>/dev/null || true
fi
[ -f "$PREFIX/local/stat" ] && ARGS="$ARGS -b $PREFIX/local/stat:/proc/stat"
[ -f "$PREFIX/local/vmstat" ] && ARGS="$ARGS -b $PREFIX/local/vmstat:/proc/vmstat"

# Standard file descriptor binds
[ -e "/proc/self/fd" ] && ARGS="$ARGS -b /proc/self/fd:/dev/fd"
[ -e "/proc/self/fd/0" ] && ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
[ -e "/proc/self/fd/1" ] && ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
[ -e "/proc/self/fd/2" ] && ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"

ARGS="$ARGS -r $ROOTFS_DIR_PATH"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

# Detect shell inside rootfs
SHELL_PATH="/bin/sh"
if [ -e "$ROOTFS_DIR_PATH/bin/bash" ]; then
    SHELL_PATH="/bin/bash"
elif [ -e "$ROOTFS_DIR_PATH/usr/bin/sh" ]; then
    SHELL_PATH="/usr/bin/sh"
elif [ -e "$ROOTFS_DIR_PATH/bin/ash" ]; then
    SHELL_PATH="/bin/ash"
fi

# Run proot via system linker
# Unset host library paths to prevent bleeding into guest
unset LD_PRELOAD
export LD_LIBRARY_PATH="$PREFIX/local/lib"

# Check if we should disable seccomp (only if environment specifies)
# But following termos, we don't set it by default here
if [ "$DISABLE_SECCOMP" = "1" ]; then
    export PROOT_NO_SECCOMP=1
    export PROOT_SECCOMP=0
fi
export PROOT_FORCE_PTRACE_TRACEME=1

$LINKER $PROOT_BINARY $ARGS $SHELL_PATH $PREFIX/local/bin/init "$@"
