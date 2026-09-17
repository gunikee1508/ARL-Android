#!/usr/bin/env python3
from pathlib import Path
import argparse
import re


def die(msg):
    raise SystemExit('[ARL NATIVE SELFTEST] ERRO: ' + msg)


def read(path):
    return path.read_text(encoding='utf-8-sig')


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(data, encoding='utf-8', newline='\n')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('repo', type=Path)
    root = ap.parse_args().repo.resolve()

    gradle = root / 'app/build.gradle'
    manifest = root / 'app/src/main/AndroidManifest.xml'
    activity = root / 'app/src/main/java/com/samp/mobile/launcher/NativeProbeActivity.java'
    for p in (gradle, manifest, activity):
        if not p.is_file():
            die('arquivo ausente: ' + str(p))

    # Ensure ActivityScenario is directly available to the instrumentation test.
    g = read(gradle)
    dep = "    androidTestImplementation 'androidx.test:core:1.4.0'\n"
    if "androidx.test:core:" not in g:
        anchor = "    androidTestImplementation 'androidx.test.espresso:espresso-core:3.4.0'\n"
        if anchor not in g:
            die('âncora androidTestImplementation não encontrada')
        g = g.replace(anchor, anchor + dep, 1)
    write(gradle, g)

    # Replace the basic probe Activity with a report-producing self-test Activity.
    write(activity, r'''package com.samp.mobile.launcher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class NativeProbeActivity extends Activity {
    private static final String TAG = "ARLNativeSelfTest";
    public static final String PREFS = "arl_phase10_native_probe";
    private static native String nativeProbe();

    private void put(String key, Object value) {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
        else e.putString(key, String.valueOf(value));
        e.commit();
    }

    private void writeJsonReport(JSONObject json) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File out = new File(dir, "native_self_test.json");
            try (FileOutputStream fos = new FileOutputStream(out, false)) {
                fos.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            put("report_path", out.getAbsolutePath());
            Log.i(TAG, "report=" + out.getAbsolutePath());
        } catch (Throwable t) {
            put("report_write_error", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            Log.e(TAG, "report write failed", t);
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().clear().commit();

        boolean probeOk = false;
        boolean sampOk = false;
        String probeResult = "";
        String sampError = "";

        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        File gtasa = new File(nativeDir, "libGTASA.so");
        File samp = new File(nativeDir, "libsamp.so");
        File probe = new File(nativeDir, "libarlprobe.so");

        String supportedAbis = Arrays.toString(Build.SUPPORTED_ABIS);
        boolean process64 = Process.is64Bit();

        put("process64", process64);
        put("supported_abis", supportedAbis);
        put("gtasa_present", gtasa.isFile());
        put("samp_present", samp.isFile());
        put("probe_present", probe.isFile());

        try {
            System.loadLibrary("arlprobe");
            probeResult = nativeProbe();
            probeOk = probeResult.contains("native_abi=aarch64")
                    && probeResult.contains("pointer_bits=64")
                    && process64;
        } catch (Throwable t) {
            probeResult = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            Log.e(TAG, "arlprobe load failed", t);
        }
        put("arlprobe_ok", probeOk);
        put("arlprobe_result", probeResult);

        // This is the real Phase 10 libsamp.so. Its JNI_OnLoad must detect the
        // intentionally absent libGTASA.so and return JNI_VERSION_1_6 before hooks.
        try {
            System.loadLibrary("samp");
            sampOk = true;
        } catch (Throwable t) {
            sampError = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            Log.e(TAG, "libsamp load failed", t);
        }
        put("samp_load_ok", sampOk);
        put("samp_load_error", sampError);

        boolean pass = process64
                && !gtasa.isFile()
                && samp.isFile()
                && probe.isFile()
                && probeOk
                && sampOk;
        put("pass", pass);

        JSONObject json = new JSONObject();
        try {
            json.put("phase", "10-native-arm64-selftest");
            json.put("pass", pass);
            json.put("process64", process64);
            json.put("supportedAbis", supportedAbis);
            json.put("gtasaPresent", gtasa.isFile());
            json.put("sampPresent", samp.isFile());
            json.put("probePresent", probe.isFile());
            json.put("arlprobeOk", probeOk);
            json.put("arlprobeResult", probeResult);
            json.put("sampLoadOk", sampOk);
            json.put("sampLoadError", sampError);
            json.put("sdkInt", Build.VERSION.SDK_INT);
            json.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        } catch (Throwable ignored) { }
        writeJsonReport(json);

        String report = "ARL PHASE 10 — NATIVE ARM64 SELF-TEST\n\n"
                + "RESULTADO: " + (pass ? "PASS" : "FAIL") + "\n"
                + "process64=" + process64 + "\n"
                + "abis=" + supportedAbis + "\n"
                + "libGTASA.present=" + gtasa.isFile() + "\n"
                + "libsamp.present=" + samp.isFile() + "\n"
                + "libarlprobe.present=" + probe.isFile() + "\n"
                + "arlprobe=" + probeResult + "\n"
                + "libsamp.load=" + (sampOk ? "OK (GTASA ausente tratado com segurança)" : "FAIL: " + sampError) + "\n"
                + "sdk=" + Build.VERSION.SDK_INT + "\n"
                + "device=" + Build.MANUFACTURER + " " + Build.MODEL + "\n";

        Log.i(TAG, report.replace('\n', ' | '));
        TextView view = new TextView(this);
        view.setTextSize(16f);
        view.setPadding(32, 32, 32, 32);
        view.setText(report);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        setContentView(scroll);
    }
}
''')

    m = read(manifest)

    # Remove launcher intent filters from every existing Activity. This test APK
    # must never auto-launch com.samp.mobile.game.SAMP (which statically loads GTASA).
    m = re.sub(
        r'\s*<intent-filter>\s*<action\s+android:name="android.intent.action.MAIN"\s*/>\s*<category\s+android:name="android.intent.category.LAUNCHER"\s*/>\s*</intent-filter>',
        '',
        m,
        flags=re.S,
    )

    # Convert the self-closing probe Activity into the sole launcher Activity.
    probe_pattern = re.compile(
        r'<activity\s+android:name="\.launcher\.NativeProbeActivity"(?P<attrs>[^>]*)/>',
        re.S,
    )
    launcher_block = r'''<activity
            android:name=".launcher.NativeProbeActivity"\g<attrs>>
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>'''
    m2, n = probe_pattern.subn(launcher_block, m, count=1)
    if n != 1:
        die('NativeProbeActivity self-closing não encontrada no manifest')
    m = m2
    write(manifest, m)

    # Instrumentation test is intentionally ARM64/device-only. It is compiled in CI
    # now and can be executed later on a real/cloud ARM64 Android without GTA.
    test = root / 'app/src/androidTest/java/com/samp/mobile/launcher/NativeProbeInstrumentedTest.java'
    write(test, r'''package com.samp.mobile.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import android.os.Process;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NativeProbeInstrumentedTest {
    @Test
    public void realArm64SampLoadsSafelyWithoutGtasa() {
        assertTrue("test process must be 64-bit", Process.is64Bit());
        try (ActivityScenario<NativeProbeActivity> scenario = ActivityScenario.launch(NativeProbeActivity.class)) {
            scenario.onActivity(activity -> {
                SharedPreferences p = activity.getSharedPreferences(NativeProbeActivity.PREFS, 0);
                assertTrue("overall native self-test", p.getBoolean("pass", false));
                assertFalse("proprietary GTASA must be absent", p.getBoolean("gtasa_present", true));
                assertTrue("real libsamp.so must exist", p.getBoolean("samp_present", false));
                assertTrue("independent native probe must exist", p.getBoolean("probe_present", false));
                assertTrue("independent AArch64 JNI probe", p.getBoolean("arlprobe_ok", false));
                assertTrue("real libsamp JNI_OnLoad safe path", p.getBoolean("samp_load_ok", false));
            });
        }
    }
}
''')

    # Invariants: one launcher only, no GTASA binary, test source present.
    final_manifest = read(manifest)
    if final_manifest.count('android.intent.action.MAIN') != 1:
        die('manifest deve ter exatamente um MAIN launcher')
    if '.launcher.NativeProbeActivity' not in final_manifest:
        die('NativeProbeActivity ausente')
    if 'com.samp.mobile.game.SAMP' not in final_manifest:
        die('Activity SAMP real deve permanecer empacotada')
    if (root / 'app/src/main/jniLibs/arm64-v8a/libGTASA.so').exists():
        die('libGTASA.so proprietária presente')
    if not test.is_file():
        die('instrumentation test ausente')

    print('[ARL NATIVE SELFTEST] OK: NativeProbeActivity é o único launcher da variante de teste')
    print('[ARL NATIVE SELFTEST] OK: SAMP real preservado, mas não iniciado automaticamente')
    print('[ARL NATIVE SELFTEST] OK: relatório JSON + logcat + instrumentation test adicionados')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
