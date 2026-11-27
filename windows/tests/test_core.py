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


# ---------------- quota giornaliera ----------------

from usageai.core import daily_quota, today_text  # noqa: E402


def week(util, reset=NOW + timedelta(days=4)):
    return Window("claude", "claude:seven_day", "Settimana", util, reset, timedelta(days=7))


def day_alerts(alerts):
    return [a for a in alerts if a.key.endswith(":day")]


def test_daily_quota_values():
    assert daily_quota(week(0)) == pytest.approx(100 / 7)          # 14,29%
    month = Window("cursor", "cursor:plan", "Mese", 0, NOW, timedelta(days=30))
    assert daily_quota(month) == pytest.approx(100 / 30)
    five = Window("claude", "claude:five_hour", "5 ore", 0, NOW, timedelta(hours=5))
    assert daily_quota(five) is None


def test_daily_quota_alert_once_per_day_and_rearms_next_day():
    s = Settings(thresholds_long=[], pace_enabled=False)
    utc = timezone.utc
    t = datetime(2026, 11, 26, 9, 0, tzinfo=utc)
    _, st = evaluate([week(20)], s, {}, t, tz=utc)                   # primo dato: base 20
    alerts, st = evaluate([week(30)], s, st, t + timedelta(hours=2), tz=utc)   # +10 < 14,29
    assert day_alerts(alerts) == []
    alerts, st = evaluate([week(35)], s, st, t + timedelta(hours=3), tz=utc)   # +15 > 14,29
    (a,) = day_alerts(alerts)
    assert "superata la quota di oggi" in a.title and "15,0 punti" in a.text and "14,3%" in a.text
    alerts, st = evaluate([week(40)], s, st, t + timedelta(hours=5), tz=utc)
    assert day_alerts(alerts) == []
    # giorno dopo: la base è l'ultimo valore di ieri (40)
    t2 = datetime(2026, 11, 27, 8, 0, tzinfo=utc)
    alerts, st = evaluate([week(41)], s, st, t2, tz=utc)
    assert day_alerts(alerts) == [] and st["claude:seven_day"].day_start == 40
    assert today_text(week(41), st["claude:seven_day"]).startswith("oggi +1,0% su 14,3% al giorno")
    alerts, st = evaluate([week(56)], s, st, t2 + timedelta(hours=4), tz=utc)
    assert len(day_alerts(alerts)) == 1


def test_daily_quota_counts_from_zero_after_reset_and_can_be_disabled():
    utc = timezone.utc
    s = Settings(thresholds_long=[], pace_enabled=False)
    t = datetime(2026, 11, 26, 9, 0, tzinfo=utc)
    _, st = evaluate([week(90, reset=t + timedelta(hours=1))], s, {}, t, tz=utc)
    new = week(16, reset=t + timedelta(days=7, hours=1))            # periodo ricominciato
    alerts, st = evaluate([new], s, st, t + timedelta(hours=3), tz=utc)
    assert len(day_alerts(alerts)) == 1 and st["claude:seven_day"].day_start == 0
    off = Settings(thresholds_long=[], pace_enabled=False, daily_enabled=False)
    _, st2 = evaluate([week(10)], off, {}, t, tz=utc)
    alerts, _ = evaluate([week(60)], off, st2, t + timedelta(hours=1), tz=utc)
    assert day_alerts(alerts) == []


def test_daily_state_is_saved(tmp_path, monkeypatch):
    from usageai import storage
    monkeypatch.setenv("APPDATA", str(tmp_path))
    st = {"claude:seven_day": WindowState("2026-12-01T00:00:00+00:00", [50], False, "2026-11-27", 40.0, True, 45.5)}
    storage.save_state(st, {})
    loaded, _ = storage.load_state()
    assert loaded == st
