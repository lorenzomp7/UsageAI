"""ChatGPT / Codex: limiti 5 ore e settimanali di Codex (chatgpt.com/backend-api/wham/usage).

Due modi per autenticarsi:
- automatico: il file auth.json della CLI di Codex (%USERPROFILE%\\.codex\\auth.json), se c'è;
- manuale: l'header Cookie di chatgpt.com, da cui ricavo il token tramite /api/auth/session.
La chat normale di ChatGPT non ha un contatore di consumo: qui ci sono solo i limiti di Codex.
"""
from __future__ import annotations

import json
import os
from datetime import timedelta
from pathlib import Path

from ..core import Window, window_label
from .base import ApiError, AuthError, Provider, cookie_header, from_epoch, number, parse_json, request

USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
SESSION_URL = "https://chatgpt.com/api/auth/session"
AUTO = "auto:codex-cli"


def parse(data: dict) -> list[Window]:
    out: list[Window] = []
    rl = data.get("rate_limit") if isinstance(data, dict) else None
    if not isinstance(rl, dict):
        return out
    for slot in ("primary_window", "secondary_window"):
        w = rl.get(slot)
        if not isinstance(w, dict):
            continue
        used = number(w.get("used_percent"))
        if used is None:
            continue
        secs = number(w.get("limit_window_seconds"))
        length = timedelta(seconds=secs) if secs else (
            timedelta(hours=5) if slot == "primary_window" else timedelta(days=7))
        out.append(Window("codex", f"codex:{slot}", window_label(length), max(0.0, used),
                          from_epoch(w.get("reset_at")), length))
    return out


def codex_auth_file() -> Path:
    home = os.environ.get("CODEX_HOME")
    return Path(home) / "auth.json" if home else Path.home() / ".codex" / "auth.json"


class CodexProvider(Provider):
    id = "codex"
    name = "ChatGPT/Codex"
    credential_label = "Cookie di chatgpt.com"
    help = ("Se usi la CLI di Codex su questo PC non serve niente: leggo il suo login da solo. "
            "Altrimenti apri chatgpt.com in Chrome o Edge → F12 → Network → ricarica → clicca una "
            "richiesta a chatgpt.com → Request Headers → copia tutto il valore di «cookie».")

    def auto_credential(self) -> str | None:
        return AUTO if codex_auth_file().is_file() else None

    def _token_from_cli(self) -> tuple[str, str | None]:
        try:
            data = json.loads(codex_auth_file().read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            raise ApiError(f"Non riesco a leggere il login di Codex: {e}") from e
        tokens = data.get("tokens") or {}
        token = tokens.get("access_token") or tokens.get("accessToken")
        if not token:
            raise AuthError("Il login della CLI di Codex non contiene un token: esegui «codex login».")
        return token, tokens.get("account_id") or tokens.get("accountId")

    def fetch(self, credential: str, http, cache: dict) -> list[Window]:
        headers = {"Accept": "application/json"}
        if credential == AUTO:
            token, account = self._token_from_cli()
            if account:
                headers["ChatGPT-Account-Id"] = account
        else:
            cookies = cookie_header(credential, "__Secure-next-auth.session-token")
            headers["Cookie"] = cookies
            _, text = request(http, SESSION_URL, {"Accept": "application/json", "Cookie": cookies})
            session = parse_json(text)
            token = session.get("accessToken") if isinstance(session, dict) else None
            if not token:
                raise AuthError("Sessione di chatgpt.com scaduta: copia di nuovo i cookie.")
        headers["Authorization"] = f"Bearer {token}"
        try:
            _, text = request(http, USAGE_URL, headers)
        except AuthError as e:
            if credential == AUTO:
                raise AuthError("Il login di Codex è scaduto: apri Codex o esegui «codex login».") from e
            raise
        windows = parse(parse_json(text))
        if not windows:
            raise ApiError("Nessun limite di Codex per questo account (serve un piano Plus o superiore).")
        return windows
