"""ChatGPT / Codex: limiti di Codex (chatgpt.com/backend-api/wham/usage).

Tre modi per leggere i limiti:
- se la CLI di Codex è installata: «codex app-server» e la richiesta JSON-RPC
  account/rateLimits/read, così il token resta alla CLI (che lo rinnova da sola);
- altrimenti il file auth.json della CLI (%USERPROFILE%\\.codex\\auth.json), se c'è;
- manuale: l'header Cookie di chatgpt.com, da cui ricavo il token tramite /api/auth/session.
La chat normale di ChatGPT non ha un contatore di consumo: qui ci sono solo i limiti di Codex.

Il formato della risposta (rate_limit, additional_rate_limits, credits, spend_control,
rate_limit_reached_type, rate_limit_reset_credits) e il protocollo di app-server vengono dal
codice di Codex CLI (github.com/openai/codex, licenza Apache-2.0): modelli
codex-backend-openapi-models e test app-server/tests/suite/v2/rate_limits.rs. Niente codice
copiato: qui c'è solo la lettura dei campi.
"""
from __future__ import annotations

import json
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta
from pathlib import Path

from ..core import Window, window_label
from .base import ApiError, AuthError, Provider, cookie_header, from_epoch, number, parse_json, request

USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
SESSION_URL = "https://chatgpt.com/api/auth/session"
AUTO = "auto:codex-cli"
APP_SERVER_TIMEOUT = 20.0

# Perché Codex si è fermato (campo rate_limit_reached_type).
REACHED_REASONS = {
    "rate_limit_reached": "limite d'uso raggiunto",
    "workspace_owner_credits_depleted": "crediti del workspace esauriti",
    "workspace_member_credits_depleted": "crediti del workspace esauriti (chiedi al proprietario)",
    "workspace_owner_usage_limit_reached": "limite di spesa del workspace raggiunto",
    "workspace_member_usage_limit_reached": "il tuo limite di spesa nel workspace è raggiunto",
}


def _limit_title(name: str) -> str:
    """«codex_other» → «Codex other»."""
    t = name.replace("_", " ").replace("-", " ").strip()
    return t[:1].upper() + t[1:] if t else "Limite aggiuntivo"


def _amount(text) -> str:
    n = number(text)
    if n is None:
        return str(text)
    return f"{n:.2f}".rstrip("0").rstrip(".").replace(".", ",")


def _date(dt: datetime | None) -> str:
    return dt.astimezone().strftime("%d/%m") if dt else ""


def _windows_from_rate_limit(rl: dict, key_prefix: str, title: str | None) -> list[Window]:
    """primary_window / secondary_window di un blocco rate_limit."""
    out: list[Window] = []
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
        label = window_label(length) if title is None else f"{title} · {window_label(length)}"
        key = f"{key_prefix}{slot}" if title is None else f"{key_prefix}{slot.split('_')[0]}"
        out.append(Window("codex", key, label, max(0.0, used), from_epoch(w.get("reset_at")), length))
    return out


def _reached_note(reason: str | None) -> Window | None:
    if not reason:
        return None
    text = REACHED_REASONS.get(reason, reason.replace("_", " "))
    return Window("codex", "codex:reached", "Limite raggiunto", 100.0, None, None, note=text)


def _credits_note(has_credits, unlimited, balance) -> Window | None:
    if unlimited is True:
        return Window("codex", "codex:credits", "Crediti ChatGPT", 0.0, None, None, note="illimitati")
    if has_credits is True and balance is not None and str(balance).strip():
        return Window("codex", "codex:credits", "Crediti ChatGPT", 0.0, None, None,
                      note=f"{_amount(balance)} rimasti")
    return None


def _resets_note(count, first_expiry: datetime | None = None) -> Window | None:
    n = number(count)
    if not n or n <= 0:
        return None
    text = f"{int(n)}" + (f" (il primo scade il {_date(first_expiry)})" if first_expiry else "")
    return Window("codex", "codex:resets", "Reset dei limiti disponibili", 0.0, None, None, note=text)


