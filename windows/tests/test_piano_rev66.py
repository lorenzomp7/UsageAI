"""Modifiche UsageAI 34, 50, 52, 56, 65 e salvataggio automatico delle risposte (UsageAI 1)."""
import json
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from usageai.app import UsageApp
from usageai.core import Settings, evaluate
from usageai.providers import BY_ID, base, claude, copilot, perplexity
from usageai.registra import Registratore
from usageai.sanifica import forma

ROOT = Path(__file__).resolve().parents[2]


def fx(path):
    return json.loads((ROOT / "fixtures" / path).read_text(encoding="utf-8"))


NOW = datetime(2026, 9, 9, 12, 0, tzinfo=timezone.utc)


# ---------------- Claude (34, 56, 65) ----------------

def test_claude_limits_are_primary_and_name_fable():
    ws = {w.key: w for w in claude.parse(fx("claude/usage.ClaudeUsageParserTest_kt.json"), NOW)}
    assert list(ws) == ["claude:five_hour", "claude:seven_day", "claude:seven_day_fable"]
    assert ws["claude:five_hour"].utilization == 21 and ws["claude:five_hour"].length == timedelta(hours=5)
    assert ws["claude:seven_day_fable"].label == "Settimana Fable"
    assert ws["claude:seven_day_fable"].length == timedelta(days=7)
    assert "claude:extra_usage" not in ws          # spesa extra spenta: nessuna riga


def test_claude_flat_keys_fill_what_limits_lack():
    ws = {w.key: w for w in claude.parse(fx("claude/usage-flat.ClaudeUsageParserTest_kt.json"), NOW)}
    assert set(ws) == {"claude:five_hour", "claude:seven_day", "claude:seven_day_opus", "claude:seven_day_fable"}
    assert ws["claude:seven_day_fable"].utilization == 5      # iguana_necktie = Fable
    data = {"limits": [{"kind": "session", "group": "session", "percent": 10}],
            "seven_day": {"utilization": 90.5, "resets_at": "2026-09-14T00:00:00Z"}}
    ws = {w.key: w for w in claude.parse(data, NOW)}
    assert ws["claude:seven_day"].utilization == 90.5 and ws["claude:five_hour"].utilization == 10
    # un percent illeggibile in limits lascia il posto alla chiave piatta
    data = {"iguana_necktie": {"utilization": 5.0}, "limits": [
        {"kind": "weekly_scoped", "percent": "unavailable", "scope": {"model": {"display_name": "Fable"}}}]}
    assert [(w.key, w.utilization) for w in claude.parse(data, NOW)] == [("claude:seven_day_fable", 5.0)]


def test_claude_extra_usage_and_resets():
    ws = {w.key: w for w in claude.parse(fx("claude/usage-extra-reset.codexbar_docs.json"), NOW)}
    extra = ws["claude:extra_usage"]
    assert extra.utilization == 25 and "12,50 su 50,00 EUR" in extra.label
    assert extra.resets_at == datetime(2026, 10, 1, tzinfo=timezone.utc) and extra.is_long
    resets = ws["claude:resets"]
    assert resets.is_info and resets.note.startswith("3 (il primo scade il ")   # 2 + 1; esclusi pausa, scaduti, futuri
    _, state = evaluate(list(ws.values()), Settings(), {}, NOW)
    assert "claude:resets" not in state and "claude:extra_usage" in state


def test_claude_resets_ignored_when_not_eligible_or_odd():
    base_ = {"five_hour": {"utilization": 1.0}}
    for block in ({"eligible": False, "grants": [{"resets_left": 1, "paused": False}]},
                  {"grants": [{"resets_left": 1, "paused": False}]},
                  {"eligible": True, "grants": [{"resets_left": "many", "paused": False}]},
                  {"eligible": True, "grants": [{"resets_left": 1}]},
                  {"eligible": True, "grants": [{"resets_left": 1, "paused": False, "ends_at": "next tuesday"}]},
                  {"eligible": True, "grants": [{"resets_left": 60, "paused": False}]}):
        assert not any(w.key == "claude:resets" for w in claude.parse(dict(base_, cedar_ember=block), NOW)), block


def test_claude_pace_only_when_recognisable():
    data = {"seven_day": {"utilization": 40.0, "resets_at": "2026-09-14T00:00:00Z",
                          "pace": {"projected_utilization": 92}},
            "five_hour": {"utilization": 30.0, "pace": {"runs_out_at": "2026-09-09T15:00:00Z"}}}
    ws = {w.key: w for w in claude.parse(data, NOW)}
    assert ws["claude:seven_day:pace"].note == "circa 92% al reset"
    assert ws["claude:five_hour:pace"].note.startswith("finisce verso ")
    data = {"seven_day": {"utilization": 40.0, "pace": {"strange": [1, 2]}}}
    assert [w.key for w in claude.parse(data, NOW)] == ["claude:seven_day"]


class Resp:
    def __init__(self, status, body):
        self.status_code = status
        self.text = body if isinstance(body, str) else json.dumps(body)


class Http:
    def __init__(self, *responses):
        self.responses, self.calls = list(responses), []

    def get(self, url, headers=None, timeout=None):
        self.calls.append(url)
        return self.responses.pop(0)


def test_claude_retries_without_cedar_ember():
    http = Http(Resp(403, "{}"), Resp(200, {"five_hour": {"utilization": 3.0}}))
    cache = {"org_id": "org"}
    ws = BY_ID["claude"].fetch("sk-ant-sid01-x", http, cache)
    assert http.calls[0].endswith("?cedar_ember=1") and http.calls[1].endswith("/usage")
    assert cache["no_cedar_ember"] and ws[0].utilization == 3


# ---------------- Copilot (50) ----------------

