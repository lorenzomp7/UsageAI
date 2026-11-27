"""Grok (SuperGrok): crediti del periodo (cli-chat-proxy.grok.com/v1/billing).

Usa il login della CLI ufficiale di Grok (%USERPROFILE%\\.grok\\auth.json), letto da solo se c'è,
oppure il token incollato a mano (il valore «key» dello stesso file).
"""
from __future__ import annotations

import json
import os
from pathlib import Path

from ..core import Window, parse_datetime, window_label
from .base import ApiError, AuthError, Provider, number, parse_json, request

URL = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"
AUTO = "auto:grok-cli"


def _amount(v):
    return number(v.get("val")) if isinstance(v, dict) else number(v)


def parse(data: dict) -> list[Window]:
    config = data.get("config") if isinstance(data, dict) else None
    if not isinstance(config, dict):
        raise ApiError("Risposta di Grok cambiata.")
    percent = number(config.get("creditUsagePercent"))
    if percent is None:
        cap, used = _amount(config.get("onDemandCap")), _amount(config.get("onDemandUsed"))
        if cap and used is not None:
            percent = used / cap * 100
    period = config.get("currentPeriod") if isinstance(config.get("currentPeriod"), dict) else {}
    end = parse_datetime(period.get("end"))
    start = parse_datetime(period.get("start")) if end else parse_datetime(config.get("billingPeriodStart"))
    if end is None:
        end = parse_datetime(config.get("billingPeriodEnd"))
    length = (end - start) if start and end and end > start else None
    if percent is None:
        raise ApiError("Grok non riporta la percentuale di consumo per questo periodo.")
    return [Window("grok", "grok:credits", f"Crediti · {window_label(length)}" if length else "Crediti",
                   max(0.0, min(100.0, percent)), end, length)]


def grok_auth_file() -> Path:
    home = os.environ.get("GROK_HOME")
    return Path(home) / "auth.json" if home else Path.home() / ".grok" / "auth.json"


def token_from_auth_file(path: Path) -> str | None:
    try:
        root = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(root, dict):
        return None
    oidc = legacy = None
    for scope, entry in root.items():
        if not isinstance(entry, dict) or not entry.get("key"):
            continue
        if scope.startswith("https://auth.x.ai::"):
            oidc = entry["key"]
        elif "/sign-in" in scope:
            legacy = entry["key"]
    return oidc or legacy


class GrokProvider(Provider):
    id = "grok"
    name = "Grok"
    credential_label = "Token della CLI di Grok"
    help = ("Se usi la CLI ufficiale di Grok su questo PC («grok login») non serve niente: leggo il suo login "
            "da solo. Altrimenti apri %USERPROFILE%\\.grok\\auth.json e incolla il valore di «key».")

    def auto_credential(self) -> str | None:
        return AUTO if token_from_auth_file(grok_auth_file()) else None

    def fetch(self, credential: str, http, cache: dict) -> list[Window]:
        token = token_from_auth_file(grok_auth_file()) if credential == AUTO else credential.strip()
        if not token:
            raise AuthError("Il login della CLI di Grok non contiene un token: esegui «grok login».")
        headers = {
            "Authorization": f"Bearer {token}",
            "x-xai-token-auth": "xai-grok-cli",
            "Accept": "application/json",
        }
        try:
            _, text = request(http, URL, headers)
        except AuthError as e:
            raise AuthError("Il login di Grok è scaduto: esegui «grok login».") from e
        return parse(parse_json(text))
