#!/usr/bin/env python3
from pathlib import Path
import argparse
import re


def die(msg):
    raise SystemExit('[ARL PHASE10 TEST] ERRO: ' + msg)


def read(p):
    return p.read_text(encoding='utf-8-sig')


def write(p, s):
    p.write_text(s, encoding='utf-8', newline='\n')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('repo', type=Path)
    root = ap.parse_args().repo.resolve()
    gradle = root / 'app/build.gradle'
    main = root / 'app/src/main/java/com/samp/mobile/launcher/MainActivity.java'
    splash = root / 'app/src/main/java/com/samp/mobile/launcher/SplashActivity.java'
    for p in (gradle, main, splash):
        if not p.is_file(): die('arquivo ausente: ' + str(p))

    # Java-only x86_64 APK for Android emulator. Production ARM sources remain unchanged.
    g = read(gradle)
    g2, n = re.subn(r"\n\s*ndk\s*\{\s*abiFilters\s+['\"][^\n]+\n\s*\}\s*", '\n', g, count=1, flags=re.S)
    if n != 1: die('ndk abiFilters inesperado')
    g = g2
    g2, n = re.subn(r"\n\s*externalNativeBuild\s*\{\s*cmake\s*\{\s*path\s+['\"]src/main/cpp/CMakeLists\.txt['\"]\s*\}\s*\}\s*", '\n', g, count=1, flags=re.S)
    if n != 1: die('externalNativeBuild inesperado')
    g = g2
    if "excludes += ['META-INF/*', '**/*.so']" not in g:
        if "excludes += ['META-INF/*']" not in g: die('packagingOptions inesperado')
        g = g.replace("excludes += ['META-INF/*']", "excludes += ['META-INF/*', '**/*.so']", 1)
    if '        debug {\n            debuggable false' in g:
        g = g.replace('        debug {\n            debuggable false', '        debug {\n            debuggable true', 1)
    elif '        debug {\n            debuggable true' not in g:
        die('buildType debug inesperado')
    write(gradle, g)

    # Keep all launcher/play validation but stop just before native GTA/SAMP startup.
    m = read(main)
    old = '        startActivity(new Intent(this, SAMP.class));'
    new = '''        getSharedPreferences("arl_phase10_emulator_harness", MODE_PRIVATE)\n                .edit()\n                .putBoolean("play_gate_ok", true)\n                .putString("nickname", nick)\n                .putString("endpoint", ArlRemoteConfig.host() + ":" + ArlRemoteConfig.port())\n                .commit();\n        Toast.makeText(this, "ARL PHASE 10 TEST • PLAY GATE OK", Toast.LENGTH_LONG).show();'''
    if new not in m:
        if m.count(old) != 1: die('startActivity SAMP marker inesperado')
        m = m.replace(old, new, 1)
    write(main, m)

    # Create only structural GTA sentinels as app UID. No Rockstar assets are supplied.
    s = read(splash)
    if 'import java.io.File;' not in s:
        marker = 'import java.util.ArrayList;'
        if marker not in s: die('imports SplashActivity inesperados')
        s = s.replace(marker, 'import java.io.File;\nimport java.util.ArrayList;', 1)
    call = '        ensurePhase10TestBase();\n        handler.post(this::bootstrap);'
    if call not in s:
        old_call = '        handler.post(this::bootstrap);'
        if s.count(old_call) != 1: die('bootstrap marker inesperado')
        s = s.replace(old_call, call, 1)
    method = '''\n    /** CI-only: structural fake base; contains zero proprietary GTA content. */\n    private void ensurePhase10TestBase() {\n        try {\n            File root = getExternalFilesDir(null);\n            if (root == null) return;\n            File texdb = new File(root, "texdb");\n            File data = new File(root, "data");\n            if ((!texdb.mkdirs() && !texdb.isDirectory()) ||\n                    (!data.mkdirs() && !data.isDirectory())) {\n                throw new IllegalStateException("falha criando base estrutural de teste");\n            }\n            File marker = new File(root, "ARL_PHASE10_TEST_BASE.txt");\n            if (!marker.exists()) marker.createNewFile();\n        } catch (Exception e) {\n            Toast.makeText(this, "ARL TEST BASE FALHOU: " + e.getMessage(), Toast.LENGTH_LONG).show();\n        }\n    }\n'''
    if 'private void ensurePhase10TestBase()' not in s:
        ins = '    private void setSplashStatus(String title, String detail) {'
        if ins not in s: die('setSplashStatus marker ausente')
        s = s.replace(ins, method + '\n' + ins, 1)
    write(splash, s)

    checks = [
        'externalNativeBuild' not in read(gradle),
        "'**/*.so'" in read(gradle),
        'putBoolean("play_gate_ok", true)' in read(main),
        'startActivity(new Intent(this, SAMP.class));' not in read(main),
        'ensurePhase10TestBase();' in read(splash),
        'ArlDataManager.verifyOrRepair(this, false' in read(splash),
    ]
    if not all(checks): die('auditoria pós-patch falhou')
    print('[ARL PHASE10 TEST] OK: Android emulator harness aplicado')
    print('[ARL PHASE10 TEST] OK: downloader/manifest/repair da Phase 10 permanecem reais')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
