"""OpenRouter: limite di spesa della chiave e credito prepagato (API ufficiale, con chiave API)."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from ..core import Window, month_before
from .base import ApiError, AuthError, Provider, number, parse_json, request

KEY_URL = "https://openrouter.ai/api/v1/key"
CREDITS_URL = "https://openrouter.ai/api/v1/credits"


def next_reset(kind: str | None, now: datetime) -> tuple[datetime | None, timedelta | None]:
    """OpenRouter azzera i limiti a mezzanotte UTC: ogni giorno, ogni lunedì o il primo del mese."""
    midnight = now.astimezone(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    if kind == "daily":
        return midnight + timedelta(days=1), timedelta(days=1)
    if kind == "weekly":
        return midnight + timedelta(days=7 - midnight.weekday()), timedelta(days=7)
    if kind == "monthly":
        first = midnight.replace(day=1)
        nxt = first.replace(year=first.year + 1, month=1) if first.month == 12 else first.replace(month=first.month + 1)
        return nxt, nxt - month_before(nxt)
    return None, None


def parse_key(data: dict, now: datetime) -> list[Window]:
    d = data.get("data") if isinstance(data, dict) else None
    if not isinstance(d, dict):
        raise ApiError("Risposta di OpenRouter cambiata.")
    limit = number(d.get("limit"))
    if not limit or limit <= 0:
        return []
    remaining = number(d.get("limit_remaining"))
    kind = d.get("limit_reset")
    if remaining is not None:
        used = limit - remaining
    else:
        used = number(d.get({"daily": "usage_daily", "weekly": "usage_weekly",
                             "monthly": "usage_monthly"}.get(kind, "usage"))) or 0
    reset, length = next_reset(kind, now)
    return [Window("openrouter", "openrouter:key", f"Limite della chiave ({limit:.0f} $)",
                   max(0.0, min(100.0, used / limit * 100)), reset, length)]


def parse_credits(data: dict) -> list[Window]:
    d = data.get("data") if isinstance(data, dict) else None
    if not isinstance(d, dict):
        return []
    total, used = number(d.get("total_credits")), number(d.get("total_usage"))
    if not total or total <= 0 or used is None:
        return []
    left = total - used
    return [Window("openrouter", "openrouter:credits", f"Credito prepagato (restano {left:.2f} $)",
                   max(0.0, min(100.0, used / total * 100)), None, None)]


class OpenRouterProvider(Provider):
    id = "openrouter"
    name = "OpenRouter"
    credential_label = "Chiave API di OpenRouter"
    help = ("Crea una chiave su openrouter.ai/settings/keys (inizia con sk-or-) e incollala qui. "
            "Se le dai un limite di spesa, l'app ti avvisa quando ti avvicini; il credito prepagato "
            "viene mostrato se OpenRouter lo concede a quella chiave.")

    def fetch(self, credential: str, http, cache: dict) -> list[Window]:
        headers = {"Authorization": f"Bearer {credential.strip()}", "Accept": "application/json"}
        _, text = request(http, KEY_URL, headers, auth_codes=(401,))
        out = parse_key(parse_json(text), datetime.now(timezone.utc))
        try:
            _, text = request(http, CREDITS_URL, headers, auth_codes=())
            out += parse_credits(parse_json(text))
        except (ApiError, AuthError):
            pass   # alcune chiavi non possono leggere il credito: non è un errore
        if not out:
            raise ApiError("La chiave non ha un limite di spesa e il credito non è leggibile: "
                           "imposta un limite alla chiave su openrouter.ai.")
        return out
