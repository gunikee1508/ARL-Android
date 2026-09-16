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

/** Remote launcher configuration + DATA manifest + APK update metadata. */
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

    public static void refresh(Context context, Callback cb) {
        final Context app = context.getApplicationContext();
        dataManifestReady = false;
        dataFiles = Collections.emptyList();
        appReleaseDeclared = false;

        StringRequest req = new StringRequest(ArlConfig.REMOTE_CONFIG,
            new Response.Listener<String>() {
                @Override public void onResponse(String response) {
                    boolean fetchExternalManifest = false;
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

                            dataReleaseDeclared = !dataVersion.isEmpty()
                                    && validHttp(dataUrl)
                                    && dataSha256.matches("(?i)[0-9a-f]{64}");

                            List<DataFile> inline = parseFiles(src);
                            if(!inline.isEmpty()) {
                                dataFiles = Collections.unmodifiableList(inline);
                                dataManifestReady = true;
                            } else if(dataReleaseDeclared && validHttp(dataManifestUrl)) {
                                fetchExternalManifest = true;
                            }
                        } else {
                            dataReleaseDeclared = false;
                        }
                    } catch(Exception ignored) {
                        dataReleaseDeclared = false;
                        appReleaseDeclared = false;
                    }

                    if(fetchExternalManifest) fetchManifest(app, cb);
                    else cb.onReady();
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

                        List<DataFile> parsed = parseFiles(root);
                        if(parsed.isEmpty())
                            throw new IllegalStateException("empty DATA manifest");

                        dataFiles = Collections.unmodifiableList(parsed);
                        dataManifestReady = true;
                    } catch(Exception ignored) {
                        dataFiles = Collections.emptyList();
                        dataManifestReady = false;
                    }
                    cb.onReady();
                }
            },
            new Response.ErrorListener() {
                @Override public void onErrorResponse(VolleyError error) {
                    dataFiles = Collections.emptyList();
                    dataManifestReady = false;
                    cb.onReady();
                }
            });
        Volley.newRequestQueue(context.getApplicationContext()).add(req);
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
