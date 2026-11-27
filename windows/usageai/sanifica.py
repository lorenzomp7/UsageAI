"""Pulizia delle risposte dei servizi: toglie token, cookie, email e identificativi.

Usata dall'app (salvataggio automatico delle risposte nuove, registra.py) e dallo script
tools/sanifica_registrazione.py (registrazioni di Proxelar). Solo libreria standard.
"""
from __future__ import annotations

import re

# (servizio, nome della fixture, espressione sull'URL)
SERVIZI: list[tuple[str, str, re.Pattern]] = [
    ("claude", "usage", re.compile(r"claude\.ai/api/organizations/[^/]+/usage")),
    ("claude", "organizations", re.compile(r"claude\.ai/api/organizations/?(\?|$)")),
    ("codex", "wham-usage", re.compile(r"chatgpt\.com/backend-api/wham/usage")),
    ("codex", "wham-reset-credits", re.compile(r"chatgpt\.com/backend-api/wham/rate-limit-reset-credits")),
    ("copilot", "copilot-internal-user", re.compile(r"api\.github\.com/copilot_internal/user")),
    ("perplexity", "billing-credits", re.compile(r"perplexity\.ai/rest/billing/credits(\?|$)")),
    ("perplexity", "billing-credits-balance", re.compile(r"perplexity\.ai/rest/billing/credits/balance")),
    ("perplexity", "billing-quota", re.compile(r"perplexity\.ai/rest/billing/quota")),
    ("grok", "cli-billing", re.compile(r"cli-chat-proxy\.grok\.com/v1/billing")),
    ("grok", "GetRateLimits", re.compile(r"grok\.com/.*RateLimits/GetRateLimits")),
    ("grok", "GetRemainingResets", re.compile(r"grok\.com/.*GetRemainingResets")),
    ("cursor", "usage-summary", re.compile(r"cursor\.com/api/usage-summary")),
    ("gemini", "usage", re.compile(r"gemini\.google\.com/.*executeQuery")),
    ("kimi", "usages", re.compile(r"api\.kimi\.com/coding/v1/usages")),
    ("openrouter", "key", re.compile(r"openrouter\.ai/api/v1/key")),
    ("openrouter", "credits", re.compile(r"openrouter\.ai/api/v1/credits")),
]

# Chiavi il cui valore testuale va sempre tolto (confronto sul nome in minuscolo, per sottostringa).
CHIAVI_SEGRETE = ("token", "cookie", "secret", "password", "authorization", "api_key", "apikey",
                  "session_key", "sessionkey", "email", "phone", "refresh", "jwt", "signature")
# Chiavi con identificativi personali: il valore resta ma diventa un segnaposto stabile.
CHIAVI_ID = ("uuid", "user_id", "userid", "account_id", "accountid", "org_id", "organization_id",
             "workspace_id", "customer_id", "subscription_id", "stripe", "name", "login", "avatar")

RE_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
RE_JWT = re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*")
RE_CHIAVE = re.compile(r"\b(sk-ant-[A-Za-z0-9_-]+|sk-or-[A-Za-z0-9_-]+|sk-[A-Za-z0-9]{20,}|"
                       r"gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xai-[A-Za-z0-9]{20,})")
RE_UUID = re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b", re.I)

# Nomi lasciati come sono anche se la chiave contiene «name» (sono etichette, non persone).
NOMI_TECNICI = ("limit_name", "limitname", "display_name", "model", "plan", "pool", "type", "kind",
                "metered_feature", "scope")


def _segnaposto(valore: str, tabella: dict[str, str], prefisso: str) -> str:
    if valore not in tabella:
        tabella[valore] = f"{prefisso}-{len(tabella) + 1}"
    return tabella[valore]


def _uuid_finto(valore: str, tabella: dict[str, str]) -> str:
    """Stesso UUID vero → stesso UUID finto, così i riferimenti tra risposte restano coerenti."""
    if valore not in tabella:
        tabella[valore] = f"00000000-0000-4000-8000-{len(tabella) + 1:012d}"
    return tabella[valore]


def pulisci_testo(s: str, ids: dict[str, str]) -> str:
    s = RE_JWT.sub("<rimosso>", s)
    s = RE_CHIAVE.sub("<rimosso>", s)
    s = RE_EMAIL.sub("utente@example.com", s)
    s = RE_UUID.sub(lambda m: _uuid_finto(m.group(0).lower(), ids), s)
    return s


def pulisci(obj, ids: dict[str, str], chiave: str = ""):
    """Copia di obj senza segreti. Numeri e booleani restano (sono i dati che servono ai test)."""
    low = chiave.lower()
    if isinstance(obj, dict):
        return {k: pulisci(v, ids, k) for k, v in obj.items()}
    if isinstance(obj, list):
        return [pulisci(v, ids, chiave) for v in obj]
    if isinstance(obj, str):
        if any(k in low for k in CHIAVI_SEGRETE):
            return "<rimosso>"
        tecnico = any(t in low for t in NOMI_TECNICI)
        if not tecnico and any(k in low for k in CHIAVI_ID):
            return _segnaposto(obj, ids, "id")
        return pulisci_testo(obj, ids)
    return obj


def servizio_di(url: str) -> tuple[str, str] | None:
    for servizio, nome, rx in SERVIZI:
        if rx.search(url):
            return servizio, nome
    return None




def forma(obj, prefisso: str = "", profondita: int = 6) -> set[str]:
    """Insieme dei percorsi dei campi (es. «limits[].scope.model.display_name»), senza i valori.

    Le chiavi che sembrano identificativi o date (uuid, numeri lunghi) sono raggruppate, così una
    risposta con un id diverso non conta come «campo nuovo».
    """
    out: set[str] = set()
    if profondita <= 0:
        return out
    if isinstance(obj, dict):
        for k, v in obj.items():
            nome = "<id>" if RE_UUID.fullmatch(str(k)) or str(k).isdigit() else str(k)
            p = f"{prefisso}.{nome}" if prefisso else nome
            out.add(p)
            out |= forma(v, p, profondita - 1)
    elif isinstance(obj, list):
        for v in obj[:50]:
            out |= forma(v, f"{prefisso}[]", profondita - 1)
    return out