def test_copilot_ai_credits_and_placeholders():
    ws = {w.key: w for w in copilot.parse(fx("copilot/user-credits.CopilotUsageModelsTests_swift.json"))}
    assert set(ws) == {"copilot:credits_used"} and ws["copilot:credits_used"].note == "31 usati"
    ws = {w.key: w for w in copilot.parse(fx("copilot/user-monthly-quotas.CopilotUsageModelsTests_swift.json"))}
    assert ws["copilot:monthly"].utilization == 75     # 75 rimaste su 300 → 75% usato
    assert "copilot:premium_interactions" not in ws    # quota finta a zero, non un consumo


def test_copilot_included_credits_and_overage():
    data = {"token_based_billing": True, "quota_reset_date": "2026-10-01",
            "quota_snapshots": {"premium_interactions": {"entitlement": 300, "remaining": -20,
                                                         "percent_remaining": 0, "credits_used": 320}}}
    ws = {w.key: w for w in copilot.parse(data)}
    assert ws["copilot:premium_interactions"].label == "Crediti AI del piano"
    assert ws["copilot:premium_interactions"].utilization == 100
    assert ws["copilot:credits_used"].note == "320 usati su 300 compresi"
    assert ws["copilot:overage"].note.startswith("20 crediti oltre i compresi")
    # vecchio formato: invariato
    old = {"quota_snapshots": {"premium_interactions": {"percent_remaining": 60}}}
    assert [(w.key, w.label) for w in copilot.parse(old)] == [("copilot:premium_interactions", "Richieste premium")]


# ---------------- Perplexity (52) ----------------

def test_perplexity_expiring_credits():
    data = {"total_usage_cents": 0, "credit_grants": [
        {"type": "recurring", "amount_cents": 5000},
        {"type": "promotional", "amount_cents": 1000, "expires_at_ts": 1790000000},
        {"type": "promotional", "amount_cents": 250, "expires_at_ts": 1800000000},
        {"type": "promotional", "amount_cents": 999, "expires_at_ts": 1000}]}
    w = {w.key: w for w in perplexity.parse(data, NOW)}["perplexity:expiring"]
    assert w.is_info and w.note.startswith("10,00 $ scadono il ")


def test_perplexity_quota_is_prudent():
    ws = perplexity.parse_quota({"remaining": 120, "limit": 300, "reset_at": "2026-10-01T00:00:00Z"}, NOW)
    assert [(w.key, w.utilization) for w in ws] == [("perplexity:quota", 60)]
    ws = perplexity.parse_quota({"pro_search": {"used": 30, "total": 300}}, NOW)
    assert ws[0].key == "perplexity:quota:pro_search" and ws[0].utilization == 10
    assert perplexity.parse_quota({"something": "else", "count": 3}, NOW) == []
    assert perplexity.parse_quota([1, 2], NOW) == []


def test_perplexity_quota_endpoint_missing_is_remembered():
    pplx = {"total_usage_cents": 100, "credit_grants": [{"type": "recurring", "amount_cents": 1000}]}
    http = Http(Resp(200, pplx), Resp(404, "{}"))
    cache = {}
    BY_ID["perplexity"].fetch("a=b", http, cache)
    assert cache["no_quota"] and http.calls[1] == perplexity.QUOTA_URL
    http = Http(Resp(200, pplx))
    BY_ID["perplexity"].fetch("a=b", http, cache)
    assert len(http.calls) == 1


# ---------------- Salvataggio automatico (UsageAI 1) ----------------

URL = "https://claude.ai/api/organizations/0f3a8c1e-1111-4222-8333-944455556666/usage?cedar_ember=1"


def test_recorder_saves_first_and_new_fields_only(tmp_path):
    day = [date(2026, 11, 27)]
    rec = Registratore(tmp_path, oggi=lambda: day[0])
    body = {"five_hour": {"utilization": 3.0}, "account": {"email": "mario@gmail.com"}}
    first = rec(URL, json.dumps(body))
    assert first and first.parent.name == "claude" and "gmail" not in first.read_text()
    assert rec(URL, json.dumps(body)) is None                       # niente di nuovo
    body["seven_day"] = {"utilization": 1.0, "pace": {"projected": 4}}
    day[0] = date(2026, 11, 28)
    assert rec(URL, json.dumps(body)) is not None                    # campi nuovi, altro giorno
    assert rec("https://example.com/x", "{}") is None                 # indirizzo non di consumo
    again = Registratore(tmp_path)                                    # l'indice resta tra un avvio e l'altro
    assert "claude/usage" in again.conosciuti and "seven_day.pace.projected" in again.conosciuti["claude/usage"]


def test_recorder_one_file_per_day(tmp_path):
    rec = Registratore(tmp_path, oggi=lambda: date(2026, 11, 27))
    assert rec(URL, '{"a": 1}')
    assert rec(URL, '{"a": 1, "b": 2}') is None


def test_shape_groups_ids():
    assert forma({"orgs": {"0f3a8c1e-1111-4222-8333-944455556666": {"x": 1}}}) == {"orgs", "orgs.<id>", "orgs.<id>.x"}


def test_app_records_through_requests(tmp_path, monkeypatch):
    monkeypatch.setenv("APPDATA", str(tmp_path))
    app = UsageApp(providers=[], http=None)
    rec = app.enable_recording(tmp_path / "reg")
    try:
        http = Http(Resp(200, {"five_hour": {"utilization": 3.0}}))
        base.request(http, URL, {})
        assert len(rec.file_salvati()) == 1
        app.settings.record_responses = False
        http = Http(Resp(200, {"five_hour": {"utilization": 3.0}, "nuovo": 1}))
        base.request(http, URL.replace("cedar_ember=1", "x=1"), {})
        assert len(rec.file_salvati()) == 1
    finally:
        base.OSSERVATORI.clear()
