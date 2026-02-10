#!/bin/sh
# Reusable launch script for Linux rootfs using PRoot
# This script is designed to run in Termux or similar Android terminal apps.

# Configuration
PREFIX="/data/data/com.xterm"
ROOTFS_DIR="${1:-alpine}"
ROOTFS_PATH="$PREFIX/local/$ROOTFS_DIR"
HOME_PATH="$PREFIX/files/home"

# Ensure directories exist
mkdir -p "$HOME_PATH" 2>/dev/null || true

# Environment setup
export PROOT_NO_SECCOMP=1
export PROOT_SECCOMP=0
export PROOT_FORCE_PTRACE_TRACEME=1
export PROOT_NO_HARDLINKS=1
export PROOT_SKIP_CLEANUP=1

# Determine linker
LINKER="/system/bin/linker64"
[ ! -f "$LINKER" ] && LINKER="/system/bin/linker"

# PRoot arguments
ARGS="--kill-on-exit"
ARGS="$ARGS -0"
ARGS="$ARGS -k 4.14.0"
ARGS="$ARGS -r $ROOTFS_PATH"
ARGS="$ARGS -w /root"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b /sys"
ARGS="$ARGS -b /tmp"
ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b $HOME_PATH:/root"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

# Shell to run
SHELL="/bin/sh"
if [ -e "$ROOTFS_PATH/bin/bash" ]; then
    SHELL="/bin/bash"
elif [ -e "$ROOTFS_PATH/usr/bin/sh" ]; then
    SHELL="/usr/bin/sh"
fi

echo "Launching Linux rootfs from $ROOTFS_PATH..."
$LINKER $PREFIX/local/bin/proot $ARGS $SHELL
