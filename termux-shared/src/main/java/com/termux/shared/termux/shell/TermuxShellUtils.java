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

            String linker = new File("/system/bin/linker64").exists() ? "/system/bin/linker64" : "/system/bin/linker";
            prootArgs.add(linker);
            prootArgs.add(filesDir + "/bin/proot");
            prootArgs.add("-r");
            prootArgs.add(rootfsDirFile.getAbsolutePath());
            prootArgs.add("-0");
            prootArgs.add("-p"); // link2symlink
            prootArgs.add("-L");
            prootArgs.add("-w");
            prootArgs.add("/root");
            prootArgs.add("-b");
            prootArgs.add("/dev");
            prootArgs.add("-b");
            prootArgs.add("/proc");
            prootArgs.add("-b");
            prootArgs.add("/sys");
            String[] systemBinds = {
                "/system", "/vendor", "/apex", "/odm", "/product", "/system_ext",
                "/linkerconfig/ld.config.txt", "/linkerconfig/com.android.art/ld.config.txt",
                "/plat_property_contexts", "/property_contexts"
            };
            for (String bind : systemBinds) {
                if (new File(bind).exists()) {
                    prootArgs.add("-b");
                    prootArgs.add(bind);
                }
            }
            if (new File("/sdcard").exists()) {
                prootArgs.add("-b");
                prootArgs.add("/sdcard");
            }
            if (new File("/storage").exists()) {
                prootArgs.add("-b");
                prootArgs.add("/storage");
            }
            prootArgs.add("-b");
            prootArgs.add("/dev/urandom:/dev/random");
            prootArgs.add("-b");
            prootArgs.add(filesDir + "/tmp:/tmp");
            prootArgs.add("-b");
            prootArgs.add(filesDir + "/tmp:/dev/shm");
            prootArgs.add("-b");
            prootArgs.add(filesDir + "/home:/root");
            prootArgs.add("-b");
            prootArgs.add(filesDir);

            // Fix for hardcoded com.termux paths in some proot builds
            prootArgs.add("-b");
            prootArgs.add(filesDir + ":/data/data/com.termux/files");
            prootArgs.add("-b");
            prootArgs.add(filesDir + ":/data/data/com.xterm/files");

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

            // Use the detected shell directly. Proot will handle basic setup.
            prootArgs.add(shell);
            if (shell.endsWith("sh")) {
                prootArgs.add("-l");
            }

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
