package com.samp.mobile.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compatibility downloader for launchers that publish:
 * client_config.json -> client_config.url_cache_files -> { files: [...] }.
 *
 * This mirrors the public catalog format used by older SAMP Mobile launchers.
 * It does not embed or hard-code game data itself.
 */
public final class ArlLegacyBaseDownloader {
    private static final int BUFFER = 64 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final String[] SKIP_NAMES = {
            "settings.ini", "samp_log.txt", "svlog.txt",
            "gta_sa.set", "gtasatelem.set", ".htaccess"
    };

    private ArlLegacyBaseDownloader() {}

    public static boolean isConfigured() {
        String u = safe(ArlRemoteConfig.baseLegacyConfigUrl());
        return ArlRemoteConfig.baseDistributionAuthorized() && validHttp(u);
    }

    public static void installOrRepair(Context context, boolean force,
                                       ArlBaseDownloadManager.Listener listener) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (!isConfigured())
                    throw new IllegalStateException("catálogo legado não configurado");

                state(listener, "LENDO CATÁLOGO DA BASE...");
                Catalog catalog = loadCatalog(ArlRemoteConfig.baseLegacyConfigUrl());

                File root = app.getExternalFilesDir(null);
                if (root == null) throw new IllegalStateException("armazenamento indisponível");

                List<Entry> needed = new ArrayList<>();
                long total = 0L;
                for (Entry e : catalog.files) {
                    if (shouldSkip(e.name)) continue;
                    File target = safeTarget(root, e.path);
                    if (target == null) continue;
                    if (force || !target.isFile() || target.length() != e.bytes) {
                        needed.add(e);
                        total += Math.max(1L, e.bytes);
                    }
                }

                if (needed.isEmpty()) {
                    if (!ArlGtaBaseValidator.isValid(root))
                        throw new IllegalStateException("catálogo conferido, mas a base GTA SA não é válida");
                    progress(listener, 100, "BASE GTA SA PRONTA");
                    complete(listener, true, "Base GTA SA já está atualizada.");
                    return;
                }

                ensureFreeSpace(root, total);
                File work = new File(root, "download/arl_phase11_legacy");
                if (!work.mkdirs() && !work.isDirectory())
                    throw new IllegalStateException("não foi possível criar pasta de download");

                long completed = 0L;
                int index = 0;
                for (Entry e : needed) {
                    index++;
                    final long before = completed;
                    final long all = total;
                    String label = index + "/" + needed.size() + " • " + e.name;
                    state(listener, "BAIXANDO BASE • " + label);

                    File target = safeTarget(root, e.path);
                    if (target == null) throw new SecurityException("caminho inválido: " + e.path);
                    File parent = target.getParentFile();
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory())
                        throw new IllegalStateException("não foi possível criar pasta de destino");

                    File part = new File(work, Integer.toHexString(e.path.hashCode()) + ".part");
                    downloadResume(e.url, part, e.bytes, bytes -> {
                        int p = (int)Math.min(96L,
                                ((before + bytes) * 96L) / Math.max(1L, all));
                        progress(listener, p, "BAIXANDO BASE • " + label + " • "
                                + human(before + bytes) + " / " + human(all));
                    });

                    if (part.length() != e.bytes) {
                        part.delete();
                        throw new IllegalStateException("tamanho não confere: " + e.path);
                    }

