"""GitHub Copilot: richieste premium del mese (api.github.com/copilot_internal/user)."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from ..core import Window, parse_datetime
from .base import ApiError, Provider, number, parse_json, request

URL = "https://api.github.com/copilot_internal/user"


def _reset(value) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    dt = parse_datetime(value)
    if dt is None:
        try:
            dt = datetime.strptime(value.strip()[:10], "%Y-%m-%d").replace(tzinfo=timezone.utc)
        except ValueError:
            return None
    return dt


def _month_length(reset: datetime | None) -> timedelta:
    if reset is None:
        return timedelta(days=30)
    year, month = (reset.year, reset.month - 1) if reset.month > 1 else (reset.year - 1, 12)
    try:
        start = reset.replace(year=year, month=month)
    except ValueError:            # es. 31 marzo -> 28/29 febbraio
        start = reset.replace(year=year, month=month, day=28)
    return reset - start


def parse(data: dict) -> list[Window]:
    if not isinstance(data, dict):
        raise ApiError("Risposta di Copilot cambiata.")
    snaps = data.get("quota_snapshots") or data.get("quotaSnapshots") or {}
    reset = _reset(data.get("quota_reset_date") or data.get("quotaResetDate"))
    length = _month_length(reset)
    out: list[Window] = []
    for key, label in (("premium_interactions", "Richieste premium"), ("chat", "Chat")):
        snap = snaps.get(key)
        if not isinstance(snap, dict) or snap.get("unlimited"):
            continue
        remaining_pct = number(snap.get("percent_remaining"))
        if remaining_pct is None:
            ent, rem = number(snap.get("entitlement")), number(snap.get("remaining"))
            if not ent or rem is None:
                continue
            remaining_pct = rem / ent * 100
        out.append(Window("copilot", f"copilot:{key}", label,
                          max(0.0, 100.0 - remaining_pct), reset, length))
    if not out:
        raise ApiError("Copilot non riporta limiti per questo account (piano illimitato o senza Copilot).")
    return out


class CopilotProvider(Provider):
    id = "copilot"
    name = "Copilot"
    credential_label = "Token GitHub"
    help = ("Il modo più semplice: installa GitHub CLI, esegui «gh auth login» e poi «gh auth token», "
            "e incolla qui il token che stampa.")

    def fetch(self, credential: str, http, cache: dict) -> list[Window]:
        headers = {
            "Authorization": f"token {credential.strip()}",
            "Accept": "application/json",
            "X-Github-Api-Version": "2025-04-01",
        }
        try:
            _, text = request(http, URL, headers)
        except ApiError as e:
            if e.status == 404:
                raise ApiError("Questo account GitHub non ha Copilot, o il token non è accettato.") from e
            raise
        return parse(parse_json(text))
