# Changelog

All notable changes to the Github Copilot sessions plugin are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-09-17

First public release.

### Added

- **Export of the selected GitHub Copilot chat sessions into a portable ZIP archive.**
  `Tools | Github Copilot sessions | Export Sessions...` lists every session found on the machine -
  name, repository, branch, date, number of turns and id - with a search filter and multi-selection,
  and writes the chosen conversations to a single archive.
- **Import from an archive**, with *Skip*, *Replace* and *Duplicate* conflict policies, and an
  option to attach the restored sessions to the folder of the project that is open.
- **Both halves of a session are carried across.** Restoring `~/.copilot` alone is not enough for a
  conversation to reappear in the chat: the list rendered by the "GitHub Copilot Chat" tool window
  is served by a separate, per project database owned by the GitHub Copilot IDE plugin. The archive
  therefore holds the Copilot CLI storage (`session-store.db` rows and the `session-state/<id>`
  folder) **and** the chat history entry of the IDE plugin, as `sessions/<id>/ide-session.json`.
  - The chat entry is scoped to a project, so a session has to be exported from the project it
    belongs to; the export warns about every session whose entry could not be found.
  - The entry is never written to the plugin database file directly - the running IDE keeps it
    locked - it is handed back through the plugin's own session persistence service. That is an
    internal, undocumented API of another plugin: when it is unavailable the import still restores
    the CLI side and reports the limitation, it never fails.
  - On the way in, the entry is given a fresh internal id (so re-importing an archive cannot collide
    with the record it came from) and any previous entry for the same conversation is removed, so
    the list never shows duplicates.
- **Full adoption of an imported session by the destination machine.** Every identifier and every
  absolute path recorded by the agent - in `session-store.db`, in `workspace.yaml`, in
  `events.jsonl` and in `rewind-file-snapshots/index.json` - is remapped onto the local home and the
  local working directory, key by key. The rewrite is structural, so the text of the conversation is
  never altered.
  - Windows folders are normalized with a lower case drive letter and `\` separators, matching the
    exact string the Copilot CLI looks sessions up by.
  - A session whose `workspace.yaml` could not be exported - typically because the source chat was
    open - is rebuilt from the exported database row instead of being restored as an invisible
    folder.
- **Imported sessions are dated as if they had just been created here.** Every timestamp is shifted
  by the same offset, computed once from the newest timestamp in the archive, so `updated_at` lands
  on the moment of the import (the session appears at the top of the chat history) while
  `created_at` and the spacing between turns are preserved. No timestamp from the source machine
  survives, and none ever ends up in the future.
- **Verification of what was actually written.** The import notification reports, re-read from disk,
  the folder each session is attached to and its number of turns, and warns explicitly about the
  cases that keep a restored session hidden: no usable `workspace.yaml`, a working directory
  different from the project that is open, a missing chat entry, or a conversation restored without
  its content. The export equally reports the files it could not read instead of producing an
  incomplete archive silently.
- **Diagnostic import log.** Every import writes a self-contained log file under
  `<IDE log path>/copilot-sessions-import/import-yyyyMMdd-HHmmss.log`: the environment, the archive
  and the policy chosen, and for each session an explicit `OK`/`KO` checklist of twelve checks
  explaining why a restored session would or would not show up in the chat history, plus a
  comparison against the most recently used native session already on the machine. The log never
  contains conversation text - only paths, keys, counts, sizes and metadata, with long values
  truncated - and writing it can never make an import fail. The notification links to it through a
  **"Show import log"** action; the last 20 files are kept and older ones are pruned automatically.
- **"Restart IDE now"** button in the import notification. The chat reads its session list once,
  when the project opens, so a restart is the reliable way to see the restored conversations. The
  label is localized in Italian, French, German, Spanish, Portuguese, Japanese, Chinese and Korean
  and follows the display language of the IDE, or the regional settings of the operating system on
  an English IDE.
- **Entry in the GitHub Copilot status bar popup**, next to the `Tools | Github Copilot sessions`
  menu, when the GitHub Copilot plugin is installed. This is a best-effort integration built on
  internal action-group ids, so it degrades silently if those ids change.
- **Schema tolerant storage access.** Tables or columns unknown to the local version of Copilot are
  ignored on both read and write, so archives stay compatible across CLI versions, and auto-generated
  integer primary keys are not copied, so an import never collides with existing rows.
