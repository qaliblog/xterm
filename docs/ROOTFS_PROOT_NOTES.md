# Rootfs / Proot environment notes

If you maintain a rootfs image or the proot launcher used with this app, apply these fixes to avoid common errors:

## Proot: duplicate -i / -0 / -S option

**Error:** `proot warning: option -i/-0/-S was already specified` / `only the last -i/-0/-S option is enabled`

**Fix:** Pass only one of `-i`, `-0`, or `-S` to proot. Do not add the same option in multiple places (e.g. both in a wrapper script and in the app). Merge into a single proot invocation with one such option.

## Apt / dpkg: dpkg-preconfigure and permission errors

**Error:** `dpkg-preconfigure: Function not implemented`, `Permission denied`, `E: Sub-process "" returned an error code (127)`

**Fix:**

- Set `DEBIAN_FRONTEND=noninteractive` in the environment before running apt/dpkg so that debconf/dpkg-preconfigure is not used.
- Or ensure the rootfs does not invoke `/usr/sbin/dpkg-preconfigure` (or replace it with a no-op script) when running under proot, where some syscalls may not be implemented.

## getcwd() / chdir: Function not implemented

**Error:** `getcwd: cannot access parent directories: Function not implemented`, `chdir: ... Function not implemented`

**Fix:** The app sets **HOME=/root**, **PWD=/root**, and **OLDPWD=/root** when using rootfs so the shell does not need to call `getcwd()` on startup. Proot is started with **-w /root**. If `cd` still fails for relative paths (e.g. `cd home`), try using an absolute path (e.g. `cd /root/home`). A full fix requires a proot build that implements the getcwd syscall.

## Storage setup command

The app provides **xterm-setup-storage** (not `termos-setup-storage`). When using rootfs, the app bind-mounts its `bin` dir to **/.xterm-app-bin** and **/system/bin** to **/.system-bin** so that `xterm-setup-storage` and `am` are available inside proot. PATH is set to include **/.xterm-app-bin**. Any scripts or messages in the rootfs that refer to the setup-storage command should use **xterm-setup-storage**.
