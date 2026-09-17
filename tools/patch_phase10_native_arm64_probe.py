#!/usr/bin/env python3
from pathlib import Path
import argparse
import re


def die(msg):
    raise SystemExit('[ARL ARM64 PROBE] ERRO: ' + msg)


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
    cmake = root / 'app/src/main/cpp/CMakeLists.txt'
    manifest = root / 'app/src/main/AndroidManifest.xml'
    for p in (gradle, cmake, manifest):
        if not p.is_file():
            die('arquivo ausente: ' + str(p))

    # Test variant: ARM64 only, but preserve the real SAMP native build.
    g = read(gradle)
    g2, n = re.subn(
        r"abiFilters\s+['\"]armeabi-v7a['\"]\s*,\s*['\"]arm64-v8a['\"]",
        "abiFilters 'arm64-v8a'",
        g,
        count=1,
    )
    if n != 1 and "abiFilters 'arm64-v8a'" not in g:
        die('abiFilters inesperado')
    g = g2 if n == 1 else g
    write(gradle, g)

    # Never package/use the proprietary Rockstar GTASA binary in this test.
    for rel in (
        'app/src/main/jniLibs/arm64-v8a/libGTASA.so',
        'app/src/main/jniLibs/armeabi-v7a/libGTASA.so',
    ):
        p = root / rel
        if p.exists():
            p.unlink()

    # Independent JNI library proves that actual Android/aarch64 native code runs.
    probe_cpp = root / 'app/src/main/cpp/arl_native_probe.cpp'
    write(probe_cpp, r'''#include <jni.h>
#include <cstdio>
#include <cstdint>

extern "C" JNIEXPORT jstring JNICALL
Java_com_samp_mobile_launcher_NativeProbeActivity_nativeProbe(JNIEnv* env, jclass) {
#if defined(__aarch64__)
    const char* abi = "aarch64";
#elif defined(__arm__)
    const char* abi = "arm";
#elif defined(__x86_64__)
    const char* abi = "x86_64";
#else
    const char* abi = "unknown";
#endif
    char out[160];
    std::snprintf(out, sizeof(out),
                  "native_abi=%s;pointer_bits=%zu;arm64=%s",
                  abi, sizeof(void*) * 8u,
#if defined(__aarch64__)
                  "true"
#else
                  "false"
#endif
    );
    return env->NewStringUTF(out);
}
''')

    c = read(cmake)
    block = '''\n# ARL Phase 10 CI-only native architecture probe.\nadd_library(arlprobe SHARED arl_native_probe.cpp)\ntarget_link_libraries(arlprobe log)\n'''
    if 'add_library(arlprobe SHARED arl_native_probe.cpp)' not in c:
        c += block
    write(cmake, c)

    activity = root / 'app/src/main/java/com/samp/mobile/launcher/NativeProbeActivity.java'
    write(activity, r'''package com.samp.mobile.launcher;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.widget.TextView;

import java.io.File;

public class NativeProbeActivity extends Activity {
    private static native String nativeProbe();

    private void put(String key, Object value) {
        android.content.SharedPreferences.Editor e = getSharedPreferences("arl_phase10_native_probe", MODE_PRIVATE).edit();
        if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
        else e.putString(key, String.valueOf(value));
        e.commit();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        StringBuilder report = new StringBuilder();
        boolean probeOk = false;
        boolean sampOk = false;
        String probeResult = "";
        String sampError = "";

        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        File gtasa = new File(nativeDir, "libGTASA.so");
        File samp = new File(nativeDir, "libsamp.so");
        File probe = new File(nativeDir, "libarlprobe.so");

        put("process64", Process.is64Bit());
        put("supported_abis", java.util.Arrays.toString(Build.SUPPORTED_ABIS));
        put("gtasa_present", gtasa.isFile());
        put("samp_present", samp.isFile());
        put("probe_present", probe.isFile());

        try {
            System.loadLibrary("arlprobe");
            probeResult = nativeProbe();
            probeOk = probeResult.contains("native_abi=aarch64") && probeResult.contains("pointer_bits=64") && Process.is64Bit();
        } catch (Throwable t) {
            probeResult = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        }
        put("arlprobe_ok", probeOk);
        put("arlprobe_result", probeResult);

        // libsamp.so is the real Phase 10 native client. Its JNI_OnLoad is expected
        // to detect that libGTASA.so is absent and return safely before hooks.
        try {
            System.loadLibrary("samp");
            sampOk = true;
        } catch (Throwable t) {
            sampError = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        }
        put("samp_load_ok", sampOk);
        put("samp_load_error", sampError);

        report.append("ARL PHASE 10 NATIVE ARM64 PROBE\n")
              .append("process64=").append(Process.is64Bit()).append('\n')
              .append("abis=").append(java.util.Arrays.toString(Build.SUPPORTED_ABIS)).append('\n')
              .append("libGTASA.present=").append(gtasa.isFile()).append('\n')
              .append("libsamp.present=").append(samp.isFile()).append('\n')
              .append("libarlprobe.present=").append(probe.isFile()).append('\n')
              .append("arlprobe=").append(probeResult).append('\n')
              .append("libsamp.load=").append(sampOk ? "OK" : "FAIL: " + sampError).append('\n');
        TextView view = new TextView(this);
        view.setTextSize(16f);
        view.setPadding(32, 32, 32, 32);
        view.setText(report.toString());
        setContentView(view);
    }
}
''')

    m = read(manifest)
    activity_xml = '''\n        <activity\n            android:name=".launcher.NativeProbeActivity"\n            android:screenOrientation="portrait"\n            android:exported="true"\n            android:theme="@style/Theme.AppCompat.NoActionBar" />\n'''
    if '.launcher.NativeProbeActivity' not in m:
        marker = '</application>'
        if marker not in m:
            die('fim de application não encontrado')
        m = m.replace(marker, activity_xml + marker, 1)
    write(manifest, m)

    # Final safety/invariant checks.
    if "abiFilters 'arm64-v8a'" not in read(gradle):
        die('ARM64-only não aplicado')
    if (root / 'app/src/main/jniLibs/arm64-v8a/libGTASA.so').exists():
        die('libGTASA.so ainda presente')
    if 'add_library(arlprobe SHARED arl_native_probe.cpp)' not in read(cmake):
        die('arlprobe não ligado ao CMake')
    if '.launcher.NativeProbeActivity' not in read(manifest):
        die('NativeProbeActivity não registrada')

    print('[ARL ARM64 PROBE] OK: variante ARM64 real preparada')
    print('[ARL ARM64 PROBE] OK: libGTASA.so proprietária ausente da variante de teste')
    print('[ARL ARM64 PROBE] OK: libsamp real preservada + arlprobe independente adicionado')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
