# Work order 1.0.0 — Github Copilot sessions

> **Destinatario:** l'agente di sviluppo che lavora su questo repository.
> **Ruolo:** sviluppatore del plugin IntelliJ qui contenuto.
> **Istruzione:** leggi l'intero documento prima di toccare il codice. Descrive il perimetro
> funzionale e i vincoli della **1.0.0**, la prima e unica versione rilasciata: è la specifica di
> riferimento, non un elenco di modifiche incrementali. Ogni intervento futuro parte da qui e, al
> termine, deve superare la checklist di §8.

---

## 1. Contesto del progetto

Plugin IntelliJ in Kotlin che esporta e importa le sessioni di chat di GitHub Copilot.

| Percorso | Ruolo |
|---|---|
| `build.gradle.kts` | build, `changeNotes` → riquadro **What's New**, firma e pubblicazione |
| `gradle.properties` | `pluginName`, `pluginVersion`, `platformVersion`, `javaVersion=21` |
| `settings.gradle.kts` | `rootProject.name`, cioè il nome dello ZIP prodotto |
| `CHANGELOG.md` | formato *Keep a Changelog*, alimenta `changeNotes` |
| `README.md` | documentazione utente e di rilascio (italiano) |
| `LICENSE` | MIT |
| `src/main/resources/META-INF/plugin.xml` | `<name>`, `<description>` → riquadro **Overview**, gruppo azioni del menu `Tools` |
| `src/main/resources/META-INF/pluginIcon*.svg` | icona su *Settings \| Plugins* e sul Marketplace |
| `src/main/resources/icons/` | icone 16×16 del menu (variante chiara e scura) |
| `src/main/resources/messages/CopilotSessionsBundle*.properties` | unici testi localizzati |
| `core/CopilotPaths.kt` | risoluzione `~/.copilot`, `normalizeWorkspacePath()` |
| `core/SessionTransfer.kt` | export/import, orchestrazione (`export()`, `import()`, `importSingle()`, `verify()`, `relocate()`) |
| `core/SessionStore.kt` | accesso schema-tolerant a `session-store.db` |
| `core/SessionStateRewriter.kt` | riscrittura di `events.jsonl` e `rewind-file-snapshots/index.json` |
| `core/WorkspaceYaml.kt` | lettura/scrittura del `workspace.yaml` piatto |
| `core/PathMapper.kt` | traduzione dei percorsi assoluti fra le due macchine |
| `core/TimestampShifter.kt` | traslazione uniforme dei timestamp verso il momento dell'import |
| `core/IdeSessionRecord.kt` | voce della cronologia chat lato IDE + `IdeSessionRewriter` + `IdeSessionRestorer` |
| `core/SessionInfo.kt` | metadati di sessione e `ImportedSession` (esito riletto da disco) |
| `core/ImportDiagnostics.kt` | diagnosi di visibilità `OK`/`KO` |
| `core/ImportLog.kt` | log diagnostico dell'import (`FileImportLog`, `NOOP`) |
| `ui/CopilotIdeSessionBridge.kt` | ponte reflection verso il plugin GitHub Copilot |
| `ui/ExportCopilotSessionsAction.kt` | azione di export |
| `ui/ImportCopilotSessionsAction.kt` | azione di import + notifica di esito |
| `ui/ExportSessionsDialog.kt`, `ui/ImportSessionsDialog.kt`, `ui/SessionSelectionPanel.kt` | dialoghi di selezione |
| `ui/CopilotNotifications.kt` | notifiche + azione "Restart IDE now" |
| `ui/CopilotSessionsBundle.kt` | risoluzione della lingua del pulsante di riavvio |
| `src/test/kotlin/.../core/SessionTransferTest.kt` | round-trip export/import, incluso il record IDE |
| `src/test/kotlin/.../core/TimestampShifterTest.kt` | traslazione dei timestamp |
| `src/test/kotlin/.../ui/CopilotSessionsBundleTest.kt` | fallback delle traduzioni |

### Vincoli architetturali da rispettare

