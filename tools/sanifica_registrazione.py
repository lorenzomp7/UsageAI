"""Trasforma una registrazione di Proxelar (HAR) in fixture pulite per i test.

Uso (dalla cartella della repo):

    python tools/sanifica_registrazione.py usageai.har            # scrive in fixtures/<servizio>/
    python tools/sanifica_registrazione.py usageai.har --prova    # mostra solo cosa scriverebbe

Tiene solo le risposte dei servizi che interessano a UsageAI (tabella SERVIZI), toglie token,
cookie, email e identificativi e salva una fixture per risposta. Le risposte gRPC-web di grok.com
(binarie) vengono salvate come base64 in un file .json con il tipo di contenuto.
Dopo, «python tools/controlla_fixture.py» verifica che non sia rimasto nessun segreto.
Solo libreria standard: gira con il Python che c'è già.
"""
from __future__ import annotations

import argparse
import base64
import json
import re
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "fixtures"

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


def corpo(entry: dict) -> tuple[str | None, bytes | None, str]:
    content = (entry.get("response") or {}).get("content") or {}
    mime = str(content.get("mimeType") or "")
    text = content.get("text")
    if text is None:
        return None, None, mime
    if content.get("encoding") == "base64":
        raw = base64.b64decode(text)
        try:
            return raw.decode("utf-8"), raw, mime
        except UnicodeDecodeError:
            return None, raw, mime
    return text, text.encode("utf-8"), mime


def estrai(har: dict) -> list[tuple[Path, str]]:
    """Elenco (percorso della fixture, contenuto) da scrivere."""
    oggi = date.today().isoformat()
    uscite: list[tuple[Path, str]] = []
    contatori: dict[tuple[str, str], int] = {}
    ids: dict[str, str] = {}
    for entry in (har.get("log") or {}).get("entries") or []:
        url = (entry.get("request") or {}).get("url") or ""
        trovato = servizio_di(url)
        status = (entry.get("response") or {}).get("status")
        if not trovato or status != 200:
            continue
        servizio, nome = trovato
        testo, grezzo, mime = corpo(entry)
        n = contatori.get(trovato, 0) + 1
        contatori[trovato] = n
        base = f"{oggi}-{nome}" + (f"-{n}" if n > 1 else "")
        cartella = FIXTURES / servizio / "registrate"
        if testo is not None and testo.strip()[:1] in "{[":
            dati = pulisci(json.loads(testo), ids)
            uscite.append((cartella / f"{base}.json", json.dumps(dati, indent=2, ensure_ascii=False) + "\n"))
        elif testo is not None and testo.lstrip().startswith(")]}'"):
            pulito = pulisci_testo(testo, ids)
            uscite.append((cartella / f"{base}.txt", pulito))
        elif grezzo is not None:
            # gRPC-web e altri formati binari: base64 dentro un .json, con il tipo di contenuto.
            uscite.append((cartella / f"{base}.grpc.json", json.dumps({
                "content_type": mime, "base64": base64.b64encode(grezzo).decode("ascii"),
                "nota": "Corpo binario registrato: controllare a mano che non contenga email o nomi.",
            }, indent=2) + "\n"))
    return uscite


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("har", type=Path, help="file .har esportato da Proxelar (--export-har)")
    ap.add_argument("--prova", action="store_true", help="non scrive niente, elenca solo i file")
    args = ap.parse_args(argv)
    har = json.loads(args.har.read_text(encoding="utf-8"))
    uscite = estrai(har)
    if not uscite:
        print("Nessuna risposta utile nella registrazione (controlla di aver aperto le pagine dei consumi).")
        return 1
    for percorso, contenuto in uscite:
        print(("prova: " if args.prova else "scrivo ") + str(percorso.relative_to(ROOT)))
        if not args.prova:
            percorso.parent.mkdir(parents=True, exist_ok=True)
            percorso.write_text(contenuto, encoding="utf-8")
    if not args.prova:
        sys.path.insert(0, str(ROOT / "tools"))
        from controlla_fixture import controlla   # noqa: E402
        problemi = controlla(FIXTURES)
        for p in problemi:
            print("ATTENZIONE:", p)
        return 1 if problemi else 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
