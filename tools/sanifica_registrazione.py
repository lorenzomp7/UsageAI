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
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "fixtures"
sys.path.insert(0, str(ROOT / "windows"))
from usageai.sanifica import (  # noqa: E402  (stessa pulizia usata dall'app)
    CHIAVI_ID, CHIAVI_SEGRETE, SERVIZI, pulisci, pulisci_testo, servizio_di,
)

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
