"""Claude (claude.ai): stesso endpoint della pagina Impostazioni > Utilizzo.

Formato della risposta:
- elenco «limits» (session, weekly_all, weekly_scoped con il nome del modello, es. Fable) come fonte
  principale, chiavi piatte (five_hour, seven_day…) per ciò che manca: dal parser e dai test di
  Usage Limits Mobile (github.com/ZepiGit/Usage-Limit-Viewer-Mobile, MIT);
- «extra_usage» (spesa extra del mese, in centesimi) e «cedar_ember» (reset dei limiti, chiesti con
  ?cedar_ember=1): dalla documentazione di CodexBar (github.com/steipete/CodexBar, MIT).
- «pace» (previsione di Claude): visto nell'app Android di Claude, formato non documentato, quindi
  letto in modo prudente: se non c'è o è diverso, non compare niente.
"""
from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone

from ..core import Window, parse_datetime
from .base import ApiError, AuthError, Provider, cookie_header, number, parse_json, request

BASE = "https://claude.ai/api"
FIVE_HOURS, WEEK = timedelta(hours=5), timedelta(days=7)
MAX_RESETS, MAX_GRANTS = 50, 200


def _slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_") or "modello"


def _title(key: str) -> str:
    return key.replace("_", " ").strip().title()


def _util(value) -> float | None:
    """Percentuale da un numero o da una stringa numerica; i booleani no."""
    if isinstance(value, bool):
        return None
    return number(value)


def _flat_windows(data: dict) -> dict[str, Window]:
    out: dict[str, Window] = {}
    for key, value in data.items():
        if key != "five_hour" and not key.startswith("seven_day") and key != "iguana_necktie":
            continue
        if not isinstance(value, dict):
            continue
        util = value.get("utilization")
        if not isinstance(util, (int, float)) or isinstance(util, bool):
            continue
        reset = parse_datetime(value.get("resets_at") or value.get("resetsAt"))
        if key == "five_hour":
            w = Window("claude", "claude:five_hour", "Sessione 5 ore", max(0.0, float(util)), reset, FIVE_HOURS)
        elif key == "seven_day":
            w = Window("claude", "claude:seven_day", "Settimana", max(0.0, float(util)), reset, WEEK)
        elif key == "iguana_necktie":   # nome in codice del limite settimanale di Fable
            w = Window("claude", "claude:seven_day_fable", "Settimana Fable", max(0.0, float(util)), reset, WEEK)
        else:
            name = key[len("seven_day_"):]
            w = Window("claude", f"claude:{key}", f"Settimana {_title(name)}", max(0.0, float(util)), reset, WEEK)
        out[w.key] = w
    return out


def _limits_windows(data: dict) -> dict[str, Window]:
    out: dict[str, Window] = {}
    for item in data.get("limits") or []:
        if not isinstance(item, dict):
            continue
        pct = _util(item.get("percent"))
        if pct is None:
            continue
        kind = str(item.get("kind") or "")
        group = str(item.get("group") or "")
        reset = parse_datetime(item.get("resets_at") or item.get("resetsAt"))
        model = ((item.get("scope") or {}).get("model") or {}) if isinstance(item.get("scope"), dict) else {}
        model_name = model.get("display_name") if isinstance(model, dict) else None
        if kind == "session":
            key, label, length = "claude:five_hour", "Sessione 5 ore", FIVE_HOURS
        elif kind == "weekly_all":
            key, label, length = "claude:seven_day", "Settimana", WEEK
        elif kind == "weekly_scoped" and isinstance(model_name, str) and model_name.strip():
            name = model_name.strip()
            key, label, length = f"claude:seven_day_{_slug(name)}", f"Settimana {name}", WEEK
        elif kind:
            length = WEEK if group == "weekly" else FIVE_HOURS if group == "session" else None
            key, label = f"claude:{_slug(kind)}", _title(kind)
        else:
            continue
        if key not in out:
            out[key] = Window("claude", key, label, max(0.0, pct), reset, length)
    return out


def _extra_usage(data: dict, now: datetime) -> Window | None:
    ex = data.get("extra_usage")
    if not isinstance(ex, dict) or ex.get("is_enabled") is not True:
        return None
    limit = number(ex.get("monthly_limit") if ex.get("monthly_limit") is not None else ex.get("monthly_credit_limit"))
    used = number(ex.get("used_credits"))
    pct = used / limit * 100 if used is not None and limit and limit > 0 else _util(ex.get("utilization"))
    if pct is None:
        return None
    currency = str(ex.get("currency") or "USD").upper()
    label = "Spesa extra del mese"
    if used is not None and limit:
        label += f" ({used / 100:.2f} su {limit / 100:.2f} {currency})".replace(".", ",")
    # Claude non dice quando si azzera: come CodexBar, il primo del mese successivo (UTC).
    first = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    nxt = (first + timedelta(days=32)).replace(day=1)
    return Window("claude", "claude:extra_usage", label, max(0.0, pct), nxt, nxt - first)


