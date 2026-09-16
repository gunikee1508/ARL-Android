package com.samp.mobile.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs the Phase 8 GTA Brasil Android layer bundled inside the APK.
 *
 * This manager never supplies Rockstar GTA SA base data. It only installs the
 * separately converted ARL/GTA Brasil layer after ArlBaseImportManager has
 * validated the user's legitimate GTA SA Android base.
 */
public final class ArlEmbeddedDataManager {
    public interface Listener {
        void onState(String text);
        void onProgress(int percent, String text);
        void onComplete(boolean success, String message);
    }

    private static final String ASSET_ROOT = "arl/";
    private static final String ASSET_ZIP = ASSET_ROOT + "embedded-data.zip";
    private static final String ASSET_MANIFEST = ASSET_ROOT + "embedded-data.manifest.json";
    private static final String ASSET_META = ASSET_ROOT + "embedded-data.meta.json";
    private static final String PREFS = "arl_embedded_data_state";
    private static final String KEY_VERSION = "version";
    private static final String KEY_PACKAGE_SHA = "package_sha256";
    private static final int BUFFER = 128 * 1024;
    private static final int MAX_ZIP_ENTRIES = 100000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ArlEmbeddedDataManager() {}

    public static boolean isAvailable(Context context) {
        if (context == null) return false;
        try {
            AssetManager am = context.getAssets();
            try (InputStream a = am.open(ASSET_ZIP);
                 InputStream b = am.open(ASSET_MANIFEST);
                 InputStream c = am.open(ASSET_META)) {
                return a.read() >= 0 && b.read() >= 0 && c.read() >= 0;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static String bundledVersion(Context context) {
        try { return loadMeta(context).version; }
        catch (Exception e) { return ""; }
    }

    public static String installedVersion(Context context) {
        return prefs(context).getString(KEY_VERSION, "");
    }

    public static boolean requiresInstall(Context context) {
        if (!isAvailable(context)) return false;
        try {
            Meta meta = loadMeta(context);
            SharedPreferences p = prefs(context);
            if (!meta.version.equals(p.getString(KEY_VERSION, ""))) return true;
            if (!meta.packageSha.equalsIgnoreCase(p.getString(KEY_PACKAGE_SHA, ""))) return true;

            File root = context.getExternalFilesDir(null);
            if (root == null) return true;
            File img = new File(root, "arlbrasil/arlbrasil.img");
            File tex = new File(root, "texdb/arlbrasil/arlbrasil.txt");
            File src = new File(root, "texdb/arlbrasil/src");
            return !img.isFile() || img.length() <= 0 ||
                    !tex.isFile() || tex.length() <= 0 ||
                    !src.isDirectory();
        } catch (Exception e) {
            return true;
        }
    }

    public static void installIfNeeded(Context context, boolean force, Listener listener) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (!ArlBaseImportManager.isBaseReady(app))
                    throw new IllegalStateException("base GTA SA legítima ainda não está pronta");
                if (!isAvailable(app))
                    throw new IllegalStateException("pacote GTA Brasil não está embutido neste APK");
                if (!force && !requiresInstall(app)) {
                    progress(listener, 100, "GTA BRASIL JÁ INSTALADO");
                    complete(listener, true, "GTA Brasil já está instalado e pronto.");
                    return;
                }
                install(app, listener);
            } catch (Exception e) {
                complete(listener, false, "Falha ao instalar GTA Brasil: " + readable(e));
            }
        }, "ARL-Embedded-Data").start();
    }

    private static void install(Context context, Listener listener) throws Exception {
        Meta meta = loadMeta(context);
        List<FileSpec> specs = loadManifest(context, meta.version);
        if (specs.isEmpty()) throw new SecurityException("manifesto embutido vazio");

        File root = context.getExternalFilesDir(null);
        if (root == null) throw new IllegalStateException("armazenamento do aplicativo indisponível");
        File work = new File(root, "download/phase8_embedded");
        File zip = new File(work, "embedded-data.zip");
        File stage = new File(work, "stage");
        deleteTree(work);
        if (!stage.mkdirs() && !stage.isDirectory())
            throw new IllegalStateException("não foi possível criar staging");

        state(listener, "PREPARANDO GTA BRASIL EMBUTIDO...");
        copyAndVerifyPackage(context, meta, zip, listener);
        progress(listener, 42, "PACOTE VERIFICADO • EXTRAINDO...");
        unzipSafely(zip, stage, meta.packageBytes, listener);

        progress(listener, 72, "VALIDANDO ARQUIVOS GTA BRASIL...");
        int verified = 0;
        for (FileSpec spec : specs) {
            if (!verifyFile(stage, spec))
                throw new SecurityException("arquivo inválido no pacote: " + spec.path);
            verified++;
            if (verified % 200 == 0) {
                int p = 72 + (int) Math.min(20L, verified * 20L / Math.max(1, specs.size()));
                progress(listener, p, "VALIDANDO • " + verified + "/" + specs.size());
            }
        }

        progress(listener, 94, "INSTALANDO CAMADA GTA BRASIL...");
        mergeTree(stage, root);

        File img = new File(root, "arlbrasil/arlbrasil.img");
        File tex = new File(root, "texdb/arlbrasil/arlbrasil.txt");
        if (!img.isFile() || img.length() <= 0 || !tex.isFile() || tex.length() <= 0)
            throw new SecurityException("camada instalada não passou na validação final");

        prefs(context).edit()
                .putString(KEY_VERSION, meta.version)
                .putString(KEY_PACKAGE_SHA, meta.packageSha)
                .apply();

        deleteTree(work);
        progress(listener, 100, "GTA BRASIL PRONTO");
        complete(listener, true,
                "GTA Brasil instalado e verificado. Versão " + meta.version + ".");
    }

    private static void copyAndVerifyPackage(Context context, Meta meta, File target,
                                             Listener listener) throws Exception {
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long done = 0;
        int last = -1;

        try (InputStream raw = context.getAssets().open(ASSET_ZIP);
             BufferedInputStream in = new BufferedInputStream(raw, BUFFER);
             FileOutputStream fos = new FileOutputStream(target);
             BufferedOutputStream out = new BufferedOutputStream(fos, BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                done += n;
                int p = meta.packageBytes > 0
                        ? (int) Math.min(40L, done * 40L / meta.packageBytes)
                        : 1;
                if (p != last) {
                    last = p;
                    progress(listener, p, "VERIFICANDO PACOTE • " + human(done));
                }
            }
            out.flush();
            fos.getFD().sync();
        }
        if (meta.packageBytes > 0 && done != meta.packageBytes)
            throw new SecurityException("tamanho do pacote embutido não confere");
        String actual = hex(digest.digest());
        if (!actual.equalsIgnoreCase(meta.packageSha))
            throw new SecurityException("SHA-256 do pacote embutido não confere");
    }

    private static void unzipSafely(File zip, File stage, long packageBytes,
                                    Listener listener) throws Exception {
        String canonicalStage = stage.getCanonicalPath();
        String prefix = canonicalStage + File.separator;
        int entries = 0;
        long unpacked = 0;
        long maxUnpacked = Math.max(1024L * 1024L * 1024L,
                Math.max(1L, packageBytes) * 10L);

        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zip), BUFFER))) {
            ZipEntry entry;
            byte[] buf = new byte[BUFFER];
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES)
                    throw new SecurityException("pacote contém arquivos demais");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals(".."))
                    throw new SecurityException("caminho inválido no ZIP: " + name);
                File out = new File(stage, name);
                String outPath = out.getCanonicalPath();
                if (!outPath.equals(canonicalStage) && !outPath.startsWith(prefix))
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
                        while ((n = zis.read(buf)) != -1) {
                            unpacked += n;
                            if (unpacked > maxUnpacked)
                                throw new SecurityException("pacote expandido excedeu o limite");
                            bos.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
                if (entries % 200 == 0) {
                    int p = 42 + Math.min(28, entries / 350);
                    progress(listener, p, "EXTRAINDO • " + entries + " arquivos");
                }
            }
        }
        if (entries == 0) throw new SecurityException("pacote embutido vazio");
    }

    private static Meta loadMeta(Context context) throws Exception {
        JSONObject root = new JSONObject(readAssetText(context, ASSET_META));
        String version = root.optString("version", "").trim();
        JSONObject pkg = root.optJSONObject("package");
        if (version.isEmpty() || pkg == null)
            throw new SecurityException("metadados embutidos inválidos");
        long bytes = pkg.optLong("bytes", -1L);
        String sha = pkg.optString("sha256", "").trim().toLowerCase(Locale.US);
        if (bytes <= 0 || !sha.matches("[0-9a-f]{64}"))
            throw new SecurityException("metadados do pacote inválidos");
        return new Meta(version, bytes, sha);
    }

    private static List<FileSpec> loadManifest(Context context, String expectedVersion)
            throws Exception {
        JSONObject root = new JSONObject(readAssetText(context, ASSET_MANIFEST));
        String version = root.optString("version", "").trim();
        if (!expectedVersion.equals(version))
            throw new SecurityException("versão do manifesto não confere");
        JSONArray files = root.optJSONArray("files");
        if (files == null) throw new SecurityException("manifesto sem lista de arquivos");
        ArrayList<FileSpec> out = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null) continue;
            String path = item.optString("path", "").trim();
            String sha = item.optString("sha256", "").trim().toLowerCase(Locale.US);
            long bytes = item.optLong("bytes", -1L);
            if (path.isEmpty() || path.startsWith("/") || path.contains("../") ||
                    bytes < 0 || !sha.matches("[0-9a-f]{64}"))
                throw new SecurityException("entrada inválida no manifesto");
            out.add(new FileSpec(path, bytes, sha));
        }
        return out;
    }

    private static String readAssetText(Context context, String path) throws Exception {
        try (InputStream in = context.getAssets().open(path);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static boolean verifyFile(File root, FileSpec spec) {
        try {
            File f = resolveInside(root, spec.path);
            return f != null && f.isFile() && f.length() == spec.bytes &&
                    spec.sha.equalsIgnoreCase(sha256(f));
        } catch (Exception e) {
            return false;
        }
    }

    private static File resolveInside(File root, String relative) {
        try {
            String clean = relative.replace('\\', '/');
            if (clean.startsWith("/") || clean.contains("../") || clean.equals("..")) return null;
            File f = new File(root, clean);
            String rp = root.getCanonicalPath() + File.separator;
            String fp = f.getCanonicalPath();
            return fp.startsWith(rp) ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream in = new BufferedInputStream(
                new FileInputStream(file), BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        return hex(digest.digest());
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
                copyAtomic(src, dst);
            }
        }
    }

    private static void copyAtomic(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory())
            throw new IllegalStateException("não foi possível criar destino");
        File tmp = new File(dst.getAbsolutePath() + ".arlphase8");
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(src), BUFFER);
             FileOutputStream fos = new FileOutputStream(tmp);
             BufferedOutputStream out = new BufferedOutputStream(fos, BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            fos.getFD().sync();
        }
        if (dst.exists() && !dst.delete())
            throw new IllegalStateException("falha substituindo " + dst.getName());
        if (!tmp.renameTo(dst))
            throw new IllegalStateException("falha finalizando " + dst.getName());
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
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m.trim();
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

    private static final class Meta {
        final String version;
        final long packageBytes;
        final String packageSha;
        Meta(String version, long packageBytes, String packageSha) {
            this.version = version;
            this.packageBytes = packageBytes;
            this.packageSha = packageSha;
        }
    }

    private static final class FileSpec {
        final String path;
        final long bytes;
        final String sha;
        FileSpec(String path, long bytes, String sha) {
            this.path = path;
            this.bytes = bytes;
            this.sha = sha;
        }
    }
}
