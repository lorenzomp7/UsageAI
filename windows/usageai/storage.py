"""Impostazioni, stato delle notifiche e sessionKey.

- impostazioni e stato: JSON in %APPDATA%\\UsageAI
- sessionKey: Gestione credenziali di Windows (tramite keyring), mai in chiaro su disco
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
KEYRING_USER = "claude.ai sessionKey"


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


def load_settings() -> Settings:
    raw = _read_json(data_dir() / "config.json", {})
    s = Settings()
    for name in ("thresholds_5h", "thresholds_week"):
        v = raw.get(name)
        if isinstance(v, list) and all(isinstance(x, int) for x in v):
            setattr(s, name, sorted(set(v)))
    if isinstance(raw.get("pace_enabled"), bool):
        s.pace_enabled = raw["pace_enabled"]
    for name, lo, hi in (("pace_margin", 1, 50), ("interval_min", 1, 60)):
        v = raw.get(name)
        if isinstance(v, int) and lo <= v <= hi:
            setattr(s, name, v)
    return s


def save_settings(s: Settings) -> None:
    _write_json(data_dir() / "config.json", asdict(s))


def load_state() -> tuple[dict[str, WindowState], str | None]:
    raw = _read_json(data_dir() / "state.json", {})
    windows = {}
    for key, v in (raw.get("windows") or {}).items():
        if isinstance(v, dict):
            windows[key] = WindowState(
                resets_at=v.get("resets_at"),
                notified=[int(x) for x in v.get("notified", []) if isinstance(x, int)],
                pace_notified=bool(v.get("pace_notified", False)),
            )
    return windows, raw.get("org_id")


def save_state(windows: dict[str, WindowState], org_id: str | None) -> None:
    _write_json(data_dir() / "state.json",
                {"windows": {k: asdict(v) for k, v in windows.items()}, "org_id": org_id})


def get_session_key() -> str | None:
    try:
        import keyring
        return keyring.get_password(KEYRING_SERVICE, KEYRING_USER)
    except Exception:
        return None


def set_session_key(value: str | None) -> None:
    import keyring
    if value:
        keyring.set_password(KEYRING_SERVICE, KEYRING_USER, value.strip())
    else:
        try:
            keyring.delete_password(KEYRING_SERVICE, KEYRING_USER)
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
