from datetime import datetime, timedelta, timezone

import pytest

from usageai.core import (
    Settings, Window, WindowState, evaluate, expected_utilization, parse_thresholds, window_label,
)
from usageai.providers.claude import parse as parse_claude

NOW = datetime(2026, 11, 27, 12, 0, tzinfo=timezone.utc)


def claude(five=40.0, week=30.0, five_reset=NOW + timedelta(hours=2), week_reset=NOW + timedelta(days=4)):
    return parse_claude({
        "five_hour": {"utilization": five, "resets_at": five_reset.isoformat()},
        "seven_day": {"utilization": week, "resets_at": week_reset.isoformat().replace("+00:00", "Z")},
    })


def test_window_kinds_and_labels():
    w5, w7 = claude()
    assert not w5.is_long and w7.is_long
    assert w7.full_label == "Claude · Settimana"
    assert window_label(timedelta(hours=5)) == "Sessione 5 ore"
    assert window_label(timedelta(days=7)) == "Settimana"
    assert window_label(timedelta(days=30)) == "Mese"


def test_threshold_fires_once_per_window():
    s = Settings(thresholds_short=[50, 80], thresholds_long=[90], pace_enabled=False)
    alerts, state = evaluate(claude(five=60), s, {}, NOW)
    assert [a.title for a in alerts] == ["Claude · Sessione 5 ore: 60% (soglia 50%)"]
    alerts, state = evaluate(claude(five=65), s, state, NOW)
    assert alerts == []
    alerts, state = evaluate(claude(five=85), s, state, NOW)
    assert len(alerts) == 1 and "soglia 80%" in alerts[0].title


def test_jump_over_two_thresholds_gives_single_alert():
    s = Settings(thresholds_short=[50, 80], pace_enabled=False)
    alerts, state = evaluate(claude(five=90), s, {}, NOW)
    assert len(alerts) == 1 and "soglia 80%" in alerts[0].title
    assert state["claude:five_hour"].notified == [50, 80]


def test_new_window_rearms_and_jitter_does_not():
    s = Settings(thresholds_short=[50], pace_enabled=False)
    _, state = evaluate(claude(five=60), s, {}, NOW)
    later = NOW + timedelta(hours=6)
    alerts, _ = evaluate(claude(five=55, five_reset=later + timedelta(hours=3)), s, state, later)
    assert len(alerts) == 1
    alerts, _ = evaluate(claude(five=62, five_reset=NOW + timedelta(hours=2, minutes=5)), s, state, NOW)
    assert alerts == []


def test_state_of_missing_provider_is_kept():
    s = Settings(thresholds_short=[50], pace_enabled=False)
    _, state = evaluate(claude(five=60), s, {}, NOW)
    _, state = evaluate([], s, state, NOW)          # Claude non ha risposto
    alerts, _ = evaluate(claude(five=61), s, state, NOW)
    assert alerts == []


def test_pace_expected_and_alert():
    w = claude()[1]
    assert expected_utilization(w, NOW) == pytest.approx(300 / 7)
    s = Settings(thresholds_long=[], pace_enabled=True, pace_margin=10)
    alerts, state = evaluate(claude(week=60), s, {}, NOW)
    pace = [a for a in alerts if a.key == "claude:seven_day:pace"]
    assert len(pace) == 1 and "saresti al 43%" in pace[0].text
    alerts, state = evaluate(claude(week=61), s, state, NOW)
    assert not [a for a in alerts if a.key.endswith(":pace")]
    _, state = evaluate(claude(week=40), s, state, NOW)
    assert state["claude:seven_day"].pace_notified is False


def test_pace_applies_to_monthly_windows_too():
    start = NOW - timedelta(days=10)
    month = Window("cursor", "cursor:plan", "Uso incluso nel mese", 70.0, start + timedelta(days=30), timedelta(days=30))
    s = Settings(thresholds_long=[], pace_margin=10)
    alerts, _ = evaluate([month], s, {}, NOW)
    assert alerts and "saresti al 33%" in alerts[0].text


def test_pace_not_noisy_at_start_of_week():
    s = Settings(thresholds_long=[], pace_margin=10)
    alerts, _ = evaluate(claude(week=15, week_reset=NOW + timedelta(days=6, hours=23)), s, {}, NOW)
    assert alerts == []


def test_parse_thresholds():
    assert parse_thresholds("75, 50;90  50") == [50, 75, 90]
    assert parse_thresholds("") == []
    with pytest.raises(ValueError):
        parse_thresholds("0, 50")
    with pytest.raises(ValueError):
        parse_thresholds("abc")


def test_legacy_state_is_migrated(tmp_path, monkeypatch):
    import json
    from usageai import storage
    monkeypatch.setenv("APPDATA", str(tmp_path))
    d = tmp_path / "UsageAI"
    d.mkdir()
    (d / "state.json").write_text(json.dumps({
        "windows": {"five_hour": {"resets_at": None, "notified": [80], "pace_notified": False}},
        "org_id": "org-old"}))
    (d / "config.json").write_text(json.dumps({"thresholds_5h": [70], "thresholds_week": [60]}))
    windows, caches = storage.load_state()
    assert windows["claude:five_hour"].notified == [80]
    assert caches == {"claude": {"org_id": "org-old"}}
    s = storage.load_settings()
    assert s.thresholds_short == [70] and s.thresholds_long == [60]
    assert WindowState() == WindowState()
