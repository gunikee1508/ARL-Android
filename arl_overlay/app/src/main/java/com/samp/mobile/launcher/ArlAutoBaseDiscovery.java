package com.samp.mobile.launcher;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Best-effort automatic discovery/import of a GTA San Andreas Android install.
 *
 * Android 11+ can intentionally deny one app access to another app's
 * Android/data directory. This class never asks for all-files/root access: it
 * silently imports only when the OS already permits normal file access. The
 * splash activity falls back to a one-time Storage Access Framework grant.
 */
public final class ArlAutoBaseDiscovery {
    public interface Callback {
        void onFinished(boolean imported, String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int BUFFER = 64 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_FILES = 250000;
    private static final long MAX_BYTES = 16L * 1024L * 1024L * 1024L;

    private ArlAutoBaseDiscovery() {}

    public static void tryImport(Context context,
                                 ArlBaseImportManager.Listener listener,
                                 Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (ArlBaseImportManager.isBaseReady(app)) {
                    finish(callback, true, "Base GTA SA já pronta.");
                    return;
                }

                File source = findReadableSource();
                if (source == null) {
                    finish(callback, false,
                            "Android não liberou acesso direto à instalação do GTA SA.");
                    return;
                }

                File destination = app.getExternalFilesDir(null);
                if (destination == null)
                    throw new IllegalStateException("armazenamento do aplicativo indisponível");

                File work = new File(destination, "download/auto_base_import");
                File stage = new File(work, "stage");
                deleteTree(work);
                if (!stage.mkdirs() && !stage.isDirectory())
                    throw new IllegalStateException("não foi possível criar staging da base");

                state(listener, "GTA SA ENCONTRADO • IMPORTANDO AUTOMATICAMENTE...");
                Stats stats = new Stats();
                copyTree(source, stage, stats, 0, listener);

                if (!looksLikeBase(stage))
                    throw new IllegalStateException("a instalação detectada não possui a DATA mínima esperada");

                state(listener, "VALIDANDO BASE GTA SA...");
                mergeTree(stage, destination, 0);
                deleteTree(work);

                if (!ArlBaseImportManager.isBaseReady(app))
                    throw new IllegalStateException("a base importada não passou na validação final");

                progress(listener, 100, "BASE GTA SA PRONTA");
                complete(listener, true,
                        "Base legítima do GTA SA importada automaticamente (" +
                                stats.files + " arquivos, " + human(stats.bytes) + ").");
                finish(callback, true, "Base GTA SA importada automaticamente.");
            } catch (Exception e) {
                finish(callback, false, "Importação automática indisponível: " + readable(e));
            }
        }, "ARL-Auto-Base-Discovery").start();
    }

    private static File findReadableSource() {
        Set<String> paths = new LinkedHashSet<>();
        try {
            File shared = Environment.getExternalStorageDirectory();
            if (shared != null) {
                paths.add(new File(shared,
                        "Android/data/com.rockstargames.gtasa/files").getAbsolutePath());
            }
        } catch (Exception ignored) {}
        paths.add("/storage/emulated/0/Android/data/com.rockstargames.gtasa/files");
        paths.add("/sdcard/Android/data/com.rockstargames.gtasa/files");

        for (String path : paths) {
            try {
                File candidate = new File(path);
                if (candidate.isDirectory() && candidate.canRead() && looksLikeBase(candidate))
                    return candidate;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void copyTree(File source, File destination, Stats stats, int depth,
                                 ArlBaseImportManager.Listener listener) throws Exception {
        if (depth > MAX_DEPTH) throw new SecurityException("estrutura de pastas profunda demais");
        File[] children = source.listFiles();
        if (children == null) return;

        String sourceCanonical = source.getCanonicalPath() + File.separator;
        for (File child : children) {
            String name = child.getName();
            if (shouldSkip(name, depth)) continue;

            String childCanonical = child.getCanonicalPath();
            if (!childCanonical.startsWith(sourceCanonical))
                throw new SecurityException("link/caminho externo recusado: " + name);

            File out = new File(destination, name);
            if (child.isDirectory()) {
                if (!out.mkdirs() && !out.isDirectory())
                    throw new IllegalStateException("falha criando " + name);
                copyTree(child, out, stats, depth + 1, listener);
            } else if (child.isFile()) {
                if (++stats.files > MAX_FILES)
                    throw new SecurityException("base contém arquivos demais");
                long length = Math.max(0L, child.length());
                stats.bytes += length;
                if (stats.bytes > MAX_BYTES)
                    throw new SecurityException("base excede o limite de segurança");
                copyFile(child, out);

                if (stats.files % 100 == 0) {
                    int percent = (int) Math.min(90L, Math.max(1L, stats.bytes / (32L * 1024L * 1024L)));
                    progress(listener, percent,
                            "IMPORTANDO GTA SA • " + stats.files + " arquivos • " + human(stats.bytes));
                }
            }
        }
    }

    private static void mergeTree(File source, File destination, int depth) throws Exception {
        if (depth > MAX_DEPTH) throw new SecurityException("estrutura de staging profunda demais");
        File[] children = source.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if (shouldSkip(name, depth)) continue;
            File out = new File(destination, name);
            if (child.isDirectory()) {
                if (!out.mkdirs() && !out.isDirectory())
                    throw new IllegalStateException("falha criando destino " + name);
                mergeTree(child, out, depth + 1);
            } else if (child.isFile()) {
                copyFile(child, out);
            }
        }
    }

    private static boolean shouldSkip(String name, int depth) {
        if (name == null) return true;
        String n = name.trim().toLowerCase(Locale.US);
        if (n.isEmpty() || n.equals(".") || n.equals("..")) return true;
        if (depth == 0 && (n.equals("samp") || n.equals("arl") || n.startsWith(".arl")))
            return true;
        return n.equals("settings.ini") || n.equals("samp_log.txt") ||
                n.equals("svlog.txt") || n.equals("gta_sa.set") ||
                n.equals("gtasatelem.set");
    }

    private static boolean looksLikeBase(File root) {
        if (root == null || !root.isDirectory()) return false;
        boolean gta3 = anyFile(root,
                "texdb/gta3/gta3.etc", "texdb/gta3/gta3.pvr", "texdb/gta3/gta3.dxt");
        boolean interior = anyFile(root,
                "texdb/gta_int/gta_int.etc", "texdb/gta_int/gta_int.pvr", "texdb/gta_int/gta_int.dxt");
        boolean audio = anyFile(root,
                "audio/SFX/FEET", "audio/SFX/GENRL", "audio/STREAMS/AA", "audio/STREAMS/CH");
        boolean data = anyFile(root,
                "data/handling.cfg", "data/gta.dat", "data/default.dat");
        return gta3 && interior && audio && data;
    }

    private static boolean anyFile(File root, String... relative) {
        for (String path : relative) {
            try {
                File f = new File(root, path);
                if (f.isFile() && f.length() > 0) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static void copyFile(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory())
            throw new IllegalStateException("não foi possível criar pasta de destino");
        File tmp = new File(dst.getAbsolutePath() + ".arlbase");
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(src), BUFFER);
             FileOutputStream fos = new FileOutputStream(tmp);
             BufferedOutputStream out = new BufferedOutputStream(fos, BUFFER)) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            out.flush();
            fos.getFD().sync();
        }
        if (dst.exists() && !dst.delete())
            throw new IllegalStateException("não foi possível substituir " + dst.getName());
        if (!tmp.renameTo(dst))
            throw new IllegalStateException("não foi possível finalizar " + dst.getName());
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    private static String readable(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName() : message.trim();
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static void state(ArlBaseImportManager.Listener listener, String text) {
        if (listener != null) MAIN.post(() -> listener.onState(text));
    }

    private static void progress(ArlBaseImportManager.Listener listener, int percent, String text) {
        if (listener != null) MAIN.post(() -> listener.onProgress(percent, text));
    }

    private static void complete(ArlBaseImportManager.Listener listener,
                                 boolean success, String message) {
        if (listener != null) MAIN.post(() -> listener.onComplete(success, message));
    }

    private static void finish(Callback callback, boolean imported, String message) {
        if (callback != null) MAIN.post(() -> callback.onFinished(imported, message));
    }

    private static final class Stats {
        int files;
        long bytes;
    }
}
