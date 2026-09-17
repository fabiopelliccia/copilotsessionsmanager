# Brief originale — Github Copilot sessions

Documento di origine del progetto: raccoglie, nella forma in cui sono stati espressi, i requisiti che
hanno portato alla **1.0.0**, prima e unica versione rilasciata. Non è una specifica tecnica: la
specifica operativa, con vincoli, architettura e checklist, è `documentation/TASK.md`.

## Perché il plugin esiste

Le sessioni di chat di GitHub Copilot restano legate alla macchina su cui sono nate. Non esiste un
modo ufficiale per portarle altrove — su un secondo PC, o sulla stessa postazione dopo un reset — né
per sceglierne solo alcune. Il plugin serve a esportarle in un archivio portabile e a ripristinarle
dove servono.

## Requisiti richiesti

* **Export selettivo.** Elencare tutte le conversazioni presenti in locale e permettere di scegliere
  puntualmente quali salvare in un archivio ZIP.
* **Import con gestione dei conflitti.** Rileggere l'archivio, segnalare le sessioni già presenti e
  poter decidere se saltarle, sostituirle o importarle come copia.
* **Le sessioni importate devono comparire davvero nella chat.** È il requisito che ha guidato tutto
  il resto: un import che scrive i file corretti ma lascia la conversazione invisibile non vale
  nulla. Da qui la necessità di ripristinare anche la voce della cronologia che il plugin GitHub
  Copilot tiene nel proprio storage, oltre ai dati della CLI.
* **Datazione locale.** Una sessione importata va datata come se fosse stata appena creata sulla
  macchina di destinazione, non con la data di quella di origine, così compare in cima all'elenco.
* **Log diagnostico dell'import.** Ogni import deve produrre un file di log che spieghi con
  precisione cosa ha fatto e, soprattutto, perché una sessione ripristinata comparirebbe o non
  comparirebbe nella cronologia — senza bisogno di accedere alla macchina su cui è avvenuto.
* **Icona propria.** Un'icona coerente con la funzione del tool, costruita sull'emblema del
  progetto: anello turchese, freccia verde di import e freccia blu di export contrapposte, senza
  nuvole di chat, con variante per i temi scuri.
* **Overview della pagina plugin.** Deve contenere i ringraziamenti ad Antonio Petricca.
* **What's New della pagina plugin.** Deve aprirsi con `[VERSIONE] - [DATA]` e riportare di seguito
  ogni aggiunta o modifica di quella versione.

## Dove è finito ciascun requisito

| Requisito | Documentazione |
|---|---|
| Export/import, policy, rilocazione | `README.md`, §2 di `TASK.md` |
| Visibilità nella chat, voce lato IDE | `README.md` → *Le due metà di una sessione*, §3 e §4 di `TASK.md` |
| Datazione locale | `README.md` → *Note operative*, §4.6 di `TASK.md` |
| Log diagnostico | `README.md` → *Log diagnostico dell'import*, §5 di `TASK.md` |
| Icona, Overview, What's New | `plugin.xml`, `build.gradle.kts`, §7 di `TASK.md` |