1. **`core/` non deve dipendere dalle API IntelliJ.** Contiene solo Java/Kotlin stdlib, Gson e
   sqlite-jdbc, ed è così che i test girano senza IDE. Ogni dipendenza da `com.intellij.*` va
   confinata in `ui/`.
2. **Schema tolerance:** tabelle e colonne sconosciute vanno ignorate, mai assunte presenti; le
   chiavi primarie intere auto-generate non si copiano.
3. **Il contenuto della conversazione non si tocca mai.** Le riscritture sono strutturali
   (JSON per JSON, chiave per chiave), mai sostituzioni di testo sul transcript.
4. **Le API interne di altri plugin sono best-effort.** Se non ci sono o cambiano, la funzione
   degrada e lo segnala; non deve mai far fallire l'operazione.
5. **Il log diagnostico non può far fallire l'import** e non contiene mai testo di conversazione.
6. Commenti in inglese, solo dove il codice non è autoesplicativo, nello stile già presente nel
   repository: spiegano il *perché*, non il *cosa*.
7. Ogni comportamento osservabile va documentato in `CHANGELOG.md` **e** in `README.md`.

### Comandi

```bash
./gradlew test            # round-trip export/import, timestamp, traduzioni
./gradlew patchPluginXml  # genera il plugin.xml finale (verifica di Overview e What's New)
./gradlew buildPlugin     # ZIP in build/distributions
./gradlew runIde          # IDE di prova
./gradlew verifyPlugin    # IntelliJ Plugin Verifier sulle IDE consigliate
./gradlew signPlugin publishPlugin   # firma e pubblicazione (vedi README)
```

---

## 2. Perimetro funzionale della 1.0.0

* **`Tools | Github Copilot sessions | Export Sessions...`** — elenco di tutte le sessioni locali
  (nome, repository, branch, data, turni, id) con filtro e selezione multipla; salva la selezione in
  un archivio ZIP.
* **`Tools | Github Copilot sessions | Import Sessions...`** — legge un archivio, segnala le
  sessioni già presenti, applica la politica di conflitto scelta (*Skip*, *Replace*, *Duplicate*, che
  è il default) e offre l'opzione **"Attach the imported sessions to this folder"**.
* **Notifica di esito** per entrambe le operazioni, con l'azione **"Show import log"** e, dopo un
  import riuscito, **"Restart IDE now"**.

Il nome utente del plugin è **`Github Copilot sessions`** ovunque: `pluginName`, `<name>`, testo del
gruppo `CopilotSessionsImportExport.Menu`, id del `<notificationGroup>` (che **deve** coincidere con
`CopilotNotifications.GROUP_ID`, altrimenti le notifiche smettono di comparire), titoli dei dialoghi e
`producer` scritto nel manifest dell'archivio.

**Non** vanno mai cambiati il `<id>` del plugin né i nomi dei package: cambiare l'id farebbe apparire
il plugin come una nuova installazione, lasciando orfana quella esistente.

---

## 3. Il problema centrale: le due metà di una sessione

Ripristinare `~/.copilot` **non basta**. Una sessione vive in due posti distinti:

| Metà | Dove vive | Chi la usa |
|---|---|---|
| CLI | `~/.copilot/session-store.db` + `~/.copilot/session-state/<id>/` | la CLI `copilot` e il language server |
| IDE | `%LOCALAPPDATA%\github-copilot\<ide>\chat-agent-sessions\<progetto>\copilot-agent-sessions-nitrite.db` | la tool window "GitHub Copilot Chat" |

L'elenco della cronologia mostrato dalla chat è costruito sulla **seconda**: un database Nitrite
**per progetto**, di proprietà del plugin GitHub Copilot, che contiene le entità `NtAgentSession`,
`NtAgentTurn`, `NtAgentWorkingSetItem`. Il legame fra le due metà è `NtAgentSession.conversationId`,
che vale l'id della sessione CLI, cioè il nome della cartella sotto `session-state`.

