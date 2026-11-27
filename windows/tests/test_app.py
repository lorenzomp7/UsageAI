"""Prova l'app senza barra delle notifiche vera: client e icona finti."""
from datetime import datetime, timedelta, timezone

from usageai import app as app_mod
from usageai import storage
from usageai.api import AuthError
from usageai.icon import render


class FakeTray:
    def __init__(self):
        self.notes, self.icon, self.title = [], None, ""

    def notify(self, text, title):
        self.notes.append((title, text))

    def update_menu(self):
        pass


def make_client(data=None, exc=None):
    class C:
        def __init__(self, key, org_id=None):
            self.org_id = org_id or "org-1"

        def usage(self):
            if exc:
                raise exc
            return data
    return C


def usage(five, week):
    now = datetime.now(timezone.utc)
    return {
        "five_hour": {"utilization": five, "resets_at": (now + timedelta(hours=1)).isoformat()},
        "seven_day": {"utilization": week, "resets_at": (now + timedelta(days=3)).isoformat()},
    }


def setup(tmp_path, monkeypatch, client):
    monkeypatch.setenv("APPDATA", str(tmp_path))
    monkeypatch.setattr(storage, "get_session_key", lambda: "sk-test")
    tray = FakeTray()
    a = app_mod.UsageApp(client_factory=client, tray=tray)
    return a, tray


def test_full_cycle_notifies_and_persists(tmp_path, monkeypatch):
    a, tray = setup(tmp_path, monkeypatch, make_client(usage(85, 80)))
    a.check_once()
    a.refresh_tray()
    titles = [t for t, _ in tray.notes]
    assert any("Sessione 5 ore: 85%" in t for t in titles)
    assert any("Settimana (tutti i modelli): 80% (soglia 75%)" in t for t in titles)
    assert "5h 85%" in tray.title and "Sett. 80%" in tray.title
    assert tray.icon.size == (64, 64)
    assert (tmp_path / "UsageAI" / "state.json").exists()
    # riavvio: lo stato salvato evita doppioni
    b, tray2 = setup(tmp_path, monkeypatch, make_client(usage(86, 81)))
    b.check_once()
    assert not [t for t, _ in tray2.notes if "soglia" in t]
    assert any("Ritmo" in line for line in b.menu_lines())


def test_auth_error_warns_once(tmp_path, monkeypatch):
    a, tray = setup(tmp_path, monkeypatch, make_client(exc=AuthError("scaduta")))
    a.check_once()
    a.check_once()
    assert len(tray.notes) == 1 and "sessione scaduta" in tray.notes[0][0]
    a.refresh_tray()
    assert "scaduta" in tray.title


def test_icon_renders_all_states():
    for p in (None, 5, 60, 99.7):
        img = render(p, p)
        assert img.mode == "RGBA"


def test_settings_roundtrip(tmp_path, monkeypatch):
    monkeypatch.setenv("APPDATA", str(tmp_path))
    s = storage.load_settings()
    s.thresholds_week = [70]
    s.interval_min = 10
    storage.save_settings(s)
    s2 = storage.load_settings()
    assert s2.thresholds_week == [70] and s2.interval_min == 10
