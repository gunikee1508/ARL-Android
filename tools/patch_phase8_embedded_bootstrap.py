#!/usr/bin/env python3
"""Wire the Phase 8 embedded GTA Brasil package into the generated launcher.

This patch is intentionally applied only by the experimental Phase 8 workflow.
The normal ARL overlay / production 1.0.1 path remains unchanged.
"""

from __future__ import annotations

import sys
from pathlib import Path


def replace_region(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"{label}: start marker not found")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f"{label}: end marker not found")
    return text[:start] + replacement.rstrip() + "\n\n    " + text[end:]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def patch_main(path: Path) -> None:
    text = path.read_text(encoding="utf-8")

    old_after_import = """                if (success && ArlDataManager.isConfigured() && ArlDataManager.requiresRepair(MainActivity.this)) {\n                    beginRepair(false);\n                }"""
    new_after_import = """                if (success && ArlEmbeddedDataManager.isAvailable(MainActivity.this)\n                        && ArlEmbeddedDataManager.requiresInstall(MainActivity.this)) {\n                    beginRepair(false);\n                } else if (success && ArlDataManager.isConfigured()\n                        && ArlDataManager.requiresRepair(MainActivity.this)) {\n                    beginRepair(false);\n                }"""
    text = replace_once(text, old_after_import, new_after_import, "manual-base auto install")

    data_methods = r'''private void refreshDataState() {
        if (dataBusy) return;

        if (!ArlBaseImportManager.isBaseReady(this)) {
            dataStatus.setText("GTA BRASIL AGUARDANDO BASE GTA SA");
            repairButton.setEnabled(false);
            updatePlayEnabled();
            return;
        }

        // Phase 8 bundled layer has priority over the legacy remote DATA path.
        if (ArlEmbeddedDataManager.isAvailable(this)) {
            repairButton.setEnabled(true);
            if (ArlEmbeddedDataManager.requiresInstall(this)) {
                dataStatus.setText("GTA BRASIL EMBUTIDO • INSTALAÇÃO NECESSÁRIA");
            } else {
                String installed = ArlEmbeddedDataManager.installedVersion(this);
                dataStatus.setText("GTA BRASIL PRONTO" +
                        (installed.isEmpty() ? "" : " • " + installed));
            }
            updatePlayEnabled();
            return;
        }

        if (!ArlRemoteConfig.hasDataRelease()) {
            dataStatus.setText("OVERLAY ARL LOCAL • PACOTE REMOTO AINDA NÃO PUBLICADO");
            repairButton.setEnabled(false);
            updatePlayEnabled();
            return;
        }
        if (!ArlRemoteConfig.dataManifestReady()) {
            dataStatus.setText("MANIFESTO DA DATA INDISPONÍVEL");
            repairButton.setEnabled(false);
            updatePlayEnabled();
            return;
        }
        if (!ArlDataManager.isConfigured()) {
            dataStatus.setText("CONFIGURAÇÃO DA DATA INVÁLIDA");
            repairButton.setEnabled(false);
            updatePlayEnabled();
            return;
        }

        repairButton.setEnabled(true);
        if (ArlDataManager.requiresRepair(this)) {
            String version = ArlRemoteConfig.dataVersion();
            dataStatus.setText("ATUALIZAÇÃO ARL NECESSÁRIA" +
                    (version.isEmpty() ? "" : " • " + version));
        } else {
            String installed = ArlDataManager.installedVersion(this);
            dataStatus.setText("ARQUIVOS ARL VERIFICADOS" +
                    (installed.isEmpty() ? "" : " • " + installed));
        }
        updatePlayEnabled();
    }

    private void beginRepair(boolean force) {
        if (dataBusy || baseBusy) return;
        if (!ArlBaseImportManager.isBaseReady(this)) {
            Toast.makeText(this, "Importe primeiro a base legítima do GTA SA.",
                    Toast.LENGTH_LONG).show();
            chooseGtaBaseSource();
            return;
        }

        if (ArlEmbeddedDataManager.isAvailable(this)) {
            dataBusy = true;
            repairButton.setEnabled(false);
            dataProgress.setVisibility(View.VISIBLE);
            dataProgress.setIndeterminate(false);
            dataProgress.setProgress(0);
            dataStatus.setText("PREPARANDO GTA BRASIL EMBUTIDO...");
            updatePlayEnabled();

            ArlEmbeddedDataManager.installIfNeeded(this, force,
                    new ArlEmbeddedDataManager.Listener() {
                @Override public void onState(String text) {
                    dataStatus.setText(text);
                }

                @Override public void onProgress(int percent, String text) {
                    dataProgress.setProgress(percent);
                    dataStatus.setText(text + " • " + percent + "%");
                }

                @Override public void onComplete(boolean success, String message) {
                    dataBusy = false;
                    dataProgress.setVisibility(View.GONE);
                    repairButton.setEnabled(true);
                    if (success) {
                        refreshDataState();
                    } else {
                        dataStatus.setText("FALHA NO GTA BRASIL • TOQUE EM REPARAR");
                    }
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    updatePlayEnabled();
                }
            });
            return;
        }

        if (ArlRemoteConfig.hasDataRelease() && !ArlRemoteConfig.dataManifestReady()) {
            Toast.makeText(this,
                    "Não foi possível carregar o manifesto da DATA. Verifique a internet e reabra o launcher.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!ArlDataManager.isConfigured()) {
            Toast.makeText(this, "O pacote ARL ainda não está configurado no launcher.json.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        dataBusy = true;
        repairButton.setEnabled(false);
        dataProgress.setVisibility(View.VISIBLE);
        dataProgress.setIndeterminate(false);
        dataProgress.setProgress(0);
        dataStatus.setText("PREPARANDO VERIFICAÇÃO...");
        updatePlayEnabled();

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
                dataProgress.setVisibility(View.GONE);
                if (success) {
                    refreshDataState();
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                } else {
                    repairButton.setEnabled(ArlRemoteConfig.dataManifestReady());
                    dataStatus.setText("FALHA NA DATA • TOQUE EM REPARAR");
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
                updatePlayEnabled();
            }
        });
    }

    private boolean dataBlocksPlay() {
        if (!ArlBaseImportManager.isBaseReady(this)) return true;
        if (ArlEmbeddedDataManager.isAvailable(this))
            return ArlEmbeddedDataManager.requiresInstall(this);
        if (!ArlRemoteConfig.hasDataRelease()) return false;
        if (!ArlRemoteConfig.dataManifestReady()) return true;
        if (!ArlDataManager.isConfigured()) return true;
        return ArlDataManager.requiresRepair(this);
    }'''

    text = replace_region(
        text,
        "private void refreshDataState() {",
        "private void updatePlayEnabled() {",
        data_methods,
        "DATA methods",
    )

    play_needle = """        if (ArlRemoteConfig.hasDataRelease() && !ArlRemoteConfig.dataManifestReady()) {"""
    play_insert = """        if (ArlEmbeddedDataManager.isAvailable(this)\n                && ArlEmbeddedDataManager.requiresInstall(this)) {\n            Toast.makeText(this, \"O GTA Brasil embutido precisa ser instalado antes de jogar.\",\n                    Toast.LENGTH_LONG).show();\n            beginRepair(false);\n            return;\n        }\n        if (ArlRemoteConfig.hasDataRelease() && !ArlRemoteConfig.dataManifestReady()) {"""

    # The marker appears in beginRepair too, but that method has already been replaced and still
    # contains it. Patch specifically inside play().
    play_start = text.find("private void play() {")
    if play_start < 0:
        raise SystemExit("play: method not found")
    prefix, play_part = text[:play_start], text[play_start:]
    play_part = replace_once(play_part, play_needle, play_insert, "play embedded gate")
    text = prefix + play_part

    path.write_text(text, encoding="utf-8")


