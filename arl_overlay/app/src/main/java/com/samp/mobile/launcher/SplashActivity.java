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
 * Android base, download/verify the managed ARL DATA and open the launcher.
 * On scoped-storage Android versions, the OS may require one folder grant;
 * that permission is persisted and reused automatically on later installs.
 */
public class SplashActivity extends AppCompatActivity {
    private static final int REQUEST_GTA_TREE = 7301;
    private static final long MIN_SPLASH_MS = 700L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long createdAt;
    private boolean finished;
    private boolean pickerLaunched;
    private List<Uri> persistedTrees = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Config.currentContext = this;
        setContentView(R.layout.activity_splash);
        createdAt = System.currentTimeMillis();
        handler.post(this::bootstrap);
    }

    private void bootstrap() {
        if (finished || isFinishing() || isDestroyed()) return;

        if (ArlBaseImportManager.isBaseReady(this)) {
            prepareManagedData();
            return;
        }

        ArlAutoBaseDiscovery.tryImport(this, quietBaseListener(), (imported, message) -> {
            if (finished || isFinishing() || isDestroyed()) return;
            if (imported || ArlBaseImportManager.isBaseReady(this)) {
                prepareManagedData();
            } else {
                tryPersistedTreesOrRequestAccess();
            }
        });
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

        Uri uri = persistedTrees.get(index);
        ArlBaseImportManager.importTree(this, uri, new ArlBaseImportManager.Listener() {
            @Override public void onState(String text) {}
            @Override public void onProgress(int percent, String text) {}
            @Override public void onComplete(boolean success, String message) {
                if (success && ArlBaseImportManager.isBaseReady(SplashActivity.this))
                    prepareManagedData();
                else
                    tryPersistedTreeAt(index + 1);
            }
        });
    }

    private void requestGtaFolderOnce() {
        if (finished || pickerLaunched || isFinishing() || isDestroyed()) {
            openLauncher();
            return;
        }
        pickerLaunched = true;
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

        ArlBaseImportManager.importTree(this, tree, new ArlBaseImportManager.Listener() {
            @Override public void onState(String text) {}
            @Override public void onProgress(int percent, String text) {}
            @Override public void onComplete(boolean success, String message) {
                if (success && ArlBaseImportManager.isBaseReady(SplashActivity.this)) {
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
        ArlRemoteConfig.refresh(this, () -> {
            if (finished || isFinishing() || isDestroyed()) return;
            if (!ArlDataManager.isConfigured()) {
                openLauncher();
                return;
            }

            ArlDataManager.verifyOrRepair(this, false, new ArlDataManager.Listener() {
                @Override public void onState(String text) {}
                @Override public void onProgress(int percent, String text) {}
                @Override public void onComplete(boolean success, String message) {
                    if (!success)
                        Toast.makeText(SplashActivity.this, message, Toast.LENGTH_LONG).show();
                    openLauncher();
                }
            });
        });
    }

    private ArlBaseImportManager.Listener quietBaseListener() {
        return new ArlBaseImportManager.Listener() {
            @Override public void onState(String text) {}
            @Override public void onProgress(int percent, String text) {}
            @Override public void onComplete(boolean success, String message) {}
        };
    }

    private void openLauncher() {
        if (finished || isFinishing() || isDestroyed()) return;
        finished = true;
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
