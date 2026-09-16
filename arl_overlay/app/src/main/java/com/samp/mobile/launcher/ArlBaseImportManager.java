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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports a user-selected, legitimately obtained GTA SA Android data tree or ZIP
 * into ARL's app-private external-files directory. No proprietary GTA data is
 * bundled or downloaded by the ARL launcher.
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
    private static final long MAX_UNPACKED_BYTES = 16L * 1024L * 1024L * 1024L;

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
                            "Essa pasta não parece conter a DATA do GTA SA. Selecione a pasta 'files' ou importe um ZIP de backup legítimo.");
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
                finishImport(app, copied, listener);
            } catch (Exception e) {
                prefs(app).edit().putBoolean(KEY_READY, false).apply();
                complete(listener, false, "Falha ao importar base GTA SA: " + readable(e));
            }
        }, "ARL-Base-Importer").start();
    }

    public static void importZip(Context context, Uri zipUri, Listener listener) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            File stage = null;
            try {
                if (zipUri == null) throw new IllegalArgumentException("ZIP não selecionado");
                File root = app.getExternalFilesDir(null);
                if (root == null) throw new IllegalStateException("armazenamento indisponível");
                File work = new File(root, "download/base_import");
                stage = new File(work, "stage");
                deleteTree(work);
                if (!stage.mkdirs() && !stage.isDirectory())
                    throw new IllegalStateException("não foi possível criar pasta temporária");

                state(listener, "EXTRAINDO BACKUP GTA SA...");
                unzipSafely(app.getContentResolver(), zipUri, stage, listener);

                File gameRoot = findFilesystemGameRoot(stage);
                if (gameRoot == null)
                    throw new IllegalStateException("o ZIP não contém uma estrutura reconhecida de DATA GTA SA");

                state(listener, "VALIDANDO BACKUP GTA SA...");
                Scan scan = scanLocal(gameRoot, 0);
                if (scan.files <= 0 || scan.bytes <= 0)
                    throw new IllegalStateException("o ZIP não contém arquivos utilizáveis");

                CopyStats copied = new CopyStats(scan.files, scan.bytes);
                state(listener, "IMPORTANDO BASE GTA SA...");
                copyLocalTree(gameRoot, root, copied, 0, listener);
                finishImport(app, copied, listener);
                deleteTree(work);
            } catch (Exception e) {
                prefs(app).edit().putBoolean(KEY_READY, false).apply();
                complete(listener, false, "Falha ao importar ZIP GTA SA: " + readable(e));
                if (stage != null) deleteTree(stage.getParentFile());
            }
        }, "ARL-Base-Zip-Importer").start();
    }

    private static void finishImport(Context app, CopyStats copied, Listener listener) {
        File destination = app.getExternalFilesDir(null);
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
    }

    private static void unzipSafely(ContentResolver resolver, Uri zipUri, File stage, Listener listener)
            throws Exception {
        String stageCanonical = stage.getCanonicalPath();
        String rootPath = stageCanonical + File.separator;
        long unpacked = 0L;
        int entries = 0;

        try (InputStream raw = resolver.openInputStream(zipUri)) {
            if (raw == null) throw new IllegalStateException("não foi possível abrir o ZIP selecionado");
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(raw, BUFFER))) {
                ZipEntry entry;
                byte[] buf = new byte[BUFFER];
                while ((entry = zis.getNextEntry()) != null) {
                    if (++entries > MAX_FILES) throw new SecurityException("ZIP contém arquivos demais");
                    String rawName = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                    while (rawName.startsWith("./")) rawName = rawName.substring(2);
                    if (rawName.isEmpty()) { zis.closeEntry(); continue; }
                    if (rawName.startsWith("/") || rawName.contains("../") || rawName.equals(".."))
                        throw new SecurityException("caminho inválido no ZIP");

                    File out = new File(stage, rawName);
                    String outPath = out.getCanonicalPath();
                    if (!outPath.equals(stageCanonical) && !outPath.startsWith(rootPath))
                        throw new SecurityException("ZIP tentou sair da pasta temporária");

                    if (entry.isDirectory()) {
                        if (!out.mkdirs() && !out.isDirectory())
                            throw new IllegalStateException("não foi possível criar pasta do ZIP");
                    } else if (!shouldSkip(out.getName())) {
                        File parent = out.getParentFile();
                        if (parent != null && !parent.mkdirs() && !parent.isDirectory())
                            throw new IllegalStateException("não foi possível criar pasta do ZIP");
                        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out), BUFFER)) {
                            int n;
                            while ((n = zis.read(buf)) != -1) {
                                unpacked += n;
                                if (unpacked > MAX_UNPACKED_BYTES)
                                    throw new SecurityException("ZIP expandido excedeu o limite de segurança");
                                bos.write(buf, 0, n);
                            }
                        }
                    }
                    zis.closeEntry();
                    if (entries % 20 == 0) {
                        int p = Math.min(35, 1 + entries / 20);
                        progress(listener, p, "EXTRAINDO • " + entries + " ITENS • " + human(unpacked));
                    }
                }
            }
        }
        if (entries == 0) throw new IllegalStateException("ZIP vazio");
    }

    private static File findFilesystemGameRoot(File root) {
        if (looksLikeInstalledBase(root)) return root;
        File files = childDir(root, "files");
        if (files != null && looksLikeInstalledBase(files)) return files;

        File android = childDir(root, "Android");
        File data = childDir(android, "data");
        if (data != null) {
            File rockstar = childDir(data, "com.rockstargames.gtasa");
            File rockstarFiles = childDir(rockstar, "files");
            if (rockstarFiles != null && looksLikeInstalledBase(rockstarFiles)) return rockstarFiles;
            File samp = childDir(data, "com.samp.mobile");
            File sampFiles = childDir(samp, "files");
            if (sampFiles != null && looksLikeInstalledBase(sampFiles)) return sampFiles;
        }

        File[] children = root.listFiles();
        if (children != null && children.length == 1 && children[0].isDirectory()) {
            File nested = children[0];
            if (looksLikeInstalledBase(nested)) return nested;
            files = childDir(nested, "files");
            if (files != null && looksLikeInstalledBase(files)) return files;
        }
        return null;
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

    private static Scan scanLocal(File root, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalStateException("estrutura de pastas profunda demais");
        Scan out = new Scan();
        File[] files = root.listFiles();
        if (files == null) return out;
        for (File file : files) {
            if (shouldSkip(file.getName())) continue;
            if (file.isDirectory()) {
                Scan nested = scanLocal(file, depth + 1);
                out.files += nested.files;
                out.bytes += nested.bytes;
            } else if (file.isFile()) {
                out.files++;
                out.bytes += Math.max(0L, file.length());
                if (out.files > MAX_FILES) throw new IllegalStateException("arquivos demais no ZIP");
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
                accountCopy(dst, stats, listener);
            }
        }
    }

    private static void copyLocalTree(File source, File destination, CopyStats stats,
                                      int depth, Listener listener) throws Exception {
        if (depth > MAX_DEPTH) throw new IllegalStateException("estrutura de pastas profunda demais");
        File[] files = source.listFiles();
        if (files == null) return;
        for (File src : files) {
            if (shouldSkip(src.getName())) continue;
            File dst = new File(destination, src.getName());
            if (src.isDirectory()) {
                if (!dst.mkdirs() && !dst.isDirectory())
                    throw new IllegalStateException("não foi possível criar " + dst.getName());
                copyLocalTree(src, dst, stats, depth + 1, listener);
            } else if (src.isFile()) {
                copyLocalFile(src, dst);
                accountCopy(dst, stats, listener);
            }
        }
    }

    private static void accountCopy(File dst, CopyStats stats, Listener listener) {
        stats.copiedFiles++;
        stats.copiedBytes += Math.max(0L, dst.length());
        int percent = stats.totalBytes > 0
                ? (int)Math.min(99, 35L + (stats.copiedBytes * 64L) / stats.totalBytes)
                : (int)Math.min(99, 35L + (stats.copiedFiles * 64L) / Math.max(1, stats.totalFiles));
        if (percent != stats.lastPercent) {
            stats.lastPercent = percent;
            progress(listener, percent,
                    "IMPORTANDO • " + stats.copiedFiles + "/" + stats.totalFiles + " • " + human(stats.copiedBytes));
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
        finishAtomic(tmp, destination);
    }

    private static void copyLocalFile(File source, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory())
            throw new IllegalStateException("não foi possível criar pasta de destino");
        File tmp = new File(destination.getAbsolutePath() + ".arlimport");
        if (tmp.exists()) tmp.delete();
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source), BUFFER);
             FileOutputStream fos = new FileOutputStream(tmp);
             BufferedOutputStream out = new BufferedOutputStream(fos, BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            fos.getFD().sync();
        }
        finishAtomic(tmp, destination);
    }

    private static void finishAtomic(File tmp, File destination) {
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

    private static File childDir(File root, String name) {
        if (root == null || !root.isDirectory()) return null;
        File[] files = root.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isDirectory() && file.getName().equalsIgnoreCase(name)) return file;
        }
        return null;
    }

    private static boolean dirExistsIgnoreCase(File root, String name) {
        return childDir(root, name) != null;
    }

    private static boolean shouldSkip(String name) {
        return name != null && SKIP_NAMES.contains(name.toLowerCase(Locale.US));
    }

    private static String safeName(String raw) {
        if (raw == null) return "";
        String n = raw.trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..") || n.contains("/") || n.contains("\\")) return "";
        return n;
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