def parse(data: dict) -> list[Window]:
    """Risposta di wham/usage (RateLimitStatusPayload)."""
    out: list[Window] = []
    if not isinstance(data, dict):
        return out
    rl = data.get("rate_limit")
    reached = False
    if isinstance(rl, dict):
        out += _windows_from_rate_limit(rl, "codex:", None)
        reached = rl.get("limit_reached") is True

    for extra in data.get("additional_rate_limits") or []:
        if not isinstance(extra, dict) or not isinstance(extra.get("rate_limit"), dict):
            continue
        name = str(extra.get("limit_name") or extra.get("metered_feature") or "aggiuntivo")
        out += _windows_from_rate_limit(extra["rate_limit"], f"codex:extra:{name}:", _limit_title(name))
        reached = reached or extra["rate_limit"].get("limit_reached") is True

    spend = data.get("spend_control")
    lim = spend.get("individual_limit") if isinstance(spend, dict) else None
    if isinstance(lim, dict) and number(lim.get("used_percent")) is not None:
        out.append(Window("codex", "codex:spend",
                          f"Spesa del workspace ({_amount(lim.get('used'))} su {_amount(lim.get('limit'))})",
                          max(0.0, number(lim.get("used_percent"))), from_epoch(lim.get("reset_at")), None))
        reached = reached or spend.get("reached") is True

    if not out:
        return out   # nessun limite: niente righe informative da sole

    c = data.get("credits")
    if isinstance(c, dict):
        n = _credits_note(c.get("has_credits"), c.get("unlimited"), c.get("balance"))
        if n:
            out.append(n)
    rr = data.get("rate_limit_reset_credits")
    if isinstance(rr, dict):
        n = _resets_note(rr.get("available_count"))
        if n:
            out.append(n)
    rt = data.get("rate_limit_reached_type")
    if reached and isinstance(rt, dict):
        n = _reached_note(rt.get("type"))
        if n:
            out.append(n)
    return out


def parse_app_server(result: dict) -> list[Window]:
    """Risposta di app-server ad account/rateLimits/read (GetAccountRateLimitsResponse)."""
    if not isinstance(result, dict):
        return []
    snapshots = result.get("rateLimitsByLimitId")
    if not isinstance(snapshots, dict) or not snapshots:
        main = result.get("rateLimits")
        snapshots = {"codex": main} if isinstance(main, dict) else {}
    out: list[Window] = []
    notes: list[Window] = []
    reached = False
    main_reason = None
    order = sorted(snapshots, key=lambda k: (k != "codex", k))
    for limit_id in order:
        snap = snapshots[limit_id]
        if not isinstance(snap, dict):
            continue
        is_main = limit_id == "codex"
        name = snap.get("limitName") or limit_id
        for slot, legacy in (("primary", "primary_window"), ("secondary", "secondary_window")):
            w = snap.get(slot)
            if not isinstance(w, dict) or number(w.get("usedPercent")) is None:
                continue
            mins = number(w.get("windowDurationMins"))
            length = timedelta(minutes=mins) if mins else (
                timedelta(hours=5) if slot == "primary" else timedelta(days=7))
            if is_main:
                key, label = f"codex:{legacy}", window_label(length)
            else:
                key, label = f"codex:extra:{limit_id}:{slot}", f"{_limit_title(str(name))} · {window_label(length)}"
            used = max(0.0, number(w.get("usedPercent")))
            reached = reached or used >= 100
            out.append(Window("codex", key, label, used, from_epoch(w.get("resetsAt")), length))
        lim = snap.get("individualLimit")
        if is_main and isinstance(lim, dict) and number(lim.get("remainingPercent")) is not None:
            used = max(0.0, 100.0 - number(lim.get("remainingPercent")))
            out.append(Window("codex", "codex:spend",
                              f"Spesa del workspace ({_amount(lim.get('used'))} su {_amount(lim.get('limit'))})",
                              used, from_epoch(lim.get("resetsAt")), None))
        c = snap.get("credits")
        if is_main and isinstance(c, dict):
            n = _credits_note(c.get("hasCredits"), c.get("unlimited"), c.get("balance"))
            if n:
                notes.append(n)
        if is_main:
            main_reason = snap.get("rateLimitReachedType")
    if not out:
        return out
    rr = result.get("rateLimitResetCredits")
    if isinstance(rr, dict):
        expiries = [from_epoch(c.get("expiresAt")) for c in (rr.get("credits") or [])
                    if isinstance(c, dict) and c.get("status") in (None, "available")]
        expiries = sorted(e for e in expiries if e)
        n = _resets_note(rr.get("availableCount"), expiries[0] if expiries else None)
        if n:
            notes.append(n)
    if reached and isinstance(main_reason, str):
        n = _reached_note(main_reason)
        if n:
            notes.append(n)
    return out + notes


