"""Cursor: uso incluso nel piano del mese (cursor.com/api/usage-summary)."""
from __future__ import annotations

from ..core import Window, parse_datetime
from .base import ApiError, Provider, cookie_header, number, parse_json, request

URL = "https://cursor.com/api/usage-summary"


def parse(data: dict) -> list[Window]:
    if not isinstance(data, dict):
        raise ApiError("Risposta di Cursor cambiata.")
    start = parse_datetime(data.get("billingCycleStart"))
    end = parse_datetime(data.get("billingCycleEnd"))
    length = (end - start) if start and end and end > start else None
    usage = data.get("individualUsage") or {}
    plan = usage.get("plan") or {}
    out: list[Window] = []

    def add(key, label, value):
        if value is not None and value >= 0:
            out.append(Window("cursor", f"cursor:{key}", label, min(100.0, value), end, length))

    total = number(plan.get("totalPercentUsed"))
    if total is None:
        used, limit = number(plan.get("used")), number(plan.get("limit"))
        if used is not None and limit:
            total = used / limit * 100
    if total is None:
        overall = usage.get("overall") or {}
        used, limit = number(overall.get("used")), number(overall.get("limit"))
        if used is not None and limit:
            total = used / limit * 100
    add("plan", "Uso incluso nel mese", total)
    # I due pool separati: quando il pool API finisce, i modelli GPT/Claude si fermano.
    add("api", "Pool modelli API", number(plan.get("apiPercentUsed")))
    add("auto", "Pool Auto/Composer", number(plan.get("autoPercentUsed")))

    on_demand = usage.get("onDemand") or {}
    used, limit = number(on_demand.get("used")), number(on_demand.get("limit"))
    if used is not None and limit:
        add("ondemand", f"Spesa extra (limite {limit / 100:.0f} $)", used / limit * 100)

    if not out:
        if data.get("isUnlimited"):
            raise ApiError("Il piano Cursor risulta illimitato: niente da controllare.")
        raise ApiError("Cursor non riporta l'uso incluso per questo account.")
    return out


class CursorProvider(Provider):
    id = "cursor"
    name = "Cursor"
    credential_label = "WorkosCursorSessionToken"
    help = ("Apri cursor.com/dashboard in Chrome o Edge, già loggato → F12 → Application → Cookies → "
            "https://cursor.com → copia il valore di «WorkosCursorSessionToken».")

    def fetch(self, credential: str, http, cache: dict) -> list[Window]:
        headers = {
            "Accept": "application/json",
            "Cookie": cookie_header(credential, "WorkosCursorSessionToken"),
            "Referer": "https://cursor.com/dashboard",
        }
        _, text = request(http, URL, headers)
        return parse(parse_json(text))
