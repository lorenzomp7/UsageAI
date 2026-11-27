"""Ogni servizio: lettura della risposta e chiamate HTTP, con risposte finte."""
import json
from datetime import datetime, timedelta, timezone

import pytest

from usageai.providers import BY_ID, ApiError, AuthError
from usageai.providers import claude, codex, copilot, cursor, gemini


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


# ---------------- Claude ----------------

CLAUDE_USAGE = {
    "five_hour": {"utilization": 40.0, "resets_at": "2026-11-27T14:00:00.512345+00:00"},
    "seven_day": {"utilization": 30.0, "resets_at": "2026-12-01T09:00:00Z"},
    "seven_day_opus": {"utilization": 5.0, "resets_at": None},
    "seven_day_oauth_apps": None,
    "extra_usage": {"is_enabled": False},
}


def test_claude_parse():
    ws = claude.parse(CLAUDE_USAGE)
    assert [w.key for w in ws] == ["claude:five_hour", "claude:seven_day", "claude:seven_day_opus"]
    assert ws[2].label == "Settimana Opus" and ws[2].resets_at is None


def test_claude_fetch_resolves_org_and_caches_it():
    http = Http(Resp(200, [{"uuid": "free", "capabilities": ["chat"]},
                           {"uuid": "max", "capabilities": ["chat", "claude_max"]}]),
                Resp(200, CLAUDE_USAGE))
    cache = {}
    ws = BY_ID["claude"].fetch("sk-ant-sid01-abc", http, cache)
    assert len(ws) == 3 and cache["org_id"] == "max"
    assert http.calls[1][0].endswith("/organizations/max/usage")
    assert http.calls[1][1]["Cookie"] == "sessionKey=sk-ant-sid01-abc"


def test_claude_errors():
    with pytest.raises(AuthError):
        BY_ID["claude"].fetch("x", Http(Resp(401, {"error": {"type": "authentication_error"}})), {})
    with pytest.raises(ApiError):   # Cloudflare: HTML, non è un problema di accesso
        BY_ID["claude"].fetch("x", Http(Resp(403, "<html>Just a moment</html>")), {})


# ---------------- ChatGPT / Codex ----------------

CODEX_USAGE = {
    "plan_type": "plus",
    "rate_limit": {
        "primary_window": {"used_percent": 42, "reset_at": 1764259200, "limit_window_seconds": 18000},
        "secondary_window": {"used_percent": 17, "reset_at": 1764700000, "limit_window_seconds": 604800},
    },
}


def test_codex_parse():
    ws = codex.parse(CODEX_USAGE)
    assert [(w.label, w.utilization) for w in ws] == [("Sessione 5 ore", 42), ("Settimana", 17)]
    assert ws[0].resets_at == datetime.fromtimestamp(1764259200, tz=timezone.utc)
    assert not ws[0].is_long and ws[1].is_long


def test_codex_fetch_with_cookie_gets_token_first():
    http = Http(Resp(200, {"accessToken": "tok123", "user": {}}), Resp(200, CODEX_USAGE))
    ws = BY_ID["codex"].fetch("__Secure-next-auth.session-token=abc; oai-did=1", http, {})
    assert len(ws) == 2
    assert http.calls[0][0] == codex.SESSION_URL
    assert http.calls[1][1]["Authorization"] == "Bearer tok123"


def test_codex_fetch_from_cli_login(tmp_path, monkeypatch):
    monkeypatch.setenv("CODEX_HOME", str(tmp_path))
    (tmp_path / "auth.json").write_text(json.dumps({"tokens": {"access_token": "cli-tok", "account_id": "acc-1"}}))
    p = BY_ID["codex"]
    assert p.auto_credential() == codex.AUTO
    http = Http(Resp(200, CODEX_USAGE))
    p.fetch(codex.AUTO, http, {})
    assert http.calls[0][1]["Authorization"] == "Bearer cli-tok"
    assert http.calls[0][1]["ChatGPT-Account-Id"] == "acc-1"


def test_codex_expired_session():
    with pytest.raises(AuthError):
        BY_ID["codex"].fetch("abc", Http(Resp(200, {})), {})


# ---------------- Gemini ----------------

