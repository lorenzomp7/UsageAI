"""Impostazioni, stato delle notifiche e credenziali dei servizi.

- impostazioni e stato: JSON in %APPDATA%\\UsageAI
- credenziali: Gestione credenziali di Windows (tramite keyring), mai in chiaro su disco
"""
from __future__ import annotations

import json
import os
import sys
from dataclasses import asdict
from pathlib import Path

from .core import Settings, WindowState

APP_NAME = "UsageAI"
KEYRING_SERVICE = "UsageAI"
_LEGACY_CLAUDE_USER = "claude.ai sessionKey"   # versione 1, solo Claude


def data_dir() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home() / ".config")
    d = Path(base) / APP_NAME
    d.mkdir(parents=True, exist_ok=True)
    return d


def _read_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def _write_json(path: Path, data) -> None:
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


def _int_list(v):
    return sorted(set(v)) if isinstance(v, list) and all(isinstance(x, int) for x in v) else None


def load_settings() -> Settings:
    raw = _read_json(data_dir() / "config.json", {})
    s = Settings()
    # thresholds_5h / thresholds_week: nomi della versione 1
    short = _int_list(raw.get("thresholds_short", raw.get("thresholds_5h")))
    long = _int_list(raw.get("thresholds_long", raw.get("thresholds_week")))
    if short is not None:
        s.thresholds_short = short
    if long is not None:
        s.thresholds_long = long
    if isinstance(raw.get("pace_enabled"), bool):
        s.pace_enabled = raw["pace_enabled"]
    if isinstance(raw.get("daily_enabled"), bool):
        s.daily_enabled = raw["daily_enabled"]
    for name, lo, hi in (("pace_margin", 1, 50), ("interval_min", 1, 60)):
        v = raw.get(name)
        if isinstance(v, int) and not isinstance(v, bool) and lo <= v <= hi:
            setattr(s, name, v)
    return s


def save_settings(s: Settings) -> None:
    _write_json(data_dir() / "config.json", asdict(s))


def load_state() -> tuple[dict[str, WindowState], dict[str, dict]]:
    """Stato delle soglie per finestra e cache per servizio (es. organizzazione di Claude)."""
    raw = _read_json(data_dir() / "state.json", {})
    windows = {}
    for key, v in (raw.get("windows") or {}).items():
        if not isinstance(v, dict):
            continue
        if ":" not in key:          # versione 1: chiavi di Claude senza prefisso
            key = f"claude:{key}"
        def opt_float(x):
            return float(x) if isinstance(x, (int, float)) and not isinstance(x, bool) else None
        windows[key] = WindowState(
            resets_at=v.get("resets_at"),
            notified=[int(x) for x in v.get("notified", []) if isinstance(x, int)],
            pace_notified=bool(v.get("pace_notified", False)),
            day=v.get("day") if isinstance(v.get("day"), str) else None,
            day_start=opt_float(v.get("day_start")),
            day_notified=bool(v.get("day_notified", False)),
            last_util=opt_float(v.get("last_util")),
        )
    caches = raw.get("caches") if isinstance(raw.get("caches"), dict) else {}
    if raw.get("org_id") and "claude" not in caches:
        caches["claude"] = {"org_id": raw["org_id"]}
    return windows, caches


def save_state(windows: dict[str, WindowState], caches: dict[str, dict]) -> None:
    _write_json(data_dir() / "state.json",
                {"windows": {k: asdict(v) for k, v in windows.items()}, "caches": caches})


def get_credential(provider_id: str) -> str | None:
    try:
        import keyring
        value = keyring.get_password(KEYRING_SERVICE, f"cred:{provider_id}")
        if value is None and provider_id == "claude":
            value = keyring.get_password(KEYRING_SERVICE, _LEGACY_CLAUDE_USER)
        return value
    except Exception:
        return None


def set_credential(provider_id: str, value: str | None) -> None:
    import keyring
    if value:
        keyring.set_password(KEYRING_SERVICE, f"cred:{provider_id}", value.strip())
        return
    for user in (f"cred:{provider_id}",) + ((_LEGACY_CLAUDE_USER,) if provider_id == "claude" else ()):
        try:
            keyring.delete_password(KEYRING_SERVICE, user)
        except Exception:
            pass


# --- Avvio automatico con Windows (registro HKCU\...\Run) ---

_RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def _launch_command() -> str:
    if getattr(sys, "frozen", False):            # .exe creato con PyInstaller
        return f'"{sys.executable}"'
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    exe = pythonw if pythonw.exists() else Path(sys.executable)
    main = Path(__file__).resolve().parent.parent / "main.py"
    return f'"{exe}" "{main}"'


def autostart_enabled() -> bool:
    if sys.platform != "win32":
        return False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN_KEY) as k:
            winreg.QueryValueEx(k, APP_NAME)
            return True
    except OSError:
        return False


def set_autostart(enabled: bool) -> None:
    if sys.platform != "win32":
        return
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if enabled:
            winreg.SetValueEx(k, APP_NAME, 0, winreg.REG_SZ, _launch_command())
        else:
            try:
                winreg.DeleteValue(k, APP_NAME)
            except OSError:
                pass