Senza quella riga la sessione esiste su disco ed è perfettamente valida — `workspace.yaml` corretto,
`cwd` canonica, turni presenti, riga nel database — ma per l'IDE non esiste, e nessun riavvio la fa
comparire. È il motivo per cui un import può superare ogni controllo e non cambiare nulla sullo
schermo: ogni intervento su questo plugin deve tenerne conto.

### 3.1 Vincolo: il file non si può scrivere

Il database Nitrite del progetto aperto è tenuto **aperto e bloccato** dall'IDE in esecuzione (la
lettura fallisce con un byte-range lock). Scriverci direttamente è escluso.

L'unica via è passare dal plugin stesso, attraverso il suo servizio di progetto
`com.github.copilot.agent.session.persistence.AgentSessionPersistenceService`, raggiunto via
reflection sul class loader del plugin `com.github.copilot`.

Elementi rilevanti dell'API (plugin GitHub Copilot 1.17.0):

* `AgentSessionPersistenceService.Companion.getInstance(Project)`;
* tutti i metodi sono `suspend`, cioè sul bytecode hanno un parametro finale
  `kotlin.coroutines.Continuation`;
* `listAllSessions()`, `getSession(String)`, `createSession(PersistedAgentSession)`,
  `deleteSession(String)`;
* i converter `NtAgentSessionKt.toNtAgentSession(PersistedAgentSession)` e
  `NtAgentSessionKt.toPersistedAgentSession(NtAgentSession, List<NtAgentTurn>, List<NtAgentWorkingSetItem>)`;
* `NtAgentSession` **incorpora** le liste `turns` e `workingSet`, e `createSession` inserisce il
  documento completo in una sola volta: non servono chiamate separate a `createTurn`;
* un working set item è identificato dal **file url**, non da un id: solo un turno ha un id. Un
  probe difensivo che cerchi `PersistedAgentWorkingSetItem.getId()` fallisce sempre e disattiva
  l'intero ponte — controllare soltanto membri che esistono davvero.

Il plugin GitHub Copilot non impacchetta una propria kotlin-stdlib, quindi
`kotlin.coroutines.Continuation` è la stessa classe per entrambi i plugin e l'invocazione riflessiva
di una `suspend` è praticabile.

---

## 4. Architettura

### 4.1 `core/IdeSessionRecord.kt`

`core/` non può vedere né IntelliJ né Copilot, quindi la voce viaggia come **JSON grezzo**:

```kotlin
data class IdeSessionRecord(
    val conversationId: String? = null,
    val session: JsonObject? = null,
    val turns: JsonArray = JsonArray(),
    val workingSet: JsonArray = JsonArray(),
)
```

`IdeSessionRewriter.adopt(record, targetConversationId, deltaMillis, mappers)` adatta la voce alla
macchina di destinazione, **chiave per chiave**:

* nuovo id interno (UUID) per la sessione, propagato al `sessionId` di turni e working set: un
  re-import non deve collidere con la voce da cui è stato prodotto;
* `conversationId` forzato all'id CLI di destinazione (che con la policy *Duplicate* è nuovo);
* chiavi `createdAt`, `modifiedAt`, `activeAt`, `deletedAt`: traslate dello **stesso** `delta` già
  usato per il resto dell'import, così le due metà restano coerenti;
* chiavi `fileUrl`, `path`, `filePath`, `dirName`, `workspaceFolder`, `uri`: tradotte con i
  `PathMapper` dell'import;
* **niente altro.** `contents` e `stringContent` contengono ciò che si sono detti utente e agente e
  non vengono toccati (vincolo §1.3).

L'interfaccia funzionale `IdeSessionRestorer` permette all'import di delegare la scrittura al livello
`ui/`, con `NOOP` per i test e i chiamanti headless.

### 4.2 `ui/CopilotIdeSessionBridge.kt`

Ponte reflection, interamente `runCatching`:

* `isAvailable()`, `capture(project, conversationIds)`, `restore(project, record)`,
  `isListed(project, conversationId)`;
