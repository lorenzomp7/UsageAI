"""Perplexity: crediti del piano, bonus e acquistati (www.perplexity.ai/rest/billing/credits)."""
from __future__ import annotations

from datetime import datetime, timezone

from ..core import Window
from .base import ApiError, AuthError, Provider, from_epoch, number, parse_json, request

URL = "https://www.perplexity.ai/rest/billing/credits?version=2.18&source=default"
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
            return parse(parse_json(text))
        raise AuthError("Sessione di Perplexity scaduta: copia di nuovo il cookie.") from rejected
