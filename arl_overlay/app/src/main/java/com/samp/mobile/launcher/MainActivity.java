package com.samp.mobile.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.samp.mobile.R;
import com.samp.mobile.game.SAMP;
import com.samp.mobile.launcher.config.Config;
import com.samp.mobile.launcher.fragments.ServerPagesItemFragment;
import com.samp.mobile.launcher.fragments.ServersFragment;
import com.samp.mobile.launcher.util.ConfigValidator;
import com.samp.mobile.launcher.util.SAMPServerInfo;
import com.samp.mobile.launcher.util.SampQueryAPI;

import org.ini4j.Wini;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private EditText nickname;
    private TextView endpoint, status;
    private Button playButton;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /*
     * Compatibility surface for the original launcher fragments/adapters that are
     * still part of the upstream source set. The ARL launcher does not use the old
     * server-browser UI, but javac still compiles those classes and they reference
     * these members on MainActivity.
     */
    public static ArrayList<SAMPServerInfo> mServersList = new ArrayList<>();
    public static ArrayList<SAMPServerInfo> mFavoriteServersList = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Config.currentContext = this;
        ConfigValidator.validateConfigFiles(this);
        setContentView(R.layout.activity_main);

        nickname = findViewById(R.id.arl_nickname);
        endpoint = findViewById(R.id.arl_endpoint);
        status = findViewById(R.id.arl_status);
        playButton = findViewById(R.id.arl_play);

        loadNickname();

        findViewById(R.id.arl_discord).setOnClickListener(v -> open(ArlConfig.DISCORD));
        findViewById(R.id.arl_instagram).setOnClickListener(v -> open(ArlConfig.INSTAGRAM));
        findViewById(R.id.arl_forum).setOnClickListener(v -> open(ArlConfig.FORUM));
        findViewById(R.id.arl_repair).setOnClickListener(v ->
            Toast.makeText(this, "DATA/hash entra na Phase 2.", Toast.LENGTH_LONG).show());

        playButton.setOnClickListener(v -> play());

        endpoint.setText(ArlConfig.DEFAULT_HOST + ":" + ArlConfig.DEFAULT_PORT);
        status.setText("CONSULTANDO SERVIDOR...");

        ArlRemoteConfig.refresh(this, () -> {
            endpoint.setText(ArlRemoteConfig.host() + ":" + ArlRemoteConfig.port());
            if(ArlRemoteConfig.maintenance()) {
                status.setText("MANUTENÇÃO");
                playButton.setEnabled(false);
            } else {
                playButton.setEnabled(true);
                refreshStatus();
            }
        });
    }

    private File settingsFile() {
        return new File(getExternalFilesDir(null), "SAMP/settings.ini");
    }

    private void loadNickname() {
        try {
            if(!settingsFile().exists()) return;
            String nick = new Wini(settingsFile()).get("client", "name");
            if(nick != null && !nick.trim().isEmpty()) nickname.setText(nick.trim());
        } catch(Exception ignored) {}
    }

    private void play() {
        String nick = nickname.getText().toString().trim();
        if(nick.length() < 3 || nick.length() > 24) {
            Toast.makeText(this, "Nickname: 3 a 24 caracteres.", Toast.LENGTH_LONG).show();
            return;
        }
        if(ArlRemoteConfig.maintenance()) {
            Toast.makeText(this, "Servidor em manutenção.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            ConfigValidator.validateConfigFiles(this);
            Wini ini = new Wini(settingsFile());
            ini.put("client", "host", ArlRemoteConfig.host());
            ini.put("client", "port", ArlRemoteConfig.port());
            ini.put("client", "name", nick);
            ini.put("client", "version", ArlConfig.CLIENT_VERSION);
            ini.store();
        } catch(Exception e) {
            Toast.makeText(this, "Falha ao salvar settings.ini.", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, SAMP.class));
    }

    private void refreshStatus() {
        final String host = ArlRemoteConfig.host();
        final int port = ArlRemoteConfig.port();
        executor.execute(() -> {
            String result = "OFFLINE";
            try {
                SampQueryAPI q = new SampQueryAPI(host, port);
                if(q.mo7166d()) {
                    String[] info = q.mo7164b();
                    if(info != null && info.length >= 3)
                        result = "ONLINE  •  " + info[1] + "/" + info[2];
                    else result = "ONLINE";
                    if(q.f7277a != null) q.f7277a.close();
                }
            } catch(Exception ignored) {}
            final String out = result;
            runOnUiThread(() -> status.setText(out));
        });
    }

    public final ArrayList<SAMPServerInfo> getServerList() {
        return mServersList;
    }

    public final ArrayList<SAMPServerInfo> getFavoriteServerList() {
        return mFavoriteServersList;
    }

    public void refreshFavoriteServers() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (!(fragment instanceof ServersFragment) || !fragment.isAdded()) continue;

            for (Fragment child : fragment.getChildFragmentManager().getFragments()) {
                if (!(child instanceof ServerPagesItemFragment)) continue;
                if (((ServerPagesItemFragment) child).getPage() != 0 || child.getView() == null) continue;

                RecyclerView view = child.getView().findViewById(R.id.server_recycler);
                if (view == null || view.getAdapter() == null) continue;
                view.post(() -> {
                    RecyclerView.Adapter<?> adapter = view.getAdapter();
                    if (adapter != null) adapter.notifyDataSetChanged();
                });
            }
        }
    }

    public static void hideKeyboard(Activity activity) {
        if (activity == null) return;
        InputMethodManager inputManager = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        View focused = activity.getCurrentFocus();
        if (inputManager != null && focused != null) {
            inputManager.hideSoftInputFromWindow(
                    focused.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
        }
    }

    private void open(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