* le `suspend` sono invocate con una `BlockingContinuation` basata su `CountDownLatch`: se la
  chiamata riflessiva restituisce il marcatore `COROUTINE_SUSPENDED` (riconosciuto dal nome di
  classe `kotlin.coroutines.intrinsics.CoroutineSingletons`) si attende il latch, altrimenti si usa
  il valore restituito. Timeout di 60 secondi: una chiamata che non torna mai è peggio di una che
  fallisce;
* **legale solo fuori dall'EDT**: entrambi i chiamanti girano dentro un `Task.Backgroundable`;
* prima di inserire, `restore` cancella le eventuali voci già presenti con lo stesso
  `conversationId`, così un import ripetuto non duplica la riga nell'elenco;
* il class loader del plugin Copilot si risolve con `PluginManager.getInstance().findEnabledPlugin`.
  Da IntelliJ 2026.2 quell'accessore è `@ApiStatus.Internal` e non esiste un equivalente pubblico:
  l'avviso del Plugin Verifier è atteso e neutralizzato tramite `failureLevel` (vedi README).

### 4.3 Formato dell'archivio

```
manifest.json                   # versione formato, data, producer, home di origine, elenco sessioni
sessions/<id>/store.json        # righe del database, tabella per tabella
sessions/<id>/state/...         # copia fedele di session-state/<id>
sessions/<id>/ide-session.json  # voce della cronologia chat dell'IDE
```

`SessionTransfer.FORMAT_VERSION` vale **1**. L'archivio è autodescrittivo: una entry che manca si
gestisce per la sua assenza, mai in base al numero di formato, che serve solo a rifiutare un layout
incomprensibile a questa build. `MAX_READABLE_FORMAT_VERSION` vale 2 perché le build di sviluppo
precedenti alla 1.0.0 marcavano con `2` esattamente questo layout: quegli archivi si leggono invece
di essere rifiutati.

* `export(..., ideSessions: Map<String, IdeSessionRecord>)` scrive la voce quando c'è, altrimenti
  emette un warning esplicito. La voce è **per progetto**: una sessione appartenente a un altro
  progetto non è leggibile da qui;
* se l'API di persistenza non è raggiungibile del tutto (plugin assente, disabilitato, nessun
  progetto aperto, eccezione nel risolvere l'API) va emesso **un solo** avviso con il motivo reale,
  non un messaggio per sessione: non è un problema delle singole conversazioni;
* `import(..., ideSessionRestorer: IdeSessionRestorer)` legge la voce, la fa adottare da
  `IdeSessionRewriter` e la passa al restorer;
* `ImportedSession` espone `ideRecordPresent` e `ideRecordRestored`, che alimentano sia la notifica
  sia la diagnosi.

### 4.4 Adozione della cartella di stato

`session-state/<id>` descrive per intero la macchina di origine: `events.jsonl` si apre con un
`session.start` che contiene id e `cwd` originali, ripetuti in ogni evento successivo, e
`rewind-file-snapshots/index.json` conserva percorsi assoluti di quella macchina.

L'import adotta la cartella replicando ciò che la CLI fa quando forka una sessione: nuovo
identificativo, `cwd` di destinazione, `alreadyInUse: false`, e rimappatura di ogni id e percorso nei
campi noti (`cwd`, `gitRoot`, `checkpointPath`, `filePath`, `transcriptPath`, `fileName`, `paths`,
`possiblePaths`). Il rimappaggio procede dal più specifico al più generico: prima
`session-state/<id>`, poi il resto di `~/.copilot`, infine la cartella di lavoro. Le stesse regole
valgono per `session_files.file_path`.

Se `workspace.yaml` manca nell'archivio — tipicamente perché la chat di origine era aperta e il file
era bloccato — va **ricostruito** dalla riga di database esportata, e scritto con `LF`.

### 4.5 Normalizzazione dei percorsi

