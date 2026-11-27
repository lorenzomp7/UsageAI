"""Prova l'app senza barra delle notifiche vera: servizi e icona finti."""
from datetime import datetime, timedelta, timezone

from usageai import app as app_mod
from usageai import storage
from usageai.core import Window
from usageai.icon import render
from usageai.providers import ApiError, AuthError, Provider


class FakeTray:
    def __init__(self):
        self.notes, self.icon, self.title = [], None, ""

    def notify(self, text, title):
        self.notes.append((title, text))

    def update_menu(self):
        pass


class FakeProvider(Provider):
    def __init__(self, pid, name, result=None, exc=None, auto=None):
        self.id, self.name, self.result, self.exc, self.auto = pid, name, result, exc, auto

    def auto_credential(self):
        return self.auto

    def fetch(self, credential, http, cache):
        if self.exc:
            raise self.exc
        return self.result


def w(pid, label, util, hours):
    now = datetime.now(timezone.utc)
    length = timedelta(hours=hours)
    return Window(pid, f"{pid}:{label}", label, util, now + length / 2, length)


def setup(tmp_path, monkeypatch, providers, creds):
    monkeypatch.setenv("APPDATA", str(tmp_path))
    monkeypatch.setattr(storage, "get_credential", lambda pid: creds.get(pid))
    tray = FakeTray()
    return app_mod.UsageApp(providers=providers, http=object(), tray=tray), tray


def test_multiple_providers_notify_and_persist(tmp_path, monkeypatch):
    providers = [
        FakeProvider("claude", "Claude", [w("claude", "Sessione 5 ore", 85, 5), w("claude", "Settimana", 80, 168)]),
        FakeProvider("cursor", "Cursor", [w("cursor", "Uso incluso nel mese", 40, 720)]),
        FakeProvider("gemini", "Gemini", [w("gemini", "Settimana", 99, 168)]),   # non configurato
    ]
    a, tray = setup(tmp_path, monkeypatch, providers, {"claude": "k", "cursor": "c"})
    a.check_once()
    a.refresh_tray()
    titles = [t for t, _ in tray.notes]
    assert any("Claude · Sessione 5 ore: 85%" in t for t in titles)
    assert any("Claude · Settimana: 80% (soglia 75%)" in t for t in titles)
    assert not any("Gemini" in t for t in titles)
    assert tray.title == "UsageAI · Claude 85% · Cursor 40%"
    assert a.headline() == (80, 85)
    assert any(line == "Cursor" for line in a.menu_lines())
    # riavvio: lo stato salvato evita doppioni
    b, tray2 = setup(tmp_path, monkeypatch, providers, {"claude": "k", "cursor": "c"})
    b.check_once()
    assert not [t for t, _ in tray2.notes if "soglia" in t]


def test_one_failing_provider_does_not_block_others(tmp_path, monkeypatch):
    providers = [
        FakeProvider("claude", "Claude", exc=AuthError("scaduta")),
        FakeProvider("codex", "ChatGPT/Codex", exc=ApiError("bloccata")),
        FakeProvider("copilot", "Copilot", [w("copilot", "Richieste premium", 91, 720)]),
    ]
    a, tray = setup(tmp_path, monkeypatch, providers, {"claude": "k", "codex": "c", "copilot": "t"})
    a.check_once()
    a.check_once()
    titles = [t for t, _ in tray.notes]
    assert titles.count("UsageAI: accesso a Claude scaduto") == 1
    assert any("Copilot · Richieste premium: 91%" in t for t in titles)
    a.refresh_tray()
    assert "Claude ⚠" in tray.title and "Copilot 91%" in tray.title
    assert "   ⚠ bloccata" in a.menu_lines()


def test_auto_credential_counts_as_configured(tmp_path, monkeypatch):
    providers = [FakeProvider("codex", "ChatGPT/Codex", [w("codex", "Settimana", 10, 168)], auto="auto:codex-cli")]
    a, _ = setup(tmp_path, monkeypatch, providers, {})
    a.check_once()
    assert a.general_error is None and a.windows["codex"]


def test_no_provider_configured(tmp_path, monkeypatch):
    a, tray = setup(tmp_path, monkeypatch, [FakeProvider("claude", "Claude", [])], {})
    a.check_once()
    a.refresh_tray()
    assert "Nessun servizio configurato" in tray.title


def test_icon_renders_all_states():
    for p in (None, 5, 60, 99.7):
        assert render(p, p).mode == "RGBA"


def test_settings_roundtrip(tmp_path, monkeypatch):
    monkeypatch.setenv("APPDATA", str(tmp_path))
    s = storage.load_settings()
    s.thresholds_long = [70]
    s.interval_min = 10
    storage.save_settings(s)
    s2 = storage.load_settings()
    assert s2.thresholds_long == [70] and s2.interval_min == 10
