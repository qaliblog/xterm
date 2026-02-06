package com.xterm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.system.Os;

import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.xterm.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.errors.Error;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

public final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(activity);
        if (preferences == null) {
            whenDone.run();
            return;
        }

        if (!preferences.isRootfsInstalled()) {
            Intent intent = new Intent(activity, com.xterm.app.activities.SetupActivity.class);
            activity.startActivityForResult(intent, 1234);
            return;
        }

        whenDone.run();
    }

    public static void installRootfs(final Activity activity, final Runnable whenDone) {
        final ProgressDialog progress = ProgressDialog.show(activity, null, "Initializing terminal...", true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(activity);
                    if (preferences == null) return;

                    File filesDir = activity.getFilesDir();
                    if (!filesDir.exists()) filesDir.mkdirs();

                    File rootfsDir = new File(filesDir, "rootfs");
                    Logger.logInfo(LOG_TAG, "Preparing rootfs directory at " + rootfsDir.getAbsolutePath());
                    FileUtils.clearDirectory("rootfs", rootfsDir.getAbsolutePath());
                    if (!rootfsDir.exists()) rootfsDir.mkdirs();
                    try { Os.chmod(rootfsDir.getAbsolutePath(), 0755); } catch (Exception e) {}

                    File tmpDir = new File(filesDir, "tmp");
                    FileUtils.clearDirectory("tmp", tmpDir.getAbsolutePath());
                    if (!tmpDir.exists()) tmpDir.mkdirs();
                    try { Os.chmod(tmpDir.getAbsolutePath(), 0777); } catch (Exception e) {}

                    installNeededAssets(activity);

                    String url = preferences.getRootfsBundleUrl();
                    String localPath = preferences.getRootfsBundleLocalPath();

                    Logger.logInfo(LOG_TAG, "Starting rootfs extraction...");
                    if (localPath != null) {
                        installLocalRootfs(activity, Uri.parse(localPath), rootfsDir);
                    } else if (url != null) {
                        installRemoteRootfs(url, rootfsDir);
                    }

                    // Flatten nested rootfs if necessary (e.g. archive contains a single top-level directory)
                    flattenRootfsIfNested(rootfsDir);

                    // Check if extraction produced any results
                    String[] contents = rootfsDir.list();
                    if (contents == null || contents.length == 0) {
                        throw new Exception("Rootfs extraction produced no files.");
                    }
                    Logger.logInfo(LOG_TAG, "Rootfs extraction completed. Items in rootfs: " + contents.length);

                    preferences.setRootfsInstalled(true);
                    activity.runOnUiThread(whenDone);
                } catch (final Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to install rootfs", e);
                    activity.runOnUiThread(() -> {
                        new AlertDialog.Builder(activity)
                            .setTitle("Installation Failed")
                            .setMessage(e.getMessage())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    });
                } finally {
                    activity.runOnUiThread(progress::dismiss);
                }
            }
        }.start();
    }

    private static void flattenRootfsIfNested(File rootfsDir) {
        File[] items = rootfsDir.listFiles();
        if (items != null && items.length == 1 && items[0].isDirectory()) {
            File nestedDir = items[0];
            Logger.logInfo(LOG_TAG, "Detected nested rootfs directory: " + nestedDir.getName() + ". Flattening...");
            File[] contents = nestedDir.listFiles();
            if (contents != null) {
                for (File content : contents) {
                    File target = new File(rootfsDir, content.getName());
                    if (!content.renameTo(target)) {
                        Logger.logWarn(LOG_TAG, "Failed to move " + content.getName() + " to " + target.getAbsolutePath());
                    }
                }
            }
            if (!nestedDir.delete()) {
                Logger.logWarn(LOG_TAG, "Failed to delete empty nested directory: " + nestedDir.getAbsolutePath());
            }
        }
    }

    private static void installNeededAssets(Context context) throws Exception {
        String arch = getArch();
        File binDir = new File(context.getFilesDir(), "bin");
        if (!binDir.exists()) binDir.mkdirs();
        try { Os.chmod(binDir.getAbsolutePath(), 0755); } catch (Exception e) {}

        Logger.logInfo(LOG_TAG, "Installing bundled proot assets for " + arch);
        String assetPath = "bin/" + arch;
        String[] assets = context.getAssets().list(assetPath);
        if (assets != null) {
            for (String asset : assets) {
                File outFile = new File(binDir, asset);
                try (java.io.InputStream in = context.getAssets().open(assetPath + "/" + asset);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                Os.chmod(outFile.getAbsolutePath(), 0755);
            }
        }

        File usrDir = new File(context.getFilesDir(), "usr");
        if (!usrDir.exists() || com.termux.shared.termux.file.TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
            Logger.logInfo(LOG_TAG, "Installing bundled Termux bootstrap");
            if (!usrDir.exists()) usrDir.mkdirs();
            try (java.io.InputStream in = context.getAssets().open("bootstraps/bootstrap-" + arch + ".zip")) {
                extractZip(in, usrDir);
            }
        }
    }

    private static String getArch() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi.contains("arm64") || abi.contains("aarch64")) return "aarch64";
            if (abi.contains("armeabi") || abi.contains("arm")) return "arm";
            if (abi.contains("x86_64")) return "x86_64";
            if (abi.contains("x86") || abi.contains("i686")) return "i686";
        }
        return "aarch64";
    }

    private static void downloadFile(String urlStr, File dest) throws IOException {
        java.net.URL url = new java.net.URL(urlStr);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        try (java.io.InputStream in = conn.getInputStream();
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static void installLocalRootfs(Context context, Uri uri, File destDir) throws Exception {
        try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Failed to open stream for " + uri);
            extractBundle(in, uri.toString(), destDir);
        }
    }

    private static void installRemoteRootfs(String url, File destDir) throws Exception {
        File tempFile = File.createTempFile("rootfs", ".tmp");
        try {
            downloadFile(url, tempFile);
            try (java.io.InputStream in = new java.io.FileInputStream(tempFile)) {
                extractBundle(in, url, destDir);
            }
        } finally {
            tempFile.delete();
        }
    }

    private static void extractBundle(java.io.InputStream in, String fileName, File destDir) throws Exception {
        if (fileName.endsWith(".zip")) {
            extractZip(in, destDir);
        } else if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
            extractTar(in, destDir, "gzip");
        } else if (fileName.endsWith(".tar.xz")) {
            extractTar(in, destDir, "xz");
        } else if (fileName.endsWith(".tar")) {
            extractTar(in, destDir, null);
        } else {
            try {
                extractTar(in, destDir, "gzip");
            } catch (Exception e) {
                throw new Exception("Unsupported rootfs bundle format: " + fileName);
            }
        }
    }

    private static void extractZip(java.io.InputStream in, File destDir) throws IOException {
        try (ZipInputStream zipIn = new ZipInputStream(in)) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zipIn.getNextEntry()) != null) {
                File file = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    file.mkdirs();
                    try { Os.chmod(file.getAbsolutePath(), 0755); } catch (Exception e) {}
                } else {
                    File parent = file.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                        try { Os.chmod(parent.getAbsolutePath(), 0755); } catch (Exception e) {}
                    }
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = zipIn.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                    try {
                        String name = entry.getName();
                        if (name.contains("bin/") || name.contains("sbin/") || name.endsWith(".so")) {
                            Os.chmod(file.getAbsolutePath(), 0755);
                        } else {
                            Os.chmod(file.getAbsolutePath(), 0644);
                        }
                    } catch (Exception e) {}
                }
                count++;
                if (count % 100 == 0) Logger.logVerbose(LOG_TAG, "Extracted " + count + " zip entries");
            }
        }
    }

    private static void extractTar(java.io.InputStream in, File destDir, String compression) throws Exception {
        java.io.InputStream decompressed;
        if ("gzip".equals(compression)) {
            decompressed = new GzipCompressorInputStream(in);
        } else if ("xz".equals(compression)) {
            decompressed = new XZCompressorInputStream(in);
        } else {
            decompressed = in;
        }

        try (TarArchiveInputStream tarIn = new TarArchiveInputStream(decompressed)) {
            TarArchiveEntry entry;
            int count = 0;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                File file = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    file.mkdirs();
                    try { Os.chmod(file.getAbsolutePath(), 0755); } catch (Exception e) {}
                } else {
                    File parent = file.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                        try { Os.chmod(parent.getAbsolutePath(), 0755); } catch (Exception e) {}
                    }
                    if (entry.isSymbolicLink()) {
                        try { Os.symlink(entry.getLinkName(), file.getAbsolutePath()); } catch (Exception e) {}
                    } else if (entry.isLink()) {
                         // Skip hard links
                    } else {
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = tarIn.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                            }
                        }
                        try {
                            int mode = entry.getMode();
                            if (mode != 0) {
                                mode |= 0444; // ensure readable
                                if (entry.isDirectory()) mode |= 0111; // ensure searchable
                                Os.chmod(file.getAbsolutePath(), mode);
                            } else {
                                String name = entry.getName();
                                if (name.contains("bin/") || name.contains("sbin/") || name.endsWith(".so")) {
                                    Os.chmod(file.getAbsolutePath(), 0755);
                                } else {
                                    Os.chmod(file.getAbsolutePath(), 0644);
                                }
                            }
                        } catch (Exception e) {}
                    }
                }
                count++;
                if (count % 100 == 0) Logger.logVerbose(LOG_TAG, "Extracted " + count + " tar entries");
            }
        }
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);
        sendBootstrapCrashReportNotification(activity, message);
        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (Exception e1) {
                // Ignore
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";
        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");
        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;
                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        return;
                    }
                    File sharedDir = android.os.Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error", e);
                }
            }
        }.start();
    }

}
