"""Mistral: budget inclusi nell'abbonamento (API e Vibe), letti dalla pagina admin.mistral.ai/subscription.

La pagina è un'app Next.js: i dati stanno nei blocchi «self.__next_f.push([1, "..."])».
"""
from __future__ import annotations

import json
import re

from ..core import Window, month_before, parse_datetime
from .base import ApiError, AuthError, Provider, cookie_header, number

URL = "https://admin.mistral.ai/subscription"
_DECODER = json.JSONDecoder()
_PUSH = re.compile(r"self\.__next_f\.push\(\s*\[")
_BUDGETS = (("api_budget", "Budget API incluso"), ("vibe_budget", "Vibe (piano mensile)"))


def _flight_stream(html: str) -> str:
    chunks = []
    for m in _PUSH.finditer(html):
        start = m.end() - 1
        try:
            arr, _ = _DECODER.raw_decode(html, start)
        except ValueError:
            continue
        if isinstance(arr, list) and len(arr) >= 2 and arr[0] == 1 and isinstance(arr[1], str):
            chunks.append(arr[1])
    return "".join(chunks)


def _find_object(text: str, key: str) -> dict | None:
    for m in re.finditer(r'"%s"\s*:\s*\{' % re.escape(key), text):
        try:
            obj, _ = _DECODER.raw_decode(text, m.end() - 1)
        except ValueError:
            continue
        if isinstance(obj, dict) and number(obj.get("usage_percentage")) is not None:
            return obj
    return None


def parse_html(html: str) -> list[Window]:
    out: list[Window] = []
    for source in (_flight_stream(html), html):
        for key, label in _BUDGETS:
            if any(w.key == f"mistral:{key}" for w in out):
                continue
            b = _find_object(source, key)
            if not b:
                continue
            pct = number(b.get("usage_percentage"))
            limit = number(b.get("initial_budget"))
            if pct is None or (limit is not None and limit <= 0):
                continue
            reset = parse_datetime(b.get("reset_at"))
            length = reset - month_before(reset) if reset else None
            currency = str(b.get("currency") or "").upper()
            text = f"{label} ({limit:.0f} {currency})".replace(" )", ")") if limit else label
            out.append(Window("mistral", f"mistral:{key}", text, max(0.0, min(100.0, pct)), reset, length))
        if out:
            break
    if not out:
        low = html.lower()
        if "auth.mistral.ai" in low and ("login" in low or "sign in" in low):
            raise AuthError("Sessione di Mistral scaduta: copia di nuovo i cookie.")
        raise ApiError("La pagina abbonamento di Mistral non mostra budget inclusi (o è cambiata).")
    return out


class MistralProvider(Provider):
    id = "mistral"
    name = "Mistral"
    credential_label = "Cookie di admin.mistral.ai"
    help = ("Apri admin.mistral.ai/subscription in Chrome o Edge, già loggato → F12 → Network → ricarica → "
            "clicca la richiesta «subscription» → Request Headers → copia tutto il valore di «cookie» "
            "(deve contenere un cookie che inizia con «ory_session_»).")

    def fetch(self, credential: str, http, cache: dict) -> list[Window]:
        headers = {"Accept": "text/html", "Cookie": cookie_header(credential, "ory_session")}
        try:
            r = http.get(URL, headers=headers, timeout=20)
        except Exception as e:
            raise ApiError(f"Rete non raggiungibile: {e}") from e
        status = getattr(r, "status_code", 0)
        final_url = str(getattr(r, "url", "") or "")
        if status in (401, 403) or "auth.mistral.ai" in final_url:
            raise AuthError("Sessione di Mistral scaduta: copia di nuovo i cookie.")
        if status != 200:
            raise ApiError(f"Risposta inattesa da Mistral (HTTP {status}).", status)
        return parse_html(getattr(r, "text", "") or "")