                    File tmp = new File(target.getAbsolutePath() + ".arllegacy");
                    if (tmp.exists()) tmp.delete();
                    if (!part.renameTo(tmp)) copyFile(part, tmp);
                    if (target.exists() && !target.delete())
                        throw new IllegalStateException("não foi possível substituir " + e.name);
                    if (!tmp.renameTo(target))
                        throw new IllegalStateException("não foi possível finalizar " + e.name);
                    part.delete();
                    completed += e.bytes;
                }

                deleteTree(work);

                if (!ArlGtaBaseValidator.isValid(root))
                    throw new IllegalStateException("download terminou, mas a base GTA SA não passou na validação");

                ArlBaseImportManager.isBaseReady(app);
                progress(listener, 100, "BASE GTA SA PRONTA");
                complete(listener, true,
                        "Base GTA SA preparada pelo catálogo legado"
                                + (catalog.version.isEmpty() ? "." : " • versão " + catalog.version + "."));
            } catch (Exception e) {
                complete(listener, false, "Falha no catálogo legado: " + readable(e));
            }
        }, "ARL-Legacy-Base").start();
    }

    private static Catalog loadCatalog(String configUrl) throws Exception {
        JSONObject root = new JSONObject(readText(configUrl));
        JSONObject cfg = root.optJSONObject("client_config");
        if (cfg == null) cfg = root;

        String filesUrl = cfg.optString("url_cache_files", "").trim();
        if (!validHttp(filesUrl))
            throw new IllegalStateException("url_cache_files ausente");

        String version = String.valueOf(cfg.optInt("version_code", -1));
        if ("-1".equals(version)) version = "";

        JSONObject filesRoot = new JSONObject(readText(filesUrl));
        JSONArray files = filesRoot.optJSONArray("files");
        if (files == null || files.length() == 0)
            throw new IllegalStateException("catálogo de arquivos vazio");

        String inferredBase = inferFilesBase(configUrl);
        ArrayList<Entry> out = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null) continue;

            String name = item.optString("name", "").trim();
            String path = item.optString("path", "").trim().replace('\\', '/');
            long bytes = item.optLong("size", -1L);
            String url = item.optString("url", "").trim();

            if (path.isEmpty() && !name.isEmpty()) path = name;
            if (name.isEmpty()) {
                int slash = path.lastIndexOf('/');
                name = slash >= 0 ? path.substring(slash + 1) : path;
            }
            if (!validRelative(path) || bytes < 0) continue;

            if (!validHttp(url)) url = inferredBase + encodePath(path);
            if (!validHttp(url)) continue;

            out.add(new Entry(name, path, bytes, url));
        }

        if (out.isEmpty()) throw new IllegalStateException("nenhum arquivo válido no catálogo");
        return new Catalog(version, out);
    }

    private static String readText(String source) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(source).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(45000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ARL-Android/Phase11");
        c.connect();
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new IllegalStateException("HTTP " + code + " em " + source);
        }
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new BufferedInputStream(c.getInputStream(), BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
        return sb.toString();
    }

    private static void downloadResume(String source, File part, long expectedBytes,
                                       BytesProgress cb) throws Exception {
        if (part.isFile() && part.length() > expectedBytes) part.delete();
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
            throw new IllegalStateException("HTTP " + code + " ao baixar " + source);
        }
        if (existing > 0 && code == HttpURLConnection.HTTP_OK) {
            existing = 0L;
            append = false;
        }

        long done = existing;
        if (cb != null) cb.onBytes(done);
        try (InputStream in = new BufferedInputStream(c.getInputStream(), BUFFER);
             FileOutputStream fos = new FileOutputStream(part, append);
             BufferedOutputStream out = new BufferedOutputStream(fos, BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            long last = done;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                if (done > expectedBytes && expectedBytes >= 0)
                    throw new IllegalStateException("download excedeu o tamanho esperado");
                if (cb != null && done - last >= 512 * 1024) {
                    last = done;
                    cb.onBytes(done);
                }
            }
            out.flush();
            fos.getFD().sync();
        } finally {
            c.disconnect();
        }
        if (cb != null) cb.onBytes(done);
    }

    private static void ensureFreeSpace(File root, long downloadBytes) {
        try {
            long required = Math.max(0L, downloadBytes) + (256L * 1024L * 1024L);
            long available = new StatFs(root.getAbsolutePath()).getAvailableBytes();
            if (available < required)
                throw new IllegalStateException("espaço insuficiente: precisa de ~"
                        + human(required) + ", disponível " + human(available));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception ignored) {}
    }

    private static File safeTarget(File root, String path) throws Exception {
        if (!validRelative(path)) return null;
        File target = new File(root, path);
        String base = root.getCanonicalPath() + File.separator;
        String actual = target.getCanonicalPath();
        if (!actual.startsWith(base)) return null;
        return target;
    }

    private static boolean validRelative(String path) {
        if (path == null) return false;
        String p = path.trim().replace('\\', '/');
        return !p.isEmpty() && !p.startsWith("/") && !p.equals("..")
                && !p.startsWith("../") && !p.contains("/../") && !p.contains(":/");
    }

    private static String inferFilesBase(String configUrl) throws Exception {
        URI u = URI.create(configUrl);
        return new URI(u.getScheme(), u.getAuthority(), "/files/", null, null).toString();
    }

    private static String encodePath(String path) {
        String[] parts = path.split("/");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append('/');
            try {
                out.append(java.net.URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                out.append(parts[i]);
            }
        }
        return out.toString();
    }

    private static boolean shouldSkip(String name) {
        String n = safe(name).toLowerCase(Locale.US);
        for (String skip : SKIP_NAMES) if (skip.equals(n)) return true;
        return false;
    }

    private static boolean validHttp(String value) {
        String s = safe(value).toLowerCase(Locale.US);
        return s.startsWith("https://") || s.startsWith("http://");
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(new java.io.FileInputStream(src), BUFFER);
             FileOutputStream fos = new FileOutputStream(dst);
             BufferedOutputStream out = new BufferedOutputStream(fos, BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            fos.getFD().sync();
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    private static String safe(String v) { return v == null ? "" : v.trim(); }

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

    private static void state(ArlBaseDownloadManager.Listener listener, String text) {
        if (listener != null) MAIN.post(() -> listener.onState(text));
    }

    private static void progress(ArlBaseDownloadManager.Listener listener, int p, String text) {
        if (listener != null) MAIN.post(() -> listener.onProgress(Math.max(0, Math.min(100, p)), text));
    }

    private static void complete(ArlBaseDownloadManager.Listener listener, boolean ok, String text) {
        if (listener != null) MAIN.post(() -> listener.onComplete(ok, text));
    }

    private interface BytesProgress { void onBytes(long bytes); }

    private static final class Entry {
        final String name;
        final String path;
        final long bytes;
        final String url;
        Entry(String name, String path, long bytes, String url) {
            this.name = name; this.path = path; this.bytes = bytes; this.url = url;
        }
    }

    private static final class Catalog {
        final String version;
        final List<Entry> files;
        Catalog(String version, List<Entry> files) {
            this.version = version; this.files = files;
        }
    }
}