def patch_splash(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    method = r'''private void prepareManagedData() {
        if (finished || isFinishing() || isDestroyed()) return;

        // Phase 8: install the bundled, cryptographically verified GTA Brasil layer
        // immediately after the legitimate GTA SA base is ready. No GTA Brasil ZIP
        // selection/download is required from the user.
        if (ArlEmbeddedDataManager.isAvailable(this)) {
            ArlEmbeddedDataManager.installIfNeeded(this, false,
                    new ArlEmbeddedDataManager.Listener() {
                @Override public void onState(String text) {}
                @Override public void onProgress(int percent, String text) {}
                @Override public void onComplete(boolean success, String message) {
                    if (!success)
                        Toast.makeText(SplashActivity.this, message, Toast.LENGTH_LONG).show();
                    openLauncher();
                }
            });
            return;
        }

        // Legacy/production path remains available when no embedded Phase 8 package exists.
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
    }'''
    text = replace_region(
        text,
        "private void prepareManagedData() {",
        "private ArlBaseImportManager.Listener quietBaseListener() {",
        method,
        "Splash embedded bootstrap",
    )
    path.write_text(text, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_phase8_embedded_bootstrap.py <SAMP-Mobile-root>")
    root = Path(sys.argv[1]).resolve()
    java = root / "app/src/main/java/com/samp/mobile/launcher"
    patch_main(java / "MainActivity.java")
    patch_splash(java / "SplashActivity.java")
    print("Phase 8 embedded bootstrap patches: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
