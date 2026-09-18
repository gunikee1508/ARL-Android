package com.samp.mobile.launcher;

import android.content.Context;

import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Remote launcher configuration + Phase 10 package manifest + APK update metadata. */
public final class ArlRemoteConfig {
    public interface Callback { void onReady(); }

    public static final class DataFile {
        public final String path;
        public final String sha256;
        public final long bytes;

        DataFile(String path, String sha256, long bytes) {
            this.path = path == null ? "" : path.trim();
            this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase();
            this.bytes = bytes;
        }

        public boolean hasSha256() {
            return sha256.matches("(?i)[0-9a-f]{64}");
        }
    }

    public static final class DataPackage {
        public final String id;
        public final String url;
        public final String sha256;
        public final long bytes;
        public final List<DataFile> files;

        DataPackage(String id, String url, String sha256, long bytes, List<DataFile> files) {
            this.id = id == null ? "" : id.trim();
            this.url = url == null ? "" : url.trim();
            this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase();
            this.bytes = bytes;
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
        }

        public boolean valid() {
            return !id.isEmpty()
                    && validHttp(url)
                    && sha256.matches("(?i)[0-9a-f]{64}")
                    && bytes > 0
                    && !files.isEmpty();
        }
    }

    private static volatile String host = ArlConfig.DEFAULT_HOST;
    private static volatile int port = ArlConfig.DEFAULT_PORT;
    private static volatile boolean maintenance = false;

    private static volatile int appLatestVersionCode = -1;
    private static volatile int appMinVersionCode = -1;
    private static volatile String appVersionName = "";
    private static volatile String appApkUrl = "";
    private static volatile String appSha256 = "";
    private static volatile long appBytes = -1L;
    private static volatile boolean appMandatory = false;
    private static volatile boolean appReleaseDeclared = false;

    private static volatile String dataVersion = "";
    private static volatile String dataUrl = "";
    private static volatile String dataSha256 = "";
    private static volatile long dataBytes = -1L;
    private static volatile String dataManifestUrl = "";
    private static volatile boolean dataReleaseDeclared = false;
    private static volatile boolean dataManifestReady = false;
    private static volatile List<DataFile> dataFiles = Collections.emptyList();
    private static volatile List<DataPackage> dataPackages = Collections.emptyList();

    // Phase 11: optional, explicitly-authorized GTA base distribution.
    // The launcher refuses to consume a remote base unless BOTH launcher config
    // and the base manifest declare distributionAuthorized=true.
    private static volatile String baseVersion = "";
    private static volatile String baseManifestUrl = "";
    private static volatile String baseProvider = "";
    private static volatile boolean baseDistributionAuthorized = false;
    private static volatile boolean baseReleaseDeclared = false;
    private static volatile boolean baseManifestReady = false;
    private static volatile List<DataFile> baseFiles = Collections.emptyList();
    private static volatile List<DataPackage> basePackages = Collections.emptyList();

    private ArlRemoteConfig() {}

    public static String host(){ return host; }
    public static int port(){ return port; }
    public static boolean maintenance(){ return maintenance; }

    public static int appLatestVersionCode(){ return appLatestVersionCode; }
    public static int appMinVersionCode(){ return appMinVersionCode; }
    public static String appVersionName(){ return appVersionName; }
    public static String appApkUrl(){ return appApkUrl; }
    public static String appSha256(){ return appSha256; }
    public static long appBytes(){ return appBytes; }
    public static boolean appMandatory(){ return appMandatory; }
    public static boolean hasAppRelease(){ return appReleaseDeclared; }

    public static String dataVersion(){ return dataVersion; }
    public static String dataUrl(){ return dataUrl; }
    public static String dataSha256(){ return dataSha256; }
    public static long dataBytes(){ return dataBytes; }
    public static String dataManifestUrl(){ return dataManifestUrl; }
    public static boolean hasDataRelease(){ return dataReleaseDeclared; }
    public static boolean dataManifestReady(){ return dataManifestReady; }
    public static List<DataFile> dataFiles(){ return dataFiles; }
    public static List<DataPackage> dataPackages(){ return dataPackages; }
    public static boolean packageMode(){ return !dataPackages.isEmpty(); }

