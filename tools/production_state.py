#!/usr/bin/env python3
"""ARL Android production channel state manager.

Keeps launcher.json small while production-state.json records immutable release
metadata, the active DATA/APK pointers and the APK signing certificate identity.
No private signing material is ever stored here.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

HEX64 = re.compile(r"^[0-9a-f]{64}$")


def die(message: str) -> None:
    raise SystemExit("[ARL PROD] ERRO: " + message)


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        die(f"falha lendo {path}: {exc}")
    if not isinstance(value, dict):
        die(f"{path} deve conter um objeto JSON")
    return value


def save_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def is_https(value: str) -> bool:
    try:
        u = urlparse(str(value).strip())
        return u.scheme == "https" and bool(u.netloc)
    except Exception:
        return False


def norm_sha(value: str, label: str) -> str:
    v = str(value or "").strip().lower().replace(":", "")
    if not HEX64.fullmatch(v):
        die(f"{label} deve ser SHA-256 hexadecimal de 64 caracteres")
    return v


def ensure_state_shape(state: dict) -> None:
    if state.get("schema") != 1:
        die("production-state.json com schema não suportado")
    state.setdefault("signingCertSha256", "")
    state.setdefault("active", {"app": None, "data": None})
    state.setdefault("history", {"app": [], "data": []})
    if not isinstance(state["active"], dict) or not isinstance(state["history"], dict):
        die("production-state.json inválido")
    state["active"].setdefault("app", None)
    state["active"].setdefault("data", None)
    state["history"].setdefault("app", [])
    state["history"].setdefault("data", [])
    if not isinstance(state["history"]["app"], list) or not isinstance(state["history"]["data"], list):
        die("histórico de produção inválido")


def core_equal(a: dict, b: dict, keys: list[str]) -> bool:
    return all(a.get(k) == b.get(k) for k in keys)


def append_immutable(history: list[dict], entry: dict, identity_key: str, compare_keys: list[str]) -> None:
    identity = entry[identity_key]
    for old in history:
        if old.get(identity_key) != identity:
            continue
        if not core_equal(old, entry, compare_keys):
            die(f"release {identity_key}={identity} já registrada com metadados diferentes")
        return
    history.append(entry)


def data_from_launcher(launcher: dict, tag: str) -> dict:
    try:
        d = launcher["client"]["data"]
    except Exception:
        die("launcher.json sem client.data")
    version = str(d.get("version", "")).strip()
    url = str(d.get("url", "")).strip()
    sha = norm_sha(d.get("sha256", ""), "DATA sha256")
    manifest = str(d.get("manifestUrl", "")).strip()
    try:
        size = int(d.get("bytes", 0))
    except Exception:
        size = 0
    if not version:
        die("DATA version vazia")
    if not is_https(url) or not is_https(manifest):
        die("DATA url/manifestUrl precisam ser HTTPS")
    if size <= 0:
        die("DATA bytes precisa ser > 0")
    return {
        "version": version,
        "tag": tag,
        "url": url,
        "sha256": sha,
        "bytes": size,
        "manifestUrl": manifest,
        "recordedAt": now_iso(),
    }


def app_from_launcher(launcher: dict, tag: str, cert_sha: str) -> dict:
    a = launcher.get("app")
    if not isinstance(a, dict):
        die("launcher.json sem app")
    try:
        code = int(a.get("latestVersionCode", 0))
        minimum = int(a.get("minVersionCode", 0))
        size = int(a.get("bytes", 0))
    except Exception:
        die("campos numéricos do app inválidos")
    name = str(a.get("versionName", "")).strip()
    url = str(a.get("apkUrl", "")).strip()
    sha = norm_sha(a.get("sha256", ""), "APK sha256")
    cert = norm_sha(cert_sha, "certificado do APK")
    mandatory = bool(a.get("mandatory", False))
    if code <= 0 or minimum <= 0 or minimum > code:
        die("versionCode/minVersionCode inválidos")
    if not name or not is_https(url) or size <= 0:
        die("metadados do APK incompletos")
    return {
        "versionCode": code,
        "versionName": name,
        "tag": tag,
        "url": url,
        "sha256": sha,
        "bytes": size,
        "minVersionCode": minimum,
        "mandatory": mandatory,
        "signingCertSha256": cert,
        "recordedAt": now_iso(),
    }


def apply_data_to_launcher(launcher: dict, entry: dict) -> None:
    launcher.setdefault("client", {}).setdefault("data", {})
    launcher["client"]["data"] = {
        "version": entry["version"],
        "url": entry["url"],
        "sha256": entry["sha256"],
        "bytes": entry["bytes"],
        "manifestUrl": entry["manifestUrl"],
    }


def cmd_capture_data(args: argparse.Namespace) -> None:
    sp, lp = Path(args.state), Path(args.launcher)
    state, launcher = load_json(sp), load_json(lp)
    ensure_state_shape(state)
    entry = data_from_launcher(launcher, args.tag)
    append_immutable(
        state["history"]["data"], entry, "version",
        ["version", "tag", "url", "sha256", "bytes", "manifestUrl"],
    )
    state["active"]["data"] = entry["version"]
    save_json(sp, state)
    print(f"[ARL PROD] DATA ativa registrada: {entry['version']}")


def cmd_capture_app(args: argparse.Namespace) -> None:
    sp, lp = Path(args.state), Path(args.launcher)
    state, launcher = load_json(sp), load_json(lp)
    ensure_state_shape(state)
    entry = app_from_launcher(launcher, args.tag, args.cert_sha256)
    current_cert = str(state.get("signingCertSha256", "")).strip().lower()
    if current_cert:
        current_cert = norm_sha(current_cert, "signingCertSha256 salvo")
        if current_cert != entry["signingCertSha256"]:
            die("certificado de assinatura mudou; release bloqueada para preservar a cadeia de updates")
    else:
        state["signingCertSha256"] = entry["signingCertSha256"]

    append_immutable(
        state["history"]["app"], entry, "versionCode",
        ["versionCode", "versionName", "tag", "url", "sha256", "bytes", "signingCertSha256"],
    )
    state["active"]["app"] = entry["versionCode"]
    save_json(sp, state)
    print(f"[ARL PROD] APK ativo registrado: {entry['versionName']} ({entry['versionCode']})")


def cmd_promote_data(args: argparse.Namespace) -> None:
    sp, lp = Path(args.state), Path(args.launcher)
    state, launcher = load_json(sp), load_json(lp)
    ensure_state_shape(state)
    target = next((x for x in state["history"]["data"] if str(x.get("version")) == args.version), None)
    if target is None:
        die(f"DATA {args.version} não existe no histórico de produção")
    apply_data_to_launcher(launcher, target)
    state["active"]["data"] = target["version"]
    save_json(lp, launcher)
    save_json(sp, state)
    print(f"[ARL PROD] DATA promovida/rollback: {target['version']}")


def cmd_maintenance(args: argparse.Namespace) -> None:
    lp = Path(args.launcher)
    launcher = load_json(lp)
    launcher["maintenance"] = args.value == "on"
    save_json(lp, launcher)
    print(f"[ARL PROD] maintenance={launcher['maintenance']}")


def cmd_app_policy(args: argparse.Namespace) -> None:
    lp = Path(args.launcher)
    launcher = load_json(lp)
    a = launcher.get("app")
    if not isinstance(a, dict):
        die("launcher.json sem app")
    latest = int(a.get("latestVersionCode", 0))
    minimum = int(args.min_version_code)
    if latest <= 0 or minimum <= 0 or minimum > latest:
        die("minVersionCode precisa estar entre 1 e latestVersionCode")
    a["minVersionCode"] = minimum
    a["mandatory"] = args.mandatory == "true"
    save_json(lp, launcher)
    print(f"[ARL PROD] política APK: minVersionCode={minimum} mandatory={a['mandatory']}")


def validate_entry_urls_and_hashes(state: dict) -> None:
    seen_data = set()
    for d in state["history"]["data"]:
        v = str(d.get("version", ""))
        if not v or v in seen_data:
            die("histórico DATA contém versão vazia/duplicada")
        seen_data.add(v)
        if not is_https(d.get("url", "")) or not is_https(d.get("manifestUrl", "")):
            die(f"DATA {v} contém URL não HTTPS")
        norm_sha(d.get("sha256", ""), f"DATA {v} sha256")
        if int(d.get("bytes", 0)) <= 0:
            die(f"DATA {v} com tamanho inválido")

    seen_app = set()
    for a in state["history"]["app"]:
        code = int(a.get("versionCode", 0))
        if code <= 0 or code in seen_app:
            die("histórico APK contém versionCode inválido/duplicado")
        seen_app.add(code)
        if not is_https(a.get("url", "")):
            die(f"APK {code} contém URL não HTTPS")
        norm_sha(a.get("sha256", ""), f"APK {code} sha256")
        norm_sha(a.get("signingCertSha256", ""), f"APK {code} certificado")
        if int(a.get("bytes", 0)) <= 0:
            die(f"APK {code} com tamanho inválido")


def cmd_validate(args: argparse.Namespace) -> None:
    state, launcher = load_json(Path(args.state)), load_json(Path(args.launcher))
    ensure_state_shape(state)
    validate_entry_urls_and_hashes(state)

    cert = str(state.get("signingCertSha256", "")).strip()
    if cert:
        norm_sha(cert, "signingCertSha256")
        for a in state["history"]["app"]:
            if a.get("signingCertSha256") != cert.lower():
                die("histórico APK contém certificado diferente do certificado de produção")

    active_data = state["active"].get("data")
    if active_data is not None:
        d = next((x for x in state["history"]["data"] if x.get("version") == active_data), None)
        if d is None:
            die("active.data aponta para versão inexistente")
        current = launcher.get("client", {}).get("data", {})
        expected = {k: d[k] for k in ["version", "url", "sha256", "bytes", "manifestUrl"]}
        if any(current.get(k) != v for k, v in expected.items()):
            die("launcher.json não corresponde à DATA ativa em production-state.json")

    active_app = state["active"].get("app")
    if active_app is not None:
        a = next((x for x in state["history"]["app"] if x.get("versionCode") == active_app), None)
        if a is None:
            die("active.app aponta para versionCode inexistente")
        current = launcher.get("app", {})
        mapping = {
            "latestVersionCode": a["versionCode"],
            "versionName": a["versionName"],
            "apkUrl": a["url"],
            "sha256": a["sha256"],
            "bytes": a["bytes"],
        }
        if any(current.get(k) != v for k, v in mapping.items()):
            die("launcher.json não corresponde ao APK ativo em production-state.json")

    server = launcher.get("server", {})
    if not str(server.get("host", "")).strip() or not (1 <= int(server.get("port", 0)) <= 65535):
        die("endpoint do servidor inválido")

    print("[ARL PROD] production-state.json + launcher.json: OK")


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Manage ARL Android production channel state")
    sub = p.add_subparsers(dest="command", required=True)

    def common(s):
        s.add_argument("--state", default="production-state.json")
        s.add_argument("--launcher", default="launcher.json")

    s = sub.add_parser("capture-data")
    common(s); s.add_argument("--tag", required=True); s.set_defaults(func=cmd_capture_data)

    s = sub.add_parser("capture-app")
    common(s); s.add_argument("--tag", required=True); s.add_argument("--cert-sha256", required=True); s.set_defaults(func=cmd_capture_app)

    s = sub.add_parser("promote-data")
    common(s); s.add_argument("--version", required=True); s.set_defaults(func=cmd_promote_data)

    s = sub.add_parser("maintenance")
    s.add_argument("--launcher", default="launcher.json"); s.add_argument("--value", choices=["on", "off"], required=True); s.set_defaults(func=cmd_maintenance)

    s = sub.add_parser("app-policy")
    s.add_argument("--launcher", default="launcher.json"); s.add_argument("--min-version-code", required=True, type=int); s.add_argument("--mandatory", choices=["true", "false"], required=True); s.set_defaults(func=cmd_app_policy)

    s = sub.add_parser("validate")
    common(s); s.set_defaults(func=cmd_validate)
    return p


def main() -> None:
    args = parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
