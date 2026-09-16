package com.samp.mobile.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/** Downloads, verifies and hands an ARL launcher APK to Android's package installer. */
public final class ArlAppUpdateManager {
    public interface Listener {
        void onProgress(int percent, String text);
        void onComplete(boolean success, String message, File apk);
    }

    private static final int BUFFER = 64 * 1024;

    private ArlAppUpdateManager() {}

    public static long currentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return info.getLongVersionCode();
            return info.versionCode;
        } catch (Exception e) {
            return -1L;
        }
    }

    public static boolean updateAvailable(Context context) {
        return ArlRemoteConfig.hasAppRelease()
                && currentVersionCode(context) > 0
                && ArlRemoteConfig.appLatestVersionCode() > currentVersionCode(context);
    }

    public static boolean updateRequired(Context context) {
        if(!updateAvailable(context)) return false;
        long current = currentVersionCode(context);
        return ArlRemoteConfig.appMandatory()
                || (ArlRemoteConfig.appMinVersionCode() > 0
                    && current < ArlRemoteConfig.appMinVersionCode());
    }

    public static void download(Context context, Listener listener) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            File result = null;
            try {
                if(!updateAvailable(app)) {
                    complete(app, listener, false, "Nenhuma atualização do launcher disponível.", null);
                    return;
                }

                File root = app.getExternalFilesDir(null);
                if(root == null) throw new IllegalStateException("armazenamento indisponível");
                File dir = new File(root, "download/arl_app");
                if(!dir.mkdirs() && !dir.isDirectory())
                    throw new IllegalStateException("não foi possível criar pasta de atualização");

                File part = new File(dir, "Amazing-Real-Life.apk.part");
                File apk = new File(dir, "Amazing-Real-Life.apk");
                if(part.exists()) part.delete();
                if(apk.exists()) apk.delete();

                DownloadResult downloaded = downloadFile(
                        ArlRemoteConfig.appApkUrl(), part, listener, app);

                long expectedBytes = ArlRemoteConfig.appBytes();
                if(expectedBytes > 0 && downloaded.bytes != expectedBytes)
                    throw new IllegalStateException("tamanho do APK não confere");

                String expectedSha = ArlRemoteConfig.appSha256().trim().toLowerCase(Locale.US);
                if(!expectedSha.equals(downloaded.sha256))
                    throw new SecurityException("SHA-256 do APK não confere");

                if(!part.renameTo(apk))
                    throw new IllegalStateException("não foi possível finalizar o APK");

                result = apk;
                progress(app, listener, 100, "APK VERIFICADO");
                complete(app, listener, true, "Atualização do launcher pronta para instalar.", apk);
            } catch(Exception e) {
                complete(app, listener, false, "Falha ao atualizar launcher: " + readable(e), result);
            }
        }, "ARL-App-Updater").start();
    }

    public static boolean canInstallPackages(Context context) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        return context.getPackageManager().canRequestPackageInstalls();
    }

    public static void openUnknownSourcesSettings(Context context) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void install(Context context, File apk) {
        if(apk == null || !apk.isFile())
            throw new IllegalArgumentException("APK de atualização ausente");

        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".provider",
                apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static DownloadResult downloadFile(
            String source, File target, Listener listener, Context context) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(source).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ARL-Android/" + ArlConfig.CLIENT_VERSION);
        c.connect();

        int code = c.getResponseCode();
        if(code < 200 || code >= 300)
            throw new IllegalStateException("HTTP " + code + " ao baixar APK");

        long total = c.getContentLengthLong();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long done = 0L;
        int last = -1;

        try(InputStream raw = c.getInputStream();
            BufferedInputStream in = new BufferedInputStream(raw, BUFFER);
            FileOutputStream fos = new FileOutputStream(target);
            BufferedOutputStream out = new BufferedOutputStream(fos, BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                done += n;
                int percent = total > 0 ? (int)Math.min(99, (done * 100L) / total) : 0;
                if(percent != last) {
                    last = percent;
                    progress(context, listener, percent,
                            "BAIXANDO LAUNCHER • " + human(done) +
                                    (total > 0 ? " / " + human(total) : ""));
                }
            }
            out.flush();
            fos.getFD().sync();
        } finally {
            c.disconnect();
        }
        return new DownloadResult(done, hex(digest.digest()));
    }

    private static void progress(Context context, Listener l, int percent, String text) {
        if(l == null) return;
        android.os.Handler main = new android.os.Handler(context.getMainLooper());
        main.post(() -> l.onProgress(Math.max(0, Math.min(100, percent)), text));
    }

    private static void complete(Context context, Listener l, boolean ok, String msg, File apk) {
        if(l == null) return;
        android.os.Handler main = new android.os.Handler(context.getMainLooper());
        main.post(() -> l.onComplete(ok, msg, apk));
    }

    private static String human(long bytes) {
        if(bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if(kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.US, "%.1f MB", mb);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for(byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static String readable(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private static final class DownloadResult {
        final long bytes;
        final String sha256;
        DownloadResult(long bytes, String sha256) {
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }
}
