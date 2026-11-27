"""Perplexity, Grok, Mistral, OpenRouter: lettura delle risposte e chiamate, con risposte finte."""
import json
from datetime import datetime, timedelta, timezone

import pytest

from usageai.providers import BY_ID, ApiError, AuthError
from usageai.providers import grok, mistral, openrouter, perplexity

from test_providers import Http, Resp

NOW = datetime(2026, 11, 27, 12, 0, tzinfo=timezone.utc)


# ---------------- Perplexity ----------------

PPLX = {
    "balance_cents": 1500,
    "renewal_date_ts": 1767225600,
    "current_period_purchased_cents": 0,
    "total_usage_cents": 3500,
    "credit_grants": [
        {"type": "recurring", "amount_cents": 5000},
        {"type": "promotional", "amount_cents": 1000, "expires_at_ts": 1893456000},
        {"type": "promotional", "amount_cents": 9999, "expires_at_ts": 1000},       # scaduto
    ],
}


def test_perplexity_parse_order_of_consumption():
    ws = {w.key: w for w in perplexity.parse(PPLX, NOW)}
    assert ws["perplexity:recurring"].utilization == 70
    assert ws["perplexity:recurring"].resets_at == datetime.fromtimestamp(1767225600, tz=timezone.utc)
    assert ws["perplexity:promo"].utilization == 0
    pplx = dict(PPLX, total_usage_cents=5500)
    ws = {w.key: w for w in perplexity.parse(pplx, NOW)}
    assert ws["perplexity:recurring"].utilization == 100 and ws["perplexity:promo"].utilization == 50


def test_perplexity_tries_cookie_names_and_remembers_the_right_one():
    http = Http(Resp(401, "{}"), Resp(200, PPLX))
    cache = {}
    BY_ID["perplexity"].fetch("eyTOKEN", http, cache)
    assert http.calls[0][1]["Cookie"] == "__Secure-authjs.session-token=eyTOKEN"
    assert http.calls[1][1]["Cookie"] == "authjs.session-token=eyTOKEN"
    assert cache["cookie_name"] == "authjs.session-token"
    http = Http(Resp(200, PPLX))
    BY_ID["perplexity"].fetch("eyTOKEN", http, cache)
    assert http.calls[0][1]["Cookie"] == "authjs.session-token=eyTOKEN"
    with pytest.raises(AuthError):
        BY_ID["perplexity"].fetch("a=b", Http(Resp(403, "{}")), {})


# ---------------- Grok ----------------

GROK = {"config": {
    "creditUsagePercent": 37.5,
    "currentPeriod": {"start": "2026-11-23T00:00:00Z", "end": "2026-11-30T00:00:00Z"},
    "prepaidBalance": {"val": 0},
}}


def test_grok_parse():
    (w,) = grok.parse(GROK)
    assert w.utilization == 37.5 and w.label == "Crediti · Settimana"
    assert w.length == timedelta(days=7) and w.resets_at == datetime(2026, 11, 30, tzinfo=timezone.utc)
    alt = {"config": {"onDemandCap": {"val": 2000}, "onDemandUsed": {"val": 500},
                      "billingPeriodStart": "2026-11-01T00:00:00Z", "billingPeriodEnd": "2026-12-01T00:00:00Z"}}
    (w,) = grok.parse(alt)
    assert w.utilization == 25 and w.label == "Crediti · Mese"
    with pytest.raises(ApiError):
        grok.parse({"config": {"currentPeriod": {"end": "2026-11-30T00:00:00Z"}}})


def test_grok_reads_cli_login(tmp_path, monkeypatch):
    monkeypatch.setenv("GROK_HOME", str(tmp_path))
    (tmp_path / "auth.json").write_text(json.dumps({
        "https://accounts.x.ai/sign-in": {"key": "legacy"},
        "https://auth.x.ai::client": {"key": "oidc-tok", "email": "x@y.z"},
    }))
    p = BY_ID["grok"]
    assert p.auto_credential() == grok.AUTO
    http = Http(Resp(200, GROK))
    p.fetch(grok.AUTO, http, {})
    assert http.calls[0][1]["Authorization"] == "Bearer oidc-tok"
    assert http.calls[0][1]["x-xai-token-auth"] == "xai-grok-cli"
    with pytest.raises(AuthError):
        p.fetch("tok", Http(Resp(401, "{}")), {})


