package com.samp.mobile.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.samp.mobile.R;
import com.samp.mobile.launcher.config.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * ARL first-run bootstrap.
 *
 * Normal path is zero-touch: detect/import an accessible legitimate GTA SA
 * Android base, verify the managed ARL experience and open the launcher.
 * On scoped-storage Android versions, the OS may require one folder grant;
 * that permission is persisted and reused automatically on later installs.
 */
public class SplashActivity extends AppCompatActivity {
    private static final int REQUEST_GTA_TREE = 7301;
    private static final long MIN_SPLASH_MS = 900L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long createdAt;
    private boolean finished;
    private boolean pickerLaunched;
    private boolean waitingForOfficialGta;
    private List<Uri> persistedTrees = new ArrayList<>();
    private TextView splashStatus;
    private TextView splashDetail;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Config.currentContext = this;
        setContentView(R.layout.activity_splash);
        splashStatus = findViewById(R.id.arl_splash_status);
        splashDetail = findViewById(R.id.arl_splash_detail);
        createdAt = System.currentTimeMillis();
        setSplashStatus("INICIALIZANDO CLIENTE ARL...",
                "Preparando uma entrada segura no Amazing Real Life");
        handler.post(this::bootstrap);
    }

    private void setSplashStatus(String title, String detail) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (splashStatus != null && title != null) splashStatus.setText(title);
            if (splashDetail != null && detail != null) splashDetail.setText(detail);
        });
    }

    private void bootstrap() {
        if (finished || isFinishing() || isDestroyed()) return;
        setSplashStatus("CONSULTANDO DISTRIBUIÇÃO ARL...",
                "Verificando base, cliente e atualizações disponíveis");
        ArlRemoteConfig.refresh(this, this::bootstrapWithRemote);
    }

    private void bootstrapWithRemote() {
        if (finished || isFinishing() || isDestroyed()) return;

        if (ArlBaseImportManager.isBaseReady(this)) {
            setSplashStatus("BASE GTA SA PRONTA",
                    "Preparando os arquivos exclusivos do ARL");
            prepareManagedData();
            return;
        }

        if (ArlBaseDownloadManager.isConfigured()) {
            setSplashStatus("PREPARANDO BASE GTA SA...",
                    "Download automático autorizado disponível");
            ArlBaseDownloadManager.installOrRepair(this, false,
                    new ArlBaseDownloadManager.Listener() {
                        @Override public void onState(String text) {
                            setSplashStatus("PREPARANDO BASE GTA SA", text);
                        }

                        @Override public void onProgress(int percent, String text) {
                            setSplashStatus("BAIXANDO BASE • " + percent + "%", text);
                        }

                        @Override public void onComplete(boolean success, String message) {
                            if (finished || isFinishing() || isDestroyed()) return;
                            if (success && ArlBaseImportManager.isBaseReady(SplashActivity.this)) {
                                setSplashStatus("BASE GTA SA PRONTA",
                                        "Aplicando a experiência Amazing Real Life");
                                prepareManagedData();
                            } else {
                                fallbackBaseDiscovery(message);
                            }
                        }
                    });
            return;
        }

        fallbackBaseDiscovery(null);
    }

    private void fallbackBaseDiscovery(String reason) {
        if (finished || isFinishing() || isDestroyed()) return;
        if (reason != null && !reason.trim().isEmpty()) {
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
        }

        setSplashStatus("VERIFICANDO BASE GTA SA...",
                "Procurando automaticamente uma instalação legítima existente");
        ArlAutoBaseDiscovery.tryImport(this, quietBaseListener(), (imported, message) -> {
            if (finished || isFinishing() || isDestroyed()) return;
            if (imported || ArlBaseImportManager.isBaseReady(this)) {
                setSplashStatus("BASE GTA SA DETECTADA",
                        "Validando a experiência Amazing Real Life");
                prepareManagedData();
            } else {
                setSplashStatus("GTA SAN ANDREAS NECESSÁRIO",
                        "Abrindo a instalação oficial para continuar");
                openOfficialGtaOrRequestAccess();
            }
        });
    }

    private void openOfficialGtaOrRequestAccess() {
        if (finished || isFinishing() || isDestroyed()) return;

        if (isOfficialGtaInstalled()) {
            setSplashStatus("GTA SA INSTALADO",
                    "Tentando acessar os arquivos do jogo");
            tryPersistedTreesOrRequestAccess();
            return;
        }

        try {
            waitingForOfficialGta = true;
            Intent market = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + ArlConfig.GTA_PACKAGE));
            market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(market);
        } catch (Exception first) {
            try {
                waitingForOfficialGta = true;
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(ArlConfig.GTA_PLAY_STORE)));
            } catch (Exception ignored) {
                waitingForOfficialGta = false;
                tryPersistedTreesOrRequestAccess();
            }
        }
    }

    private boolean isOfficialGtaInstalled() {
        try {
            getPackageManager().getPackageInfo(ArlConfig.GTA_PACKAGE, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!waitingForOfficialGta || finished) return;
        if (!isOfficialGtaInstalled()) return;

        waitingForOfficialGta = false;
        setSplashStatus("GTA SA ENCONTRADO",
                "Importando automaticamente os arquivos necessários");
        handler.postDelayed(this::bootstrapWithRemote, 450L);
    }

    private void tryPersistedTreesOrRequestAccess() {
        persistedTrees = new ArrayList<>();
        try {
            for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
                if (permission != null && permission.isReadPermission() && permission.getUri() != null)
                    persistedTrees.add(permission.getUri());
            }
        } catch (Exception ignored) {}
        tryPersistedTreeAt(0);
    }

    private void tryPersistedTreeAt(int index) {
        if (finished || isFinishing() || isDestroyed()) return;
        if (ArlBaseImportManager.isBaseReady(this)) {
            prepareManagedData();
            return;
        }
        if (index >= persistedTrees.size()) {
            requestGtaFolderOnce();
            return;
        }

        setSplashStatus("VALIDANDO ACESSO SALVO...",
                "Tentando reutilizar a permissão do GTA San Andreas");
        Uri uri = persistedTrees.get(index);
        ArlBaseImportManager.importTree(this, uri, new ArlBaseImportManager.Listener() {
            @Override public void onState(String text) {
                setSplashStatus("PREPARANDO BASE GTA SA...", text);
            }
            @Override public void onProgress(int percent, String text) {
                setSplashStatus("IMPORTANDO BASE • " + percent + "%", text);
            }
            @Override public void onComplete(boolean success, String message) {
                if (success && ArlBaseImportManager.isBaseReady(SplashActivity.this)) {
                    setSplashStatus("BASE GTA SA PRONTA",
                            "Carregando a personalização exclusiva do ARL");
                    prepareManagedData();
                } else {
                    tryPersistedTreeAt(index + 1);
                }
            }
        });
    }

    private void requestGtaFolderOnce() {
        if (finished || pickerLaunched || isFinishing() || isDestroyed()) {
            openLauncher();
            return;
        }
        pickerLaunched = true;
        setSplashStatus("CONFIRMAÇÃO ÚNICA DO ANDROID",
                "Selecione a pasta files da sua instalação legítima do GTA SA");
        Toast.makeText(this,
                "O Android bloqueou a detecção direta. Confirme uma vez a pasta 'files' do seu GTA San Andreas.",
                Toast.LENGTH_LONG).show();
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_GTA_TREE);
        } catch (Exception e) {
            openLauncher();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_GTA_TREE) return;

        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            openLauncher();
            return;
        }

        Uri tree = data.getData();
        try {
            int flags = data.getFlags() &
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(tree, flags);
        } catch (Exception ignored) {}

        setSplashStatus("IMPORTANDO BASE GTA SA...",
                "Isso só é necessário na primeira preparação do cliente");
        ArlBaseImportManager.importTree(this, tree, new ArlBaseImportManager.Listener() {
            @Override public void onState(String text) {
                setSplashStatus("PREPARANDO BASE GTA SA...", text);
            }
            @Override public void onProgress(int percent, String text) {
                setSplashStatus("IMPORTANDO BASE • " + percent + "%", text);
            }
            @Override public void onComplete(boolean success, String message) {
                if (success && ArlBaseImportManager.isBaseReady(SplashActivity.this)) {
                    setSplashStatus("BASE GTA SA PRONTA",
                            "Aplicando a experiência Amazing Real Life");
                    prepareManagedData();
                } else {
                    Toast.makeText(SplashActivity.this, message, Toast.LENGTH_LONG).show();
                    openLauncher();
                }
            }
        });
    }

    private void prepareManagedData() {
        if (finished || isFinishing() || isDestroyed()) return;
        setSplashStatus("VERIFICANDO ARQUIVOS ARL...",
                "Conferindo integridade e atualizações do cliente");

        if (!ArlDataManager.isConfigured()) {
            openLauncher();
            return;
        }

        ArlDataManager.verifyOrRepair(this, false, new ArlDataManager.Listener() {
            @Override public void onState(String text) {
                setSplashStatus("PREPARANDO AMAZING REAL LIFE", text);
            }
            @Override public void onProgress(int percent, String text) {
                setSplashStatus("PREPARANDO ARL • " + percent + "%", text);
            }
            @Override public void onComplete(boolean success, String message) {
                if (!success)
                    Toast.makeText(SplashActivity.this, message, Toast.LENGTH_LONG).show();
                setSplashStatus("CLIENTE ARL PRONTO",
                        "Abrindo o acesso ao servidor");
                openLauncher();
            }
        });
    }

    private ArlBaseImportManager.Listener quietBaseListener() {
        return new ArlBaseImportManager.Listener() {
            @Override public void onState(String text) {
                setSplashStatus("DETECTANDO BASE GTA SA...", text);
            }
            @Override public void onProgress(int percent, String text) {
                setSplashStatus("PREPARANDO BASE • " + percent + "%", text);
            }
            @Override public void onComplete(boolean success, String message) {}
        };
    }

    private void openLauncher() {
        if (finished || isFinishing() || isDestroyed()) return;
        finished = true;
        setSplashStatus("BEM-VINDO AO AMAZING REAL LIFE",
                "Tudo pronto para continuar");
        long elapsed = System.currentTimeMillis() - createdAt;
        long wait = Math.max(0L, MIN_SPLASH_MS - elapsed);
        handler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, wait);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
