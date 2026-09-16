package com.samp.mobile.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Imports a user-selected, legitimately obtained GTA SA Android data tree into
 * ARL's app-private external-files directory. No proprietary GTA data is bundled
 * or downloaded by the ARL launcher.
 */
public final class ArlBaseImportManager {
    public interface Listener {
        void onState(String text);
        void onProgress(int percent, String text);
        void onComplete(boolean success, String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String PREFS = "arl_base_state";
    private static final String KEY_READY = "ready";
    private static final String KEY_FILES = "files";
    private static final String KEY_BYTES = "bytes";
    private static final int BUFFER = 64 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_FILES = 250000;

    private static final Set<String> SKIP_NAMES = new HashSet<>();
    static {
        SKIP_NAMES.add("settings.ini");
        SKIP_NAMES.add("samp_log.txt");
        SKIP_NAMES.add("svlog.txt");
        SKIP_NAMES.add("gta_sa.set");
        SKIP_NAMES.add("gtasatelem.set");
        SKIP_NAMES.add(".htaccess");
    }

    private ArlBaseImportManager() {}

    /**
     * Existing installations from earlier ARL builds are auto-detected even if
     * the Phase-6 preference marker has not been written yet.
     */
    public static boolean isBaseReady(Context context) {
        File root = context.getExternalFilesDir(null);
        if (root == null) return false;
        boolean structure = looksLikeInstalledBase(root);
        if (structure && !prefs(context).getBoolean(KEY_READY, false)) {
            prefs(context).edit().putBoolean(KEY_READY, true).apply();
        }
        return structure;
    }

    public static long importedBytes(Context context) {
        return prefs(context).getLong(KEY_BYTES, 0L);
    }

    public static int importedFiles(Context context) {
        return prefs(context).getInt(KEY_FILES, 0);
    }

    public static void importTree(Context context, Uri selectedTree, Listener listener) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (selectedTree == null) throw new IllegalArgumentException("pasta não selecionada");
                DocumentFile selected = DocumentFile.fromTreeUri(app, selectedTree);
                if (selected == null || !selected.exists() || !selected.isDirectory())
                    throw new IllegalArgumentException("não foi possível abrir a pasta selecionada");

                DocumentFile gameRoot = findGameRoot(selected);
                if (gameRoot == null) {
                    complete(listener, false,
                            "Essa pasta não parece conter a DATA do GTA SA. Selecione a pasta 'files' da sua instalação/cópia legítima do jogo.");
                    return;
                }

                File destination = app.getExternalFilesDir(null);
                if (destination == null) throw new IllegalStateException("armazenamento indisponível");

                state(listener, "LENDO BASE GTA SA...");
                Scan scan = scanTree(gameRoot, 0);
                if (scan.files <= 0 || scan.bytes <= 0)
                    throw new IllegalStateException("a pasta selecionada está vazia");

                CopyStats copied = new CopyStats(scan.files, scan.bytes);
                state(listener, "IMPORTANDO BASE GTA SA...");
                copyTree(app.getContentResolver(), gameRoot, destination, copied, 0, listener);

                if (!looksLikeInstalledBase(destination))
                    throw new IllegalStateException("estrutura mínima do GTA SA não foi encontrada após a importação");

                prefs(app).edit()
                        .putBoolean(KEY_READY, true)
                        .putInt(KEY_FILES, copied.copiedFiles)
                        .putLong(KEY_BYTES, copied.copiedBytes)
                        .apply();
                progress(listener, 100, "BASE GTA SA PRONTA");
                complete(listener, true,
                        "Base GTA SA importada: " + copied.copiedFiles + " arquivos • " + human(copied.copiedBytes) + ".");
            } catch (Exception e) {
                prefs(app).edit().putBoolean(KEY_READY, false).apply();
                complete(listener, false, "Falha ao importar base GTA SA: " + readable(e));
            }
        }, "ARL-Base-Importer").start();
    }

    private static DocumentFile findGameRoot(DocumentFile selected) {
        if (looksLikeSourceRoot(selected)) return selected;

        // Common backup layouts: <selected>/files or Android/data/<package>/files.
        DocumentFile files = childDir(selected, "files");
        if (files != null && looksLikeSourceRoot(files)) return files;

        DocumentFile android = childDir(selected, "Android");
        DocumentFile data = childDir(android, "data");
        if (data != null) {
            DocumentFile rockstar = childDir(data, "com.rockstargames.gtasa");
            DocumentFile rockstarFiles = childDir(rockstar, "files");
            if (rockstarFiles != null && looksLikeSourceRoot(rockstarFiles)) return rockstarFiles;

            DocumentFile samp = childDir(data, "com.samp.mobile");
            DocumentFile sampFiles = childDir(samp, "files");
            if (sampFiles != null && looksLikeSourceRoot(sampFiles)) return sampFiles;
        }
        return null;
    }

    private static boolean looksLikeSourceRoot(DocumentFile root) {
        if (root == null || !root.isDirectory()) return false;
        boolean texdb = childDir(root, "texdb") != null;
        boolean data = childDir(root, "data") != null;
        boolean models = childDir(root, "models") != null;
        boolean audio = childDir(root, "audio") != null;
        boolean samp = childDir(root, "SAMP") != null || childDir(root, "samp") != null;
        // GTA Android/SAMP packs vary by build. texdb plus any second game-data
        // family is a strong enough signal without tying ARL to one pirated pack.
        return texdb && (data || models || audio || samp);
    }

    private static boolean looksLikeInstalledBase(File root) {
        if (root == null || !root.isDirectory()) return false;
        boolean texdb = dirExistsIgnoreCase(root, "texdb");
        boolean data = dirExistsIgnoreCase(root, "data");
        boolean models = dirExistsIgnoreCase(root, "models");
        boolean audio = dirExistsIgnoreCase(root, "audio");
        boolean samp = dirExistsIgnoreCase(root, "SAMP");
        return texdb && (data || models || audio || samp);
    }

    private static boolean dirExistsIgnoreCase(File root, String name) {
        File[] files = root.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isDirectory() && file.getName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static DocumentFile childDir(DocumentFile root, String name) {
        if (root == null || !root.isDirectory()) return null;
        DocumentFile[] children;
        try { children = root.listFiles(); }
        catch (Exception e) { return null; }
        for (DocumentFile child : children) {
            String n = child.getName();
            if (child.isDirectory() && n != null && n.equalsIgnoreCase(name)) return child;
        }
        return null;
    }

    private static Scan scanTree(DocumentFile root, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalStateException("estrutura de pastas profunda demais");
        Scan out = new Scan();
        for (DocumentFile child : root.listFiles()) {
            String name = safeName(child.getName());
            if (name.isEmpty() || shouldSkip(name)) continue;
            if (child.isDirectory()) {
                Scan nested = scanTree(child, depth + 1);
                out.files += nested.files;
                out.bytes += nested.bytes;
            } else if (child.isFile()) {
                out.files++;
                long len = child.length();
                if (len > 0) out.bytes += len;
                if (out.files > MAX_FILES) throw new IllegalStateException("arquivos demais na pasta selecionada");
            }
        }
        return out;
    }

    private static void copyTree(ContentResolver resolver, DocumentFile source, File destination,
                                 CopyStats stats, int depth, Listener listener) throws Exception {
        if (depth > MAX_DEPTH) throw new IllegalStateException("estrutura de pastas profunda demais");
        for (DocumentFile child : source.listFiles()) {
            String name = safeName(child.getName());
            if (name.isEmpty() || shouldSkip(name)) continue;
            File dst = new File(destination, name);

            if (child.isDirectory()) {
                if (!dst.mkdirs() && !dst.isDirectory())
                    throw new IllegalStateException("não foi possível criar " + name);
                copyTree(resolver, child, dst, stats, depth + 1, listener);
            } else if (child.isFile()) {
                copyFile(resolver, child.getUri(), dst);
                stats.copiedFiles++;
                stats.copiedBytes += Math.max(0L, dst.length());
                int percent = stats.totalBytes > 0
                        ? (int)Math.min(99, (stats.copiedBytes * 100L) / stats.totalBytes)
                        : (int)Math.min(99, (stats.copiedFiles * 100L) / Math.max(1, stats.totalFiles));
                if (percent != stats.lastPercent) {
                    stats.lastPercent = percent;
                    progress(listener, percent,
                            "IMPORTANDO • " + stats.copiedFiles + "/" + stats.totalFiles + " • " + human(stats.copiedBytes));
                }
            }
        }
    }

    private static void copyFile(ContentResolver resolver, Uri source, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory())
            throw new IllegalStateException("não foi possível criar pasta de destino");
        File tmp = new File(destination.getAbsolutePath() + ".arlimport");
        if (tmp.exists()) tmp.delete();

        try (InputStream raw = resolver.openInputStream(source)) {
            if (raw == null) throw new IllegalStateException("não foi possível ler um arquivo selecionado");
            try (BufferedInputStream in = new BufferedInputStream(raw, BUFFER);
                 FileOutputStream fos = new FileOutputStream(tmp);
                 BufferedOutputStream out = new BufferedOutputStream(fos, BUFFER)) {
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
                fos.getFD().sync();
            }
        }

        if (destination.exists() && !destination.delete())
            throw new IllegalStateException("não foi possível substituir " + destination.getName());
        if (!tmp.renameTo(destination))
            throw new IllegalStateException("não foi possível finalizar " + destination.getName());
    }

    private static boolean shouldSkip(String name) {
        return SKIP_NAMES.contains(name.toLowerCase(Locale.US));
    }

    private static String safeName(String raw) {
        if (raw == null) return "";
        String n = raw.trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..") || n.contains("/") || n.contains("\\")) return "";
        return n;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

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

    private static void state(Listener listener, String text) {
        if (listener != null) MAIN.post(() -> listener.onState(text));
    }

    private static void progress(Listener listener, int percent, String text) {
        if (listener != null) MAIN.post(() -> listener.onProgress(Math.max(0, Math.min(100, percent)), text));
    }

    private static void complete(Listener listener, boolean success, String message) {
        if (listener != null) MAIN.post(() -> listener.onComplete(success, message));
    }

    private static final class Scan {
        int files;
        long bytes;
    }

    private static final class CopyStats {
        final int totalFiles;
        final long totalBytes;
        int copiedFiles;
        long copiedBytes;
        int lastPercent = -1;
        CopyStats(int totalFiles, long totalBytes) {
            this.totalFiles = totalFiles;
            this.totalBytes = totalBytes;
        }
    }
}
