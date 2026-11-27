"""Integrazioni del Piano di battaglia (UsageAI 1-4): fixture, Codex, Kimi, Grok."""
import io
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from usageai.core import Settings, evaluate
from usageai.providers import BY_ID, ApiError, AuthError
from usageai.providers import codex, grok, kimi

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "fixtures"
sys.path.insert(0, str(ROOT / "tools"))
import controlla_fixture  # noqa: E402
import sanifica_registrazione  # noqa: E402


def fixture(path: str):
    return json.loads((FIXTURES / path).read_text(encoding="utf-8"))


class Resp:
    def __init__(self, status, body):
        self.status_code = status
        self.text = body if isinstance(body, str) else json.dumps(body)


class Http:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.calls = []

    def get(self, url, headers=None, timeout=None):
        self.calls.append((url, headers or {}))
        return self.responses.pop(0)


# ---------------- UsageAI 1: fixture pulite ----------------

def test_fixtures_have_no_secrets():
    assert controlla_fixture.controlla(FIXTURES) == []


def test_secret_check_finds_planted_secrets(tmp_path):
    d = tmp_path / "fixtures"
    d.mkdir()
    (d / "x.json").write_text('{"a": "sk-ant-sid01-AAAAAAAAAAAAAAAA", "e": "mario.rossi@gmail.com"}')
    (d / "ok.json").write_text('{"e": "utente@example.com", "n": 42}')
    problemi = controlla_fixture.controlla(d)
    assert len(problemi) == 2 and all("x.json" in p for p in problemi)


def test_every_recorded_fixture_is_readable_by_its_parser():
    """Le risposte registrate con Proxelar (fixtures/<servizio>/registrate) devono leggersi."""
    parsers = {"codex": codex.parse, "kimi": kimi.parse, "grok": grok.parse}
    for f in sorted(FIXTURES.glob("*/registrate/*.json")):
        service = f.parts[-3]
        if service in parsers and not f.name.endswith(".grpc.json") and "reset-credits" not in f.name:
            assert parsers[service](json.loads(f.read_text(encoding="utf-8"))), f


def _har(*entries):
    return {"log": {"entries": [
        {"request": {"url": url}, "response": {"status": status, "content": {"mimeType": mime, "text": text}}}
        for url, status, mime, text in entries]}}


def test_sanitizer_keeps_numbers_and_removes_secrets():
    body = json.dumps({
        "five_hour": {"utilization": 41.0, "resets_at": "2026-11-27T14:00:00Z"},
        "account": {"email_address": "mario.rossi@gmail.com", "uuid": "0f3a8c1e-1111-4222-8333-944455556666",
                    "display_name": "Mario"},
        "access_token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc",
        "tokens_used": 1200,
        "note": "scrivi a mario.rossi@gmail.com",
    })
    out = sanifica_registrazione.estrai(_har(
        ("https://claude.ai/api/organizations/0f3a8c1e-1111-4222-8333-944455556666/usage", 200,
         "application/json", body),
        ("https://claude.ai/api/bootstrap", 200, "application/json", "{}"),           # non interessa
        ("https://api.github.com/copilot_internal/user", 401, "application/json", "{}"),  # errore: scartata
    ))
    assert len(out) == 1
    path, text = out[0]
    assert path.parts[-3:-1] == ("claude", "registrate") and path.name.endswith("-usage.json")
    data = json.loads(text)
    assert data["five_hour"]["utilization"] == 41.0 and data["tokens_used"] == 1200
    assert data["access_token"] == "<rimosso>" and data["account"]["email_address"] == "<rimosso>"
    assert data["account"]["uuid"].startswith("id-") and "gmail" not in text
    assert data["account"]["display_name"] == "Mario"   # etichetta tecnica, la lascio
    assert controlla_fixture.CONTROLLI and not any(rx.search(text) for _, rx in controlla_fixture.CONTROLLI)


def test_sanitizer_saves_grpc_web_as_base64():
    import base64
    raw = bytes([0, 0, 0, 0, 5, 8, 150, 1, 16, 7])
    har = {"log": {"entries": [{"request": {"url": "https://grok.com/grok_api.RateLimits/GetRateLimits"},
                                "response": {"status": 200, "content": {
                                    "mimeType": "application/grpc-web+proto", "encoding": "base64",
                                    "text": base64.b64encode(raw).decode()}}}]}}
    (path, text), = sanifica_registrazione.estrai(har)
    assert path.name.endswith("GetRateLimits.grpc.json")
    assert base64.b64decode(json.loads(text)["base64"]) == raw


# ---------------- UsageAI 2: Codex ----------------