    public static String baseVersion(){ return baseVersion; }
    public static String baseManifestUrl(){ return baseManifestUrl; }
    public static String baseProvider(){ return baseProvider; }
    public static boolean baseDistributionAuthorized(){ return baseDistributionAuthorized; }
    public static boolean hasBaseRelease(){ return baseReleaseDeclared; }
    public static boolean baseManifestReady(){ return baseManifestReady; }
    public static List<DataFile> baseFiles(){ return baseFiles; }
    public static List<DataPackage> basePackages(){ return basePackages; }

    public static void refresh(Context context, Callback cb) {
        final Context app = context.getApplicationContext();
        dataManifestReady = false;
        dataFiles = Collections.emptyList();
        dataPackages = Collections.emptyList();
        baseManifestReady = false;
        baseFiles = Collections.emptyList();
        basePackages = Collections.emptyList();
        baseReleaseDeclared = false;
        baseDistributionAuthorized = false;
        baseVersion = "";
        baseManifestUrl = "";
        baseProvider = "";
        appReleaseDeclared = false;

        StringRequest req = new StringRequest(ArlConfig.REMOTE_CONFIG,
            new Response.Listener<String>() {
                @Override public void onResponse(String response) {
                    boolean fetchExternalManifest = false;
                    boolean fetchExternalBaseManifest = false;
                    try {
                        JSONObject root = new JSONObject(response);
                        maintenance = root.optBoolean("maintenance", false);

                        JSONObject server = root.optJSONObject("server");
                        if(server != null) {
                            String h = server.optString("host", host).trim();
                            int p = server.optInt("port", port);
                            if(!h.isEmpty()) host = h;
                            if(p > 0 && p <= 65535) port = p;
                        }

                        JSONObject appNode = root.optJSONObject("app");
                        if(appNode != null) {
                            appLatestVersionCode = appNode.optInt("latestVersionCode", -1);
                            appMinVersionCode = appNode.optInt("minVersionCode", -1);
                            appVersionName = appNode.optString("versionName", "").trim();
                            appApkUrl = appNode.optString("apkUrl", "").trim();
                            appSha256 = appNode.optString("sha256", "").trim().toLowerCase();
                            appBytes = appNode.optLong("bytes", -1L);
                            appMandatory = appNode.optBoolean("mandatory", false);
                            appReleaseDeclared = appLatestVersionCode > 0
                                    && validHttp(appApkUrl)
                                    && appSha256.matches("(?i)[0-9a-f]{64}");
                        }

                        JSONObject client = root.optJSONObject("client");
                        if(client != null) {
                            JSONObject base = client.optJSONObject("base");
                            if(base != null) {
                                baseVersion = base.optString("version", "").trim();
                                baseManifestUrl = base.optString("manifestUrl",
                                        base.optString("manifest", "")).trim();
                                baseProvider = base.optString("provider", "").trim();
                                baseDistributionAuthorized =
                                        base.optBoolean("distributionAuthorized", false);

                                List<DataPackage> inlineBasePackages = parsePackages(base);
                                if(baseDistributionAuthorized && !inlineBasePackages.isEmpty()) {
                                    setBasePackages(inlineBasePackages);
                                    baseManifestReady = true;
                                } else if(baseDistributionAuthorized
                                        && validHttp(baseManifestUrl)
                                        && !baseVersion.isEmpty()) {
                                    fetchExternalBaseManifest = true;
                                }

                                baseReleaseDeclared = baseDistributionAuthorized
                                        && !baseVersion.isEmpty()
                                        && (validHttp(baseManifestUrl)
                                            || !inlineBasePackages.isEmpty());
                            }

                            JSONObject data = client.optJSONObject("data");
                            JSONObject src = data != null ? data : client;

                            dataVersion = src.optString("version",
                                    src.optString("dataVersion", "")).trim();
                            dataUrl = src.optString("url",
                                    src.optString("dataUrl", "")).trim();
                            dataSha256 = src.optString("sha256",
                                    src.optString("dataSha256", "")).trim().toLowerCase();
                            dataBytes = src.optLong("bytes",
                                    src.optLong("dataBytes", -1L));
                            dataManifestUrl = src.optString("manifestUrl",
                                    src.optString("manifest", "")).trim();

                            List<DataPackage> inlinePackages = parsePackages(src);
                            if(!inlinePackages.isEmpty()) {
                                setPackages(inlinePackages);
                                dataManifestReady = true;
                            } else {
                                List<DataFile> inline = parseFiles(src);
                                if(!inline.isEmpty()) {
                                    dataFiles = Collections.unmodifiableList(inline);
                                    dataManifestReady = true;
                                } else if(validHttp(dataManifestUrl) && !dataVersion.isEmpty()) {
                                    fetchExternalManifest = true;
                                }
                            }

                            boolean legacy = !dataVersion.isEmpty()
                                    && validHttp(dataUrl)
                                    && dataSha256.matches("(?i)[0-9a-f]{64}");
                            boolean packaged = !dataVersion.isEmpty()
                                    && validHttp(dataManifestUrl);
                            dataReleaseDeclared = legacy || packaged || !inlinePackages.isEmpty();
                        } else {
                            dataReleaseDeclared = false;
                        }
                    } catch(Exception ignored) {
                        dataReleaseDeclared = false;
                        appReleaseDeclared = false;
                    }

                    if(fetchExternalBaseManifest) {
                        final boolean fetchDataAfterBase = fetchExternalManifest;
                        fetchBaseManifest(app, () -> {
                            if(fetchDataAfterBase) fetchManifest(app, cb);
                            else cb.onReady();
                        });
                    } else if(fetchExternalManifest) {
                        fetchManifest(app, cb);
                    } else {
                        cb.onReady();
                    }
                }
            },
            new Response.ErrorListener() {
                @Override public void onErrorResponse(VolleyError error) {
                    cb.onReady();
                }
            });
        Volley.newRequestQueue(app).add(req);
    }