def _resets(data: dict, now: datetime) -> Window | None:
    """Reset dei limiti disponibili («Reset for free»), dal blocco cedar_ember. Solo lettura."""
    block = data.get("cedar_ember")
    if not isinstance(block, dict) or block.get("eligible") is not True:
        return None
    grants = block.get("grants")
    if not isinstance(grants, list) or len(grants) > MAX_GRANTS:
        return None
    count, first_end = 0, None
    for g in grants:
        if not isinstance(g, dict):
            continue
        left = g.get("resets_left")
        if isinstance(left, bool) or not isinstance(left, int) or left < 0 or g.get("paused") is not False:
            continue
        starts, ends = g.get("starts_at"), g.get("ends_at")
        start_dt = parse_datetime(starts) if isinstance(starts, str) else None
        end_dt = parse_datetime(ends) if isinstance(ends, str) else None
        if (isinstance(starts, str) and start_dt is None) or (isinstance(ends, str) and end_dt is None):
            continue
        if (start_dt and start_dt > now) or (end_dt and end_dt <= now) or left == 0:
            continue
        count += left
        if end_dt and (first_end is None or end_dt < first_end):
            first_end = end_dt
    if count <= 0 or count > MAX_RESETS:
        return None
    tail = f" (il primo scade il {first_end.astimezone():%d/%m})" if first_end else ""
    return Window("claude", "claude:resets", "Reset dei limiti disponibili", 0.0, None, None, note=f"{count}{tail}")


PACE_PERCENT_KEYS = ("projected_utilization", "projected_percent", "projected", "utilization_at_reset",
                     "percent_at_reset", "estimated_utilization")
PACE_TIME_KEYS = ("runs_out_at", "exhausted_at", "exhausts_at", "depletes_at", "projected_exhaustion_at")


def _pace_note(window: Window, raw) -> Window | None:
    """Previsione di Claude per una finestra, se il campo «pace» ha una forma riconoscibile."""
    text = None
    if isinstance(raw, dict):
        for k in PACE_TIME_KEYS:
            dt = parse_datetime(raw.get(k)) if isinstance(raw.get(k), str) else None
            if dt:
                text = f"finisce verso {dt.astimezone():%d/%m %H:%M}"
                break
        if text is None:
            for k in PACE_PERCENT_KEYS:
                n = _util(raw.get(k))
                if n is not None:
                    text = f"circa {n:.0f}% al reset"
                    break
        if text is None:
            msg = raw.get("message") or raw.get("text")
            text = msg.strip() if isinstance(msg, str) and msg.strip() else None
    elif isinstance(raw, str) and raw.strip() and not raw.strip().isidentifier():
        text = raw.strip()
    if not text:
        return None
    return Window("claude", f"{window.key}:pace", f"Previsione di Claude ({window.label})", 0.0, None, None, note=text)


def parse(data: dict, now: datetime | None = None) -> list[Window]:
    now = now or datetime.now(timezone.utc)
    if not isinstance(data, dict):
        return []
    found = _limits_windows(data)
    for key, w in _flat_windows(data).items():
        found.setdefault(key, w)         # le chiavi piatte solo per ciò che «limits» non ha
    order = {"claude:five_hour": 0, "claude:seven_day": 1}
    out = sorted(found.values(), key=lambda w: (order.get(w.key, 2), w.key))
    if not out:
        return out
    extra = _extra_usage(data, now)
    if extra:
        out.append(extra)
    notes = []
    for key, value in data.items():
        if isinstance(value, dict) and "pace" in value:
            target = next((w for w in out if w.key == f"claude:{key}" or (key == "iguana_necktie" and w.key == "claude:seven_day_fable")), None)
            n = _pace_note(target, value["pace"]) if target else None
            if n:
                notes.append(n)
    resets = _resets(data, now)
    if resets:
        notes.append(resets)
    return out + notes


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

    def _usage(self, http, org: str, credential: str, cache: dict) -> list[Window]:
        """Con ?cedar_ember=1 arrivano anche i reset dei limiti; se Claude lo rifiuta, richiedo senza."""
        if not cache.get("no_cedar_ember"):
            try:
                return parse(self._get(http, f"/organizations/{org}/usage?cedar_ember=1", credential))
            except AuthError:
                raise
            except ApiError as e:
                if e.status in (0, 429) or e.status >= 500:
                    raise
                # Lo ricordo solo se senza il parametro funziona (un blocco di Cloudflare fallirebbe comunque).
                windows = parse(self._get(http, f"/organizations/{org}/usage", credential))
                cache["no_cedar_ember"] = True
                return windows
        return parse(self._get(http, f"/organizations/{org}/usage", credential))

    def fetch(self, credential: str, http, cache: dict) -> list[Window]:
        org = cache.get("org_id")
        if org:
            try:
                return self._usage(http, org, credential, cache)
            except (ApiError, AuthError):
                pass   # organizzazione cambiata: la ricalcolo
        org = pick_org_id(self._get(http, "/organizations", credential))
        if not org:
            raise ApiError("Nessuna organizzazione trovata per questo account.")
        cache["org_id"] = org
        return self._usage(http, org, credential, cache)
