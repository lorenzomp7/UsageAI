# Fixture

Risposte d'esempio dei servizi, usate dai test di **entrambe** le app
(`windows/tests/test_integrazioni.py` e `android/app/src/test/.../IntegrazioniTest.kt`).

| Cartella | Contenuto | Provenienza |
|---|---|---|
| `codex/wham-usage.rate_limits_rs.json` | Risposta di `wham/usage` con limiti aggiuntivi, spesa del workspace, motivo del blocco e reset disponibili | Test `app-server/tests/suite/v2/rate_limits.rs` di [Codex CLI](https://github.com/openai/codex) (Apache-2.0) |
| `codex/app-server-rateLimits-read.rate_limits_rs.json` | Risposta di `codex app-server` ad `account/rateLimits/read` | Stesso test (valore atteso, in camelCase come lo serializza app-server) |
| `kimi/usages.managed-usage_test_ts.json` | Risposta di `api.kimi.com/coding/v1/usages` con le varianti dei nomi dei campi | Test `packages/oauth/test/managed-usage.test.ts` di [Kimi Code](https://github.com/MoonshotAI/kimi-code) (MIT) |
| `claude/usage.ClaudeUsageParserTest_kt.json`, `claude/usage-flat.ClaudeUsageParserTest_kt.json` | Risposta di Claude con `limits[]` (Fable) e solo chiavi piatte | Test `ClaudeUsageParserTest.kt` di [Usage Limits Mobile](https://github.com/ZepiGit/Usage-Limit-Viewer-Mobile) (MIT; dati inventati con la forma di un account vero) |
| `claude/usage-extra-reset.codexbar_docs.json` | Spesa extra attiva e blocco `cedar_ember` dei reset | Costruita sui nomi dei campi della documentazione e dei test di [CodexBar](https://github.com/steipete/CodexBar) (MIT) |
| `copilot/user-*.CopilotUsageModelsTests_swift.json` | Copilot con crediti AI (`token_based_billing`, `credits_used`, quote del mese) | Test `CopilotUsageModelsTests.swift` di CodexBar (MIT) |
| `<servizio>/registrate/` | Risposte vere registrate con Proxelar e pulite | `tools/sanifica_registrazione.py`, vedi `docs/registrare-risposte.md` |

Le fixture prese dai test di altri progetti riportano solo dati d'esempio (numeri e date inventati da quei
test), non codice.

Regola: **nessuna fixture può contenere token, cookie, chiavi o email**. Lo controlla
`tools/controlla_fixture.py`, che gira anche nei test di Windows a ogni push.
