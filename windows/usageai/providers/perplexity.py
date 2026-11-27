"""Perplexity: crediti del piano, bonus e acquistati (www.perplexity.ai/rest/billing/credits).

In più (modifica 52 del Piano): i crediti con una data di scadenza, dalla stessa risposta, e le ricerche
rimaste nel mese da /rest/billing/quota. Quell'indirizzo lo usa l'app Android di Perplexity ma il formato
non è documentato: la lettura è prudente (solo se trova un «rimaste» e un «totale» riconoscibili) e
un errore lì non blocca i crediti.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from ..core import Window, parse_datetime
from .base import ApiError, AuthError, Provider, from_epoch, number, parse_json, request

URL = "https://www.perplexity.ai/rest/billing/credits?version=2.18&source=default"
QUOTA_URL = "https://www.perplexity.ai/rest/billing/quota"
SESSION_COOKIES = (
    "__Secure-authjs.session-token",
    "authjs.session-token",
    "__Secure-next-auth.session-token",
    "next-auth.session-token",
)


def _get(d: dict, snake: str, camel: str):
    return d.get(snake, d.get(camel))


def parse(data: dict, now: datetime | None = None) -> list[Window]:
    if not isinstance(data, dict):
        raise ApiError("Risposta di Perplexity cambiata.")
    now_ts = (now or datetime.now(timezone.utc)).timestamp()
    grants = _get(data, "credit_grants", "creditGrants")
    used = number(_get(data, "total_usage_cents", "totalUsageCents"))
    if not isinstance(grants, list) or used is None:
        raise ApiError("Risposta di Perplexity cambiata.")

    def total(kind, only_valid=False):
        s = 0.0
        for g in grants:
            if not isinstance(g, dict) or g.get("type") != kind:
                continue
            if only_valid:
                exp = number(_get(g, "expires_at_ts", "expiresAtTs"))
                if exp is not None and exp <= now_ts:
                    continue
            s += number(_get(g, "amount_cents", "amountCents")) or 0
        return max(0.0, s)

    recurring = total("recurring")
    promo = total("promotional", only_valid=True)
    purchased = max(total("purchased"),
                    number(_get(data, "current_period_purchased_cents", "currentPeriodPurchasedCents")) or 0)
    # Perplexity consuma prima i crediti del piano, poi quelli acquistati, poi i bonus.
    rec_used = min(used, recurring)
    left = used - rec_used
    pur_used = min(left, purchased)
    left -= pur_used
    promo_used = min(left, promo)

    renewal = from_epoch(_get(data, "renewal_date_ts", "renewalDateTs"))
    out: list[Window] = []
    if recurring > 0:
        out.append(Window("perplexity", "perplexity:recurring", "Crediti del piano",
                          min(100.0, rec_used / recurring * 100), renewal, None))
    if purchased > 0:
        out.append(Window("perplexity", "perplexity:purchased", "Crediti acquistati",
                          min(100.0, pur_used / purchased * 100), None, None))
    if promo > 0:
        out.append(Window("perplexity", "perplexity:promo", "Crediti bonus",
                          min(100.0, promo_used / promo * 100), None, None))
    if not out:
        raise ApiError("Perplexity non riporta crediti per questo account.")
    # Crediti che scadono: la scadenza più vicina ancora valida.
    expiring = []
    for g in grants:
        if not isinstance(g, dict):
            continue
        exp = number(_get(g, "expires_at_ts", "expiresAtTs"))
        amount = number(_get(g, "amount_cents", "amountCents"))
        if exp and exp > now_ts and amount and amount > 0:
            expiring.append((exp, amount))
    if expiring:
        exp = min(e for e, _ in expiring)
        amount = sum(a for e, a in expiring if e == exp)
        when = from_epoch(exp)
        out.append(Window("perplexity", "perplexity:expiring", "Crediti in scadenza", 0.0, None, None,
                          note=f"{amount / 100:.2f} $ scadono il {when.astimezone():%d/%m}".replace(".", ",")))
    return out


REMAINING_KEYS = ("remaining", "queries_remaining", "remaining_queries", "remainingQueries", "queriesRemaining", "left")
TOTAL_KEYS = ("limit", "total", "quota", "max", "monthly_limit", "monthlyLimit", "allowance")
USED_KEYS = ("used", "queries_used", "usage", "count", "consumed")
RESET_KEYS = ("reset_at", "resets_at", "reset_ts", "reset_date_ts", "resetAt", "resetsAt", "renewal_date_ts")


def parse_quota(data, now: datetime | None = None) -> list[Window]:
    """Ricerche rimaste nel mese, se la risposta ha una forma riconoscibile; altrimenti niente."""
    now = now or datetime.now(timezone.utc)
    candidates = []
    if isinstance(data, dict):
        candidates.append(("", data))
        candidates += [(k, v) for k, v in data.items() if isinstance(v, dict)]
    out: list[Window] = []
    for name, d in candidates:
        total = next((number(d[k]) for k in TOTAL_KEYS if number(d.get(k)) is not None), None)
        if not total or total <= 0:
            continue
        rem = next((number(d[k]) for k in REMAINING_KEYS if number(d.get(k)) is not None), None)
        used = next((number(d[k]) for k in USED_KEYS if number(d.get(k)) is not None), None)
        if used is None and rem is None:
            continue
        used = used if used is not None else total - rem
        reset = None
        for k in RESET_KEYS:
            v = d.get(k)
            reset = from_epoch(v) if number(v) is not None else (parse_datetime(v) if isinstance(v, str) else None)
            if reset:
                break
        label = "Ricerche del mese" + (f" ({name.replace('_', ' ')})" if name else "")
        key = "perplexity:quota" + (f":{name}" if name else "")
        out.append(Window("perplexity", key, label, max(0.0, min(100.0, used / total * 100)),
                          reset, timedelta(days=30) if reset else None))
        if not name:
            break   # trovato al primo livello: non cerco più dentro
    return out


class PerplexityProvider(Provider):
    id = "perplexity"
    name = "Perplexity"
    credential_label = "Cookie o session-token di perplexity.ai"
    help = ("Apri www.perplexity.ai in Chrome o Edge, già loggato → F12 → Application → Cookies → "
            "https://www.perplexity.ai → copia il valore di «__Secure-authjs.session-token» "
            "(o «__Secure-next-auth.session-token», dipende dall'account).")

    def fetch(self, credential: str, http, cache: dict) -> list[Window]:
        c = credential.strip()
        if c.lower().startswith("cookie:"):
            c = c[7:].strip()
        names = [cache["cookie_name"]] if cache.get("cookie_name") else []
        candidates = [c] if "=" in c else [f"{n}={c}" for n in names + [n for n in SESSION_COOKIES if n not in names]]
        rejected = None
        for cookie in candidates:
            headers = {
                "Accept": "application/json",
                "Cookie": cookie,
                "Origin": "https://www.perplexity.ai",
                "Referer": "https://www.perplexity.ai/account/usage",
            }
            try:
                _, text = request(http, URL, headers)
            except AuthError as e:
                rejected = e
                continue
            if "=" not in c:
                cache["cookie_name"] = cookie.split("=", 1)[0]
            windows = parse(parse_json(text))
            if not cache.get("no_quota"):
                try:
                    _, qtext = request(http, QUOTA_URL, headers)
                    windows = parse_quota(parse_json(qtext)) + windows
                except (ApiError, AuthError) as e:
                    if getattr(e, "status", 0) in (404, 405):
                        cache["no_quota"] = True   # indirizzo assente: non lo richiedo più
            return windows
        raise AuthError("Sessione di Perplexity scaduta: copia di nuovo il cookie.") from rejected
