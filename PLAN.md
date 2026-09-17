# Hunk ↔ PyCharm Review Integration Plugin

## Context

[hunk](https://github.com/modem-dev/hunk) is a terminal diff/review tool already in use (a live `hunk diff master` session was running in a terminal on the `wi_blocking_rules` repo at the time this plan was written — verified). The goal is a PyCharm plugin that lets you start a Hunk review from inside PyCharm and see it rendered in PyCharm's own review UI — the same tool-window/diff experience used for reviewing a GitHub/GitLab MR — instead of a terminal pane, while staying live-synced with the underlying Hunk session (including one you or an agent may also be driving from a real terminal).

**Decisions already made:**
- **UI fidelity**: build directly on `com.intellij.collaboration.ui` (the same internal module the bundled GitHub "Pull Requests" / GitLab "Merge Requests" tool windows use) for true visual/behavioral parity, accepting cross-IDE-version maintenance cost. Isolated behind an adapter interface so a future rewrite stays contained.
- **Location**: this repo, `/home/r2r/dev/hunk-pycharm-plugin` (flat under `~/dev`, matching the convention for personal/non-`wi` projects — `dev/wi/...` is reserved for client work).
- **Target**: PyCharm **Professional** only.
- **Stack**: Kotlin, Gradle with the `org.jetbrains.intellij.platform` Gradle Plugin (2.x, currently 2.18.1).
- **Current target IDE version** (checked Sept 2026): PyCharm 2026.2.2 (build `262.10315.174`) is the latest stable point release on the 2026.2 line; 2026.1 is the prior major line. Pin `sinceBuild`/`untilBuild` to one major line (e.g. `262`) per the churn-risk strategy below — confirm the exact current point build against `https://www.jetbrains.com/pycharm/download/other/` at scaffolding time since patch releases ship frequently.

## Verified ground truth (checked directly against the installed CLI, not just docs)

Checked with `hunk --version`, `hunk session --help` and children, and by querying the live session directly:

- Installed: `hunk` 0.22.0 (`~/.npm-global/bin/hunk` → `hunkdiff` npm package). Runs as three processes: the TUI (`hunk diff master`, attached to a real tty), a native `hunkdiff-linux-x64` binary, and a background `hunk daemon serve`.
- **No `session status` / `session stop`** — confirmed non-existent (`Unknown session command`). Session cleanup is 100% process-kill; there is no clean per-session teardown or daemon-admin API to call.
- **Full `session` subcommand surface** (from `hunk session --help`), all accepting either a positional `sessionId` or `--repo <path>`:
  `list`, `get`, `context`, `review [--include-patch] [--include-notes]`, `navigate --file <path> (--hunk n | --old-line n | --new-line n | --comment id | --next-comment | --prev-comment)`, `reload -- diff|show ...`, `comment add|apply|list|rm|clear`, `highlight add|clear`.
- **`session list --json`** returns a cheap per-session skeleton: `sessionId, pid, cwd, repoRoot, launchedAt, terminal.locations[], inputKind, title, sourceLabel, experimentalFeatures, fileCount, files[]` where each file has `id, path, additions, deletions, hunkCount` — no hunk/patch content.
- **`session review --json`** (verified against the live session) returns `review: { sessionId, title, sourceLabel, cwd, repoRoot, inputKind, experimentalFeatures, selectedFile, selectedHunk, showAgentNotes, liveCommentCount, reviewNoteCount, reviewNotes[] }`. Crucially, **`selectedFile.hunks[]`/`patch` only cover whichever file/hunk is currently focused** — not all files. Full-tree content requires navigating to each file first.
- `reviewNotes[]` items: `noteId, parentId? (reply), source ("agent"|"user"), filePath, hunkIndex, newRange:[start,count], body, author, createdAt, editable`.
- **`comment add`** takes one target per call: `(--reply-to <note-id> | --file <path> (--old-line n | --new-line n))` + `--summary` (+ `--rationale`, `--author`, `--markup`, `--focus`).
- **`comment apply --stdin`** batch shape (from `--help`, verbatim):
  ```json
  {"comments":[
    {"filePath":"README.md","hunk":2,"summary":"Explain this hunk","rationale":"...","author":"Pi"},
    {"replyTo":"user:123","summary":"Addressed in the latest revision"}
  ]}
  ```
  Only `hunk`-index targeting is shown in the example (not `oldLine`/`newLine`) — **needs a smoke test** before relying on line-based batch anchoring; fall back to N sequential `comment add` calls if unsupported.
- **A non-PTY invocation does not register a session** — `hunk diff < /dev/null > out` exits 0 immediately with a static dump, no daemon registration. A hidden **pty4j**-backed PTY is mandatory, not an optimization.
- **Multiple sessions can share the same `repoRoot`** (there was a real one open on `wi_blocking_rules` during planning). `--repo` behavior with >1 match is undocumented → **target every call by the positional `sessionId`**, resolved once via `pid` after spawning (see below), never by `--repo`.
- Readiness signal: after spawning, poll `hunk session list --json` filtering by `pid == <our spawned PTY's pid>` — process-alive is not sufficient (see non-PTY finding above).

## Architecture

```
PyCharm Action ("Review Working Tree with Hunk")
      │
      ▼
HunkSessionService (project service)
      │ spawns hidden PTY via pty4j: `hunk diff` / `hunk show <rev>`
      │ polls `hunk session list --json` by pid → resolves sessionId
      ▼
HunkCliInvoker  ──shells out to `hunk session <sub> <sessionId> --json`──▶  hunk daemon (local, Ed25519-authed — opaque to us; the CLI handles auth)
      │ parses JSON (kotlinx.serialization, ignoreUnknownKeys = true)
      ▼
HunkReviewUiHost (adapter interface)
      │
      ├─ Phase 1: SimpleTreeHunkReviewUiHost (plain Swing tree, stable APIs only)
      └─ Phase 2+: CollaborationToolsHunkReviewUiHost (com.intellij.collaboration.ui — MR-review parity, version-fragile, isolated here)
```

Why shell out instead of implementing the daemon protocol directly: the wire protocol (Ed25519 challenge-response, RFC 8785 canonical JSON, WebSocket/HTTP) is explicitly not meant for third-party reimplementation, has no Python/JVM SDK, and credentials are owner-private/auto-discovered per OS user — invoking the `hunk` binary as the same user gets us authenticated access for free.

## Project layout (target)

```
/home/r2r/dev/hunk-pycharm-plugin/
├── settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml
├── src/main/kotlin/dev/hunkreview/pycharm/
│   ├── cli/HunkCliLocator.kt          # resolves `hunk` binary + version check
│   ├── cli/HunkCliInvoker.kt          # one-shot `hunk session <sub>` calls (GeneralCommandLine)
│   ├── cli/HunkJsonCodec.kt           # kotlinx.serialization models + parsing
│   ├── cli/HunkCliException.kt        # NotFound / DaemonUnreachable / BinaryMissing / Unexpected
│   ├── model/HunkReviewModels.kt      # HunkSessionSummary, HunkReview, HunkFileDetail, HunkHunk, HunkNote
│   ├── session/HunkHiddenSessionLauncher.kt  # pty4j spawn, hidden (no visible console)
│   ├── session/HunkSessionService.kt  # @Service(PROJECT), Disposable; state machine + pid→sessionId pinning
│   ├── actions/StartHunkWorkingTreeReviewAction.kt
│   ├── actions/ReviewCommitWithHunkAction.kt   # Phase 4, VCS Log context action
│   ├── ui/HunkReviewToolWindowFactory.kt
│   ├── ui/HunkReviewUiHost.kt          # adapter interface — isolation seam
│   ├── ui/simple/SimpleTreeHunkReviewUiHost.kt        # Phase 1
│   ├── ui/collab/CollaborationToolsHunkReviewUiHost.kt # Phase 2+, all collab-tools usage lives here
│   ├── ui/collab/HunkDiffContentBuilder.kt     # parses `patch` via platform's UnifiedDiffReader/PatchReader
│   └── settings/HunkPluginSettings.kt   # CLI path override, poll interval, min version
└── src/main/resources/META-INF/plugin.xml
```

Key `plugin.xml` points: `<depends>Git4Idea</depends>`, `<dependencies><module name="intellij.platform.collaborationTools"/></dependencies>`, a `toolWindow` extension, and the `Start Hunk Review` action added to the `Git.Menu` group.

Key `build.gradle.kts` points: `org.jetbrains.intellij.platform` Gradle plugin 2.x (2.18.1 as of Sept 2026) targeting PyCharm Professional; `bundledPlugin("Git4Idea")`; try `bundledPlugin("org.jetbrains.plugins.terminal")` first to reuse the IDE's own pinned pty4j (avoids shipping duplicate native libs) and only add an explicit `org.jetbrains.pty4j` dependency if those classes aren't exported to third-party plugins; `kotlinx-serialization-json` for JSON. Pin `sinceBuild`/`untilBuild` to a single current PyCharm major version rather than a wide range, given the accepted collaboration-tools churn risk — widen only after a `verifyPlugin` pass against each new major release.

## Session lifecycle details

- `HunkSessionService.startWorkingTreeReview()`: spawn `hunk diff` (or `hunk show <rev>` for Phase 4 commit review) via `PtyProcessBuilder(...).setConsole(false)`; capture the PTY process's OS pid; poll `hunk session list --json` (via the *non*-PTY `HunkCliInvoker`, every ~150ms with a timeout) until an entry's `pid` matches; store its `sessionId`. All subsequent calls target that `sessionId` positionally, never `--repo`.
- Kill the spawned process (`destroy()` → `destroyForcibly()` fallback) only on **project close** or an explicit "Stop Review" action — not on tool-window hide/tab-switch, since the session is meant to persist the same way a real terminal-hosted `hunk diff` would. Only the Phase-3 poller pauses when the tool window isn't visible.
- Never call anything daemon-wide (`stop` doesn't exist anyway) — cleanup is scoped to the one process we spawned.

## CLI bridge & error handling

`HunkCliInvoker` wraps one-shot calls (`GeneralCommandLine` + captured output), separate from the long-lived PTY process. Error policy, informed by testing: exit code 1 during the readiness-poll phase just means "not registered yet" (harmless); exit code 1 on a call against an already-pid-confirmed `sessionId` is a real failure (session died / daemon crashed) — surface raw stderr rather than trying to pattern-match it, since the CLI's error text (`protocol-validation-failed`) is generic. Use `Json { ignoreUnknownKeys = true }` since hunk is pre-1.0 (issue #166 shows the `session`/`--repo` surface is still actively stabilizing).

## Bidirectional sync

- **File tree**: build immediately and cheaply from `session list --json` (path/additions/deletions/hunkCount for all files).
- **Content, lazily per file**: on selection in PyCharm's tree, call `session navigate --file <path> --new-line 1` (this *is* the PyCharm→hunk sync requirement) then `session review --include-patch --include-notes --json` to fetch that file's hunks/patch/notes. Parse `patch` (standard unified diff text) with the platform's own `UnifiedDiffReader`/`PatchReader` rather than a hand-rolled parser.
- **PyCharm → hunk**: hunk/line selection → `navigate`; gutter "add comment" → `comment add`; bulk comment import → `comment apply --stdin` (pending the smoke test above) or N `comment add` calls as fallback.
- **hunk → PyCharm (MVP = polling)**: `HunkReviewPoller`, gated on tool-window visibility, calls `session review --include-notes --json` on an interval (start at 2–3s; tune after measuring real round-trip latency in Phase 1), diffs `reviewNotes` by `noteId` and updates incrementally.
- **Future, not MVP**: a small TS extension loaded into the hunk process (`.hunk/extensions/`) hooking `hunk_viewed`/`note_changed` to push updates instead of polling.

## Review UI adapter

```kotlin
interface HunkReviewUiHost : Disposable {
    fun render(review: HunkReview, files: List<HunkFileSummary>)
    fun updateNotes(notes: List<HunkNote>)
    fun onFileSelected(handler: (path: String) -> Unit)
    fun onHunkSelected(handler: (path: String, hunkIndex: Int) -> Unit)
}
```
`HunkSessionService`/`HunkCliInvoker` never reference collaboration-tools types — only `CollaborationToolsHunkReviewUiHost` and `HunkDiffContentBuilder` do, so a future IDE-version break is a contained rewrite of two files, not a plugin-wide one.

## Phasing

| Phase | Deliverable | Verification |
|---|---|---|
| **0** ✅ | Repo/Gradle scaffold, empty plugin loads | `./gradlew runIde` opens PyCharm Professional sandbox with an empty "Hunk Review" tool window; `./gradlew verifyPlugin` clean; confirm exact product-accessor name (`pycharmProfessional(...)` vs unified `create(...)`) and pty4j visibility via IDE code completion — don't guess from docs |
| **1** ✅ | CLI bridge + hidden-PTY spawn + pid→sessionId resolution + read-only file tree (plain Swing, no collab-tools) | Action spawns a hidden PTY (`pgrep -af hunk` shows a new pid not attached to a real `/dev/pts/*`); tool window populates from real `session list`/`session review`; process is cleanly killed on project close with nothing orphaned |
| **2** ◐ | Native PyCharm diff viewer, file/hunk navigation, and hunk-to-terminal synchronization | Native diff rendering, file selection, and hunk selection are implemented and verifier-checked; full collaboration-tools review-host parity remains pending |
| **3** ✅ | Inline comment authoring, prefixed user comments, inline comment cards, replies, and polling live sync | Gutter `+` opens an inline editor; `Ctrl+Enter` saves; top-level PyCharm comments use `[author:user]` and hide it in the plugin; replies use `--reply-to` without the prefix; end-to-end user/AI reply identity still needs verification |
| **4 (optional)** | VCS Log "Review commit/range with Hunk"; TS push-extension replacing polling | Spike only, not required for a working MVP |

## Open items to verify once implementation starts

1. `comment apply --stdin` per-item `oldLine`/`newLine` support (only `hunk`-index shown in `--help`'s example).
2. Cold-daemon-start latency (only a warm daemon was observed during planning) — sets the Phase-1 readiness-poll timeout.
3. Whether `com.intellij.collaboration.ui` classes used in Phase 2 trip `verifyPlugin`'s internal-API warnings.
4. Exact IntelliJ Platform Gradle Plugin 2.x accessor for "PyCharm Professional" given signs of a 2025.3+ IU/PY product unification.
5. Minimum `hunk` version to require in `HunkCliLocator` (0.22.0 confirmed to have the full `session` family; re-check the changelog immediately before coding, since the CLI is pre-1.0 and this surface is actively moving).
6. Replace the Phase-1 hard-coded `hunk diff master` target with a client-selectable comparison ref (branch, commit, or range), exposed through the action or plugin settings. The current MVP uses `master` so staged and unstaged changes are reviewed together.
7. Verify replying to comments created by both a user and an AI agent: PyCharm-created replies must remain unprefixed, user replies must render with the user identity, and unprefixed AI replies must render as `AI agent`.

## Environment note

This machine (where the plan was drafted) has **no local JDK/Gradle/Kotlin toolchain installed** — only network access was verified. Phase 0's `runIde`/`verifyPlugin` verification steps require a real JDK-backed Gradle run, which will need either a portable JDK installed here or (more likely, since this plugin will be developed/debugged inside real PyCharm anyway) opening this project directly in PyCharm Professional and using its bundled Gradle/Plugin DevKit tooling.
