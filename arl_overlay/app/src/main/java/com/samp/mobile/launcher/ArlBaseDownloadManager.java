package com.samp.mobile.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Phase 11 authorized GTA base downloader.
 *
 * This class contains no Rockstar content and no hard-coded GTA download URL.
 * It becomes active only when launcher config AND the fetched base manifest
 * explicitly declare distributionAuthorized=true.
 */
public final class ArlBaseDownloadManager {
    public interface Listener {
        void onState(String text);
        void onProgress(int percent, String text);
        void onComplete(boolean success, String message);
    }

    private interface DownloadProgress { void onBytes(long bytes); }

    private static final String PREFS = "arl_remote_base_state";
    private static final String KEY_VERSION = "installed_version";
    private static final int BUFFER = 64 * 1024;
    private static final int MAX_ZIP_ENTRIES = 250000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ArlBaseDownloadManager() {}

    public static boolean isConfigured() {
        if (!ArlRemoteConfig.baseDistributionAuthorized()) return false;
        if (!ArlRemoteConfig.hasBaseRelease()) return false;

        if (ArlLegacyBaseDownloader.isConfigured()) return true;

        if (!ArlRemoteConfig.baseManifestReady()) return false;
        if (safe(ArlRemoteConfig.baseVersion()).isEmpty()) return false;
        List<ArlRemoteConfig.DataPackage> packages = ArlRemoteConfig.basePackages();
        if (packages.isEmpty()) return false;

        for (ArlRemoteConfig.DataPackage pkg : packages) {
            if (pkg == null || !pkg.valid()) return false;
            for (ArlRemoteConfig.DataFile file : pkg.files) {
                if (!validRelativePath(file.path) || !file.hasSha256() || file.bytes < 0) return false;
            }
        }
        return true;
    }

    public static String installedVersion(Context context) {
        return prefs(context).getString(KEY_VERSION, "");
    }

    public static boolean requiresRepair(Context context) {
        if (!isConfigured()) return false;
        if (ArlLegacyBaseDownloader.isConfigured())
            return !ArlGtaBaseValidator.isValid(context);
        File root = context.getExternalFilesDir(null);
        if (root == null) return true;
        if (!ArlGtaBaseValidator.isValid(root)) return true;
        if (!safe(ArlRemoteConfig.baseVersion()).equals(installedVersion(context))) return true;

        for (ArlRemoteConfig.DataFile spec : ArlRemoteConfig.baseFiles()) {
            if (!verifyFile(root, spec)) return true;
        }
        return false;
    }

