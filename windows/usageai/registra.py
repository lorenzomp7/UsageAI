"""Salvataggio automatico delle risposte nuove dei servizi, già pulite (modifiche UsageAI 1 del Piano).

Ogni volta che una risposta di consumo arriva con campi mai visti (o è la prima di quell'indirizzo),
ne salvo una copia senza token, cookie, email e identificativi in %APPDATA%\\UsageAI\\registrazioni.
Servono a scrivere i parser dei campi non documentati (previsione e reset di Claude, quota di
Perplexity…) su dati veri, senza registrare a mano con un proxy.

Limiti: una copia al giorno per indirizzo, al massimo MAX_FILE file in tutto. Niente esce dal PC.
"""
from __future__ import annotations

import json
import logging
import threading
from datetime import date
from pathlib import Path

from .sanifica import forma, pulisci, servizio_di

log = logging.getLogger("usageai")
MAX_FILE = 200


class Registratore:
    def __init__(self, cartella: Path, oggi=date.today):
        self.cartella = Path(cartella)
        self._oggi = oggi
        self._lock = threading.Lock()
        self._indice = self.cartella / "campi-conosciuti.json"
        try:
            raw = json.loads(self._indice.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            raw = {}
        self.conosciuti: dict[str, set[str]] = {k: set(v) for k, v in raw.get("campi", {}).items()}
        self.ultimi: dict[str, str] = dict(raw.get("ultimo_salvataggio", {}))

    def _salva_indice(self) -> None:
        self.cartella.mkdir(parents=True, exist_ok=True)
        data = {"campi": {k: sorted(v) for k, v in self.conosciuti.items()}, "ultimo_salvataggio": self.ultimi}
        tmp = self._indice.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=1, ensure_ascii=False), encoding="utf-8")
        tmp.replace(self._indice)

    def file_salvati(self) -> list[Path]:
        return sorted(p for p in self.cartella.glob("*/*.json") if p.name != self._indice.name)

    def __call__(self, url: str, testo: str) -> Path | None:
        """Da chiamare con ogni risposta 200. Restituisce il file scritto, se ne ha scritto uno."""
        trovato = servizio_di(url)
        if not trovato:
            return None
        t = (testo or "").strip()
        if not t or t[0] not in "{[":
            return None
        try:
            dati = json.loads(t)
        except ValueError:
            return None
        servizio, nome = trovato
        chiave = f"{servizio}/{nome}"
        campi = forma(dati)
        with self._lock:
            noti = self.conosciuti.get(chiave)
            nuovi = campi - noti if noti is not None else campi
            if noti is not None and not nuovi:
                return None
            oggi = self._oggi().isoformat()
            if self.ultimi.get(chiave) == oggi or len(self.file_salvati()) >= MAX_FILE:
                return None
            pulito = pulisci(dati, {})
            dest = self.cartella / servizio / f"{oggi}-{nome}.json"
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(json.dumps(pulito, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            self.conosciuti[chiave] = (noti or set()) | campi
            self.ultimi[chiave] = oggi
            self._salva_indice()
            if noti is not None:
                log.info("risposta di %s con campi nuovi salvata in %s: %s", chiave, dest, ", ".join(sorted(nuovi))[:500])
            else:
                log.info("prima risposta di %s salvata in %s", chiave, dest)
            return dest
