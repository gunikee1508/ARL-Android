package com.samp.mobile.launcher;

import android.content.Context;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import org.json.JSONObject;

public final class ArlRemoteConfig {
    public interface Callback { void onReady(); }

    private static volatile String host = ArlConfig.DEFAULT_HOST;
    private static volatile int port = ArlConfig.DEFAULT_PORT;
    private static volatile boolean maintenance = false;
    private static volatile String dataVersion = "1";
    private static volatile String dataUrl = "";

    private ArlRemoteConfig() {}
    public static String host(){ return host; }
    public static int port(){ return port; }
    public static boolean maintenance(){ return maintenance; }
    public static String dataVersion(){ return dataVersion; }
    public static String dataUrl(){ return dataUrl; }

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
                            dataVersion = client.optString("dataVersion", dataVersion);
                            dataUrl = client.optString("dataUrl", dataUrl);
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
