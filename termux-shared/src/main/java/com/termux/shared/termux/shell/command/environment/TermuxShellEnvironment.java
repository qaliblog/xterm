package com.termux.shared.termux.shell.command.environment;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.AndroidShellEnvironment;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.shell.command.environment.ShellCommandShellEnvironment;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellUtils;

import java.io.File;
import java.nio.charset.Charset;
import java.util.HashMap;

/**
 * Environment for Termux.
 */
public class TermuxShellEnvironment extends AndroidShellEnvironment {

    private static final String LOG_TAG = "TermuxShellEnvironment";

    /** Environment variable for the termux {@link TermuxConstants#TERMUX_PREFIX_DIR_PATH}. */
    public static final String ENV_PREFIX = "PREFIX";

    public TermuxShellEnvironment() {
        super();
        shellCommandShellEnvironment = new TermuxShellCommandShellEnvironment();
    }


    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void init(@NonNull Context currentPackageContext) {
        TermuxAppShellEnvironment.setTermuxAppEnvironment(currentPackageContext);
    }

    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void writeEnvironmentToFile(@NonNull Context currentPackageContext) {
        HashMap<String, String> environmentMap = new TermuxShellEnvironment().getEnvironment(currentPackageContext, false);
        String environmentString = ShellEnvironmentUtils.convertEnvironmentToDotEnvFile(environmentMap);

        // Write environment string to temp file and then move to final location since otherwise
        // writing may happen while file is being sourced/read
        Error error = FileUtils.writeTextToFile("termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH,
            Charset.defaultCharset(), environmentString, false);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
            return;
        }

        error = FileUtils.moveRegularFile("termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH, TermuxConstants.TERMUX_ENV_FILE_PATH, true);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
        }
    }

    /** Get shell environment for Termux. */
    @NonNull
    @Override
    public HashMap<String, String> getEnvironment(@NonNull Context currentPackageContext, boolean isFailSafe) {

        // Termux environment builds upon the Android environment
        HashMap<String, String> environment = super.getEnvironment(currentPackageContext, isFailSafe);

        HashMap<String, String> termuxAppEnvironment = TermuxAppShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxAppEnvironment != null)
            environment.putAll(termuxAppEnvironment);

        HashMap<String, String> termuxApiAppEnvironment = TermuxAPIShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxApiAppEnvironment != null)
            environment.putAll(termuxApiAppEnvironment);

        String filesDir = currentPackageContext.getFilesDir().getAbsolutePath();

        com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences preferences = com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences.build(currentPackageContext);
        boolean isRootfsInstalled = preferences != null && preferences.isRootfsInstalled();

        // Always set host-side environment variables properly to valid host directories
        environment.put(ENV_HOME, filesDir + "/home");
        environment.put(ENV_PREFIX, filesDir + "/usr");
        environment.put(ENV_TMPDIR, filesDir + "/tmp");
        environment.put("FILES_DIR", filesDir);

        // Ensure host PATH includes app's bin directory for proot
        String appBinDir = filesDir + "/bin";
        String currentPath = environment.get(ENV_PATH);
        if (currentPath == null) {
            environment.put(ENV_PATH, appBinDir + ":/system/bin:/system/xbin");
        } else if (!currentPath.contains(appBinDir)) {
            environment.put(ENV_PATH, appBinDir + ":" + currentPath);
        }

        environment.put("PROOT_TMP_DIR", filesDir + "/tmp");
        // Following termos, we don't disable seccomp by default unless troubleshooting
        // environment.put("PROOT_NO_SECCOMP", "1");
        // environment.put("PROOT_SECCOMP", "0");
        environment.put("PROOT_NO_HARDLINKS", "1");
        environment.put("PROOT_LOADER", "");
        environment.put("PROOT_LOADER32", "");
        environment.put("PROOT_FORCE_PTRACE_TRACEME", "1");

        // Set linker path
        File linker64 = new File("/system/bin/linker64");
        environment.put("LINKER", linker64.exists() ? "/system/bin/linker64" : "/system/bin/linker");

        if (isRootfsInstalled) {
            environment.remove("LD_PRELOAD");
            if (preferences != null) {
                String bundlePath = preferences.getRootfsBundleLocalPath();
                if (bundlePath != null) {
                    environment.put("ROOTFS_BUNDLE_PATH", bundlePath);
                }
            }
        }

        // Add app's local lib dir to LD_LIBRARY_PATH to support proot and its libraries
        String localLibDir = filesDir.getParent() + "/local/lib";
        String currentLdLibraryPath = environment.get(ENV_LD_LIBRARY_PATH);
        if (currentLdLibraryPath == null) {
            environment.put(ENV_LD_LIBRARY_PATH, localLibDir + ":" + appBinDir);
        } else if (!currentLdLibraryPath.contains(localLibDir)) {
            environment.put(ENV_LD_LIBRARY_PATH, localLibDir + ":" + appBinDir + ":" + currentLdLibraryPath);
        }

        return environment;
    }


    @NonNull
    @Override
    public String getDefaultWorkingDirectoryPath() {
        return TermuxConstants.TERMUX_FILES_DIR_PATH;
    }

    @NonNull
    @Override
    public String getDefaultBinPath() {
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
    }

    @NonNull
    @Override
    public String[] setupShellCommandArguments(@NonNull Context currentPackageContext, @NonNull String executable, String[] arguments) {
        return TermuxShellUtils.setupShellCommandArguments(currentPackageContext, executable, arguments);
    }

}
