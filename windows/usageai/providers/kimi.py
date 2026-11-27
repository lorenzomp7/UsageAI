"""Kimi Code: limite settimanale, finestre brevi (es. 5 ore) e credito extra (api.kimi.com/coding/v1/usages).

Credenziale: la chiave API di Kimi Code, oppure (da sola) il login della CLI ufficiale di Kimi Code
(%USERPROFILE%\\.kimi-code\\credentials\\kimi-code.json, o KIMI_CODE_HOME).

Il formato della risposta viene dal codice della CLI (github.com/MoonshotAI/kimi-code,
packages/oauth/src/managed-usage.ts, licenza MIT). I nomi dei campi sono cambiati nel tempo,
quindi la lettura è tollerante come quella della CLI: «used» oppure «remaining», «resetAt» oppure
«reset_at», durata della finestra come «duration» + «timeUnit». Niente codice copiato.
"""
from __future__ import annotations

import json
import os
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

from ..core import Window, parse_datetime, window_label
from .base import ApiError, AuthError, Provider, number, parse_json, request

URL = "https://api.kimi.com/coding/v1/usages"
AUTO = "auto:kimi-cli"
FIXED_POINT_CENTS = 1_000_000   # i saldi del boosterWallet sono in milionesimi di centesimo

UNIT_SECONDS = (("MINUTE", 60), ("HOUR", 3600), ("DAY", 86400), ("WEEK", 7 * 86400), ("SECOND", 1))


def _int(v) -> int | None:
    n = number(v)
    return int(n) if n is not None else None


def _first(*dicts_and_keys):
    """_first((d1, d2), "a", "b"): il primo valore non vuoto tra le chiavi, cercato in ogni dizionario."""
    dicts, keys = dicts_and_keys[0], dicts_and_keys[1:]
    for d in dicts:
        if not isinstance(d, dict):
            continue
        for k in keys:
            v = d.get(k)
            if v not in (None, ""):
                return v
    return None


def _length(item: dict, detail: dict, window: dict) -> timedelta | None:
    duration = _int(_first((window, item, detail), "duration"))
    unit = str(_first((window, item, detail), "timeUnit", "time_unit") or "").upper()
    if duration is None or duration <= 0:
        return None
    for name, secs in UNIT_SECONDS:
        if name in unit:
            return timedelta(seconds=duration * secs)
    return timedelta(seconds=duration)


def _reset(raws: tuple, now: datetime) -> datetime | None:
    v = _first(raws, "reset_at", "resetAt", "reset_time", "resetTime")
    if isinstance(v, str):
        # nanosecondi (…123456789Z) → microsecondi, che Python capisce
        if "." in v and v.endswith("Z"):
            base, frac = v[:-1].split(".", 1)
            v = f"{base}.{frac[:6]}Z"
        dt = parse_datetime(v)
        if dt:
            return dt
    secs = _int(_first(raws, "reset_in", "resetIn", "ttl"))
    if secs and secs > 0:
        return now + timedelta(seconds=secs)
    return None


def _used_limit(detail: dict) -> tuple[int | None, int | None]:
    limit = _int(detail.get("limit"))
    used = _int(detail.get("used"))
    if used is None:
        remaining = _int(detail.get("remaining"))
        if remaining is not None and limit is not None:
            used = limit - remaining
    return used, limit


def _label(name, length: timedelta | None, fallback: str) -> str:
    if length:
        return window_label(length)
    if isinstance(name, str) and name:
        low = name.lower()
        if "week" in low:
            return "Settimana"
        if "5h" in low or "5 h" in low:
            return "Sessione 5 ore"
        return name
    return fallback


def _money(cents: int, currency: str) -> str:
    return f"{cents / 100:.2f}".replace(".", ",") + f" {currency}"


def _cents(v) -> int | None:
    n = _int(v)
    if n is None:
        return None
    c = n / FIXED_POINT_CENTS
    return 1 if 0 < c < 1 else round(c)


