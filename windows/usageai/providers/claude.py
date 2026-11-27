"""Claude (claude.ai): stesso endpoint della pagina Impostazioni > Utilizzo."""
from __future__ import annotations

from datetime import timedelta

from ..core import Window, parse_datetime
from .base import ApiError, AuthError, Provider, cookie_header, parse_json, request

BASE = "https://claude.ai/api"


def parse(data: dict) -> list[Window]:
    out: list[Window] = []
    if not isinstance(data, dict):
        return out
    for key, value in data.items():
        if key != "five_hour" and not key.startswith("seven_day"):
            continue
        if not isinstance(value, dict):
            continue
        util = value.get("utilization")
        if not isinstance(util, (int, float)) or isinstance(util, bool):
            continue
        if key == "five_hour":
            label, length = "Sessione 5 ore", timedelta(hours=5)
        elif key == "seven_day":
            label, length = "Settimana", timedelta(days=7)
        else:
            name = key[len("seven_day_"):].replace("_", " ").strip().title()
            label, length = f"Settimana {name}", timedelta(days=7)
        out.append(Window("claude", f"claude:{key}", label, max(0.0, float(util)),
                          parse_datetime(value.get("resets_at")), length))
    order = {"claude:five_hour": 0, "claude:seven_day": 1}
    out.sort(key=lambda w: (order.get(w.key, 2), w.key))
    return out


def pick_org_id(orgs) -> str | None:
    """Sceglie l'organizzazione con l'abbonamento Claude (Pro/Max)."""
    if not isinstance(orgs, list):
        return None
    candidates = [o for o in orgs if isinstance(o, dict) and o.get("uuid")]
    for o in candidates:
        if any(isinstance(c, str) and c.startswith("claude_") for c in (o.get("capabilities") or [])):
            return o["uuid"]
    for o in candidates:
        if "chat" in (o.get("capabilities") or []):
            return o["uuid"]
    return candidates[0]["uuid"] if candidates else None


class ClaudeProvider(Provider):
    id = "claude"
    name = "Claude"
    credential_label = "sessionKey"
    help = ("Apri claude.ai in Chrome o Edge → F12 → Application → Cookies → https://claude.ai "
            "→ copia il valore di «sessionKey» (inizia con sk-ant-sid).")

    def _get(self, http, path: str, credential: str):
        headers = {
            "Accept": "application/json",
            "Referer": "https://claude.ai/settings/usage",
            "Cookie": cookie_header(credential, "sessionKey"),
        }
        try:
            _, text = request(http, BASE + path, headers, auth_codes=(401,))
        except ApiError as e:
            if e.status != 403:
                raise
            # 403 può essere sessione non valida (JSON con errore) o blocco di Cloudflare (HTML).
            low = e.body.lower()
            if e.body.strip().startswith("{") and any(k in low for k in ("auth", "session")):
                raise AuthError("Sessione di claude.ai scaduta: copia di nuovo il sessionKey.") from e
            raise ApiError("Richiesta bloccata da claude.ai (HTTP 403): riprovo al prossimo controllo.",
                           403, e.body) from e
        return parse_json(text)

    def fetch(self, credential: str, http, cache: dict) -> list[Window]:
        org = cache.get("org_id")
        if org:
            try:
                return parse(self._get(http, f"/organizations/{org}/usage", credential))
            except (ApiError, AuthError):
                pass   # organizzazione cambiata: la ricalcolo
        org = pick_org_id(self._get(http, "/organizations", credential))
        if not org:
            raise ApiError("Nessuna organizzazione trovata per questo account.")
        cache["org_id"] = org
        return parse(self._get(http, f"/organizations/{org}/usage", credential))
