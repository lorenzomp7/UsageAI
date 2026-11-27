"""Pezzi comuni ai servizi: errori, risposta HTTP, utilità di parsing."""
from __future__ import annotations

import json
from datetime import datetime, timezone


class AuthError(Exception):
    """Credenziale scaduta o non valida: va rifatto l'accesso."""


class ApiError(Exception):
    """Qualsiasi altro problema (rete, Cloudflare, risposta cambiata)."""

    def __init__(self, message: str, status: int = 0, body: str = ""):
        super().__init__(message)
        self.status = status
        self.body = body


class Provider:
    id: str = ""
    name: str = ""
    credential_label: str = ""
    help: str = ""

    def auto_credential(self) -> str | None:
        """Credenziale trovata da sola sul PC (es. login di una CLI), se esiste."""
        return None

    def fetch(self, credential: str, http, cache: dict) -> list:
        raise NotImplementedError


# Funzioni chiamate con (url, testo) a ogni risposta 200 (es. il salvataggio delle risposte nuove).
OSSERVATORI: list = []


def request(http, url: str, headers: dict, auth_codes=(401, 403)):
    """GET con gestione errori comune. Restituisce (status, testo)."""
    try:
        r = http.get(url, headers=headers, timeout=20)
    except Exception as e:
        raise ApiError(f"Rete non raggiungibile: {e}") from e
    status = getattr(r, "status_code", 0)
    text = getattr(r, "text", "") or ""
    if status in auth_codes:
        raise AuthError("Accesso scaduto o non valido: aggiorna la credenziale nelle impostazioni.")
    if status == 429:
        raise ApiError("Troppe richieste (HTTP 429): riprovo al prossimo controllo.", status, text)
    if status >= 500:
        raise ApiError(f"Servizio non disponibile (HTTP {status}).", status, text)
    if status != 200:
        raise ApiError(f"Risposta inattesa (HTTP {status}).", status, text)
    for osserva in list(OSSERVATORI):
        try:
            osserva(url, text)
        except Exception:   # un problema di salvataggio non deve mai fermare il controllo
            pass
    return status, text


def parse_json(text: str):
    t = text.strip()
    if t.startswith("<"):
        raise ApiError("Il servizio ha risposto con una pagina web invece che con i dati "
                       "(probabile blocco anti-bot o accesso scaduto).")
    try:
        return json.loads(t)
    except ValueError as e:
        raise ApiError("Risposta non leggibile: il servizio potrebbe aver cambiato formato.") from e


def number(value) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return None
    return None


def from_epoch(value) -> datetime | None:
    n = number(value)
    if n is None or n <= 0:
        return None
    if n > 1e12:          # millisecondi
        n /= 1000
    return datetime.fromtimestamp(n, tz=timezone.utc)


def cookie_header(credential: str, default_name: str) -> str:
    """Accetta un header Cookie completo oppure il solo valore del cookie principale."""
    c = credential.strip()
    if c.lower().startswith("cookie:"):
        c = c[7:].strip()
    return c if "=" in c else f"{default_name}={c}"