# ---------------- Mistral ----------------

def mistral_page(budget: dict) -> str:
    flight = '0:["$","div",null,{}]\n5:' + json.dumps({"subscription": {"budget": budget}}) + "\n"
    return ("<html><body><script>self.__next_f.push([1," + json.dumps(flight) + "])</script>"
            "<script>self.__next_f.push([2,null])</script></body></html>")


MISTRAL_BUDGET = {
    "api_budget": {"usage_percentage": 42.0, "initial_budget": 15, "currency": "eur",
                   "reset_at": "2026-12-01T00:00:00Z"},
    "vibe_budget": {"usage_percentage": 80, "initial_budget": 10, "currency": "eur", "reset_at": None},
}


def test_mistral_parse_flight_data():
    ws = {w.key: w for w in mistral.parse_html(mistral_page(MISTRAL_BUDGET))}
    assert ws["mistral:api_budget"].utilization == 42
    assert ws["mistral:api_budget"].label == "Budget API incluso (15 EUR)"
    assert ws["mistral:api_budget"].length == timedelta(days=30)
    assert ws["mistral:vibe_budget"].utilization == 80 and ws["mistral:vibe_budget"].resets_at is None


def test_mistral_login_page_and_changed_page():
    with pytest.raises(AuthError):
        mistral.parse_html('<html><a href="https://auth.mistral.ai/self-service/login">Login</a></html>')
    with pytest.raises(ApiError):
        mistral.parse_html("<html>niente</html>")


def test_mistral_fetch_redirect_to_login_is_auth_error():
    r = Resp(200, "<html></html>")
    r.url = "https://auth.mistral.ai/self-service/login/browser"
    with pytest.raises(AuthError):
        BY_ID["mistral"].fetch("ory_session_x=1", Http(r), {})
    http = Http(Resp(200, mistral_page(MISTRAL_BUDGET)))
    assert len(BY_ID["mistral"].fetch("ory_session_x=1; csrftoken=2", http, {})) == 2


# ---------------- OpenRouter ----------------

def test_openrouter_next_reset():
    reset, length = openrouter.next_reset("daily", NOW)
    assert reset == datetime(2026, 11, 28, tzinfo=timezone.utc) and length == timedelta(days=1)
    reset, _ = openrouter.next_reset("weekly", NOW)       # 27/11/2026 è venerdì
    assert reset == datetime(2026, 11, 30, tzinfo=timezone.utc)
    reset, length = openrouter.next_reset("monthly", NOW)
    assert reset == datetime(2026, 12, 1, tzinfo=timezone.utc) and length == timedelta(days=30)
    assert openrouter.next_reset(None, NOW) == (None, None)


def test_openrouter_parse_and_fetch():
    key = {"data": {"label": "x", "limit": 50, "limit_remaining": 12.5, "limit_reset": "monthly",
                    "usage": 100, "usage_monthly": 37.5}}
    (w,) = openrouter.parse_key(key, NOW)
    assert w.utilization == 75 and w.label == "Limite della chiave (50 $)"
    assert openrouter.parse_key({"data": {"limit": None, "usage": 3}}, NOW) == []
    (c,) = openrouter.parse_credits({"data": {"total_credits": 20, "total_usage": 5}})
    assert c.utilization == 25 and "15.00 $" in c.label

    http = Http(Resp(200, key), Resp(403, {"error": "forbidden"}))
    ws = BY_ID["openrouter"].fetch("sk-or-v1-abc", http, {})
    assert [w.key for w in ws] == ["openrouter:key"]
    assert http.calls[0][1]["Authorization"] == "Bearer sk-or-v1-abc"
    with pytest.raises(ApiError):
        BY_ID["openrouter"].fetch("k", Http(Resp(200, {"data": {"limit": None}}), Resp(403, "{}")), {})
    with pytest.raises(AuthError):
        BY_ID["openrouter"].fetch("k", Http(Resp(401, "{}")), {})
