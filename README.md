# Github Copilot sessions

Plugin IntelliJ (Kotlin) per **esportare e importare le sessioni di chat di GitHub Copilot**.

Permette di scegliere puntualmente quali conversazioni esportare in un archivio ZIP portabile e quali
sessioni ripristinare da un archivio, ad esempio per spostarle su un'altra macchina o dopo un reset
della postazione.

## Funzionalità

* **Tools → Github Copilot sessions → Export Sessions...**
  Mostra tutte le sessioni presenti in locale (nome, repository, branch, data, numero di turni, id),
  con filtro di ricerca e selezione multipla tramite checkbox; salva la selezione in un file ZIP.
* **Tools → Github Copilot sessions → Import Sessions...**
  Legge un archivio, elenca le sessioni contenute segnalando quelle già presenti e consente di
  scegliere quali importare e come gestire i conflitti:
  * *Skip* – ignora le sessioni già presenti;
  * *Replace* – sostituisce la sessione locale;
  * *Duplicate* – importa come nuova copia con un nuovo id (default).

  Il dialogo espone inoltre l'opzione **"Attach the imported sessions to this folder"**, spiegata
  in [Cartella di lavoro e visibilità](#cartella-di-lavoro-e-visibilità).

## Cosa viene esportato

I dati di una sessione Copilot vivono in **tre** punti, tutti inclusi nell'archivio:

| Origine | Contenuto |
|---|---|
| `~/.copilot/session-store.db` (SQLite) | righe delle tabelle `sessions`, `turns`, `checkpoints`, `session_files`, `session_refs`, `forge_trajectory_events`, `assistant_usage_events`, `search_index` |
| `~/.copilot/session-state/<id>/` | `workspace.yaml`, `events.jsonl`, `session.db`, `checkpoints/`, `files/`, `research/` … |
| database Nitrite del plugin GitHub Copilot, dentro l'IDE | la **voce della cronologia chat**: `NtAgentSession`, i suoi `NtAgentTurn` e il working set |

I primi due appartengono alla **CLI** di Copilot; il terzo appartiene al **plugin IDE** ed è ciò che
la tool window "GitHub Copilot Chat" elenca davvero. Vedi
[Le due metà di una sessione](#le-due-metà-di-una-sessione).

La posizione della home della CLI può essere sovrascritta con la variabile d'ambiente `COPILOT_HOME`
o con la property di sistema `copilot.home`.

### Formato dell'archivio

```
manifest.json                 # versione formato, data, elenco sessioni
sessions/<id>/store.json      # righe del database, tabella per tabella
sessions/<id>/state/...       # copia fedele di session-state/<id>
sessions/<id>/ide-session.json  # voce della cronologia chat dell'IDE
```

L'archivio è **autodescrittivo**: `manifest.json` dichiara la versione del formato, oggi **1**. Una
sessione il cui `ide-session.json` manca — perché esportata da un progetto diverso da quello a cui
appartiene — viene ripristinata su disco ma **non compare** nella chat; il caso è riconosciuto
dall'assenza della entry, non dal numero di formato, e va risolto ri-esportando la sessione dal
progetto giusto.

## Le due metà di una sessione

Ripristinare `~/.copilot` **non basta** perché una conversazione ricompaia nella chat di IntelliJ.

La cartella `~/.copilot` è il magazzino della **CLI**. L'elenco che vedi nella tool window "GitHub
Copilot Chat" è invece servito da un database **Nitrite per progetto**, di proprietà del plugin
GitHub Copilot, che su Windows si trova in
`%LOCALAPPDATA%\github-copilot\<ide>\chat-agent-sessions\<progetto>\copilot-agent-sessions-nitrite.db`.
Le due metà sono collegate dal campo `conversationId`, che vale l'id della sessione CLI.

Ripristinare solo la metà CLI non basta: l'import risulterebbe perfettamente riuscito —
`workspace.yaml` valido, `cwd` corretta, riga nel database, turni presenti — e la sessione non
comparirebbe comunque, nemmeno dopo un riavvio dell'IDE, perché il plugin GitHub Copilot non ne
saprebbe nulla. Per questo l'export cattura anche quella voce e l'import la reinserisce.

Come viene reinserita:

* il plugin **non scrive** direttamente sul file Nitrite: quel file è tenuto aperto e bloccato
  dall'IDE in esecuzione. La voce viene passata al plugin GitHub Copilot attraverso il suo servizio
  `AgentSessionPersistenceService`, raggiunto via reflection sul suo class loader;
* si tratta di un'API **interna e non documentata** di un altro plugin: se non è disponibile (plugin
  assente, disabilitato o con un'API cambiata) l'import prosegue e ripristina comunque la metà CLI,
  segnalando che la sessione non comparirà nella chat;
* alla voce viene assegnato un nuovo id interno, così un re-import non collide con quello di
  partenza; un'eventuale voce già presente per la stessa conversazione viene rimossa prima, per non
  duplicare la riga nell'elenco;
* id, timestamp e percorsi vengono adattati alla macchina di destinazione con le stesse regole usate
  per il resto dell'import, **chiave per chiave**: il testo della conversazione non viene mai
  toccato.

L'export segnala con un avviso le sessioni per cui la voce non è stata trovata — tipicamente perché
appartengono a un progetto diverso da quello aperto: la voce della cronologia è per progetto, quindi
**va esportata dal progetto a cui la sessione appartiene**.

## Schema tolerance

La lettura e la scrittura del database sono *schema tolerant*: tabelle o colonne sconosciute alla
versione locale di Copilot vengono ignorate, quindi gli archivi restano compatibili fra versioni
diverse della CLI. Le chiavi primarie intere auto-generate non vengono copiate, così l'import non
entra in conflitto con le righe già presenti.

## Cartella di lavoro e visibilità

L'elenco delle sessioni mostrato dalla chat **non** viene da `session-store.db`: la CLI di Copilot lo
costruisce dai metadati delle cartelle `session-state/*` e lo filtra confrontando in modo **esatto**
la directory di lavoro con quella del progetto aperto. Il database alimenta solo la ricerca. La
sorgente autoritativa è quindi `session-state/<id>/workspace.yaml`: se quel file manca, non è valido
o punta a un'altra cartella, la sessione esiste su disco ma non compare da nessuna parte.

Di conseguenza una sessione esportata da `C:\Users\alice\progetti\demo` e importata su un altro PC —
dove lo stesso progetto si trova, ad esempio, in `C:\Users\bob\work\demo` — viene scritta
correttamente ma **non compare** nell'elenco, perché continua a puntare alla cartella di origine.

Per questo il dialogo di import offre l'opzione **"Attach the imported sessions to this folder"**,
pre-selezionata quando la cartella registrata nell'archivio è diversa da quella del progetto aperto.
Quando è attiva, l'import riscrive i percorsi assoluti della sessione:

* `sessions.cwd` e `session_files.file_path` nel database;
* le chiavi `cwd` e `git_root` di `workspace.yaml`.

La traduzione mantiene i percorsi relativi: i discendenti della vecchia `cwd` vengono riagganciati
alla nuova, mentre gli antenati (tipicamente `git_root`) conservano lo stesso numero di livelli.
Il confronto ignora maiuscole/minuscole e la differenza fra `\` e `/`.

### Adozione della cartella di stato

Riscrivere `workspace.yaml` non basta: la cartella `session-state/<id>` descrive per intero la
macchina che ha prodotto l'archivio. In particolare `events.jsonl` si apre con un evento
`session.start` che contiene l'identificativo **originale** della sessione e la `cwd` **originale**,
ripetuti poi in ogni evento successivo, e `rewind-file-snapshots/index.json` conserva percorsi
assoluti di quella macchina.

L'import adotta quindi la cartella sulla macchina di destinazione, replicando ciò che la CLI fa
quando forka una sessione: il primo evento riceve il nuovo identificativo, la `cwd` di destinazione e
`alreadyInUse: false`; ogni identificativo e ogni percorso registrato nei campi noti (`cwd`,
`gitRoot`, `checkpointPath`, `filePath`, `transcriptPath`, `fileName`, `paths`, …) viene rimappato.
Il rimappaggio procede dal più specifico al più generico: prima `session-state/<id>` della sessione
stessa, poi il resto di `~/.copilot` dell'altro PC — altre sessioni, cache, registro dell'IDE — e
infine la cartella di lavoro. Le stesse regole valgono per la colonna `session_files.file_path` del
database, che registra anche i file scritti dall'agente dentro la propria cartella di stato. La
riscrittura è **strutturale** (JSON per JSON, non sostituzione di testo): il contenuto della
conversazione non viene mai alterato, e le righe eventualmente illeggibili sono copiate tali e quali.

Se l'archivio non conteneva `workspace.yaml` — tipicamente perché la chat di origine era aperta e il
file risultava bloccato — il file viene **ricostruito** dalla riga di database esportata, invece di
lasciare una cartella invisibile.

### Normalizzazione del percorso su Windows

La ricerca che Copilot esegue sulla directory di lavoro è un confronto **esatto**, mentre l'IDE e la
CLI scrivono il percorso in due forme diverse: IntelliJ comunica `C:\Users\bob\work\demo` (vedi
`~/.copilot/ide/.registry/*.jsonl`), ma la CLI memorizza e cerca `c:\Users\bob\work\demo`, con la
lettera di unità **minuscola**. Per questo la cartella di destinazione viene normalizzata prima di
essere scritta (separatori `\` e drive minuscolo): senza questo passaggio l'import va a buon fine ma
la sessione non compare mai nell'elenco della chat.

Disattivando l'opzione la sessione mantiene la cartella originale e sarà visibile solo aprendo
esattamente quel percorso.

Quando l'opzione è attiva la `cwd` viene **sempre** riscritta nella forma canonica della
destinazione, anche quando la cartella di origine e quella di arrivo coincidono: senza questa
forzatura una sessione esportata da un percorso scritto in modo diverso (`C:/Users/...` invece di
`c:\Users\...`) verrebbe importata correttamente ma non comparirebbe mai nell'elenco.

## Verifica automatica dell'import

Al termine dell'import il plugin **rilegge da disco** ciò che ha appena scritto e lo mostra nella
notifica: nome della sessione, cartella a cui è agganciata e numero di turni. Il controllo è fatto su
`workspace.yaml`, cioè sulla stessa sorgente che governa la visibilità, non sul database. Vengono
segnalati esplicitamente i casi problematici:

* la cartella ripristinata non contiene un `workspace.yaml` utilizzabile (la sessione non può essere
  elencata);
* la `cwd` scritta è diversa dalla cartella del progetto aperto (Copilot la mostrerà solo aprendo
  quella cartella);
* l'archivio non contiene la voce della cronologia chat dell'IDE: la sessione viene ripristinata su
  disco ma non comparirà nella chat;
* il plugin GitHub Copilot ha rifiutato la voce della cronologia (assente, disabilitato o con un'API
  cambiata): il motivo preciso è nel log diagnostico;
* la sessione è stata ripristinata senza i contenuti della conversazione;
* la sessione è elencata ma non indicizzata per la ricerca, perché l'archivio non conteneva la
  relativa riga di `session-store.db`.

Anche l'export segnala ora i file che non è riuscito a leggere — tipicamente `events.jsonl` e
`session.db` di una chat ancora aperta, che Copilot tiene bloccati — invece di produrre in silenzio
un archivio incompleto che sull'altra macchina si ripristina come **sessione vuota**, e le sessioni
per cui non ha trovato la voce della cronologia nell'IDE.

I due casi sono distinti, perché la soluzione è diversa:

* se l'API di persistenza del plugin GitHub Copilot **non è raggiungibile** (plugin assente,
  disabilitato, nessun progetto aperto, API cambiata) viene emesso **un solo** avviso con il motivo
  effettivo: nessuna sessione può contenere la voce di cronologia, non è un problema delle chat;
* se l'API risponde ma non conosce una specifica conversazione, l'avviso riguarda **quella** sessione
  e va risolto ri-esportandola dal progetto a cui appartiene.

### Lingua dell'interfaccia

Ogni testo del plugin è tradotto: i dialoghi di export/import, le colonne e lo stato della tabella
delle sessioni, le notifiche di esito, gli avvisi e i messaggi di errore, oltre al pulsante
**"Restart IDE now"** della notifica di import. Ogni etichetta è risolta negli stessi due passaggi:

1. la lingua di visualizzazione dell'IDE, quando è una localizzazione esplicita (cioè quando è
   installato un language pack diverso dall'inglese);
2. le impostazioni internazionali del sistema operativo, così l'interfaccia parla la lingua della
   macchina anche su un IDE in inglese.

Se nessuna delle due è tradotta si ricade sull'inglese. Oltre all'inglese sono incluse italiano,
francese, tedesco, spagnolo, portoghese, giapponese, cinese e coreano: per aggiungerne un'altra basta
creare `src/main/resources/messages/CopilotSessionsBundle_<lingua>.properties` con le stesse chiavi
del file inglese.

## Log diagnostico dell'import

Ogni import scrive un file di log dettagliato in
`<cartella dei log dell'IDE>/copilot-sessions-import/import-yyyyMMdd-HHmmss.log` (la cartella dei log
dell'IDE è quella aperta da *Help → Show Log in Explorer/Finder*). Il log **viene sempre scritto**,
senza alcuna opzione da abilitare: serve a capire, senza avere accesso alla macchina, perché una
sessione importata non compaia nella cronologia della chat.

Contiene, in ordine:

* l'**ambiente**: versione del plugin e dell'IDE, JVM, sistema operativo, locale, percorsi di
  `~/.copilot` e lo schema del database effettivamente trovato;
* il **contesto dell'operazione**: archivio, policy sui conflitti, cartella di rilocazione richiesta
  e il contenuto del manifest dell'archivio;
* per ogni sessione, un sotto-log con l'estrazione dello stato, la riscrittura di `workspace.yaml` ed
  `events.jsonl`, l'import nel database e la verifica di quanto scritto, riletta da disco;
* una **diagnosi di visibilità** con esito esplicito `OK`/`KO` su dodici controlli (esistenza e
  contenuto di `workspace.yaml`, corrispondenza della `cwd`, presenza di `events.jsonl`, percorsi
  residui della macchina di origine, riga nel database, file di lock rimasti e, dal controllo #12, il
  reinserimento della voce nella cronologia chat dell'IDE);
* un **confronto** con l'ultima sessione nativa già presente sulla macchina, utile a individuare una
  chiave obbligatoria mancante;
* un estratto del **registro IDE di Copilot** (`~/.copilot/ide/.registry/*.jsonl`);
* un **riepilogo finale** con i conteggi e, per ogni fallimento, il messaggio e lo stack trace
  completo.

Il file **non contiene mai il testo delle conversazioni**: solo percorsi, chiavi, conteggi,
dimensioni e metadati, con i valori stringa troncati a 300 caratteri. Ogni scrittura è *best-effort*
e non può mai far fallire l'import, nemmeno quando la cartella dei log non è scrivibile. Vengono
conservati solo gli ultimi 20 file: i più vecchi vengono cancellati a ogni nuovo import. La notifica
di esito riporta il percorso del file e offre l'azione **"Show import log"** per aprirlo
direttamente — anche quando l'import termina con un avviso o con un errore.

## Note operative

* I file di lock (`inuse.*.lock`) non vengono esportati.
* L'import richiede che Copilot sia già stato avviato almeno una volta sulla macchina di destinazione
  (il database deve esistere con il suo schema).
* Dopo un import, la notifica offre il pulsante **"Restart IDE now"**: la voce della cronologia viene
  scritta subito nello storage del plugin GitHub Copilot, ma la chat legge il proprio elenco una sola
  volta, all'apertura del progetto, quindi il riavvio dell'IDE è il modo affidabile per vedere le
  sessioni ripristinate. In alternativa si può riavviare a mano; non esiste un'azione dedicata di
  "restart della chat", perché il processo `copilot-language-server` viene riavviato insieme all'IDE.
* Se una sessione importata non compare, leggere la notifica di import: indica la cartella a cui la
  sessione è stata agganciata e se la voce della cronologia è stata ripristinata. Se l'archivio non
  la conteneva, va ri-esportato dalla macchina di origine aprendo il progetto giusto.
* L'export della voce della cronologia è **per progetto**: per portare via una sessione, esportala
  aprendo il progetto a cui appartiene, altrimenti l'archivio contiene solo la metà CLI.
* Per correggere un import precedente finito nella cartella sbagliata, ripetere l'import con politica
  *Replace* e l'opzione di rilocazione attiva.
* Se una sessione si ripristina **vuota**, è stata esportata mentre la chat era aperta: chiudere la
  conversazione sulla macchina di origine e rifare l'export controllando che non compaiano warning.
* È consigliabile non avere sessioni Copilot attive durante un import con politica *Replace*.
* Le sessioni importate vengono datate come appena create su questo PC: tutti i timestamp (data di
  creazione, di aggiornamento, dei singoli turni) sono traslati dello stesso scarto, calcolato dal
  timestamp più recente presente nell'archivio, così la sessione compare in cima alla cronologia
  invece che con la data della macchina di origine. La spaziatura fra i turni resta invariata.

## Sviluppo

```bash
./gradlew buildPlugin   # produce build/distributions/github-copilot-sessions-<versione>.zip
./gradlew test          # test di round-trip export/import
./gradlew runIde        # avvia una IDE di prova con il plugin installato
```

La versione è in `gradle.properties` (`pluginVersion`). Le note di rilascio si scrivono in
`CHANGELOG.md` nel formato *Keep a Changelog*: la sezione corrispondente alla versione corrente viene
convertita in HTML e inserita automaticamente nel tag `<change-notes>` di `plugin.xml`, cioè nel
riquadro **What's New** della pagina del plugin. Il riquadro si apre sempre con l'intestazione
`[<versione>] - <data>` (ricavata dall'intestazione `## [<versione>] - <data>` della sezione
corrispondente di `CHANGELOG.md`, che per questo deve riportare sempre una data), seguita da tutte le
voci `Added`/`Changed`/`Fixed` di quella versione; la sezione `## [Unreleased]` resta invece sempre
vuota, perché senza data non potrebbe alimentare quell'intestazione.

Requisiti: JDK 21, IntelliJ IDEA 2026.1 o successive.
Il plugin si installa da *Settings → Plugins → ⚙ → Install Plugin from Disk...* selezionando lo ZIP
prodotto in `build/distributions`.

## Pubblicazione sul JetBrains Marketplace

```bash
./gradlew verifyPlugin    # struttura del plugin + IntelliJ Plugin Verifier sulle IDE consigliate
./gradlew signPlugin      # firma l'archivio (build/distributions/*-signed.zip)
./gradlew publishPlugin   # carica l'archivio firmato sul Marketplace
```

`signPlugin` e `publishPlugin` **non contengono alcuna credenziale**: leggono esclusivamente
l'ambiente, quindi nessun segreto finisce nel repository.

| Variabile d'ambiente | Contenuto |
|---|---|
| `CERTIFICATE_CHAIN` | catena di certificati in formato PEM |
| `PRIVATE_KEY` | chiave privata in formato PEM |
| `PRIVATE_KEY_PASSWORD` | passphrase della chiave privata |
| `PUBLISH_TOKEN` | token generato da *JetBrains Hub → Marketplace → Personal access token* |

La coppia chiave/certificato si genera come descritto nella
[guida ufficiale alla firma dei plugin](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html);
il token si crea dal profilo sul [Marketplace](https://plugins.jetbrains.com/author/me/tokens).

Il canale di pubblicazione è dedotto da `pluginVersion`: una versione stabile (`1.0.0`) va sul canale
`default`, mentre una pre-release (`1.1.0-beta.1`) va sul canale omonimo (`beta`), visibile solo a
chi lo ha aggiunto fra i repository dei plugin.

### Avvisi attesi del Plugin Verifier

`verifyPlugin` riporta **1 uso di API interne** (`PluginManager.findEnabledPlugin`) su IntelliJ
2026.2 e successive. Non è un difetto correggibile: il bridge verso il plugin GitHub Copilot deve
risolverne il class loader, e da 2026.2 JetBrains ha marcato `@ApiStatus.Internal` ogni accessore in
grado di farlo, senza fornire un sostituto pubblico. `resolveCopilotPluginDescriptor()` in
`CopilotIdeSessionBridge.kt` è l'**unico** punto del plugin che invoca quell'API: sia il bridge verso
Copilot sia la voce diagnostica nel log di import passano da lì, così il Plugin Verifier conta un
solo utilizzo invece di ripeterlo per ogni chiamante. È un avviso, non un problema di compatibilità,
e non blocca la pubblicazione sul Marketplace; per questo `failureLevel` in `build.gradle.kts` lo
esclude, mentre continua a far fallire la build sui problemi reali di compatibilità, sulla struttura
del plugin, sulle dipendenze mancanti e sulle API già pianificate per la rimozione. Il plugin
risulta **Compatible** su IU-261, IU-262 e IU-263.

Checklist prima di un rilascio:

1. aggiornare `pluginVersion` in `gradle.properties`;
2. aggiungere in `CHANGELOG.md` una sezione `## [<versione>] - <data>` **con la data**, lasciando
   vuota `## [Unreleased]`;
3. `./gradlew clean test verifyPlugin` senza errori;
4. `./gradlew signPlugin publishPlugin` con le quattro variabili d'ambiente impostate.

Il primo caricamento di un plugin sul Marketplace passa per una **revisione manuale** di JetBrains e
richiede un `pluginId` univoco (`com.github.fabiopelliccia.copilotsessionsimportexport`), una
`<description>` e un `<vendor>` compilati in `plugin.xml`, e una licenza: tutto è già presente in
questo repository.

## Licenza

[MIT](LICENSE) © Fabio Pelliccia

## Special thanks

Un ringraziamento sentito ad **Antonio Petricca**, che ha seguito il plugin fin dai primi passi: i
suoi suggerimenti ne hanno guidato le scelte e le sue prove sul campo hanno fatto emergere problemi
che nessun test avrebbe intercettato. Grazie per il tempo e per la cura che ci ha messo.