La ricerca che Copilot esegue sulla directory di lavoro è un confronto **esatto**. L'IDE comunica
`C:\Users\bob\work\demo`, la CLI memorizza e cerca `c:\Users\bob\work\demo`, con la lettera di unità
**minuscola**. `CopilotPaths.normalizeWorkspacePath()` impone quella forma prima della scrittura, e
la `cwd` viene riscritta nella forma canonica della destinazione **anche** quando origine e
destinazione coincidono: senza questa forzatura un percorso scritto in modo diverso produce un import
riuscito e una sessione mai elencata.

### 4.6 Timestamp

Tutti i timestamp della sessione — `session-store.db`, `workspace.yaml`, `events.jsonl`, voce IDE —
sono traslati dello **stesso** scarto, calcolato una sola volta dal timestamp più recente presente
nell'archivio, così `updated_at` cade nel momento dell'import e la sessione compare in cima alla
cronologia, mentre `created_at` e la spaziatura fra i turni restano invariati. Nessun timestamp della
macchina di origine sopravvive e nessuno finisce nel futuro. Anche la data di modifica dei file
ripristinati viene rinfrescata. Un valore che l'archivio non conteneva ricade sul momento
dell'import.

`TimestampShifter` riconosce i valori **per forma**, non per colonna, e li riscrive nella stessa
forma in cui li ha letti: epoch in millisecondi (13 cifre), epoch in secondi (10 cifre), ISO-8601
con separatore, frazione e suffisso `Z` preservati, e il formato SQL usato da Copilot. Un valore che
non corrisponde a nessuna di queste forme viene **restituito invariato**: meglio un timestamp non
traslato che un turno corrotto.

Le due liste che delimitano l'intervento sono contrattuali e verificate dai test:

* colonne del database (`isTimestampColumn`): `created_at`, `updated_at`, `timestamp`, `time`,
  `date`. Colonne come `summary` o `user_message` non vanno mai toccate;
* chiavi degli eventi (`EVENT_TIMESTAMP_KEYS`): `timestamp`, `time`, `createdAt`, `updatedAt`,
  `startedAt`, `endedAt`, `ts`.

Allargare una delle due liste significa rischiare di riscrivere un numero che appartiene alla
conversazione: va fatto solo con un test che dimostri il contrario.

---

## 5. Diagnostica e messaggi

### 5.1 La checklist di visibilità

`ImportDiagnostics` produce **dodici** controlli `OK`/`KO`, numerati esattamente così. Il numero fa
parte del contratto: è il riferimento usato quando si legge un log senza avere accesso alla macchina.

