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
import android.widget.ProgressBar;
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
    private TextView endpoint, status, dataStatus;
    private Button playButton, repairButton;
    private ProgressBar dataProgress;
    private boolean dataBusy = false;
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
        dataStatus = findViewById(R.id.arl_data_status);
        dataProgress = findViewById(R.id.arl_data_progress);
        playButton = findViewById(R.id.arl_play);
        repairButton = findViewById(R.id.arl_repair);

        loadNickname();

        findViewById(R.id.arl_discord).setOnClickListener(v -> open(ArlConfig.DISCORD));
        findViewById(R.id.arl_instagram).setOnClickListener(v -> open(ArlConfig.INSTAGRAM));
        findViewById(R.id.arl_forum).setOnClickListener(v -> open(ArlConfig.FORUM));
        repairButton.setOnClickListener(v -> beginRepair(false));
        playButton.setOnClickListener(v -> play());

        endpoint.setText(ArlConfig.DEFAULT_HOST + ":" + ArlConfig.DEFAULT_PORT);
        status.setText("CONSULTANDO SERVIDOR...");
        dataStatus.setText("VERIFICANDO DATA...");
        dataProgress.setVisibility(View.GONE);

        ArlRemoteConfig.refresh(this, () -> {
            endpoint.setText(ArlRemoteConfig.host() + ":" + ArlRemoteConfig.port());
            refreshDataState();

            if(ArlRemoteConfig.maintenance()) {
                status.setText("MANUTENÇÃO");
                playButton.setEnabled(false);
            } else {
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

    private void refreshDataState() {
        if(dataBusy) return;

        if(!ArlDataManager.isConfigured()) {
            dataStatus.setText("DATA LOCAL • PACOTE REMOTO AINDA NÃO CONFIGURADO");
            repairButton.setEnabled(false);
            if(!ArlRemoteConfig.maintenance()) playButton.setEnabled(true);
            return;
        }

        repairButton.setEnabled(true);
        if(ArlDataManager.requiresRepair(this)) {
            String version = ArlRemoteConfig.dataVersion();
            dataStatus.setText("ATUALIZAÇÃO DE ARQUIVOS NECESSÁRIA" +
                    (version.isEmpty() ? "" : " • " + version));
            playButton.setEnabled(false);
        } else {
            String installed = ArlDataManager.installedVersion(this);
            dataStatus.setText("DATA ATUALIZADA" +
                    (installed.isEmpty() ? "" : " • " + installed));
            if(!ArlRemoteConfig.maintenance()) playButton.setEnabled(true);
        }
    }

    private void beginRepair(boolean force) {
        if(dataBusy) return;
        if(!ArlDataManager.isConfigured()) {
            Toast.makeText(this,
                    "O pacote DATA ainda não está configurado no launcher.json.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        dataBusy = true;
        playButton.setEnabled(false);
        repairButton.setEnabled(false);
        dataProgress.setVisibility(View.VISIBLE);
        dataProgress.setIndeterminate(false);
        dataProgress.setProgress(0);
        dataStatus.setText("PREPARANDO VERIFICAÇÃO...");

        ArlDataManager.verifyOrRepair(this, force, new ArlDataManager.Listener() {
            @Override public void onState(String text) {
                dataStatus.setText(text);
            }

            @Override public void onProgress(int percent, String text) {
                dataProgress.setProgress(percent);
                dataStatus.setText(text + " • " + percent + "%");
            }

            @Override public void onComplete(boolean success, String message) {
                dataBusy = false;
                repairButton.setEnabled(true);
                dataProgress.setVisibility(View.GONE);

                if(success) {
                    refreshDataState();
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                } else {
                    dataStatus.setText("FALHA NA DATA • TOQUE EM REPARAR");
                    playButton.setEnabled(!ArlDataManager.requiresRepair(MainActivity.this)
                            && !ArlRemoteConfig.maintenance());
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void play() {
        if(dataBusy) {
            Toast.makeText(this, "Aguarde a atualização dos arquivos.", Toast.LENGTH_LONG).show();
            return;
        }

        if(ArlRemoteConfig.maintenance()) {
            Toast.makeText(this, "Servidor em manutenção.", Toast.LENGTH_LONG).show();
            return;
        }

        if(ArlDataManager.isConfigured() && ArlDataManager.requiresRepair(this)) {
            Toast.makeText(this,
                    "Os arquivos do ARL precisam ser atualizados antes de jogar.",
                    Toast.LENGTH_LONG).show();
            beginRepair(false);
            return;
        }

        String nick = nickname.getText().toString().trim();
        if(nick.length() < 3 || nick.length() > 24) {
            Toast.makeText(this, "Nickname: 3 a 24 caracteres.", Toast.LENGTH_LONG).show();
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
