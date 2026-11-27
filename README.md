# UsageAI

Ti avvisa quando superi le soglie di consumo dei tuoi abbonamenti AI, per non arrivare al reset col fiato corto.

Servizi supportati:

| Servizio | Cosa legge | Accesso su Android | Accesso su Windows |
|---|---|---|---|
| **Claude** | Sessione 5 ore, settimana, settimane per modello (anche Fable), spesa extra del mese, reset dei limiti disponibili | Login nell'app, link email o `sessionKey` | `sessionKey` |
| **ChatGPT / Codex** | Sessione 5 ore e settimana di Codex, limiti aggiuntivi, spesa del workspace, crediti, reset disponibili | **Codice** (accesso dal browser), login nell'app o cookie | Da solo, se usi la CLI di Codex (tramite `codex app-server`); altrimenti cookie |
| **Gemini** | Sessione 5 ore e settimana del piano Google AI | Cookie (Google blocca il login nelle app) | Cookie |
| **Cursor** | Uso incluso del mese, pool API e Auto, spesa extra | Login nell'app o `WorkosCursorSessionToken` | `WorkosCursorSessionToken` |
| **GitHub Copilot** | Crediti AI del mese (o richieste premium), uso aggiuntivo oltre i compresi | Token GitHub | Token GitHub |
| **Perplexity** | Crediti del piano, acquistati e bonus, crediti in scadenza, ricerche del mese (se Perplexity le riporta) | Login nell'app (email) o cookie | Cookie / session-token |
| **Grok** (SuperGrok) | Crediti del periodo (settimana o mese) | **Codice** (accesso dal browser) o token della CLI di Grok | Da solo, se usi la CLI di Grok; altrimenti token |
| **Mistral** | Budget API incluso e Vibe del mese | Login nell'app (email) o cookie | Cookie di admin.mistral.ai |
| **OpenRouter** | Limite di spesa della chiave, credito prepagato | Chiave API | Chiave API |
| **Kimi Code** | Settimana, finestre brevi (es. 5 ore), spesa extra del mese e credito extra | Chiave API di Kimi Code | Da solo, se usi la CLI di Kimi Code; altrimenti chiave API |

La chat normale di ChatGPT non ha un contatore di consumo: di OpenAI si leggono solo i limiti di Codex.

Ci sono due app che fanno la stessa cosa:

| | Android (`android/`) | Windows (`windows/`) |
|---|---|---|
| Dove la vedi | Notifiche + widget nella Home (il valore più alto di ogni servizio) | Icona nella barra delle notifiche con la % più alta; menu con tutti i servizi |
| Ogni quanto controlla | 15 minuti (il minimo concesso da Android) | Da 1 a 60 minuti (5 di default) |
| Dove restano le credenziali | Cifrate con il Keystore di Android | Gestione credenziali di Windows |
| Costo | Gratis | Gratis |

## Avvisi

Valgono per tutti i servizi. **Ogni opzione si accende e si spegne da sola** (Windows: Impostazioni → Avvisi e una scheda per servizio; Android: schermata principale):

| Interruttore | Default | Cosa spegne |
|---|---|---|
| Notifiche attive | acceso | Tutte le notifiche (i dati continuano ad aggiornarsi). Su Windows anche dal menu dell'icona |
| Controllo automatico (solo Android) | acceso | Il controllo ogni 15 minuti in background; resta «Aggiorna ora» |
| Soglie sessioni brevi | acceso | Gli avvisi sulle sessioni da 5 ore |
| Soglie periodi lunghi | acceso | Gli avvisi su settimana e mese |
| Ritmo | acceso | L'avviso «stai andando troppo veloce» |
| Quota giornaliera | acceso | L'avviso sul 14,29% al giorno |
| Accesso scaduto | acceso | L'avviso quando una credenziale scade |
| Controlla <servizio> | acceso se configurato | Un singolo servizio; la credenziale resta salvata |
| Avvia con Windows (solo Windows) | spento | L'avvio automatico |

Come funzionano:

