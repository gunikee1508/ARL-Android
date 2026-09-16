package com.samp.mobile.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
                ContentResolver resolver = app.getContentResolver();
                String selectedId = DocumentsContract.getTreeDocumentId(selectedTree);
                if (selectedId == null || selectedId.trim().isEmpty())
                    throw new IllegalArgumentException("não foi possível abrir a pasta selecionada");

                String gameRootId = findGameRoot(resolver, selectedTree, selectedId);
                if (gameRootId == null) {
                    complete(listener, false,
                            "Essa pasta não parece conter a DATA do GTA SA. Selecione a pasta 'files' da sua instalação/cópia legítima do jogo.");
                    return;
                }

                File destination = app.getExternalFilesDir(null);
                if (destination == null) throw new IllegalStateException("armazenamento indisponível");

                state(listener, "LENDO BASE GTA SA...");
                Scan scan = scanTree(resolver, selectedTree, gameRootId, 0);
                if (scan.files <= 0 || scan.bytes <= 0)
                    throw new IllegalStateException("a pasta selecionada está vazia");

                CopyStats copied = new CopyStats(scan.files, scan.bytes);
                state(listener, "IMPORTANDO BASE GTA SA...");
                copyTree(resolver, selectedTree, gameRootId, destination, copied, 0, listener);

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

    private static String findGameRoot(ContentResolver resolver, Uri treeUri, String selectedId) {
        if (looksLikeSourceRoot(resolver, treeUri, selectedId)) return selectedId;

        String files = childDirId(resolver, treeUri, selectedId, "files");
        if (files != null && looksLikeSourceRoot(resolver, treeUri, files)) return files;

        String android = childDirId(resolver, treeUri, selectedId, "Android");
        String data = childDirId(resolver, treeUri, android, "data");
        if (data != null) {
            String rockstar = childDirId(resolver, treeUri, data, "com.rockstargames.gtasa");
            String rockstarFiles = childDirId(resolver, treeUri, rockstar, "files");
            if (rockstarFiles != null && looksLikeSourceRoot(resolver, treeUri, rockstarFiles)) return rockstarFiles;

            String samp = childDirId(resolver, treeUri, data, "com.samp.mobile");
            String sampFiles = childDirId(resolver, treeUri, samp, "files");
            if (sampFiles != null && looksLikeSourceRoot(resolver, treeUri, sampFiles)) return sampFiles;
        }
        return null;
    }

    private static boolean looksLikeSourceRoot(ContentResolver resolver, Uri treeUri, String docId) {
        if (docId == null) return false;
        boolean texdb = childDirId(resolver, treeUri, docId, "texdb") != null;
        boolean data = childDirId(resolver, treeUri, docId, "data") != null;
        boolean models = childDirId(resolver, treeUri, docId, "models") != null;
        boolean audio = childDirId(resolver, treeUri, docId, "audio") != null;
        boolean samp = childDirId(resolver, treeUri, docId, "SAMP") != null;
        return texdb && (data || models || audio || samp);
    }

    private static String childDirId(ContentResolver resolver, Uri treeUri, String parentId, String name) {
        if (parentId == null) return null;
        for (DocNode child : listChildren(resolver, treeUri, parentId)) {
            if (child.directory && child.name.equalsIgnoreCase(name)) return child.id;
        }
        return null;
    }

    private static List<DocNode> listChildren(ContentResolver resolver, Uri treeUri, String parentId) {
        ArrayList<DocNode> out = new ArrayList<>();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) return out;
            int idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int typeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            while (cursor.moveToNext()) {
                String id = idCol >= 0 ? cursor.getString(idCol) : null;
                String name = nameCol >= 0 ? cursor.getString(nameCol) : null;
                String mime = typeCol >= 0 ? cursor.getString(typeCol) : null;
                long size = 0L;
                if (sizeCol >= 0 && !cursor.isNull(sizeCol)) size = Math.max(0L, cursor.getLong(sizeCol));
                if (id == null || name == null) continue;
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                out.add(new DocNode(id, name, dir, size));
            }
        } catch (Exception e) {
            throw new IllegalStateException("não foi possível ler a pasta selecionada", e);
        }
        return out;
    }

    private static Scan scanTree(ContentResolver resolver, Uri treeUri, String docId, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalStateException("estrutura de pastas profunda demais");
        Scan out = new Scan();
        for (DocNode child : listChildren(resolver, treeUri, docId)) {
            String name = safeName(child.name);
            if (name.isEmpty() || shouldSkip(name)) continue;
            if (child.directory) {
                Scan nested = scanTree(resolver, treeUri, child.id, depth + 1);
                out.files += nested.files;
                out.bytes += nested.bytes;
            } else {
                out.files++;
                out.bytes += child.size;
                if (out.files > MAX_FILES) throw new IllegalStateException("arquivos demais na pasta selecionada");
            }
        }
        return out;
    }

    private static void copyTree(ContentResolver resolver, Uri treeUri, String docId, File destination,
                                 CopyStats stats, int depth, Listener listener) throws Exception {
        if (depth > MAX_DEPTH) throw new IllegalStateException("estrutura de pastas profunda demais");
        for (DocNode child : listChildren(resolver, treeUri, docId)) {
            String name = safeName(child.name);
            if (name.isEmpty() || shouldSkip(name)) continue;
            File dst = new File(destination, name);

            if (child.directory) {
                if (!dst.mkdirs() && !dst.isDirectory())
                    throw new IllegalStateException("não foi possível criar " + name);
                copyTree(resolver, treeUri, child.id, dst, stats, depth + 1, listener);
            } else {
                Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id);
                copyFile(resolver, documentUri, dst);
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

    private static final class DocNode {
        final String id;
        final String name;
        final boolean directory;
        final long size;
        DocNode(String id, String name, boolean directory, long size) {
            this.id = id;
            this.name = name;
            this.directory = directory;
            this.size = size;
        }
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
