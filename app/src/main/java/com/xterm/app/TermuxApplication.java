package com.xterm.app;

import android.app.Application;
import android.content.Context;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.theme.TermuxThemeUtils;

import java.io.File;

public class TermuxApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        // Load termux shared preferences
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context, true);
        if (preferences != null) {
            // Set log level
            Logger.setLogLevel(context, preferences.getLogLevel());
        }

        // Setup crash handler
        TermuxCrashUtils.setDefaultCrashHandler(context);

        // Set night mode
        TermuxThemeUtils.setAppNightMode(context);

        // Load Termux app SharedProperties from disk
        TermuxAppSharedProperties.init(context);

        // Initialize Termux shell manager
        TermuxShellManager.init(context);

        // Ensure essential directories exist
        File filesDir = context.getFilesDir();
        if (filesDir != null) {
            new File(filesDir, "home").mkdirs();
            new File(filesDir, "usr").mkdirs();
            new File(filesDir, "tmp").mkdirs();
        }
    }
}
