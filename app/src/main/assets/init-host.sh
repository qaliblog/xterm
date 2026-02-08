# Determine rootfs file and directory from environment or defaults
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

# ROOTFS_DIR_PATH should be relative to the app's files directory
# In xterm, we use $FILES_DIR/rootfs for the current installation
ROOTFS_DIR_PATH="$FILES_DIR/rootfs"

mkdir -p "$ROOTFS_DIR_PATH"

# Extract rootfs if directory is empty (excluding root and tmp)
if [ -z "$(ls -A "$ROOTFS_DIR_PATH" 2>/dev/null | grep -vE '^(root|tmp)$')" ]; then
    ROOTFS_FILE_PATH="$FILES_DIR/rootfs_bundle.tar.gz" # Fallback if not specified
    if [ -n "$ROOTFS_BUNDLE_PATH" ]; then
        ROOTFS_FILE_PATH="$ROOTFS_BUNDLE_PATH"
    fi

    if [ ! -f "$ROOTFS_FILE_PATH" ]; then
        echo "Error: Rootfs bundle not found at $ROOTFS_FILE_PATH"
        exit 1
    fi
    echo "Extracting rootfs to $ROOTFS_DIR_PATH..."
    # Use appropriate tar flags based on file extension
    if echo "$ROOTFS_FILE_PATH" | grep -q "\.zip$"; then
        unzip -q "$ROOTFS_FILE_PATH" -d "$ROOTFS_DIR_PATH" || true
    elif echo "$ROOTFS_FILE_PATH" | grep -q "\.tar\.gz$\|\.tgz$"; then
        tar -xzf "$ROOTFS_FILE_PATH" -C "$ROOTFS_DIR_PATH" --no-same-owner --no-same-permissions 2>/dev/null || true
    elif echo "$ROOTFS_FILE_PATH" | grep -q "\.tar\.xz$"; then
        tar -xJf "$ROOTFS_FILE_PATH" -C "$ROOTFS_DIR_PATH" --no-same-owner --no-same-permissions 2>/dev/null || true
    else
        tar -xf "$ROOTFS_FILE_PATH" -C "$ROOTFS_DIR_PATH" --no-same-owner --no-same-permissions 2>/dev/null || true
    fi

    # Verify extraction
    if [ ! -d "$ROOTFS_DIR_PATH/usr" ] && [ ! -d "$ROOTFS_DIR_PATH/bin" ] && [ ! -d "$ROOTFS_DIR_PATH/etc" ]; then
        echo "Error: Failed to extract rootfs - no system directories found"
        exit 1
    fi
    echo "Rootfs extracted successfully"
fi

# Use proot from app's bin directory
PROOT_BINARY="$FILES_DIR/bin/proot"
if [ ! -f "$PROOT_BINARY" ]; then
    echo "Error: proot binary not found at $PROOT_BINARY"
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
ARGS="$ARGS -b $FILES_DIR/tmp:/tmp"
ARGS="$ARGS -b $FILES_DIR/tmp:/dev/shm"
ARGS="$ARGS -b $FILES_DIR/home:/root"

# Bind app data directory variants to themselves
# Use FILES_DIR to infer the package data directory
DATA_DIR=$(dirname "$FILES_DIR")
if [ -d "$DATA_DIR" ]; then
    ARGS="$ARGS -b $DATA_DIR"
fi

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
export PROOT_NO_SECCOMP=1
export PROOT_SECCOMP=0
export PROOT_FORCE_PTRACE_TRACEME=1

$LINKER $PROOT_BINARY $ARGS $SHELL_PATH $FILES_DIR/bin/init.sh "$@"