def test_codex_parse_full_payload_from_codex_cli_test():
    ws = codex.parse(fixture("codex/wham-usage.rate_limits_rs.json"))
    by_key = {w.key: w for w in ws}
    assert by_key["codex:primary_window"].utilization == 42
    assert by_key["codex:primary_window"].length == timedelta(hours=1)
    assert by_key["codex:secondary_window"].length == timedelta(days=1)
    extra = by_key["codex:extra:codex_other:primary"]
    assert extra.utilization == 88 and extra.label.startswith("Codex other · ")
    assert extra.length == timedelta(minutes=30)
    spend = by_key["codex:spend"]
    assert spend.utilization == 32 and "(8000 su 25000)" in spend.label and spend.resets_at
    assert by_key["codex:resets"].note == "3"
    assert "codex:reached" not in by_key   # c'è il tipo, ma nessun limite è raggiunto


def test_codex_reached_reason_and_credits():
    data = fixture("codex/wham-usage.rate_limits_rs.json")
    data["rate_limit"]["limit_reached"] = True
    data["credits"] = {"has_credits": True, "unlimited": False, "balance": "125.5"}
    by_key = {w.key: w for w in codex.parse(data)}
    assert by_key["codex:reached"].note == codex.REACHED_REASONS["workspace_member_usage_limit_reached"]
    assert by_key["codex:credits"].note == "125,5 rimasti"


def test_codex_old_payload_still_works():
    ws = codex.parse({"rate_limit": {"primary_window": {"used_percent": 10, "limit_window_seconds": 18000,
                                                        "reset_at": 1767225600}}})
    assert [w.key for w in ws] == ["codex:primary_window"] and ws[0].label == "Sessione 5 ore"


def test_codex_app_server_matches_web_keys():
    web = {w.key: w for w in codex.parse(fixture("codex/wham-usage.rate_limits_rs.json"))}
    app = {w.key: w for w in codex.parse_app_server(fixture("codex/app-server-rateLimits-read.rate_limits_rs.json"))}
    for key in ("codex:primary_window", "codex:secondary_window", "codex:extra:codex_other:primary", "codex:spend"):
        assert app[key].utilization == web[key].utilization, key
        assert app[key].resets_at == web[key].resets_at, key
    assert app["codex:resets"].note.startswith("2 (il primo scade il ")


def test_info_rows_never_alert():
    ws = codex.parse(fixture("codex/wham-usage.rate_limits_rs.json"))
    alerts, state = evaluate(ws, Settings(thresholds_short=[1], thresholds_long=[1]), {},
                             datetime(2024, 12, 31, 23, 0, tzinfo=timezone.utc))
    assert not any(a.key in ("codex:resets", "codex:credits", "codex:reached") for a in alerts)
    assert "codex:resets" not in state


class FakeProc:
    """Finto «codex app-server»: risponde alle richieste JSON-RPC come nel test di Codex."""

    def __init__(self, result, error=None):
        self.result, self.error = result, error
        self.sent = []
        self._out = []
        self.stdout = self
        self.stdin = self
        self.terminated = False

    # stdin
    def write(self, text):
        msg = json.loads(text)
        self.sent.append(msg)
        if msg.get("method") == "initialize":
            self._out.append(json.dumps({"id": msg["id"], "result": {"userAgent": "codex"}}) + "\n")
        elif msg.get("method") == "account/rateLimits/read":
            self._out.append(json.dumps({"method": "account/rateLimits/updated", "params": {}}) + "\n")
            reply = {"id": msg["id"], "error": self.error} if self.error else {"id": msg["id"], "result": self.result}
            self._out.append(json.dumps(reply) + "\n")

    def flush(self):
        pass

    def close(self):
        pass

    # stdout
    def __iter__(self):
        import time
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if self._out:
                yield self._out.pop(0)
            else:
                time.sleep(0.005)

    def terminate(self):
        self.terminated = True

    def wait(self, timeout=None):
        return 0


def test_app_server_handshake_and_read():
    result = fixture("codex/app-server-rateLimits-read.rate_limits_rs.json")
    proc = FakeProc(result)
    got = codex.read_with_app_server("codex", timeout=5, popen=lambda *a, **k: proc)
    assert got == result
    assert [m.get("method") for m in proc.sent] == ["initialize", "initialized", "account/rateLimits/read"]
    assert proc.sent[0]["params"]["clientInfo"]["name"] == "usageai" and proc.terminated


def test_app_server_auth_error():
    proc = FakeProc(None, error={"code": -32000, "message": "not logged in: run codex login"})
    with pytest.raises(AuthError):
        codex.read_with_app_server("codex", timeout=5, popen=lambda *a, **k: proc)