# ---------------- codex app-server (JSON-RPC su stdin/stdout) ----------------

def codex_executable() -> str | None:
    return shutil.which("codex") or shutil.which("codex.cmd") or shutil.which("codex.exe")


def read_with_app_server(exe: str, timeout: float = APP_SERVER_TIMEOUT, popen=subprocess.Popen) -> dict:
    """Avvia «codex app-server», fa l'handshake e chiede account/rateLimits/read.

    Un messaggio JSON per riga, come nel test di Codex (test_app_server.rs).
    """
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if sys.platform == "win32" else 0
    try:
        proc = popen([exe, "app-server"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     stderr=subprocess.DEVNULL, text=True, encoding="utf-8", creationflags=flags)
    except OSError as e:
        raise ApiError(f"Non riesco ad avviare Codex: {e}") from e
    lines: "queue.Queue[str | None]" = queue.Queue()

    def pump():
        try:
            for line in proc.stdout:
                lines.put(line)
        finally:
            lines.put(None)

    threading.Thread(target=pump, daemon=True).start()
    deadline = time.monotonic() + timeout

    def send(msg: dict):
        proc.stdin.write(json.dumps(msg) + "\n")
        proc.stdin.flush()

    def wait_for(request_id: int) -> dict:
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                raise ApiError("Codex app-server non ha risposto in tempo.")
            try:
                line = lines.get(timeout=left)
            except queue.Empty:
                continue
            if line is None:
                raise ApiError("Codex app-server si è chiuso senza rispondere.")
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if not isinstance(msg, dict) or msg.get("id") != request_id:
                continue   # notifiche o altre risposte
            if "error" in msg:
                err = msg["error"] if isinstance(msg["error"], dict) else {}
                text = str(err.get("message") or msg["error"])
                low = text.lower()
                if any(k in low for k in ("auth", "login", "token", "sign in")):
                    raise AuthError("Il login di Codex è scaduto: apri Codex o esegui «codex login».")
                raise ApiError(f"Codex app-server: {text}")
            return msg.get("result") or {}

    try:
        send({"id": 0, "method": "initialize",
              "params": {"clientInfo": {"name": "usageai", "title": "UsageAI", "version": "2.4"}}})
        wait_for(0)
        send({"method": "initialized"})
        send({"id": 1, "method": "account/rateLimits/read"})
        return wait_for(1)
    except (BrokenPipeError, OSError) as e:
        raise ApiError(f"Codex app-server non raggiungibile: {e}") from e
    finally:
        try:
            proc.stdin.close()
        except Exception:
            pass
        try:
            proc.terminate()
            proc.wait(timeout=3)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass


def codex_auth_file() -> Path:
    home = os.environ.get("CODEX_HOME")
    return Path(home) / "auth.json" if home else Path.home() / ".codex" / "auth.json"


class CodexProvider(Provider):
    id = "codex"
    name = "ChatGPT/Codex"
    credential_label = "Cookie di chatgpt.com"
    help = ("Se usi la CLI di Codex su questo PC non serve niente: chiedo i limiti a Codex stessa "
            "(o leggo il suo login). Altrimenti apri chatgpt.com in Chrome o Edge → F12 → Network → "
            "ricarica → clicca una richiesta a chatgpt.com → Request Headers → copia tutto il valore di «cookie».")

    def __init__(self, find_exe=None, app_server=None):
        # Funzioni sostituibili nei test; di default quelle del modulo (lette a ogni chiamata).
        self._find_exe = find_exe
        self._app_server = app_server

    def _exe(self) -> str | None:
        return (self._find_exe or codex_executable)()

    def auto_credential(self) -> str | None:
        return AUTO if (codex_auth_file().is_file() or self._exe()) else None

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
        if credential == AUTO:
            exe = self._exe()
            if exe:
                try:
                    windows = parse_app_server((self._app_server or read_with_app_server)(exe))
                    if windows:
                        cache["source"] = "app-server"
                        return windows
                except ApiError:
                    if not codex_auth_file().is_file():
                        raise
                    # app-server vecchio o non avviabile: provo con auth.json
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
        cache["source"] = "web"
        return windows

