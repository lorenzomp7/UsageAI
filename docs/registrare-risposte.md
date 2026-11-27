# Registrare risposte vere dei servizi (Proxelar)

Diversi campi che UsageAI vuole leggere non sono documentati: la previsione («pace») e i reset di Claude,
i crediti AI di Copilot, la quota di Perplexity, i limiti di grok.com (gRPC-web). Prima di scrivere un parser
serve una risposta vera. Questa procedura la registra dal browser del PC e la trasforma in una fixture
pulita per i test di Android e Windows.

Strumento: [Proxelar](https://github.com/emanuele-em/proxelar) (licenza MIT, gratis), un proxy locale che
mostra e salva il traffico HTTPS. Tutto resta sul PC.

## 1. Installare Proxelar (una volta)

```
winget install --id EmanueleMicheletti.Proxelar --exact
```

## 2. Registrare

```
proxelar -i gui --launch-browser --export-har %USERPROFILE%\Desktop\usageai.har
```

- `--launch-browser` apre un profilo di Chrome/Edge **separato**, già impostato sul proxy: il browser di tutti
  i giorni non viene toccato.
- Nel profilo separato apri `http://proxel.ar` e installa il certificato **solo lì** (serve per vedere l'HTTPS).
- Accedi e apri le pagine dei consumi che servono:
  - Claude: `https://claude.ai/settings/usage`
  - Copilot: `https://github.com/settings/copilot`
  - Perplexity: `https://www.perplexity.ai/account/usage`
  - Grok: `https://grok.com` (apri il menu dei limiti)
  - Codex: `https://chatgpt.com/codex/settings/usage`
- Torna sulla finestra di Proxelar e premi `Ctrl+C`: il file `.har` viene scritto sul Desktop.

## 3. Pulire e salvare come fixture

Dalla cartella della repo:

```
python tools\sanifica_registrazione.py %USERPROFILE%\Desktop\usageai.har --prova
python tools\sanifica_registrazione.py %USERPROFILE%\Desktop\usageai.har
```

Il primo comando mostra solo cosa verrebbe salvato; il secondo scrive in `fixtures/<servizio>/registrate/`.
Lo script:

- tiene solo le risposte 200 degli indirizzi di consumo (tabella `SERVIZI` nello script);
- toglie token, cookie, email, telefoni e chiavi; sostituisce gli identificativi (uuid, id di account,
  organizzazione) con segnaposto stabili, così i riferimenti tra risposte restano coerenti;
- lascia numeri, date e nomi dei campi, che sono i dati che servono ai test;
- salva le risposte binarie di grok.com (gRPC-web) come base64 in un `.grpc.json`;
- alla fine esegue `tools/controlla_fixture.py` e avvisa se è rimasto qualcosa di sospetto.

Poi **cancella il file .har**: contiene cookie e token veri.

## 4. Controllo prima del commit

```
python tools\controlla_fixture.py
```

Lo stesso controllo gira nei test di Windows (`windows/tests/test_integrazioni.py`) a ogni push: se una fixture
contiene un JWT, una chiave (`sk-…`, `ghp_…`, `xai-…`), un cookie di sessione, un header `Bearer` o un'email
che non sia `@example.com`, la build fallisce.

I test leggono da soli ogni fixture in `fixtures/codex|kimi|grok/registrate/` con il parser del servizio.
Per gli altri servizi, il test del nuovo parser va scritto insieme al parser (modifiche del Piano collegate:
UsageAI 34, 50, 52, 53, 56, 65).
