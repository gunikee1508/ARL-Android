package com.samp.mobile.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ARL Phase 2 data updater.
 *
 * The launcher downloads only the ARL data package described by launcher.json,
 * verifies the whole archive with SHA-256, extracts it into an isolated staging
 * directory and only then merges it into the app-specific game directory.
 *
 * The ZIP must contain paths relative to getExternalFilesDir(null), for example:
 *   SAMP/...
 *   texdb/...
 *   data/...
 */
public final class ArlDataManager {
    public interface Listener {
        void onState(String text);
        void onProgress(int percent, String text);
        void onComplete(boolean success, String message);
    }

    private static final String PREFS = "arl_data_state";
    private static final String KEY_VERSION = "installed_version";
    private static final String KEY_SHA256 = "installed_sha256";
    private static final int BUFFER = 64 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ArlDataManager() {}

    public static boolean isConfigured() {
        String url = ArlRemoteConfig.dataUrl();
        String sha = ArlRemoteConfig.dataSha256();
        return url != null && (url.startsWith("https://") || url.startsWith("http://"))
                && sha != null && sha.matches("(?i)[0-9a-f]{64}");
    }

    public static String installedVersion(Context context) {
        return prefs(context).getString(KEY_VERSION, "");
    }

    public static boolean requiresRepair(Context context) {
        if (!isConfigured()) return false;

        String remote = safe(ArlRemoteConfig.dataVersion());
        if (remote.isEmpty() || !remote.equals(installedVersion(context))) return true;

        File root = context.getExternalFilesDir(null);
        if (root == null) return true;

        for (String relative : ArlRemoteConfig.requiredFiles()) {
            File f = resolveInside(root, relative);
            if (f == null || !f.isFile() || f.length() <= 0) return true;
        }
        return false;
    }

    public static void verifyOrRepair(Context context, boolean force, Listener listener) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (!isConfigured()) {
                    complete(listener, false,
                            "A DATA do ARL ainda não foi publicada no launcher.json.");
                    return;
                }

                if (!force && !requiresRepair(app)) {
                    progress(listener, 100, "ARQUIVOS ATUALIZADOS");
                    complete(listener, true,
                            "Arquivos do ARL verificados. Versão " + ArlRemoteConfig.dataVersion() + ".");
                    return;
                }

