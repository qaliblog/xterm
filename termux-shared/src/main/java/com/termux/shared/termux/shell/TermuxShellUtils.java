package com.termux.shared.termux.shell;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.content.Context;

import com.termux.shared.errors.Error;
import com.termux.shared.file.filesystem.FileTypes;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;

import org.apache.commons.io.filefilter.TrueFileFilter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TermuxShellUtils {

    private static final String LOG_TAG = "TermuxShellUtils";

    /**
     * Setup shell command arguments for the execute. The file interpreter may be prefixed to
     * command arguments if needed.
     */
    @NonNull
    public static String[] setupShellCommandArguments(@NonNull Context currentPackageContext, @NonNull String executable, @Nullable String[] arguments) {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(currentPackageContext);
        if (preferences != null && preferences.isRootfsInstalled()) {
            List<String> prootArgs = new ArrayList<>();
            String filesDir = currentPackageContext.getFilesDir().getAbsolutePath();
            File rootfsDirFile = new File(filesDir + "/rootfs");

            prootArgs.add(filesDir + "/bin/proot");
            prootArgs.add("-r");
            prootArgs.add(rootfsDirFile.getAbsolutePath());
            prootArgs.add("-0");
            prootArgs.add("-p"); // link2symlink
            prootArgs.add("-w");
            prootArgs.add("/root");
            // Comprehensive bind mounts for Android compatibility
            String[] systemBinds = {
                "/system", "/vendor", "/apex", "/odm", "/product", "/system_ext",
                "/plat_property_contexts", "/property_contexts",
                "/proc", "/sys", "/dev", "/sdcard", "/storage"
            };
            for (String bind : systemBinds) {
                if (new File(bind).exists()) {
                    prootArgs.add("-b");
                    prootArgs.add(bind);
                }
            }

            // Bind specific linker config files if readable to avoid permission issues
            String[] linkerConfigs = {
                "/linkerconfig/ld.config.txt",
                "/linkerconfig/com.android.art/ld.config.txt"
            };
            for (String config : linkerConfigs) {
                File configFile = new File(config);
                if (configFile.exists() && configFile.canRead()) {
                    prootArgs.add("-b");
                    prootArgs.add(config);
                }
            }

            // Standard file descriptor binds
            if (new File("/proc/self/fd").exists()) {
                prootArgs.add("-b");
                prootArgs.add("/proc/self/fd:/dev/fd");
            }
            if (new File("/proc/self/fd/0").exists()) {
                prootArgs.add("-b");
                prootArgs.add("/proc/self/fd/0:/dev/stdin");
            }
            if (new File("/proc/self/fd/1").exists()) {
                prootArgs.add("-b");
                prootArgs.add("/proc/self/fd/1:/dev/stdout");
            }
            if (new File("/proc/self/fd/2").exists()) {
                prootArgs.add("-b");
                prootArgs.add("/proc/self/fd/2:/dev/stderr");
            }

            prootArgs.add("-b");
            prootArgs.add("/dev/urandom:/dev/random");

            prootArgs.add("-b");
            prootArgs.add(filesDir + "/tmp:/tmp");
            prootArgs.add("-b");
            prootArgs.add(filesDir + "/tmp:/dev/shm");
            prootArgs.add("-b");
            prootArgs.add(filesDir + "/home:/root");

            // Bind the entire app data directory to itself to handle absolute paths correctly
            String dataDir = currentPackageContext.getApplicationInfo().dataDir;
            prootArgs.add("-b");
            prootArgs.add(dataDir);

            // Fix for hardcoded com.xterm paths used by the app or host
            prootArgs.add("-b");
            prootArgs.add(filesDir + ":/data/data/com.xterm/files");

            // Fix for hardcoded com.termux paths in some proot builds
            prootArgs.add("-b");
            prootArgs.add(filesDir + ":/data/data/com.termux/files");

            String osType = preferences.getRootfsOsType();

            // Comprehensive shell detection
            String shell = null;
            String[] commonShells = {"/bin/sh", "/bin/bash", "/usr/bin/sh", "/usr/bin/bash"};

            // Try to find the preferred shell first
            if ("ubuntu".equals(osType) || "debian".equals(osType) || "kali".equals(osType) || "arch".equals(osType)) {
                if (new File(rootfsDirFile, "/bin/bash").exists()) shell = "/bin/bash";
                else if (new File(rootfsDirFile, "/usr/bin/bash").exists()) shell = "/usr/bin/bash";
            }

            // Fallback to any available shell
            if (shell == null) {
                for (String s : commonShells) {
                    if (new File(rootfsDirFile, s).exists()) {
                        shell = s;
                        break;
                    }
                }
            }

            if (shell == null) {
                shell = "/bin/sh"; // Desperate fallback
                Logger.logError(LOG_TAG, "No shell found in rootfs, defaulting to /bin/sh");
            }

            // Resolve symlinks to absolute path within rootfs to avoid execve issues
            try {
                File shellFile = new File(rootfsDirFile, shell);
                if (shellFile.exists()) {
                    String canonicalPath = shellFile.getCanonicalPath();
                    if (canonicalPath.startsWith(rootfsDirFile.getAbsolutePath())) {
                        shell = canonicalPath.substring(rootfsDirFile.getAbsolutePath().length());
                        if (shell.isEmpty()) shell = "/";
                    }
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to resolve shell symlink", e);
            }

            // Use the host shell as a stable entry point to proot's guest
            prootArgs.add("/system/bin/sh");
            prootArgs.add("-c");

            String guestCommand = "unset LD_PRELOAD LD_LIBRARY_PATH; " +
                                  "export PROOT_NO_SECCOMP=1; " +
                                  "export HOME=/root; " +
                                  "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin; " +
                                  "export TERM=xterm-256color; " +
                                  "export USER=root; " +
                                  "export TMPDIR=/tmp; " +
                                  "cd /root; " +
                                  "if [ -x " + shell + " ]; then exec " + shell + " -l; else exec /system/bin/sh; fi";
            prootArgs.add(guestCommand);

            return prootArgs.toArray(new String[0]);
        }

        // Standard Termux execution logic (no rootfs)
        String interpreter = null;
        try {
            File file = new File(executable);
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[256];
                int bytesRead = in.read(buffer);
                if (bytesRead > 4) {
                    if (buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F') {
                        // Elf file, do nothing.
                    } else if (buffer[0] == '#' && buffer[1] == '!') {
                        // Try to parse shebang.
                        StringBuilder builder = new StringBuilder();
                        for (int i = 2; i < bytesRead; i++) {
                            char c = (char) buffer[i];
                            if (c == ' ' || c == '\n') {
                                if (builder.length() == 0) {
                                    // Skip whitespace after shebang.
                                } else {
                                    // End of shebang.
                                    String shebangExecutable = builder.toString();
                                    if (shebangExecutable.startsWith("/usr") || shebangExecutable.startsWith("/bin")) {
                                        String[] parts = shebangExecutable.split("/");
                                        String binary = parts[parts.length - 1];
                                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + binary;
                                    }
                                    break;
                                }
                            } else {
                                builder.append(c);
                            }
                        }
                    } else {
                        // No shebang and no ELF, use standard shell.
                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
                    }
                }
            }
        } catch (IOException e) {
            // Ignore.
        }

        List<String> result = new ArrayList<>();
        if (interpreter != null) result.add(interpreter);
        result.add(executable);
        if (arguments != null) Collections.addAll(result, arguments);
        return result.toArray(new String[0]);
    }

    /** Clear files under {@link TermuxConstants#TERMUX_TMP_PREFIX_DIR_PATH}. */
    public static void clearTermuxTMPDIR(boolean onlyIfExists) {
        if(onlyIfExists && !FileUtils.directoryFileExists(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, false))
            return;

        Error error;

        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        int days = properties.getDeleteTMPDIRFilesOlderThanXDaysOnExit();

        // Disable currently until FileUtils.deleteFilesOlderThanXDays() is fixed.
        if (days > 0)
            days = 0;

        if (days < 0) {
            Logger.logInfo(LOG_TAG, "Not clearing termux $TMPDIR");
        } else if (days == 0) {
            error = FileUtils.clearDirectory("$TMPDIR",
                FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null));
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Failed to clear termux $TMPDIR\n" + error);
            }
        } else {
            error = FileUtils.deleteFilesOlderThanXDays("$TMPDIR",
                FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null),
                TrueFileFilter.INSTANCE, days, true, FileTypes.FILE_TYPE_ANY_FLAGS);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Failed to delete files from termux $TMPDIR older than " + days + " days\n" + error);
            }
        }
    }

}
