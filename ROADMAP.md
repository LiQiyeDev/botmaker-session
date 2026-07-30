# ROADMAP

A running history of features and refactors for `botmaker-session`, for future Claude Code sessions.
**Append here whenever you add a feature or refactor** (required — see `CLAUDE.md` › Planning).

Format: newest first. Each dated entry has a **Done** list and, when relevant, **Deferred / next**
(intentionally left for later, with enough context to pick up cold).

> **History before 2026-07-30 lives in [`../botmaker-shared/ROADMAP.md`](../botmaker-shared/ROADMAP.md).**
> This stack was `com.botmaker.shared.session` until the extraction below, and its history there is
> *interleaved* with launch-stack and capture work in the same dated entries — Phase 11 step 1 is
> `HostLauncherProbe` (which stayed in shared), Phase 12 is `PointerPolicy` (which moved), and several
> entries cover both. Splitting them apart would have falsified both records, so they were left whole and
> cross-referenced instead. The Bot-owned-display plan (Phases 0–H) and the Isolated-launch fixes
> (Phases 1–13) are all there.

---

## 2026-07-30 — extracted from `botmaker-shared` into its own module

The session stack had become the part of the codebase changing every week (Phases 9–13 were all session
work) and the hardest to reason about, because it sat inside a module whose charter is native window
plumbing + OCR + matching + launch. Nothing stopped a session type from reaching into any of it, or a future
shared type from reaching back. This gives the boundary a compiler.

### Done

- **New module, new repo** (`git@github.com:LiQiyeDev/botmaker-session.git`), a full peer of
  shared/sdk/studio in the umbrella reactor, ordered `shared → session → {sdk, studio}`.
- **`com.botmaker.shared.session` → `com.botmaker.session`**, 25 main + 16 test files, **split by role**:
  the contract at the root, then `.display` (the two backends), `.impl` (the three `DesktopSession`s),
  `.process` (reaper/bus/members/app-log) and `.input`.
- **12 types widened from package-private to `public`** — `SessionReaper`, `SessionBus`, `SessionMembers`,
  `AppOutputLog`, `NestedDisplay`, `GamescopeDisplay`, `DisplayReadiness`, `SessionDisplay`,
  `SessionAttachment`, `SessionHostWindow`, `ControllerPointer`, `ControllerKeyboard` — plus their members.
  This is the cost of sub-packages in a pre-JPMS module and is worth stating plainly: **`public` in this
  module does not mean "API"**. `CLAUDE.md` carries the contract/plumbing table; JPMS would make it
  enforced and is deferred (shared and JNA would become automatic modules named after their jar files).
- **Standalone, and checked rather than asserted.** The pom excludes OpenCV and Tess4J from the shared
  dependency — verified safe, since nothing under `capture/` or `launch/` links an `org.opencv` or
  `net.sourceforge.tess4j` type. dadb is deliberately kept: `HostSession.launch` → `Launcher.start` →
  `EmulatorAppLauncher` → `AdbDevice` is a live path for `emu-app:` targets, so excluding it would leave
  that one launch kind throwing `NoClassDefFoundError` for a standalone consumer.
- Test counts confirm nothing was lost: shared went 211 → **142**, session is **69** (211 = 142 + 69), with
  all 8 live-gated skips moving across. sdk **118** and studio **354** unchanged.

### Deliberately left in shared

The whole `com.botmaker.shared.launch` package, **including `LaunchIsolation`, `HostLauncherProbe` and
`ProcessOrigin`** — which are conceptually session code. The reason is forced: `RunningProbe` uses
`ProcessOrigin`, so it cannot leave without inverting the dependency. Isolation logic is therefore split
across two modules whichever way it goes, and splitting it *twice* would be worse — it would also widen
`RunningProbe.programNames` to public purely to serve a concept boundary. Revisit only if `ProcessOrigin`'s
probes stop being something `RunningProbe` needs.

### Deferred / next

Phase 13's remaining steps, all in this module or its shared launch neighbours: name the pid behind a
"close Heroic and try again" refusal and stop counting dead sessions' leftovers as live launchers; attach
*provisionally* and gate readiness on the target's own window rather than the launcher's first one; and
SIGTERM the game's tree before SIGKILLing the launcher, which is what produces the Electron/CEF `SIGTRAP`
coredumps seen on every teardown.
