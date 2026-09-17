#!/usr/bin/env python3
from pathlib import Path
import argparse


def die(msg):
    raise SystemExit('[ARL PHASE10 GATE DIAG] ERRO: ' + msg)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('repo', type=Path)
    root = ap.parse_args().repo.resolve()
    p = root / 'app/src/main/java/com/samp/mobile/launcher/MainActivity.java'
    if not p.is_file():
        die('MainActivity.java ausente')
    s = p.read_text(encoding='utf-8-sig')

    cases = [
        (
            '        if (baseBusy || dataBusy || appBusy) {\n            Toast.makeText(this, "Aguarde a operação em andamento.", Toast.LENGTH_LONG).show();',
            '        if (baseBusy || dataBusy || appBusy) {\n            getSharedPreferences("arl_phase10_emulator_harness", MODE_PRIVATE).edit().putString("stage", "blocked_busy").commit();\n            Toast.makeText(this, "Aguarde a operação em andamento.", Toast.LENGTH_LONG).show();'
        ),
        (
            '        if (ArlRemoteConfig.maintenance()) {\n            Toast.makeText(this, "Servidor em manutenção.", Toast.LENGTH_LONG).show();',
            '        if (ArlRemoteConfig.maintenance()) {\n            getSharedPreferences("arl_phase10_emulator_harness", MODE_PRIVATE).edit().putString("stage", "blocked_maintenance").commit();\n            Toast.makeText(this, "Servidor em manutenção.", Toast.LENGTH_LONG).show();'
        ),
        (
            '        if (ArlAppUpdateManager.updateRequired(this)) {\n            Toast.makeText(this, "Atualize o launcher antes de jogar.", Toast.LENGTH_LONG).show();',
            '        if (ArlAppUpdateManager.updateRequired(this)) {\n            getSharedPreferences("arl_phase10_emulator_harness", MODE_PRIVATE).edit().putString("stage", "blocked_app_update").commit();\n            Toast.makeText(this, "Atualize o launcher antes de jogar.", Toast.LENGTH_LONG).show();'
        ),
        (
            '        if (!ArlBaseImportManager.isBaseReady(this)) {\n            Toast.makeText(this, "Importe a base legítima do GTA SA antes de jogar.", Toast.LENGTH_LONG).show();',
            '        if (!ArlBaseImportManager.isBaseReady(this)) {\n            getSharedPreferences("arl_phase10_emulator_harness", MODE_PRIVATE).edit().putString("stage", "blocked_base").commit();\n            Toast.makeText(this, "Importe a base legítima do GTA SA antes de jogar.", Toast.LENGTH_LONG).show();'
        ),
        (
            '        if (ArlRemoteConfig.hasDataRelease() && !ArlRemoteConfig.dataManifestReady()) {\n            Toast.makeText(this, "Não foi possível verificar a integridade dos arquivos ARL.", Toast.LENGTH_LONG).show();',
            '        if (ArlRemoteConfig.hasDataRelease() && !ArlRemoteConfig.dataManifestReady()) {\n            getSharedPreferences("arl_phase10_emulator_harness", MODE_PRIVATE).edit().putString("stage", "blocked_manifest").commit();\n            Toast.makeText(this, "Não foi possível verificar a integridade dos arquivos ARL.", Toast.LENGTH_LONG).show();'
        ),
        (
            '        if (ArlDataManager.isConfigured() && ArlDataManager.requiresRepair(this)) {\n            Toast.makeText(this, "Os arquivos do ARL precisam ser atualizados antes de jogar.", Toast.LENGTH_LONG).show();',
            '        if (ArlDataManager.isConfigured() && ArlDataManager.requiresRepair(this)) {\n            getSharedPreferences("arl_phase10_emulator_harness", MODE_PRIVATE).edit().putString("stage", "blocked_repair").commit();\n            Toast.makeText(this, "Os arquivos do ARL precisam ser atualizados antes de jogar.", Toast.LENGTH_LONG).show();'
        ),
    ]

    for old, new in cases:
        if new in s:
            continue
        if s.count(old) != 1:
            die('gate marker inesperado: ' + old.splitlines()[0].strip())
        s = s.replace(old, new, 1)

    snapshot_old = '''        getSharedPreferences("arl_phase10_emulator_harness", MODE_PRIVATE)\n                .edit()\n                .putBoolean("play_entered", true)\n                .putString("stage", "entered")\n                .commit();\n'''
    snapshot_new = '''        getSharedPreferences("arl_phase10_emulator_harness", MODE_PRIVATE)\n                .edit()\n                .putBoolean("play_entered", true)\n                .putString("stage", "entered")\n                .putBoolean("snap_base_busy", baseBusy)\n                .putBoolean("snap_data_busy", dataBusy)\n                .putBoolean("snap_app_busy", appBusy)\n                .putBoolean("snap_maintenance", ArlRemoteConfig.maintenance())\n                .putBoolean("snap_update_required", ArlAppUpdateManager.updateRequired(this))\n                .putBoolean("snap_base_ready", ArlBaseImportManager.isBaseReady(this))\n                .putBoolean("snap_has_data_release", ArlRemoteConfig.hasDataRelease())\n                .putBoolean("snap_manifest_ready", ArlRemoteConfig.dataManifestReady())\n                .putBoolean("snap_data_configured", ArlDataManager.isConfigured())\n                .putBoolean("snap_requires_repair", ArlDataManager.isConfigured() && ArlDataManager.requiresRepair(this))\n                .commit();\n'''
    if snapshot_new not in s:
        if s.count(snapshot_old) != 1:
            die('snapshot marker ausente')
        s = s.replace(snapshot_old, snapshot_new, 1)

    p.write_text(s, encoding='utf-8', newline='\n')
    print('[ARL PHASE10 GATE DIAG] OK: snapshot + stages por gate aplicados')


if __name__ == '__main__':
    raise SystemExit(main())