    public static void installOrRepair(Context context, boolean force, Listener listener) {
        if (ArlLegacyBaseDownloader.isConfigured()) {
            ArlLegacyBaseDownloader.installOrRepair(context, force, listener);
            return;
        }

        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (!isConfigured()) {
                    complete(listener, false,
                            "Nenhuma distribuição autorizada da base GTA SA está configurada.");
                    return;
                }

                if (!force && !requiresRepair(app)) {
                    progress(listener, 100, "BASE GTA SA VERIFICADA");
                    complete(listener, true, "Base GTA SA já está pronta.");
                    return;
                }

                runRepair(app, force, listener);
            } catch (Exception e) {
                complete(listener, false, "Falha ao preparar base GTA SA: " + readable(e));
            }
        }, "ARL-Authorized-Base").start();
    }

    private static void runRepair(Context context, boolean force, Listener listener)
            throws Exception {
        File root = context.getExternalFilesDir(null);
        if (root == null) throw new IllegalStateException("armazenamento indisponível");

        List<ArlRemoteConfig.DataPackage> needed = new ArrayList<>();
        boolean versionChanged = !safe(ArlRemoteConfig.baseVersion()).equals(installedVersion(context));
        for (ArlRemoteConfig.DataPackage pkg : ArlRemoteConfig.basePackages()) {
            if (force || versionChanged || packageNeedsRepair(root, pkg)) needed.add(pkg);
        }

        if (needed.isEmpty()) {
            if (!ArlGtaBaseValidator.isValid(root))
                throw new IllegalStateException("a base instalada não passou na validação GTA SA");
            persist(context);
            progress(listener, 100, "BASE GTA SA PRONTA");
            complete(listener, true, "Base GTA SA verificada.");
            return;
        }

        ensureFreeSpace(root, needed);
        File work = new File(root, "download/arl_phase11_base");
        if (!work.mkdirs() && !work.isDirectory())
            throw new IllegalStateException("não foi possível criar diretório de download");

        long total = 0L;
        for (ArlRemoteConfig.DataPackage pkg : needed) total += Math.max(1L, pkg.bytes);
        long completed = 0L;
        int index = 0;

        for (ArlRemoteConfig.DataPackage pkg : needed) {
            index++;
            String label = "BASE " + index + "/" + needed.size() + " • " + pkg.id;
            state(listener, "BAIXANDO " + label);

            File part = new File(work, sanitizeId(pkg.id) + ".zip.part");
            File stage = new File(work, "stage-" + sanitizeId(pkg.id));
            deleteTree(stage);
            if (!stage.mkdirs() && !stage.isDirectory())
                throw new IllegalStateException("não foi possível criar staging");

            final long before = completed;
            final long grandTotal = total;
            DownloadResult result = downloadResume(pkg.url, part, pkg.bytes, pkg.sha256,
                    bytes -> {
                        int p = (int)Math.min(86L,
                                ((before + bytes) * 86L) / Math.max(1L, grandTotal));
                        progress(listener, p, "BAIXANDO " + label + " • "
                                + human(before + bytes) + " / " + human(grandTotal));
                    });

            if (result.bytes != pkg.bytes)
                throw new SecurityException("tamanho não confere em " + pkg.id);
            if (!pkg.sha256.equalsIgnoreCase(result.sha256)) {
                part.delete();
                throw new SecurityException("SHA-256 não confere em " + pkg.id);
            }

            progress(listener, Math.min(94, 86 + (index * 8 / needed.size())),
                    "VALIDANDO " + label);
            unzipSafely(part, stage, pkg.bytes);

            for (ArlRemoteConfig.DataFile spec : pkg.files) {
                if (!verifyFile(stage, spec))
                    throw new SecurityException("arquivo inválido em " + pkg.id + ": " + spec.path);
            }

            mergeTree(stage, root);
            for (ArlRemoteConfig.DataFile spec : pkg.files) {
                if (!verifyFile(root, spec))
                    throw new SecurityException("arquivo instalado inválido: " + spec.path);
            }

            completed += pkg.bytes;
            deleteTree(stage);
            part.delete();
        }

        if (!ArlGtaBaseValidator.isValid(root))
            throw new SecurityException("a distribuição terminou, mas a base GTA SA não é válida");

        persist(context);
        deleteTree(work);
        progress(listener, 100, "BASE GTA SA PRONTA");
        complete(listener, true,
                "Base GTA SA preparada e verificada. Versão "
                        + ArlRemoteConfig.baseVersion() + ".");
    }

    private static boolean packageNeedsRepair(File root, ArlRemoteConfig.DataPackage pkg) {
        for (ArlRemoteConfig.DataFile spec : pkg.files)
            if (!verifyFile(root, spec)) return true;
        return false;
    }

    private static void persist(Context context) {
        prefs(context).edit()
                .putString(KEY_VERSION, safe(ArlRemoteConfig.baseVersion()))
                .apply();
        // ArlBaseImportManager derives readiness from the actual validated tree.
        ArlBaseImportManager.isBaseReady(context);
    }

    private static void ensureFreeSpace(File root, List<ArlRemoteConfig.DataPackage> packages) {
        try {
            long unpacked = 0L;
            long largest = 0L;
            for (ArlRemoteConfig.DataPackage pkg : packages) {
                largest = Math.max(largest, pkg.bytes);
                for (ArlRemoteConfig.DataFile f : pkg.files) unpacked += Math.max(0L, f.bytes);
            }
            long required = unpacked + largest + (256L * 1024L * 1024L);
            long available = new StatFs(root.getAbsolutePath()).getAvailableBytes();
            if (available < required)
                throw new IllegalStateException("espaço insuficiente: precisa de ~"
                        + human(required) + ", disponível " + human(available));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception ignored) {}
    }

    private static DownloadResult downloadResume(String source, File part, long expectedBytes,
                                                 String expectedSha, DownloadProgress progress)
            throws Exception {
        File parent = part.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory())
            throw new IllegalStateException("não foi possível criar diretório de download");

        if (part.isFile() && part.length() == expectedBytes) {
            String hash = sha256(part);
            if (expectedSha.equalsIgnoreCase(hash))
                return new DownloadResult(part.length(), hash);
            part.delete();
        } else if (part.isFile() && part.length() > expectedBytes) {
            part.delete();
        }

        long existing = part.isFile() ? part.length() : 0L;
        HttpURLConnection c = (HttpURLConnection)new URL(source).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ARL-Android/Phase11");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");
        c.connect();

        int code = c.getResponseCode();
        boolean append = existing > 0 && code == HttpURLConnection.HTTP_PARTIAL;
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new IllegalStateException("HTTP " + code + " ao baixar base");
        }
        if (existing > 0 && code == HttpURLConnection.HTTP_OK) {
            existing = 0L;
            append = false;
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        if (append) {
            try (BufferedInputStream old = new BufferedInputStream(new FileInputStream(part), BUFFER)) {
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = old.read(buf)) != -1) digest.update(buf, 0, n);
            }
        }

        long done = existing;
        if (progress != null) progress.onBytes(done);
        try (InputStream in = new BufferedInputStream(c.getInputStream(), BUFFER);
             FileOutputStream fos = new FileOutputStream(part, append);
             BufferedOutputStream out = new BufferedOutputStream(fos, BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            long last = done;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                done += n;
                if (progress != null && (done - last >= 512 * 1024 || done == expectedBytes)) {
                    last = done;
                    progress.onBytes(done);
                }
            }
            out.flush();
            fos.getFD().sync();
        } finally {
            c.disconnect();
        }
        return new DownloadResult(done, hex(digest.digest()));
    }

    private static void unzipSafely(File zip, File stage, long packageBytes) throws Exception {
        String stageCanonical = stage.getCanonicalPath();
        String rootPath = stageCanonical + File.separator;
        int entries = 0;
        long unpacked = 0L;
        long maxUnpacked = Math.max(2L * 1024L * 1024L * 1024L, packageBytes * 24L);

        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zip), BUFFER))) {
            ZipEntry entry;
            byte[] buf = new byte[BUFFER];
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES)
                    throw new SecurityException("pacote contém arquivos demais");

                String name = entry.getName().replace('\\', '/');
                if (!validRelativePath(name))
                    throw new SecurityException("caminho inválido no ZIP: " + name);

                File out = new File(stage, name);
                String outPath = out.getCanonicalPath();
                if (!outPath.equals(stageCanonical) && !outPath.startsWith(rootPath))
                    throw new SecurityException("ZIP tentou sair da pasta de destino");

                if (entry.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory())
                        throw new IllegalStateException("falha criando pasta");
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory())
                        throw new IllegalStateException("falha criando pasta de arquivo");
                    try (BufferedOutputStream bos = new BufferedOutputStream(
                            new FileOutputStream(out), BUFFER)) {
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            unpacked += n;
                            if (unpacked > maxUnpacked)
                                throw new SecurityException("pacote expandido excedeu o limite");
                            bos.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        if (entries == 0) throw new IllegalStateException("pacote da base vazio");
    }

    private static boolean verifyFile(File root, ArlRemoteConfig.DataFile spec) {
        try {
            if (!validRelativePath(spec.path)) return false;
            File f = new File(root, spec.path);
            String rootPath = root.getCanonicalPath() + File.separator;
            if (!f.getCanonicalPath().startsWith(rootPath)) return false;
            if (!f.isFile()) return false;
            if (spec.bytes >= 0 && f.length() != spec.bytes) return false;
            return spec.hasSha256() && spec.sha256.equalsIgnoreCase(sha256(f));
        } catch (Exception e) {
            return false;
        }
    }

    private static void mergeTree(File source, File destination) throws Exception {
        File[] children = source.listFiles();
        if (children == null) return;
        for (File child : children) {
            File out = new File(destination, child.getName());
            if (child.isDirectory()) {
                if (!out.mkdirs() && !out.isDirectory())
                    throw new IllegalStateException("falha criando " + out.getName());
                mergeTree(child, out);
            } else {
                File parent = out.getParentFile();
                if (parent != null && !parent.mkdirs() && !parent.isDirectory())
                    throw new IllegalStateException("falha criando destino");
                File tmp = new File(out.getAbsolutePath() + ".arlbase");
                copyFile(child, tmp);
                if (out.exists() && !out.delete())
                    throw new IllegalStateException("falha substituindo " + out.getName());
                if (!tmp.renameTo(out))
                    throw new IllegalStateException("falha finalizando " + out.getName());
            }
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(src), BUFFER);
             FileOutputStream fos = new FileOutputStream(dst);
             BufferedOutputStream out = new BufferedOutputStream(fos, BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            fos.getFD().sync();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static boolean validRelativePath(String path) {
        if (path == null) return false;
        String p = path.trim().replace('\\', '/');
        return !p.isEmpty() && !p.startsWith("/") && !p.equals("..")
                && !p.startsWith("../") && !p.contains("/../") && !p.contains(":/");
    }

    private static String sanitizeId(String id) {
        return safe(id).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String readable(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static void state(Listener listener, String text) {
        if (listener != null) MAIN.post(() -> listener.onState(text));
    }

    private static void progress(Listener listener, int percent, String text) {
        if (listener != null)
            MAIN.post(() -> listener.onProgress(Math.max(0, Math.min(100, percent)), text));
    }

    private static void complete(Listener listener, boolean success, String message) {
        if (listener != null) MAIN.post(() -> listener.onComplete(success, message));
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
