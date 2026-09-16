package com.samp.mobile.launcher;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import com.samp.mobile.R;
import com.samp.mobile.launcher.config.Config;

public class SplashActivity extends AppCompatActivity {
    private static final long SPLASH_MS = 1250L;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable openLauncher = () -> {
        if (isFinishing() || isDestroyed()) return;
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Config.currentContext = this;
        setContentView(R.layout.activity_splash);
        handler.postDelayed(openLauncher, SPLASH_MS);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(openLauncher);
        super.onDestroy();
    }
}