- **Soglie brevi** (sessioni fino a 12 ore, es. le 5 ore): default 80%.
- **Soglie lunghe** (settimana o mese): default 50, 75, 90%.
- Ogni soglia ti avvisa una sola volta per finestra e si riattiva al reset.
- **Ritmo**: ti avvisa se un periodo lungo va più veloce del tempo passato. Esempio: a 3 giorni su 7 il ritmo costante è il 43%; se sei al 55% con margine 10, arriva l'avviso. Vale anche per i mesi di Cursor e Copilot. Non scatta sotto il 20% per evitare falsi allarmi a inizio periodo.
- **Quota giornaliera**: un periodo lungo diviso per i suoi giorni dà quanto puoi usare al giorno: settimana = 100% / 7 = 14,29%, mese di 30 giorni = 3,33%. Se in un giorno di calendario (da mezzanotte) superi la quota, ricevi un avviso, una volta al giorno. L'app e il menu mostrano anche «oggi +X% su 14,3% al giorno». Il conteggio di oggi parte dall'ultimo valore visto ieri; dopo un reset riparte da zero.
- Se un servizio non risponde, gli altri vengono controllati lo stesso. Se l'accesso a un servizio scade, ricevi una sola notifica.

## Risposte salvate (solo Windows)

Quando un servizio risponde con campi mai visti (o la prima volta), l'app ne salva una copia **già pulita** da token,
cookie, email e identificativi in `%APPDATA%\UsageAI\registrazioni` (una al giorno per indirizzo, al massimo 200 file).
Servono a leggere i campi non documentati (previsione di Claude, ricerche di Perplexity…) su dati veri. Niente esce
dal PC: la cartella si apre dal menu dell'icona («Cartella delle risposte salvate») e si spegne in Impostazioni.

## Scaricare le app

Ogni push su `main` che tocca `android/` o `windows/` compila l'app corrispondente con GitHub Actions e aggiorna la release **[Ultima build](../../releases/tag/ultima-build)**. I push su `main_claude` compilano e provano le app allo stesso modo, ma non toccano la release. Se un push non fa partire la build (capita con alcuni push fatti da integrazioni), il workflow **Sentinella build** se ne accorge e la avvia da solo entro mezz'ora. Tutto gratis: le repo pubbliche non pagano i minuti di Actions.

Nota: GitHub sospende i workflow programmati (la Sentinella) dopo 60 giorni senza attività sulla repo; li riattivi dalla scheda Actions. Le build sui push restano sempre attive.

