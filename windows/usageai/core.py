"""Logica pura comune a tutti i servizi: finestre di consumo, soglie, ritmo.

Niente rete, niente interfaccia: tutto qui è testabile da solo.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

# Finestre fino a 12 ore sono "brevi" (sessioni), le altre "lunghe" (settimana, mese).
SHORT_WINDOW_MAX = timedelta(hours=12)
# Se l'orario di reset si sposta di più di così, è iniziata una finestra nuova.
NEW_WINDOW_TOLERANCE = timedelta(minutes=30)
# Sotto questa percentuale l'avviso sul ritmo non scatta (a inizio periodo è rumore).
PACE_MIN_UTILIZATION = 20.0

PROVIDER_NAMES = {
    "claude": "Claude",
    "codex": "ChatGPT/Codex",
    "gemini": "Gemini",
    "cursor": "Cursor",
    "copilot": "Copilot",
    "perplexity": "Perplexity",
    "grok": "Grok",
    "mistral": "Mistral",
    "openrouter": "OpenRouter",
}


@dataclass(frozen=True)
class Window:
    provider: str             # "claude", "codex", ...
    key: str                  # unico per tutti i servizi, es. "claude:five_hour"
    label: str                # es. "Sessione 5 ore"
    utilization: float        # 0-100
    resets_at: datetime | None
    length: timedelta | None  # durata della finestra, se nota

    @property
    def is_long(self) -> bool:
        return self.length is None or self.length > SHORT_WINDOW_MAX

    @property
    def full_label(self) -> str:
        return f"{PROVIDER_NAMES.get(self.provider, self.provider)} · {self.label}"


@dataclass
class Settings:
    thresholds_short: list[int] = field(default_factory=lambda: [80])
    thresholds_long: list[int] = field(default_factory=lambda: [50, 75, 90])
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


def parse_datetime(value) -> datetime | None:
    if not value or not isinstance(value, str):
        return None
    try:
        dt = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def window_label(length: timedelta | None) -> str:
    if length is None:
        return "Periodo"
    hours = length.total_seconds() / 3600
    if abs(hours - 5) < 0.1:
        return "Sessione 5 ore"
    if abs(hours - 24 * 7) < 1:
        return "Settimana"
    if 27 * 24 <= hours <= 32 * 24:
        return "Mese"
    if hours < 48:
        return f"Finestra {hours:.0f} ore"
    return f"Finestra {hours / 24:.0f} giorni"


def month_before(end: datetime) -> datetime:
    """Stesso giorno del mese precedente (per stimare l'inizio di un periodo mensile)."""
    year, month = (end.year, end.month - 1) if end.month > 1 else (end.year - 1, 12)
    for day in (end.day, 30, 29, 28):
        try:
            return end.replace(year=year, month=month, day=day)
        except ValueError:
            continue
    return end


def expected_utilization(window: Window, now: datetime) -> float | None:
    """Percentuale che avresti consumato andando a ritmo costante fino al reset."""
    if window.resets_at is None or not window.length:
        return None
    remaining = window.resets_at - now
    elapsed = 1.0 - remaining / window.length
    return min(1.0, max(0.0, elapsed)) * 100.0


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
    when = f"{days_it[local.weekday()]} {local.day:02d}/{local.month:02d} {local:%H:%M}" if hours >= 24 * 6 \
        else f"{days_it[local.weekday()]} {local:%H:%M}"
    if hours >= 24:
        return f"reset {when} (tra {hours // 24} g {hours % 24} h)"
    return f"reset {when} (tra {hours} h {minutes} min)"


def evaluate(windows: list[Window], settings: Settings,
             state: dict[str, WindowState], now: datetime
             ) -> tuple[list[Alert], dict[str, WindowState]]:
    """Decide quali notifiche mandare. Ogni soglia scatta una volta sola per finestra.

    Lo stato dei servizi non presenti in `windows` viene conservato (un servizio che oggi
    non risponde non deve far ripartire le sue notifiche quando torna).
    """
    alerts: list[Alert] = []
    new_state: dict[str, WindowState] = dict(state)

    for w in windows:
        old = state.get(w.key)
        if old is None or not _same_window(old.resets_at, w.resets_at):
            ws = WindowState(resets_at=w.resets_at.isoformat() if w.resets_at else None)
        else:
            ws = WindowState(resets_at=old.resets_at, notified=list(old.notified),
                             pace_notified=old.pace_notified)

        thresholds = settings.thresholds_long if w.is_long else settings.thresholds_short
        crossed = sorted(t for t in thresholds if w.utilization >= t and t not in ws.notified)
        if crossed:
            top = crossed[-1]
            alerts.append(Alert(
                key=w.key,
                title=f"{w.full_label}: {w.utilization:.0f}% (soglia {top}%)",
                text=format_reset(w.resets_at, now),
            ))
            ws.notified = sorted(set(ws.notified) | set(crossed))

        if w.is_long and settings.pace_enabled:
            exp = expected_utilization(w, now)
            if exp is not None:
                ahead = w.utilization - exp
                if (ahead > settings.pace_margin and w.utilization >= PACE_MIN_UTILIZATION
                        and not ws.pace_notified):
                    alerts.append(Alert(
                        key=w.key + ":pace",
                        title=f"{w.full_label}: stai andando troppo veloce",
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
