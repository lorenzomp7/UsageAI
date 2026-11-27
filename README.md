# UsageAI

Ti avvisa quando superi le soglie di consumo del tuo piano Claude, per non arrivare al reset settimanale col fiato corto.

Ci sono due app che fanno la stessa cosa:

| | Android (`android/`) | Windows (`windows/`) |
|---|---|---|
| Dove la vedi | Notifiche + widget nella Home | Icona nella barra delle notifiche, con la % settimanale |
| Ogni quanto controlla | 15 minuti (il minimo concesso da Android) | Da 1 a 60 minuti (5 di default) |
| Accesso | Login a claude.ai dentro l'app, link email o sessionKey incollato | sessionKey incollato |
| Dove resta la sessione | Cifrata con il Keystore di Android | Gestione credenziali di Windows |
| Costo | Gratis | Gratis |

## Cosa controlla

- **Sessione 5 ore** e **settimana (tutti i modelli)**, più le eventuali settimane per modello (es. Opus) se il tuo piano le ha.
- **Soglie**: una o più per finestra (default 80% per le 5 ore; 50, 75, 90% per la settimana). Ogni soglia ti avvisa una sola volta per finestra e si riattiva al reset.
- **Ritmo**: ti avvisa se la percentuale settimanale va più veloce dei giorni passati. Esempio: a 3 giorni su 7 il ritmo costante è il 43%; se sei al 55% con margine 10, arriva l'avviso. Non scatta sotto il 20% di consumo per evitare falsi allarmi a inizio settimana.

## Scaricare le app

Ogni push su `main` compila tutto con GitHub Actions e aggiorna la release **[Ultima build](../../releases/tag/ultima-build)**:

- `UsageAI.apk` → aprilo dal telefono e installalo (va consentita l'installazione da fonti sconosciute per il browser).
- `UsageAI.exe` → avvialo su Windows. SmartScreen potrebbe avvisarti perché l'exe non è firmato: «Ulteriori informazioni» → «Esegui comunque».

L'APK è firmato con una chiave di debug fissa (`android/app/debug.keystore`), così ogni nuova versione si installa sopra la precedente senza disinstallare.

## Accesso

L'app legge lo stesso endpoint della pagina **Impostazioni > Utilizzo** di claude.ai (`/api/organizations/{id}/usage`) usando il cookie di sessione del tuo account. **Non è un'API documentata da Anthropic**: se la cambiano, le app smettono di leggere i dati finché non si aggiorna il codice (`ClaudeApi.kt` e `windows/usageai/api.py`).

Il cookie `sessionKey` dà pieno accesso al tuo account: resta solo sul dispositivo e viene mandato solo a claude.ai.

**Android**
- *Accedi a claude.ai*: login con email dentro l'app. L'accesso con Google non funziona nelle WebView (Google lo blocca).
- *Accedi con il link ricevuto via email*: se claude.ai ti manda un link, copialo dall'email e incollalo qui.
- *Incolla il sessionKey*: preso dal PC come sotto.

**Windows** (e Android, terza opzione)
1. Apri claude.ai in Chrome o Edge, già loggato.
2. `F12` → scheda **Application** → **Cookies** → `https://claude.ai`.
3. Copia il valore di `sessionKey` (inizia con `sk-ant-sid`) e incollalo nelle Impostazioni dell'app.

La sessione scade ogni tanto: l'app te lo notifica una volta e ti chiede di rifare l'accesso.

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

La logica (soglie, ritmo, lettura della risposta) è la stessa nelle due app: `windows/usageai/core.py` e `android/app/src/main/java/io/github/lorenzomp7/usageai/UsageLogic.kt`, con gli stessi test.
