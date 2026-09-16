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

    private static volatile String host = ArlConfig.DEFAULT_HOST;
    private static volatile int port = ArlConfig.DEFAULT_PORT;
    private static volatile boolean maintenance = false;

    private static volatile String dataVersion = "";
    private static volatile String dataUrl = "";
    private static volatile String dataSha256 = "";
    private static volatile long dataBytes = -1L;
    private static volatile List<String> requiredFiles = Collections.emptyList();

    private ArlRemoteConfig() {}

    public static String host(){ return host; }
    public static int port(){ return port; }
    public static boolean maintenance(){ return maintenance; }
    public static String dataVersion(){ return dataVersion; }
    public static String dataUrl(){ return dataUrl; }
    public static String dataSha256(){ return dataSha256; }
    public static long dataBytes(){ return dataBytes; }
    public static List<String> requiredFiles(){ return requiredFiles; }

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
                            // Accept both the original flat Phase-1 format and the
                            // preferred Phase-2 nested "data" object.
                            JSONObject data = client.optJSONObject("data");
                            JSONObject src = data != null ? data : client;

                            dataVersion = src.optString("version",
                                    src.optString("dataVersion", dataVersion)).trim();
                            dataUrl = src.optString("url",
                                    src.optString("dataUrl", dataUrl)).trim();
                            dataSha256 = src.optString("sha256",
                                    src.optString("dataSha256", dataSha256)).trim();
                            dataBytes = src.optLong("bytes",
                                    src.optLong("dataBytes", dataBytes));

                            JSONArray required = src.optJSONArray("requiredFiles");
                            if(required != null) {
                                ArrayList<String> next = new ArrayList<>();
                                for(int i = 0; i < required.length(); i++) {
                                    String item = required.optString(i, "").trim();
                                    if(!item.isEmpty()) next.add(item);
                                }
                                requiredFiles = Collections.unmodifiableList(next);
                            }
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
