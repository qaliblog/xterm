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
            String filesDir = currentPackageContext.getFilesDir().getAbsolutePath();
            String initHostPath = filesDir + "/bin/init-host.sh";

            List<String> result = new ArrayList<>();
            result.add("/system/bin/sh");
            result.add("-c");

            StringBuilder guestCommand = new StringBuilder();
            guestCommand.append(initHostPath);

            // If it's not a standard shell, pass the executable and arguments to init-host.sh
            if (executable != null && !executable.endsWith("/sh") && !executable.endsWith("/bash") && !executable.endsWith("/zsh")) {
                guestCommand.append(" ").append(executable);
                if (arguments != null) {
                    for (String arg : arguments) {
                        guestCommand.append(" ").append(arg);
                    }
                }
            }

            result.add(guestCommand.toString());
            return result.toArray(new String[0]);
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
