"""Logica pura: lettura della risposta /usage, soglie, ritmo settimanale.

Niente rete, niente interfaccia: tutto qui è testabile da solo.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

FIVE_HOURS = timedelta(hours=5)
SEVEN_DAYS = timedelta(days=7)
# Se l'orario di reset si sposta di più di così, è iniziata una finestra nuova.
NEW_WINDOW_TOLERANCE = timedelta(minutes=30)
# Sotto questa percentuale l'avviso sul ritmo non scatta (a inizio settimana è rumore).
PACE_MIN_UTILIZATION = 20.0


@dataclass(frozen=True)
class Window:
    key: str                  # "five_hour", "seven_day", "seven_day_opus", ...
    label: str                # testo per l'utente
    utilization: float        # 0-100
    resets_at: datetime | None
    length: timedelta

    @property
    def is_weekly(self) -> bool:
        return self.key.startswith("seven_day")


@dataclass
class Settings:
    thresholds_5h: list[int] = field(default_factory=lambda: [80])
    thresholds_week: list[int] = field(default_factory=lambda: [50, 75, 90])
    pace_enabled: bool = True
    pace_margin: int = 10      # punti percentuali oltre il ritmo "giusto"
    interval_min: int = 5      # solo Windows


@dataclass
class WindowState:
    resets_at: str | None = None          # ISO, per riconoscere la finestra
    notified: list[int] = field(default_factory=list)
    pace_notified: bool = False


@dataclass(frozen=True)
class Alert:
    key: str
    title: str
    text: str


def _label_for(key: str) -> str:
    if key == "five_hour":
        return "Sessione 5 ore"
    if key == "seven_day":
        return "Settimana (tutti i modelli)"
    if key.startswith("seven_day_"):
        name = key[len("seven_day_"):].replace("_", " ").strip()
        return f"Settimana · {name.title()}"
    return key.replace("_", " ")


def parse_datetime(value) -> datetime | None:
    if not value or not isinstance(value, str):
        return None
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def parse_usage(data: dict) -> list[Window]:
    """Trasforma la risposta JSON di /usage in finestre ordinate (5 ore, settimana, altre)."""
    windows: list[Window] = []
    if not isinstance(data, dict):
        return windows
    for key, value in data.items():
        if key != "five_hour" and not key.startswith("seven_day"):
            continue
        if not isinstance(value, dict):
            continue
        util = value.get("utilization")
        if not isinstance(util, (int, float)) or isinstance(util, bool):
            continue
        windows.append(Window(
            key=key,
            label=_label_for(key),
            utilization=max(0.0, float(util)),
            resets_at=parse_datetime(value.get("resets_at")),
            length=FIVE_HOURS if key == "five_hour" else SEVEN_DAYS,
        ))

    def order(w: Window):
        return (0 if w.key == "five_hour" else 1 if w.key == "seven_day" else 2, w.key)

    windows.sort(key=order)
    return windows


def expected_utilization(window: Window, now: datetime) -> float | None:
    """Percentuale che avresti consumato andando a ritmo costante fino al reset."""
    if window.resets_at is None:
        return None
    remaining = window.resets_at - now
    elapsed = 1.0 - remaining / window.length
    elapsed = min(1.0, max(0.0, elapsed))
    return elapsed * 100.0


def pace_text(window: Window, now: datetime) -> str | None:
    exp = expected_utilization(window, now)
    if exp is None:
        return None
    diff = window.utilization - exp
    if diff > 2:
        return f"in anticipo di {diff:.0f} punti sul ritmo (ideale {exp:.0f}%)"
    if diff < -2:
        return f"margine di {-diff:.0f} punti sul ritmo (ideale {exp:.0f}%)"
    return f"in linea con il ritmo (ideale {exp:.0f}%)"


def _same_window(old_iso: str | None, new: datetime | None) -> bool:
    old = parse_datetime(old_iso)
    if old is None or new is None:
        return old is None and new is None
    return abs(old - new) <= NEW_WINDOW_TOLERANCE


def format_reset(resets_at: datetime | None, now: datetime) -> str:
    if resets_at is None:
        return "nessuna finestra attiva"
    delta = resets_at - now
    if delta.total_seconds() <= 0:
        return "reset in corso"
    hours, rem = divmod(int(delta.total_seconds()), 3600)
    minutes = rem // 60
    local = resets_at.astimezone()
    days_it = ["lun", "mar", "mer", "gio", "ven", "sab", "dom"]
    when = f"{days_it[local.weekday()]} {local:%H:%M}"
    if hours >= 24:
        return f"reset {when} (tra {hours // 24} g {hours % 24} h)"
    return f"reset {when} (tra {hours} h {minutes} min)"


def evaluate(windows: list[Window], settings: Settings,
             state: dict[str, WindowState], now: datetime
             ) -> tuple[list[Alert], dict[str, WindowState]]:
    """Decide quali notifiche mandare. Ogni soglia scatta una volta sola per finestra."""
    alerts: list[Alert] = []
    new_state: dict[str, WindowState] = {}

    for w in windows:
        old = state.get(w.key)
        if old is None or not _same_window(old.resets_at, w.resets_at):
            # Finestra nuova (o primo avvio): si riparte da zero. Le soglie già superate
            # al primo avvio vengono notificate, così sai subito dove sei.
            ws = WindowState(resets_at=w.resets_at.isoformat() if w.resets_at else None)
        else:
            ws = WindowState(resets_at=old.resets_at,
                             notified=list(old.notified),
                             pace_notified=old.pace_notified)

        thresholds = settings.thresholds_5h if w.key == "five_hour" else settings.thresholds_week
        crossed = sorted(t for t in thresholds if w.utilization >= t and t not in ws.notified)
        if crossed:
            top = crossed[-1]
            alerts.append(Alert(
                key=w.key,
                title=f"{w.label}: {w.utilization:.0f}% (soglia {top}%)",
                text=format_reset(w.resets_at, now),
            ))
            ws.notified = sorted(set(ws.notified) | set(crossed))

        if w.is_weekly and settings.pace_enabled:
            exp = expected_utilization(w, now)
            if exp is not None:
                ahead = w.utilization - exp
                if (ahead > settings.pace_margin and w.utilization >= PACE_MIN_UTILIZATION
                        and not ws.pace_notified):
                    alerts.append(Alert(
                        key=w.key + "_pace",
                        title=f"{w.label}: stai andando troppo veloce",
                        text=(f"Hai usato il {w.utilization:.0f}%, a ritmo costante saresti al "
                              f"{exp:.0f}%. {format_reset(w.resets_at, now)}"),
                    ))
                    ws.pace_notified = True
                elif ahead <= 0:
                    ws.pace_notified = False   # tornato in linea: l'avviso si riarma

        new_state[w.key] = ws

    return alerts, new_state


def parse_thresholds(text: str) -> list[int]:
    """'50, 75 90' -> [50, 75, 90]. Solleva ValueError se qualcosa non va."""
    parts = [p for p in text.replace(";", ",").replace(" ", ",").split(",") if p.strip()]
    values = sorted({int(p) for p in parts})
    if any(v < 1 or v > 100 for v in values):
        raise ValueError("Le soglie devono stare tra 1 e 100")
    return values


def pick_org_id(orgs) -> str | None:
    """Sceglie l'organizzazione con l'abbonamento Claude (Pro/Max) tra quelle dell'account."""
    if not isinstance(orgs, list):
        return None
    candidates = [o for o in orgs if isinstance(o, dict) and o.get("uuid")]
    for o in candidates:
        caps = o.get("capabilities") or []
        if any(isinstance(c, str) and c.startswith("claude_") for c in caps):
            return o["uuid"]
    for o in candidates:
        if "chat" in (o.get("capabilities") or []):
            return o["uuid"]
    return candidates[0]["uuid"] if candidates else None