GEMINI_BODY = ")]}'\n" + json.dumps([{"data": {"viewer": {"rpcs": {"getUsageInfo": {
    "subscriptionTier": "TIER1",
    "pools": [
        {"poolType": "GXU_FIVE_HOURLY", "percentageUsed": 0.25, "gxu": {"windowRefreshesAt": "2026-11-27T15:00:00Z"}},
        {"poolType": "GXU_WEEKLY", "percentageUsed": 0.6, "gxu": {"windowRefreshesAt": "2026-12-02T08:00:00Z"}},
    ]}}}}}])


def test_gemini_parse():
    ws = gemini.parse(GEMINI_BODY)
    assert [(w.label, round(w.utilization)) for w in ws] == [("Sessione 5 ore", 25), ("Settimana", 60)]
    assert ws[1].resets_at == datetime(2026, 12, 2, 8, tzinfo=timezone.utc)


def test_gemini_signed_out_and_changed():
    with pytest.raises(AuthError):
        gemini.parse(json.dumps([{"data": {"viewer": None}}]))
    with pytest.raises(AuthError):
        gemini.parse("<!doctype html>")
    with pytest.raises(ApiError):
        gemini.parse(json.dumps([{"data": {"somethingElse": 1}}]))


def test_gemini_fetch_sends_cookie_and_referer():
    http = Http(Resp(200, GEMINI_BODY))
    BY_ID["gemini"].fetch("SID=1; __Secure-1PSID=2", http, {})
    url, headers = http.calls[0]
    assert "query_signature=" in url and headers["Cookie"] == "SID=1; __Secure-1PSID=2"
    assert headers["Referer"] == "https://gemini.google.com/usage"


# ---------------- Cursor ----------------

CURSOR_SUMMARY = {
    "billingCycleStart": "2026-11-18T08:09:13.000Z",
    "billingCycleEnd": "2026-12-18T08:09:13.000Z",
    "membershipType": "pro",
    "isUnlimited": False,
    "individualUsage": {
        "plan": {"enabled": True, "used": 1516, "limit": 2000, "remaining": 484,
                 "autoPercentUsed": 3.0, "apiPercentUsed": 81.0},
        "onDemand": {"enabled": True, "used": 500, "limit": 2000, "remaining": 1500},
    },
    "teamUsage": {},
}


def test_cursor_parse():
    ws = {w.key: w for w in cursor.parse(CURSOR_SUMMARY)}
    assert ws["cursor:plan"].utilization == pytest.approx(75.8)
    assert ws["cursor:api"].utilization == 81 and ws["cursor:auto"].utilization == 3
    assert ws["cursor:ondemand"].utilization == 25 and "20 $" in ws["cursor:ondemand"].label
    assert ws["cursor:plan"].length == timedelta(days=30)
    assert ws["cursor:plan"].label == "Uso incluso nel mese"


def test_cursor_fetch_accepts_bare_token():
    http = Http(Resp(200, CURSOR_SUMMARY))
    BY_ID["cursor"].fetch("user_01%3A%3Aeyabc", http, {})
    assert http.calls[0][1]["Cookie"] == "WorkosCursorSessionToken=user_01%3A%3Aeyabc"


# ---------------- Copilot ----------------

COPILOT_USER = {
    "copilot_plan": "individual",
    "quota_reset_date": "2026-12-01",
    "quota_snapshots": {
        "chat": {"unlimited": True},
        "completions": {"unlimited": True},
        "premium_interactions": {"entitlement": 300, "remaining": 120, "percent_remaining": 40.0,
                                 "unlimited": False},
    },
}


def test_copilot_parse():
    (w,) = copilot.parse(COPILOT_USER)
    assert w.label == "Richieste premium" and w.utilization == 60
    assert w.resets_at == datetime(2026, 12, 1, tzinfo=timezone.utc)
    assert w.length == timedelta(days=30) and w.is_long


def test_copilot_derives_percent_and_errors():
    data = {"quota_snapshots": {"premium_interactions": {"entitlement": 50, "remaining": 10}}}
    assert copilot.parse(data)[0].utilization == 80
    with pytest.raises(ApiError):
        copilot.parse({"quota_snapshots": {"chat": {"unlimited": True}}})
    with pytest.raises(ApiError):
        BY_ID["copilot"].fetch("ghp_x", Http(Resp(404, {"message": "Not Found"})), {})
    http = Http(Resp(200, COPILOT_USER))
    BY_ID["copilot"].fetch(" gho_abc ", http, {})
    assert http.calls[0][1]["Authorization"] == "token gho_abc"
