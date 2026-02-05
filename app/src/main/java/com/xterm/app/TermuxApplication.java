package com.xterm.app;

import android.app.Application;
import android.content.Context;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;

public class TermuxApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context, true);
        if (preferences == null) {
            return;
        }

        // Set log level
        Logger.setLogLevel(context, preferences.getLogLevel());

        // Ensure essential directories exist
        java.io.File filesDir = context.getFilesDir();
        if (filesDir != null) {
            new java.io.File(filesDir, "home").mkdirs();
            new java.io.File(filesDir, "usr").mkdirs();
            new java.io.File(filesDir, "tmp").mkdirs();
        }
    }
}
