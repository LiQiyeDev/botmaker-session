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

## 2026-07-31 — refactor Phase 3: the test floor (SS3, SS4)

Part of the repo-wide refactor scheduled in `../docs/refactor/02-execution-order.md`; this module's share is
units **SS3** and **SS4**, both test-only. **69 → 80 tests**, no production code touched.

### Done

- **SS4 — the OpenCV/Tess4J exclusion stops being a `grep` a reviewer has to remember.** `CLAUDE.md` rule 2
  was enforced by a `mvn dependency:tree | grep` in prose. It is now `NoOcvOnTheClasspathTest`, from two
  angles: the banned classes are *not resolvable* at test runtime, and every class in this module *links*
  without one appearing. dadb is asserted **present** in the same file, because that exclusion looks equally
  harmless on a pom review and is the one that would break `emu-app:` launches.
- **SS3 item 3 — `AdoptedSession` owns nothing**, asserted structurally: it holds no `SessionReaper` and no
  `SessionMembers`, with `NestedSession` as the control (it must hold one, or the first assertion is checking
  nothing after a rename). The refactor this guards against is the tempting one — unifying `close()` across
  the two session types, which differ by little else — and its symptom is a user's live session being torn
  down under them because a bot they started finished.
- **SS3 item 5 — the teardown never signals the JVM running it**, through `SessionMembers.of` across four
  display names, checking this pid *and its whole ancestry*. `SessionMembersTest` covered `of()`; nothing
  covered the guarantee on the path `NestedSession.shutdownMembers` actually takes.

### SS3 item 1 — B8 measured, and the audit's severity is wrong in an interesting direction

`SessionReaperRaceTest` races `launch()` against `reap()` 40 times per run. **It passes, and it is not B8's
gate** — the class javadoc says so at length rather than letting a green test imply coverage it does not have.

Racing it **240 times** gave 109 refusals, 131 reaps, **zero leaks**. The window does not open under systemd,
structurally: `reap()` spends seconds in `systemctl --user stop` before it ever reads `launched`, so a launch
that passed the guard has always finished its bookkeeping by the time the kill loop runs — and the slice stop
takes the cgroup down regardless of what the list holds. The `launched` list is the entire teardown **only
under the no-systemd fallback**, which is where B8 actually bites and which no test on this box can produce
(`systemdAvailable` is a static cached probe with no injection point).

So **SS6 in Phase 4 needs a seam that makes the strategy injectable**, not just the one-line lock. Without it
the fix ships unverified and the fallback path keeps its current coverage, which is none. Recorded as a Phase 3
addendum under B8 in `../docs/refactor/bugs.md`.

### Deliberately not written

`AdoptedSession`'s non-ownership and `NestedSession`'s launch/close interleaving (item 2) are asserted
structurally, not behaviourally. Behaviourally both need a live private display with a real game in it — the
`-Dbotmaker.live=true` suite — and a structural assertion that runs everywhere catches the specific refactor
that would cause the damage. A capability assertion was written, found to pass **vacuously** (capabilities are
built in an instance method, so the static-field scan behind it found nothing and reported success), and
deleted rather than shipped. That is the same defect Phase 2 deleted `ImageFinderGroupTest` for.

---

## 2026-07-31 — refactor Phase 2: `GpuProbe` deleted

Part of the repo-wide refactor scheduled in `../docs/refactor/02-execution-order.md`; this module's
share is unit **SS5**.

### Done

- **Deleted `display/GpuProbe.java` (378 lines, 8.9% of the module).** Nothing in session, shared,
  sdk, studio or any test referenced it — verified by grep across all four modules. Its javadoc
  called it the "Phase-0 go/no-go probe" that "runs in Studio's diagnostics panel"; Studio has never
  referenced it. The question it answered was settled structurally instead: the module ships
  gamescope *because* Xephyr is software-only, so the probe's verdict is a design premise now, not a
  runtime one. If the diagnostic is wanted again it belongs in `tools/` as a script, not on a
  library's classpath.
- With it went the module's **only `ExecutorService`, only `System.out`, only `main()`, one of the
  six copies of `onPath`** (shared has had `Executables.onPath` all along), and **2 of its 7
  `ProcessBuilder` sites** — 5 remain. Coverage moved **26.5% → 30.6%** line, and
  `com.botmaker.session.display` dropped off the least-covered-packages table entirely.
- **Deleted the `SessionHostWindow.find(long, String)` 2-arg overload** and its `FIND_TIMEOUT_MS`.
  Every caller — one production site, two tests — passes an explicit timeout, so the default was
  never used. `timeoutMs` is now a documented parameter rather than a constant referenced from two
  javadoc blocks.

Note for anyone reading the audit: `12-session.md`'s **SS17** says to reformat `SessionBackends` *to
tabs*. That was written when session was 36-of-41 tab-indented. Phase 1 converged the whole repo on
4 spaces, and `SessionBackends` was already one of the two files conforming — the other 36 moved to
meet it. `GpuProbe`, the other 4-space file, is gone with this entry.

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
