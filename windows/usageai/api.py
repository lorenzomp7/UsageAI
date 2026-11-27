"""Chiamate a claude.ai: lista organizzazioni e consumo.

Usa curl_cffi con l'impronta TLS di Chrome, così Cloudflare tratta la richiesta
come quella di un browser normale. L'endpoint non è documentato da Anthropic:
se cambia, va aggiornato qui.
"""
from __future__ import annotations

from .core import pick_org_id

BASE = "https://claude.ai/api"


class AuthError(Exception):
    """sessionKey scaduto o non valido: va rifatto il login."""


class ApiError(Exception):
    """Qualsiasi altro problema (rete, Cloudflare, risposta inattesa)."""


class ClaudeClient:
    def __init__(self, session_key: str, org_id: str | None = None, session=None):
        if session is None:
            from curl_cffi import requests as creq  # import tardivo: i test non ne hanno bisogno
            session = creq.Session(impersonate="chrome")
        self._s = session
        self._key = session_key.strip()
        self.org_id = org_id

    def _get(self, path: str):
        try:
            r = self._s.get(
                BASE + path,
                headers={
                    "Accept": "application/json",
                    "Referer": "https://claude.ai/settings/usage",
                    "Cookie": f"sessionKey={self._key}",
                },
                timeout=20,
            )
        except Exception as e:  # errori di rete di curl_cffi
            raise ApiError(f"Rete non raggiungibile: {e}") from e

        status = getattr(r, "status_code", 0)
        body = None
        try:
            body = r.json()
        except Exception:
            body = None

        if status in (401, 403):
            err_type = ""
            if isinstance(body, dict):
                err = body.get("error")
                err_type = (err.get("type") if isinstance(err, dict) else str(err or "")) or ""
            if body is not None and ("auth" in err_type or "permission" in err_type or status == 401):
                raise AuthError("Sessione scaduta: incolla un nuovo sessionKey nelle impostazioni.")
            raise ApiError(f"Richiesta bloccata (HTTP {status}). Riprovo al prossimo controllo.")
        if status != 200 or body is None:
            raise ApiError(f"Risposta inattesa da claude.ai (HTTP {status}).")
        return body

    def resolve_org(self) -> str:
        if not self.org_id:
            org = pick_org_id(self._get("/organizations"))
            if not org:
                raise ApiError("Nessuna organizzazione trovata per questo account.")
            self.org_id = org
        return self.org_id

    def usage(self) -> dict:
        org = self.resolve_org()
        try:
            return self._get(f"/organizations/{org}/usage")
        except ApiError:
            # L'organizzazione salvata potrebbe non valere più: la ricalcolo una volta.
            self.org_id = None
            org = self.resolve_org()
            return self._get(f"/organizations/{org}/usage")