    private static void fetchManifest(Context context, Callback cb) {
        StringRequest req = new StringRequest(dataManifestUrl,
            new Response.Listener<String>() {
                @Override public void onResponse(String response) {
                    try {
                        JSONObject root = new JSONObject(response);
                        String manifestVersion = root.optString("version", "").trim();
                        if(!manifestVersion.isEmpty() && !manifestVersion.equals(dataVersion))
                            throw new IllegalStateException("manifest version mismatch");

                        List<DataPackage> packages = parsePackages(root);
                        if(!packages.isEmpty()) {
                            setPackages(packages);
                            dataManifestReady = true;
                        } else {
                            List<DataFile> parsed = parseFiles(root);
                            if(parsed.isEmpty())
                                throw new IllegalStateException("empty DATA manifest");
                            dataFiles = Collections.unmodifiableList(parsed);
                            dataPackages = Collections.emptyList();
                            dataManifestReady = true;
                        }
                    } catch(Exception ignored) {
                        dataFiles = Collections.emptyList();
                        dataPackages = Collections.emptyList();
                        dataManifestReady = false;
                    }
                    cb.onReady();
                }
            },
            new Response.ErrorListener() {
                @Override public void onErrorResponse(VolleyError error) {
                    dataFiles = Collections.emptyList();
                    dataPackages = Collections.emptyList();
                    dataManifestReady = false;
                    cb.onReady();
                }
            });
        Volley.newRequestQueue(context.getApplicationContext()).add(req);
    }