                runRepair(app, listener);
            } catch (Exception e) {
                complete(listener, false, "Falha ao reparar DATA: " + readable(e));
            }
        }, "ARL-Data-Updater").start();
    }

    private static void runRepair(Context context, Listener listener) throws Exception {
        File root = context.getExternalFilesDir(null);
        if (root == null) throw new IllegalStateException("armazenamento externo indisponível");

        File work = new File(root, "download/arl_phase2");
        File zipPart = new File(work, "arl-data.zip.part");
        File stage = new File(work, "stage");
        deleteTree(work);
        if (!stage.mkdirs() && !stage.isDirectory())
            throw new IllegalStateException("não foi possível criar diretório temporário");

        state(listener, "BAIXANDO DATA DO ARL...");
        DownloadResult result = download(ArlRemoteConfig.dataUrl(), zipPart, listener);

        long expectedBytes = ArlRemoteConfig.dataBytes();
        if (expectedBytes > 0 && result.bytes != expectedBytes) {
            throw new IllegalStateException("tamanho inválido: " + result.bytes + " / " + expectedBytes);
        }

        String expectedSha = safe(ArlRemoteConfig.dataSha256()).toLowerCase(Locale.US);
        if (!expectedSha.equals(result.sha256)) {
            throw new SecurityException("SHA-256 da DATA não confere");
        }

        progress(listener, 86, "HASH OK • EXTRAINDO...");
        unzipSafely(zipPart, stage, listener);

        for (String relative : ArlRemoteConfig.requiredFiles()) {
            File f = resolveInside(stage, relative);
            if (f == null || !f.isFile() || f.length() <= 0)
                throw new IllegalStateException("arquivo obrigatório ausente no pacote: " + relative);
        }

        progress(listener, 97, "INSTALANDO ARQUIVOS...");
        mergeTree(stage, root);

        for (String relative : ArlRemoteConfig.requiredFiles()) {
            File f = resolveInside(root, relative);
            if (f == null || !f.isFile() || f.length() <= 0)
                throw new IllegalStateException("arquivo obrigatório não foi instalado: " + relative);
        }

        prefs(context).edit()
                .putString(KEY_VERSION, safe(ArlRemoteConfig.dataVersion()))
                .putString(KEY_SHA256, expectedSha)
                .apply();

        deleteTree(work);
        progress(listener, 100, "DATA PRONTA");
        complete(listener, true,
                "DATA do ARL instalada com sucesso. Versão " + ArlRemoteConfig.dataVersion() + ".");
    }

    private static DownloadResult download(String source, File target, Listener listener) throws Exception {
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();

        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "ARL-Android/" + ArlConfig.CLIENT_VERSION);
        connection.connect();

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300)
            throw new IllegalStateException("HTTP " + code + " ao baixar DATA");

        long total = connection.getContentLengthLong();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long done = 0;
        int lastPercent = -1;

        try (InputStream in = new BufferedInputStream(connection.getInputStream(), BUFFER);
             FileOutputStream fileOut = new FileOutputStream(target);
             BufferedOutputStream out = new BufferedOutputStream(fileOut, BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                done += n;

                int p;
                if (total > 0) p = (int) Math.min(85, (done * 85L) / total);
                else p = (int) Math.min(84, done / (1024L * 1024L));

                if (p != lastPercent) {
                    lastPercent = p;
                    progress(listener, p, "BAIXANDO • " + human(done) +
                            (total > 0 ? " / " + human(total) : ""));
                }
            }
            out.flush();
            fileOut.getFD().sync();
        } finally {
            connection.disconnect();
        }

        return new DownloadResult(done, hex(digest.digest()));
    }

    private static void unzipSafely(File zip, File stage, Listener listener) throws Exception {
        String rootPath = stage.getCanonicalPath() + File.separator;
        int entries = 0;

        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zip), BUFFER))) {
            ZipEntry entry;
            byte[] buf = new byte[BUFFER];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals(".."))
                    throw new SecurityException("caminho inválido no ZIP: " + name);

                File out = new File(stage, name);
                String outPath = out.getCanonicalPath();
                if (!outPath.equals(stage.getCanonicalPath()) && !outPath.startsWith(rootPath))
                    throw new SecurityException("ZIP tentou sair da pasta de destino");

                if (entry.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory())
                        throw new IllegalStateException("falha criando pasta: " + name);
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory())
                        throw new IllegalStateException("falha criando pasta para: " + name);

                    try (BufferedOutputStream bos = new BufferedOutputStream(
                            new FileOutputStream(out), BUFFER)) {
                        int n;
                        while ((n = zis.read(buf)) != -1) bos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
                entries++;
                if (entries % 20 == 0)
                    progress(listener, Math.min(96, 86 + entries / 20),
                            "EXTRAINDO • " + entries + " arquivos");
            }
        }

        if (entries == 0) throw new IllegalStateException("pacote DATA vazio");
    }

    private static void mergeTree(File source, File destination) throws Exception {
        File[] files = source.listFiles();
        if (files == null) return;

        for (File src : files) {
            File dst = new File(destination, src.getName());
            if (src.isDirectory()) {
                if (!dst.mkdirs() && !dst.isDirectory())
                    throw new IllegalStateException("não foi possível criar " + dst.getName());
                mergeTree(src, dst);
            } else {
                copy(src, dst);
            }
        }
    }

    private static void copy(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory())
            throw new IllegalStateException("não foi possível criar destino");

        File tmp = new File(dst.getAbsolutePath() + ".arlnew");
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(src), BUFFER);
             FileOutputStream fos = new FileOutputStream(tmp);
             BufferedOutputStream out = new BufferedOutputStream(fos, BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            fos.getFD().sync();
        }

        if (dst.exists() && !dst.delete()) throw new IllegalStateException("falha substituindo " + dst.getName());
        if (!tmp.renameTo(dst)) throw new IllegalStateException("falha finalizando " + dst.getName());
    }

    private static File resolveInside(File root, String relative) {
        try {
            if (relative == null) return null;
            String clean = relative.trim().replace('\\', '/');
            while (clean.startsWith("/")) clean = clean.substring(1);
            if (clean.isEmpty() || clean.contains("../") || clean.equals("..")) return null;
            File f = new File(root, clean);
            String rp = root.getCanonicalPath() + File.separator;
            String fp = f.getCanonicalPath();
            return fp.startsWith(rp) ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File c : children) deleteTree(c);
        }
        file.delete();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private static String readable(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static void state(Listener l, String text) {
        if (l != null) MAIN.post(() -> l.onState(text));
    }

    private static void progress(Listener l, int percent, String text) {
        if (l != null) MAIN.post(() -> l.onProgress(Math.max(0, Math.min(100, percent)), text));
    }

    private static void complete(Listener l, boolean success, String message) {
        if (l != null) MAIN.post(() -> l.onComplete(success, message));
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
