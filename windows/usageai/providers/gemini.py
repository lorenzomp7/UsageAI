"""Gemini (app gemini.google.com): limiti 5 ore e settimanali del piano Google AI.

Usa la stessa richiesta della pagina gemini.google.com/usage. È la più fragile: Google può
cambiare la firma della query in qualsiasi momento, e i suoi cookie scadono spesso.
"""
from __future__ import annotations

import json
from datetime import timedelta
from urllib.parse import quote

from ..core import Window, parse_datetime
from .base import ApiError, AuthError, Provider, cookie_header, number, request

SIGNATURE = "2/dWbCvkVB0X16r9NPnxifeOAAqdCqMf0xeWvau9yPy7g="
VARIABLES = json.dumps({
    "g1Request": {"entrypoints": ["GEMINI_USAGE_SETTINGS_UPGRADE_BANNER"]},
    "workspaceRequest": {"recommendationsForSurfaceRequests": []},
}, separators=(",", ":"))
URL = ("https://gemini.google.com/_/BardChatUi/graphql/schemas/GEMINI_WEB_GRAPHQL/executeQuery"
       f"?query_signature={quote(SIGNATURE, safe='')}&variables={quote(VARIABLES, safe='')}")

POOLS = {
    "GXU_FIVE_HOURLY": ("Sessione 5 ore", timedelta(hours=5)),
    "GXU_WEEKLY": ("Settimana", timedelta(days=7)),
}


def parse(text: str) -> list[Window]:
    t = text.strip()
    if t.startswith(")]}'"):
        t = t[4:].strip()
    if t.startswith("<"):
        raise AuthError("Accesso a Gemini scaduto: copia di nuovo i cookie.")
    try:
        results = json.loads(t)
    except ValueError as e:
        raise ApiError("Risposta di Gemini non leggibile: Google potrebbe averla cambiata.") from e
    if isinstance(results, dict):
        results = [results]
    if not isinstance(results, list):
        raise ApiError("Risposta di Gemini cambiata.")
    data = next((r.get("data") for r in results
                 if isinstance(r, dict) and isinstance(r.get("data"), dict) and "viewer" in r["data"]), None)
    if data is None:
        raise ApiError("Risposta di Gemini cambiata.")
    viewer = data.get("viewer")
    if viewer is None:
        raise AuthError("Accesso a Gemini scaduto: copia di nuovo i cookie.")
    usage = ((viewer.get("rpcs") or {}).get("getUsageInfo") or {}) if isinstance(viewer, dict) else {}
    pools = usage.get("pools") if isinstance(usage, dict) else None
    if not isinstance(pools, list):
        raise ApiError("Risposta di Gemini cambiata.")
    out: list[Window] = []
    for pool_type, (label, length) in POOLS.items():
        pool = next((p for p in pools if isinstance(p, dict) and p.get("poolType") == pool_type), None)
        if not pool:
            continue
        frac = number(pool.get("percentageUsed"))
        if frac is None or frac < 0:
            continue
        reset = parse_datetime((pool.get("gxu") or {}).get("windowRefreshesAt"))
        out.append(Window("gemini", f"gemini:{pool_type.lower()}", label,
                          min(100.0, frac * 100.0), reset, length))
    if not out:
        raise ApiError("Questo account Google non riporta limiti dell'app Gemini.")
    return out


class GeminiProvider(Provider):
    id = "gemini"
    name = "Gemini"
    credential_label = "Cookie di gemini.google.com"
    help = ("Apri gemini.google.com/usage in Chrome o Edge → F12 → Network → ricarica → clicca la "
            "richiesta «usage» → Request Headers → copia tutto il valore di «cookie». "
            "Google rinnova spesso questi cookie: se l'app segnala l'accesso scaduto, ricopiali.")

    def fetch(self, credential: str, http, cache: dict) -> list[Window]:
        headers = {
            "Accept": "application/json",
            "Referer": "https://gemini.google.com/usage",
            "Cookie": cookie_header(credential, "__Secure-1PSID"),
        }
        _, text = request(http, URL, headers)
        return parse(text)
