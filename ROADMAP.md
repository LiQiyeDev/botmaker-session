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

## 2026-08-01 — improvements Phase 6: getting the housekeeping off the session-start critical path

**Done**

- **The orphan sweep runs concurrently with the bring-up** instead of in front of it. `NestedSession.start`
  opened with a synchronous `reapOrphanSessions()` — a `systemctl list-units` plus a `systemctl stop` per
  leftover slice, each with a 5s budget — before it spawned anything, for work Studio already does at startup.
  It now starts on a daemon thread beside `startDisplay`.
- **That required claiming the session id earlier.** `LIVE.add(id)` used to happen once the tree was up; a
  sweep running concurrently would have seen the new slice as an unclaimed cgroup owned by this pid, which is
  precisely its definition of an orphan, and stopped the session being built. The claim moved to the top of
  `start` and is dropped in `cleanupFailedStart`. Verified the way it matters: a `kill -9`'d session-owning
  JVM's two slices were still reaped by the next launch, and that launch's own slice survived.
- **gamescope's stderr is read, not polled.** `GamescopeDisplay` wrote stderr to a temp file and re-read the
  whole file every 150ms looking for the `Starting Xwayland on :N` banner. A `StderrWatcher` daemon thread now
  reads the piped stream line by line and completes a future the moment the banner arrives. It keeps draining
  for the life of the process — a piped stream nobody reads fills its buffer and blocks the writer — and keeps
  the last 40 lines, so a bring-up that fails can finally quote what gamescope said instead of just "did not
  announce a display".
- **`DisplayReadiness` polls at 25ms, not 100ms.** Measured on this box, gamescope's Xwayland becomes
  connectable 114–164ms after the banner, so the old interval rounded that up to 200ms.
- `SessionReaper.systemdAvailable()` is `synchronized`, so the probe runs once rather than typically-once —
  with the sweep now concurrent with a start, two callers arriving together is the normal case.
- New diagnostic: the "display up" line splits the wait into *announced Xms after spawn, connectable Yms
  later*. The two halves have different owners (gamescope's own bring-up vs. our poll), and the line says
  which one to go and look at.

**Measured, and the honest version of it**

Warm gamescope `start()` on the dev box is **~390–540ms**, of which ~220–370ms is gamescope announcing its
Xwayland and ~115–165ms is that Xwayland accepting a connection — i.e. **the floor here is gamescope's own
bring-up**, and the phase's savings (the serialized sweep, up to 75ms of poll granularity) are real but sit
inside the run-to-run variance of a single measurement. Two of the plan's predicted costs did **not**
materialize on this machine: `systemd-run --user --scope true` returns in **19ms**, not the 4s its `waitFor`
budget allows, and a `systemctl stop` of a leftover slice costs ~50–100ms rather than seconds. The changes
are still right — they are worth the most exactly on the slow-systemd box where the budgets were chosen —
but nobody should expect a visible speed-up here.

**Deferred / next**

- The ~50ms private D-Bus start and gamescope's own ~250ms are what is left; neither is ours to shorten.
- The 20s/120s `windowTimeoutFor` budgets still dominate a *failing* launch and were deliberately excluded.

---

## 2026-08-01 — improvements Phase 5: publishing the host window so Studio can overlay a session

**Done**

- `NestedSession.hostWindowId()` — the X id of the display server's own window on the host desktop, `0` while
  it isn't known yet (a normal answer for the first seconds: the window is found on the hider thread, and
  gamescope doesn't map it until a client maps something on its Xwayland). `revealHostWindow()` went from
  private to public alongside it, since a host-side tool has to un-minimize the window before it can look at it.
  Both are plumbing, not contract — but the id, not the title, is the only thing that names a gamescope host
  window: gamescope renames its output window after whatever app is inside it, and a second unmanaged window of
  its own carries the same `WM_CLASS`. (Only the managed one is in `_NET_CLIENT_LIST`, so `SessionHostWindow.find`
  was already picking correctly — measured, not assumed.)
- `SessionHostWindowLiveTest.theHostWindowReadsRealPixelsFromTheHostSide` — the new live gate, and the
  assumption Studio's overlay editor rests on. The existing test proves a session can capture *itself* while
  minimized; this proves the *host* can capture the session's window, which is a different question under
  gamescope (frames go through its own Vulkan swapchain, and a host capture returning black would mean the
  overlay draws over a window it cannot see into). Passes on both backends; skips by default like its siblings.

**Deferred / next**

- Phase 6 of the same batch is the latency work in this module: `reapOrphanSessions()` off the critical path,
  `SessionReaper.systemdAvailable()` warmed at Studio startup, and `GamescopeDisplay.awaitDisplay`'s stderr
  *file* polling replaced with a reader thread. The `START_TIMEOUT_MS`/`windowTimeoutFor` budgets stay as they
  are — this is about latency, not patience.

---

## 2026-08-01 — refactor Phase 4: B8, the reaper's own failure mode (SS6)

This module's single Phase 4 unit. **80 → 82 tests**, and the first production change here since the
extraction.

### Done

- **B8 — `launch()` and `reap()` are now atomic against each other.** The guard, the `launched` list and the
  `reaped` flag move under one lock, and `launch` **re-tests the flag after `pb.start()`**: a launch that loses
  the race destroys its own child (and stops its transient scope under systemd) instead of returning a healthy
  process into a list the teardown has already drained. `launched` is a plain `ArrayList` guarded by the lock
  rather than a `CopyOnWriteArrayList` — a thread-safe *container* was never the missing piece; a point in time
  where the flag and the list agree was.
- **The lock is never held across a spawn or a kill.** `reap()` flips the flag and copy-and-clears the list
  under it, then does `systemctl --user stop` (seconds) and the kill loop outside. A launcher racing a reap
  therefore blocks for a few instructions, not for the teardown.
- **A strategy seam: `SessionReaper(String, boolean)`, package-private.** `systemdAvailable` is a cached static
  probe with no injection point, so the **fallback** path — where the `launched` list *is* the entire teardown,
  and the only configuration B8 actually bites in — was unreachable from any test on a systemd box. The fix
  would otherwise have shipped unverified.
- **`SessionReaperRaceTest` 3 → 5 tests, and the gate moved.** The fallback race is B8's gate
  (**40 of 40 launches leaked** with the lock reverted and the seam kept); the systemd race stays as a
  regression sampler and is explicitly *not* the gate — it was green before the fix too. Added the first
  coverage the fallback teardown has ever had: `reap()` kills a tracked process **and its descendants**.

### The finding worth keeping

The fix is one lock; the *verification* needed a seam. A bug whose severity is conditional on a configuration
the machine cannot produce is unverifiable until that configuration is injectable — and "unverifiable" is how a
fix that is obviously right ships subtly wrong. The measured numbers make the point: the same race is
0-in-240 under systemd and 40-in-40 under the fallback.

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