- `UsageAI.apk` → aprilo dal telefono e installalo (va consentita l'installazione da fonti sconosciute per il browser).
- `UsageAI.exe` → avvialo su Windows. SmartScreen potrebbe avvisarti perché l'exe non è firmato: «Ulteriori informazioni» → «Esegui comunque».

L'APK è firmato con una chiave di debug fissa (`android/app/debug.keystore`), così ogni nuova versione si installa sopra la precedente senza disinstallare. Passando dalla versione 1 (solo Claude) le soglie, lo stato e il login di Claude restano.

## Come prendere le credenziali

Ogni servizio ha le istruzioni anche dentro l'app (Windows: Impostazioni → scheda del servizio; Android: pulsante «Incolla»). In breve, da Chrome o Edge sul PC, già loggato:

- **Claude**: claude.ai → `F12` → Application → Cookies → `https://claude.ai` → valore di `sessionKey`.
- **ChatGPT/Codex**: su Android il modo più semplice è **«Codice»**: l'app mostra un codice, apri la pagina di OpenAI, accedi e lo inserisci. Va abilitato una volta in ChatGPT → Impostazioni → Sicurezza («accesso con codice dispositivo per Codex»). In alternativa: chatgpt.com → `F12` → Network → ricarica → clicca una richiesta a chatgpt.com → Request Headers → tutto il valore di `cookie`. Su Windows non serve se usi la CLI di Codex: UsageAI chiede i limiti a `codex app-server` (il token resta alla CLI, che lo rinnova da sola); se non parte, legge `%USERPROFILE%\.codex\auth.json`.
- **Gemini**: gemini.google.com/usage → `F12` → Network → ricarica → richiesta «usage» → tutto il valore di `cookie`. Google rinnova spesso questi cookie: quando l'app segnala l'accesso scaduto, ricopiali.
- **Cursor**: cursor.com/dashboard → `F12` → Application → Cookies → `https://cursor.com` → valore di `WorkosCursorSessionToken`.
- **Copilot**: installa GitHub CLI, `gh auth login`, poi `gh auth token` e incolla il token.
- **Perplexity**: www.perplexity.ai → `F12` → Application → Cookies → valore di `__Secure-authjs.session-token` (o `__Secure-next-auth.session-token`). L'app prova da sola i nomi possibili.
- **Grok**: non serve su Windows se usi la CLI ufficiale (`grok login`, legge `%USERPROFILE%\.grok\auth.json`). Su Android usa **«Codice»** (accesso dal browser del telefono, senza PC) oppure incolla il valore di `key` da quel file.
- **Kimi Code**: crea una chiave API nella console di Kimi Code (kimi.com/code) e incollala. Su Windows non serve se usi la CLI di Kimi Code (legge `%USERPROFILE%\.kimi-code\credentials\kimi-code.json`; se il login è scaduto apri Kimi Code).
- **Mistral**: admin.mistral.ai/subscription → `F12` → Network → ricarica → richiesta «subscription» → tutto il valore di `cookie` (deve contenere `ory_session_…`).
- **OpenRouter**: crea una chiave su openrouter.ai/settings/keys. Per avere un avviso utile dalle un limite di spesa (giornaliero, settimanale o mensile).

## Limiti da conoscere

- **Nessuno di questi indirizzi è un'API documentata** dai produttori: sono quelli che usano le loro pagine web. Se cambiano, un servizio smette di leggere i dati finché non si aggiorna il suo file (Windows: `windows/usageai/providers/`; Android: `Services.kt` e `Parsers.kt`). Gli altri continuano a funzionare.
- Gemini è il più fragile: usa una query interna con una firma che Google può cambiare in qualsiasi momento.
- Mistral legge i budget dalla pagina web dell'abbonamento (non da un'API): è la seconda più fragile dopo Gemini.
- OpenRouter usa l'API ufficiale e documentata. Gli orari di reset dei limiti della chiave sono calcolati (mezzanotte UTC, lunedì per i settimanali, il primo del mese per i mensili).
- Grok legge solo i crediti di SuperGrok tramite il login della CLI (o «Codice», che usa lo stesso accesso); i limiti dell'app grok.com non sono ancora coperti.
- «Codice» per Codex e Grok usa gli identificativi client delle CLI ufficiali: i produttori potrebbero cambiarli o limitarli. Per questo restano anche gli altri modi di accesso.
- Le righe come «Crediti ChatGPT», «Reset dei limiti disponibili» o «Credito extra» sono solo informative: niente percentuale e niente avvisi.
- Copilot: l'API `copilot_internal` non è garantito che accetti ogni tipo di token; il token di GitHub CLI è quello più probabile. Se ricevi «token non accettato», dimmelo.
- Le credenziali danno accesso ai tuoi account: restano sul dispositivo e vengono mandate solo al sito del rispettivo servizio.

## Aggiungere un servizio

- Windows: un file in `windows/usageai/providers/` con `parse()` e una classe `Provider`, registrata in `providers/__init__.py`.
- Android: un `object` in `Services.kt`, la lettura della risposta in `Parsers.kt`, il nome in `PROVIDER_NAMES` (`UsageLogic.kt`).
- I test con risposte d'esempio stanno in `windows/tests/test_providers.py` e `android/app/src/test/.../UsageLogicTest.kt`.
- Le risposte d'esempio condivise dalle due app stanno in `fixtures/`. Per registrarne di vere dal browser (con Proxelar) e pulirle da token e cookie: [docs/registrare-risposte.md](docs/registrare-risposte.md).

Gli indirizzi e i formati delle risposte sono stati ricavati dal progetto open source [CodexBar](https://github.com/steipete/CodexBar) (licenza MIT), che fa la stessa cosa su macOS. I formati di Codex (limiti aggiuntivi, crediti, spesa, `app-server`, accesso con codice) vengono dal codice di [Codex CLI](https://github.com/openai/codex) (Apache-2.0), quelli di Kimi da [Kimi Code](https://github.com/MoonshotAI/kimi-code) (MIT), l'accesso con codice di Grok dalla CLI di Grok (Apache-2.0). Nessun codice copiato: solo i nomi dei campi e i passi delle chiamate.

## Sviluppo

**Windows** (Python 3.12)
```
cd windows
pip install -r requirements.txt pytest
python -m pytest -q tests
python main.py
```

**Android** (JDK 17, Android SDK)
```
cd android
gradle testDebugUnitTest assembleDebug
```

La logica (soglie, ritmo, lettura delle risposte) è la stessa nelle due app, con gli stessi test.
