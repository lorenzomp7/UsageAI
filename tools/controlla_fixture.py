"""Controlla che le fixture non contengano token, cookie, chiavi o email.

Uso: python tools/controlla_fixture.py      (esce con 1 se trova qualcosa)

Gira anche nei test di Windows (windows/tests/test_fixtures.py), quindi in GitHub Actions.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

CONTROLLI: list[tuple[str, re.Pattern]] = [
    ("token JWT", re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.")),
    ("sessionKey di Claude", re.compile(r"sk-ant-[A-Za-z0-9_-]{8,}")),
    ("chiave OpenRouter", re.compile(r"sk-or-[A-Za-z0-9_-]{8,}")),
    ("chiave API", re.compile(r"\bsk-[A-Za-z0-9]{20,}")),
    ("token GitHub", re.compile(r"\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})")),
    ("chiave xAI", re.compile(r"\bxai-[A-Za-z0-9]{20,}")),
    ("header Bearer", re.compile(r"Bearer\s+[A-Za-z0-9._-]{12,}")),
    ("cookie di sessione", re.compile(r"(__Secure-[A-Za-z0-9.-]+|sessionKey|WorkosCursorSessionToken|"
                                      r"ory_session_[A-Za-z0-9]*)\s*[=:]\s*[\"']?[A-Za-z0-9%._-]{12,}")),
    ("email", re.compile(r"[A-Za-z0-9._%+-]+@(?!example\.(com|org|net)\b)[A-Za-z0-9.-]+\.[A-Za-z]{2,}")),
]
ESTENSIONI = {".json", ".txt", ".har", ".html"}


def controlla(cartella: Path) -> list[str]:
    problemi = []
    for f in sorted(cartella.rglob("*")):
        if not f.is_file() or f.suffix.lower() not in ESTENSIONI:
            continue
        testo = f.read_text(encoding="utf-8", errors="replace")
        for nome, rx in CONTROLLI:
            m = rx.search(testo)
            if m:
                riga = testo.count("\n", 0, m.start()) + 1
                problemi.append(f"{f.relative_to(cartella.parent)}:{riga}: sembra un {nome}")
    return problemi


def main() -> int:
    problemi = controlla(ROOT / "fixtures")
    for p in problemi:
        print(p)
    if problemi:
        print(f"{len(problemi)} possibili segreti: pulisci i file prima del commit.")
        return 1
    print("Fixture pulite.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