| # | Controllo |
|---|---|
| 1 | `workspace.yaml` esiste ed è leggibile |
| 2 | l'`id` in `workspace.yaml` coincide col nome della cartella |
| 3 | `cwd` non è vuota |
| 4 | `cwd` è identica byte per byte alla cartella di progetto normalizzata |
| 5 | `cwd` è in forma canonica (drive minuscolo, `\`, nessuno slash finale) |
| 6 | la cartella indicata da `cwd` esiste su questa macchina |
| 7 | `events.jsonl` esiste, non è vuoto e inizia con `session.start` per questo id |
| 8 | nessun percorso residuo della macchina di origine nei campi machine-facing di `events.jsonl` |
| 9 | la riga `sessions` esiste e la sua `cwd` coincide con `workspace.yaml` |
| 10 | `turn_count > 0` |
| 11 | nessun file di lock rimasto nella cartella ripristinata |
| 12 | la voce della cronologia chat è stata reinserita nell'IDE |

Il **#12** è quello decisivo: tutti gli altri possono essere `OK` e la conversazione restare
invisibile. Distingue *archivio senza voce* da *voce rifiutata dal plugin Copilot*.

### 5.2 Messaggi

* il suggerimento *"Close the affected conversations (or restart the IDE) and export again"* va
  mostrato **solo** quando un file `session-state` non è stato davvero letto, che è l'unico caso che
  risolve;
* il suggerimento di riavvio spiega il motivo vero: la chat legge il proprio elenco una sola volta,
  all'apertura del progetto;
* ogni import scrive `<log dell'IDE>/copilot-sessions-import/import-yyyyMMdd-HHmmss.log`, senza
  opzioni da abilitare, con ambiente, contesto, sotto-log per sessione, la checklist di §5.1,
  confronto con l'ultima sessione nativa ed estratto del registro IDE di Copilot. Valori stringa
  troncati a 300 caratteri, ultimi 20 file conservati.

---

## 6. Localizzazione

Ogni testo del plugin - dialoghi di export/import, colonne e stato della tabella delle sessioni,
notifiche, avvisi di export/import, messaggi di errore, oltre al pulsante **"Restart IDE now"** -
passa da `CopilotSessionsBundle`, non solo quel pulsante. La risoluzione è uguale per ogni chiave, in
due passaggi: prima la lingua di visualizzazione dell'IDE quando è una localizzazione esplicita, poi le
impostazioni internazionali del sistema operativo; altrimenti inglese. Oltre all'inglese: italiano,
francese, tedesco, spagnolo, portoghese, giapponese, cinese, coreano. Per aggiungerne una basta
creare `src/main/resources/messages/CopilotSessionsBundle_<lingua>.properties` con le stesse chiavi
del file inglese.

---

## 7. Versione e rilascio

### 7.1 Icone

Due asset distinti, perché servono a due scopi diversi:

* `META-INF/pluginIcon.svg` e `pluginIcon_dark.svg` — il **logo** mostrato su *Settings | Plugins* e
  sul Marketplace. È l'emblema circolare del progetto: anello turchese, freccia verde `IMPORT` a
  sinistra, freccia blu `EXPORT` a destra, il segno GitHub e l'assistente al centro. È un raster
  incapsulato in SVG (`<image>` con data URI PNG a 256 px): il formato del file resta quello
  richiesto dal Marketplace e il disegno è identico all'originale. Nessuna nuvola di chat.
* `icons/copilotSessions.svg` e `copilotSessions_dark.svg` — l'**icona d'azione** 16×16 usata nei
  menu e nel popup. A 16 px l'emblema completo sarebbe illeggibile, quindi resta vettoriale e
  riprende solo la parte che a quella dimensione si riconosce ancora: le due frecce contrapposte,
  verde in entrata e blu in uscita, con gli stessi colori del logo.

### 7.2 Versione

`gradle.properties` → `pluginVersion=1.0.0`, prima e unica versione pubblicata.

`CHANGELOG.md` contiene un'unica sezione **datata** `## [1.0.0] - 2026-09-17`. La data è
obbligatoria: alimenta l'intestazione `[versione] - [data]` del riquadro **What's New**. La sezione
`## [Unreleased]` resta vuota, perché senza data non potrebbe alimentare quell'intestazione.

### 7.3 Firma e pubblicazione

Firma e pubblicazione leggono **solo** variabili d'ambiente (`CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
`PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN`): nessuna credenziale entra nel repository. Il canale è
dedotto da `pluginVersion`. Procedura completa nel README, sezione *Pubblicazione sul JetBrains
Marketplace*.

---

## 8. Checklist di verifica finale

1. `./gradlew test` verde.
2. `./gradlew patchPluginXml`: in `build/tmp/patchPluginXml/plugin.xml` il `<name>` è
   `Github Copilot sessions` e `<change-notes>` inizia con `[1.0.0] - 2026-09-17`.
3. `./gradlew buildPlugin`: lo ZIP si chiama `github-copilot-sessions-1.0.0.zip` e contiene
   `icons/copilotSessions.svg` e `icons/copilotSessions_dark.svg`.
4. `./gradlew verifyPlugin` verde: **Compatible** su tutte le IDE consigliate. Gli unici avvisi
   ammessi sono gli usi di API interne del modello dei plugin, documentati nel README.
5. L'id del `<notificationGroup>` in `plugin.xml` coincide con `CopilotNotifications.GROUP_ID`.
6. `core/` non importa nulla da `com.intellij.*` né da `com.github.copilot.*`.
7. `README.md` e `CHANGELOG.md` descrivono ogni comportamento osservabile.
8. Nessun riferimento a versioni diverse dalla 1.0.0 in codice, documentazione e messaggi utente.