    private static void fetchBaseManifest(Context context, Callback cb) {
        StringRequest req = new StringRequest(baseManifestUrl,
            new Response.Listener<String>() {
                @Override public void onResponse(String response) {
                    try {
                        JSONObject root = new JSONObject(response);
                        if(!root.optBoolean("distributionAuthorized", false))
                            throw new SecurityException("base manifest is not authorized");
                        String manifestVersion = root.optString("version", "").trim();
                        if(!manifestVersion.isEmpty() && !manifestVersion.equals(baseVersion))
                            throw new IllegalStateException("base manifest version mismatch");

                        List<DataPackage> packages = parsePackages(root);
                        if(packages.isEmpty())
                            throw new IllegalStateException("empty base manifest");
                        setBasePackages(packages);
                        baseManifestReady = true;
                    } catch(Exception ignored) {
                        baseFiles = Collections.emptyList();
                        basePackages = Collections.emptyList();
                        baseManifestReady = false;
                    }
                    cb.onReady();
                }
            },
            new Response.ErrorListener() {
                @Override public void onErrorResponse(VolleyError error) {
                    baseFiles = Collections.emptyList();
                    basePackages = Collections.emptyList();
                    baseManifestReady = false;
                    cb.onReady();
                }
            });
        Volley.newRequestQueue(context.getApplicationContext()).add(req);
    }

    private static void setBasePackages(List<DataPackage> packages) {
        ArrayList<DataPackage> valid = new ArrayList<>();
        ArrayList<DataFile> all = new ArrayList<>();
        for(DataPackage pkg : packages) {
            if(pkg == null || !pkg.valid()) continue;
            valid.add(pkg);
            all.addAll(pkg.files);
        }
        basePackages = Collections.unmodifiableList(valid);
        baseFiles = Collections.unmodifiableList(all);
    }

    private static void setPackages(List<DataPackage> packages) {
        ArrayList<DataPackage> valid = new ArrayList<>();
        ArrayList<DataFile> all = new ArrayList<>();
        for(DataPackage pkg : packages) {
            if(pkg == null || !pkg.valid()) continue;
            valid.add(pkg);
            all.addAll(pkg.files);
        }
        dataPackages = Collections.unmodifiableList(valid);
        dataFiles = Collections.unmodifiableList(all);
    }

    private static List<DataPackage> parsePackages(JSONObject src) {
        ArrayList<DataPackage> next = new ArrayList<>();
        JSONArray packages = src.optJSONArray("packages");
        if(packages == null) return next;

        for(int i = 0; i < packages.length(); i++) {
            JSONObject item = packages.optJSONObject(i);
            if(item == null) continue;
            String id = item.optString("id", "").trim();
            String url = item.optString("url", "").trim();
            String sha = item.optString("sha256", "").trim();
            long bytes = item.optLong("bytes", -1L);
            List<DataFile> files = parseFiles(item);
            DataPackage pkg = new DataPackage(id, url, sha, bytes, files);
            if(pkg.valid()) next.add(pkg);
        }
        return next;
    }

    private static List<DataFile> parseFiles(JSONObject src) {
        ArrayList<DataFile> next = new ArrayList<>();
        JSONArray files = src.optJSONArray("files");
        if(files != null) {
            for(int i = 0; i < files.length(); i++) {
                JSONObject item = files.optJSONObject(i);
                if(item == null) continue;
                String path = item.optString("path", "").trim();
                String sha = item.optString("sha256", "").trim();
                long bytes = item.optLong("bytes", -1L);
                if(!path.isEmpty()) next.add(new DataFile(path, sha, bytes));
            }
        }

        if(next.isEmpty()) {
            JSONArray required = src.optJSONArray("requiredFiles");
            if(required != null) {
                for(int i = 0; i < required.length(); i++) {
                    String path = required.optString(i, "").trim();
                    if(!path.isEmpty()) next.add(new DataFile(path, "", -1L));
                }
            }
        }
        return next;
    }

    private static boolean validHttp(String value) {
        if(value == null) return false;
        String s = value.trim().toLowerCase();
        return s.startsWith("https://") || s.startsWith("http://");
    }
}
