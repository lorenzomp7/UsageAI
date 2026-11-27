from datetime import datetime, timedelta, timezone

import pytest

from usageai.api import ApiError, AuthError, ClaudeClient
from usageai.core import (
    Settings, WindowState, evaluate, expected_utilization, parse_thresholds,
    parse_usage, pick_org_id,
)

NOW = datetime(2026, 11, 27, 12, 0, tzinfo=timezone.utc)


def sample(five=40.0, week=30.0, week_reset=NOW + timedelta(days=4)):
    return {
        "five_hour": {"utilization": five, "resets_at": (NOW + timedelta(hours=2)).isoformat()},
        "seven_day": {"utilization": week, "resets_at": week_reset.isoformat().replace("+00:00", "Z")},
        "seven_day_opus": {"utilization": 5.0, "resets_at": None},
        "seven_day_oauth_apps": None,
        "extra_usage": {"is_enabled": False},
    }


def test_parse_usage_orders_and_skips_nulls():
    ws = parse_usage(sample())
    assert [w.key for w in ws] == ["five_hour", "seven_day", "seven_day_opus"]
    assert ws[1].resets_at == NOW + timedelta(days=4)
    assert ws[2].resets_at is None
    assert ws[2].label == "Settimana · Opus"


def test_parse_fractional_offset_timestamp():
    ws = parse_usage({"five_hour": {"utilization": 1, "resets_at": "2026-11-27T14:00:00.512345+00:00"}})
    assert ws[0].resets_at.minute == 0


def test_threshold_fires_once_per_window():
    s = Settings(thresholds_5h=[50, 80], thresholds_week=[90], pace_enabled=False)
    alerts, state = evaluate(parse_usage(sample(five=60)), s, {}, NOW)
    assert [a.title for a in alerts] == ["Sessione 5 ore: 60% (soglia 50%)"]
    alerts, state = evaluate(parse_usage(sample(five=65)), s, state, NOW)
    assert alerts == []
    alerts, state = evaluate(parse_usage(sample(five=85)), s, state, NOW)
    assert len(alerts) == 1 and "soglia 80%" in alerts[0].title


def test_jump_over_two_thresholds_gives_single_alert():
    s = Settings(thresholds_5h=[50, 80], pace_enabled=False)
    alerts, state = evaluate(parse_usage(sample(five=90)), s, {}, NOW)
    assert len(alerts) == 1 and "soglia 80%" in alerts[0].title
    assert state["five_hour"].notified == [50, 80]


def test_new_window_rearms_thresholds():
    s = Settings(thresholds_5h=[50], pace_enabled=False)
    _, state = evaluate(parse_usage(sample(five=60)), s, {}, NOW)
    later = NOW + timedelta(hours=6)
    data = sample(five=55)
    data["five_hour"]["resets_at"] = (later + timedelta(hours=3)).isoformat()
    alerts, _ = evaluate(parse_usage(data), s, state, later)
    assert len(alerts) == 1


def test_small_reset_jitter_is_same_window():
    s = Settings(thresholds_5h=[50], pace_enabled=False)
    _, state = evaluate(parse_usage(sample(five=60)), s, {}, NOW)
    data = sample(five=62)
    data["five_hour"]["resets_at"] = (NOW + timedelta(hours=2, minutes=5)).isoformat()
    alerts, _ = evaluate(parse_usage(data), s, state, NOW)
    assert alerts == []


def test_pace_expected_and_alert():
    # Reset tra 4 giorni => passati 3/7 della settimana => ideale ~42.9%
    w = parse_usage(sample())[1]
    assert expected_utilization(w, NOW) == pytest.approx(300 / 7)
    s = Settings(thresholds_week=[], pace_enabled=True, pace_margin=10)
    alerts, state = evaluate(parse_usage(sample(week=60)), s, {}, NOW)
    pace = [a for a in alerts if a.key == "seven_day_pace"]
    assert len(pace) == 1 and "saresti al 43%" in pace[0].text
    alerts, state = evaluate(parse_usage(sample(week=61)), s, state, NOW)
    assert not [a for a in alerts if a.key == "seven_day_pace"]
    # torna in linea -> si riarma
    _, state = evaluate(parse_usage(sample(week=40)), s, state, NOW)
    assert state["seven_day"].pace_notified is False


def test_pace_not_noisy_at_start_of_week():
    s = Settings(thresholds_week=[], pace_margin=10)
    data = sample(week=15, week_reset=NOW + timedelta(days=6, hours=23))
    alerts, _ = evaluate(parse_usage(data), s, {}, NOW)
    assert alerts == []


def test_parse_thresholds():
    assert parse_thresholds("75, 50;90  50") == [50, 75, 90]
    assert parse_thresholds("") == []
    with pytest.raises(ValueError):
        parse_thresholds("0, 50")
    with pytest.raises(ValueError):
        parse_thresholds("abc")


def test_pick_org_prefers_paid_plan():
    orgs = [
        {"uuid": "api-only", "capabilities": ["api"]},
        {"uuid": "chat-free", "capabilities": ["chat"]},
        {"uuid": "max", "capabilities": ["chat", "claude_max"]},
    ]
    assert pick_org_id(orgs) == "max"
    assert pick_org_id([{"uuid": "a", "capabilities": ["chat"]}]) == "a"
    assert pick_org_id([]) is None


class FakeResp:
    def __init__(self, status, body):
        self.status_code = status
        self._body = body

    def json(self):
        if isinstance(self._body, Exception):
            raise self._body
        return self._body


class FakeSession:
    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    def get(self, url, headers=None, timeout=None):
        self.calls.append((url, headers))
        return self.responses.pop(0)


def test_client_resolves_org_and_sends_cookie():
    sess = FakeSession([FakeResp(200, [{"uuid": "o1", "capabilities": ["claude_pro"]}]),
                        FakeResp(200, sample())])
    c = ClaudeClient(" sk-ant-sid01-abc ", session=sess)
    data = c.usage()
    assert data["five_hour"]["utilization"] == 40.0
    assert sess.calls[1][0].endswith("/organizations/o1/usage")
    assert sess.calls[1][1]["Cookie"] == "sessionKey=sk-ant-sid01-abc"


def test_client_auth_error():
    sess = FakeSession([FakeResp(401, {"error": {"type": "authentication_error"}})])
    with pytest.raises(AuthError):
        ClaudeClient("x", session=sess).usage()


def test_cloudflare_block_is_not_auth_error():
    sess = FakeSession([FakeResp(403, ValueError("html"))])
    with pytest.raises(ApiError):
        ClaudeClient("x", session=sess).usage()
