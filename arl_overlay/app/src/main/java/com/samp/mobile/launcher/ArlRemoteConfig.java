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

    private static volatile String dataVersion = "";
    private static volatile String dataUrl = "";
    private static volatile String dataSha256 = "";
    private static volatile long dataBytes = -1L;
    private static volatile List<DataFile> dataFiles = Collections.emptyList();

    private ArlRemoteConfig() {}

    public static String host(){ return host; }
    public static int port(){ return port; }
    public static boolean maintenance(){ return maintenance; }
    public static String dataVersion(){ return dataVersion; }
    public static String dataUrl(){ return dataUrl; }
    public static String dataSha256(){ return dataSha256; }
    public static long dataBytes(){ return dataBytes; }
    public static List<DataFile> dataFiles(){ return dataFiles; }

    public static void refresh(Context context, Callback cb) {
        StringRequest req = new StringRequest(ArlConfig.REMOTE_CONFIG,
            new Response.Listener<String>() {
                @Override public void onResponse(String response) {
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

                        JSONObject client = root.optJSONObject("client");
                        if(client != null) {
                            // Preferred Phase-2 format: client.data.{version,url,sha256,bytes,files}.
                            // Flat Phase-1 fields remain accepted for compatibility.
                            JSONObject data = client.optJSONObject("data");
                            JSONObject src = data != null ? data : client;

                            dataVersion = src.optString("version",
                                    src.optString("dataVersion", dataVersion)).trim();
                            dataUrl = src.optString("url",
                                    src.optString("dataUrl", dataUrl)).trim();
                            dataSha256 = src.optString("sha256",
                                    src.optString("dataSha256", dataSha256)).trim().toLowerCase();
                            dataBytes = src.optLong("bytes",
                                    src.optLong("dataBytes", dataBytes));

                            ArrayList<DataFile> nextFiles = new ArrayList<>();
                            JSONArray files = src.optJSONArray("files");
                            if(files != null) {
                                for(int i = 0; i < files.length(); i++) {
                                    JSONObject item = files.optJSONObject(i);
                                    if(item == null) continue;
                                    String path = item.optString("path", "").trim();
                                    String sha = item.optString("sha256", "").trim();
                                    long bytes = item.optLong("bytes", -1L);
                                    if(!path.isEmpty()) nextFiles.add(new DataFile(path, sha, bytes));
                                }
                            }

                            // Compatibility fallback for the earlier string-only list.
                            if(nextFiles.isEmpty()) {
                                JSONArray required = src.optJSONArray("requiredFiles");
                                if(required != null) {
                                    for(int i = 0; i < required.length(); i++) {
                                        String path = required.optString(i, "").trim();
                                        if(!path.isEmpty()) nextFiles.add(new DataFile(path, "", -1L));
                                    }
                                }
                            }
                            dataFiles = Collections.unmodifiableList(nextFiles);
                        }
                    } catch(Exception ignored) {}
                    cb.onReady();
                }
            },
            new Response.ErrorListener() {
                @Override public void onErrorResponse(VolleyError error) {
                    cb.onReady();
                }
            });
        Volley.newRequestQueue(context.getApplicationContext()).add(req);
    }
}