def parse(data: dict, now: datetime | None = None) -> list[Window]:
    now = now or datetime.now(timezone.utc)
    if not isinstance(data, dict):
        raise ApiError("Risposta di Kimi cambiata.")
    out: list[Window] = []

    summary = data.get("usage")
    if isinstance(summary, dict):
        used, limit = _used_limit(summary)
        if used is not None and limit and limit > 0:
            name = _first((summary,), "name", "title")
            length = timedelta(days=7) if not name or "week" in str(name).lower() else None
            out.append(Window("kimi", "kimi:weekly", _label(name, length, "Settimana"),
                              max(0.0, min(100.0, used / limit * 100)), _reset((summary,), now), length))

    for idx, item in enumerate(data.get("limits") or []):
        if not isinstance(item, dict):
            continue
        detail = item.get("detail") if isinstance(item.get("detail"), dict) else item
        window = item.get("window") if isinstance(item.get("window"), dict) else {}
        used, limit = _used_limit(detail)
        if used is None or not limit or limit <= 0:
            continue
        length = _length(item, detail, window)
        name = _first((item, detail), "name", "title", "scope")
        key = f"kimi:limit:{int(length.total_seconds())}s" if length else f"kimi:limit:{idx}"
        if any(w.key == key for w in out):
            key = f"{key}:{idx}"
        out.append(Window("kimi", key, _label(name, length, f"Limite {idx + 1}"),
                          max(0.0, min(100.0, used / limit * 100)), _reset((detail, item), now), length))

    wallet = data.get("boosterWallet") or data.get("booster_wallet")
    balance = wallet.get("balance") if isinstance(wallet, dict) else None
    if isinstance(balance, dict) and balance.get("type") == "BOOSTER":
        total = _cents(balance.get("amount"))
        left = _cents(balance.get("amountLeft")) or 0
        def money(key):
            m = wallet.get(key)
            return (_int(m.get("priceInCents")), str(m.get("currency") or "")) if isinstance(m, dict) else (None, "")
        cap, cap_cur = money("monthlyChargeLimit")
        spent, spent_cur = money("monthlyUsed")
        currency = cap_cur or spent_cur or "USD"
        if total and total > 0:
            if wallet.get("monthlyChargeLimitEnabled") is True and cap and cap > 0:
                out.append(Window("kimi", "kimi:extra_month", f"Spesa extra del mese (limite {_money(cap, currency)})",
                                  max(0.0, min(100.0, (spent or 0) / cap * 100)), None, None))
            out.append(Window("kimi", "kimi:booster", "Credito extra", 0.0, None, None,
                              note=f"{_money(left, currency)} rimasti su {_money(total, currency)}"))

    if not any(not w.is_info for w in out):
        raise ApiError("Kimi non riporta limiti per questo account (serve un abbonamento Kimi Code).")
    return out


def kimi_credentials_file() -> Path:
    home = os.environ.get("KIMI_CODE_HOME")
    base = Path(home) if home else Path.home() / ".kimi-code"
    return base / "credentials" / "kimi-code.json"


def token_from_cli(path: Path, now: float | None = None) -> str | None:
    """access_token del login della CLI; None se manca. AuthError se è scaduto."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(data, dict) or not data.get("access_token"):
        return None
    expires = number(data.get("expires_at"))
    if expires and expires > 1e12:
        expires /= 1000
    if expires and expires < (now if now is not None else time.time()):
        # Non rinnovo io il token: il rinnovo cambia anche il refresh_token e la CLI resterebbe fuori.
        raise AuthError("Il login di Kimi Code è scaduto: apri Kimi Code (rinnova da solo) e riprova.")
    return str(data["access_token"])


class KimiProvider(Provider):
    id = "kimi"
    name = "Kimi Code"
    credential_label = "Chiave API di Kimi Code"
    help = ("Se usi la CLI di Kimi Code su questo PC non serve niente: leggo il suo login da solo. "
            "Altrimenti crea una chiave API nella console di Kimi Code (kimi.com/code) e incollala qui.")

    def auto_credential(self) -> str | None:
        f = kimi_credentials_file()
        if not f.is_file():
            return None
        try:
            return AUTO if token_from_cli(f) else None
        except AuthError:
            return AUTO   # scaduto: lo segnalo al controllo, così l'utente lo vede

    def fetch(self, credential: str, http, cache: dict) -> list[Window]:
        token = token_from_cli(kimi_credentials_file()) if credential == AUTO else credential.strip()
        if not token:
            raise AuthError("Il login della CLI di Kimi Code non contiene un token: rifai l'accesso.")
        headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
        try:
            _, text = request(http, URL, headers)
        except AuthError as e:
            if credential == AUTO:
                raise AuthError("Il login di Kimi Code è scaduto: apri Kimi Code e riprova.") from e
            raise AuthError("La chiave di Kimi Code non è valida o è scaduta.") from e
        except ApiError as e:
            if e.status == 404:
                raise ApiError("Kimi non espone i consumi per questa chiave (serve Kimi Code, non la piattaforma API).",
                               404, e.body) from e
            raise
        return parse(parse_json(text))