def test_codex_provider_prefers_app_server_and_falls_back(tmp_path, monkeypatch):
    monkeypatch.setenv("CODEX_HOME", str(tmp_path))
    result = fixture("codex/app-server-rateLimits-read.rate_limits_rs.json")
    p = codex.CodexProvider(find_exe=lambda: "codex", app_server=lambda exe: result)
    assert p.auto_credential() == codex.AUTO
    cache = {}
    ws = p.fetch(codex.AUTO, Http(), cache)
    assert cache["source"] == "app-server" and any(w.key == "codex:spend" for w in ws)

    # app-server che non risponde + auth.json presente → si usa il web
    (tmp_path / "auth.json").write_text(json.dumps({"tokens": {"access_token": "tok", "account_id": "acc"}}))

    def broken(exe):
        raise ApiError("non parte")
    p = codex.CodexProvider(find_exe=lambda: "codex", app_server=broken)
    http = Http(Resp(200, fixture("codex/wham-usage.rate_limits_rs.json")))
    cache = {}
    p.fetch(codex.AUTO, http, cache)
    assert cache["source"] == "web" and http.calls[0][1]["ChatGPT-Account-Id"] == "acc"


# ---------------- UsageAI 3: Kimi Code ----------------

NOW = datetime(2026, 11, 27, 12, 0, tzinfo=timezone.utc)


def test_kimi_parse_fixture():
    ws = kimi.parse(fixture("kimi/usages.managed-usage_test_ts.json"), now=NOW)
    by_key = {w.key: w for w in ws}
    week = by_key["kimi:weekly"]
    assert week.label == "Settimana" and week.utilization == 40 and week.length == timedelta(days=7)
    assert week.resets_at == datetime(2026, 12, 1, 9, 0, 0, 123456, tzinfo=timezone.utc)
    five = by_key["kimi:limit:18000s"]
    assert five.label == "Sessione 5 ore" and five.utilization == 60
    day = by_key["kimi:limit:86400s"]
    assert day.utilization == 80 and day.resets_at == NOW + timedelta(hours=2)   # remaining + resetIn
    assert by_key["kimi:extra_month"].utilization == 25
    assert by_key["kimi:booster"].note == "100,00 USD rimasti su 200,00 USD"


@pytest.mark.parametrize("variant", [
    {"usage": {"used": 1, "limit": 10, "reset_at": "2026-12-01T00:00:00Z"}},
    {"usage": {"remaining": 9, "limit": 10, "resetTime": "2026-12-01T00:00:00Z"}},
    {"usage": {"used": "1", "limit": "10", "resetAt": "2026-12-01T00:00:00Z"}},
])
def test_kimi_field_variants(variant):
    w, = kimi.parse(variant, now=NOW)
    assert w.utilization == 10 and w.resets_at == datetime(2026, 12, 1, tzinfo=timezone.utc)


def test_kimi_limit_labels_and_empty():
    ws = kimi.parse({"limits": [
        {"name": "Daily cap", "detail": {"used": 5, "limit": 100}},
        {"detail": {"used": 1, "limit": 4}, "window": {"duration": 2, "timeUnit": "DAY"}},
    ]}, now=NOW)
    assert [w.label for w in ws] == ["Daily cap", "Finestra 2 giorni"]
    with pytest.raises(ApiError):
        kimi.parse({"usage": {"name": "Weekly limit"}}, now=NOW)


def test_kimi_fetch_with_api_key_and_cli_login(tmp_path, monkeypatch):
    http = Http(Resp(200, fixture("kimi/usages.managed-usage_test_ts.json")))
    BY_ID["kimi"].fetch("sk-kimi-xyz", http, {})
    assert http.calls[0][0] == kimi.URL and http.calls[0][1]["Authorization"] == "Bearer sk-kimi-xyz"

    monkeypatch.setenv("KIMI_CODE_HOME", str(tmp_path))
    assert BY_ID["kimi"].auto_credential() is None
    creds = tmp_path / "credentials"
    creds.mkdir()
    (creds / "kimi-code.json").write_text(json.dumps({"access_token": "cli-tok", "expires_at": 4102444800}))
    assert BY_ID["kimi"].auto_credential() == kimi.AUTO
    http = Http(Resp(200, {"usage": {"used": 1, "limit": 2}}))
    BY_ID["kimi"].fetch(kimi.AUTO, http, {})
    assert http.calls[0][1]["Authorization"] == "Bearer cli-tok"

    (creds / "kimi-code.json").write_text(json.dumps({"access_token": "old", "expires_at": 1000}))
    with pytest.raises(AuthError):
        BY_ID["kimi"].fetch(kimi.AUTO, Http(), {})
    with pytest.raises(AuthError):
        BY_ID["kimi"].fetch("bad", Http(Resp(401, {"message": "unauthorized"})), {})


# ---------------- UsageAI 4: Grok (formato dei crediti confermato) ----------------

def test_grok_percent_from_monthly_credits():
    w, = grok.parse({"config": {"monthlyLimit": {"val": 2000}, "used": {"val": 500},
                                "currentPeriod": {"start": "2026-11-01T00:00:00Z", "end": "2026-12-01T00:00:00Z"}}})
    assert w.utilization == 25 and w.label == "Crediti · Mese"
