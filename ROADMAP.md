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

## 2026-08-06 — the pinned input backend is a type, not the literal `"xtest"`

**Tests unchanged.** Changed: `impl/NestedSession.java`, `impl/AdoptedSession.java` (one argument each).

**Done.** Both sessions pin XTest on their private display — on a display the bot owns, device-level input is
both accepted by games and non-intrusive, and the process-wide `botmaker.linux.input` property that steers
`:0` must not decide `:N`'s backend. They said so with the bare string `"xtest"`; they now pass
`LinuxInputBackendId.XTEST`, the closed set shared grew in the same change (see
[`../botmaker-shared/ROADMAP.md`](../botmaker-shared/ROADMAP.md), same date). No behaviour change here — the
point is that a typo in the pin is now a compile error rather than a silent fall-through to the
cursor-preserving backend, which is what the old string switch did.

---

## 2026-08-06 — two bugs a live gamescope run found: a stranger's window, and the wrong window

**94 tests (+7).** Changed: `impl/SessionHostWindow.java`, `impl/SessionAttachment.java`,
`impl/NestedSession.java`, `impl/SessionHostWindowTest.java`, `impl/SessionHostWindowLiveTest.java`; new
`impl/SessionAttachmentTest.java`. Both found by running the entry below on a real box, neither of them new.

### Done

- **A session no longer minimizes a window it cannot prove is its own.** `SessionHostWindow.find`'s fallback
  match was "one window whose `WM_CLASS` mentions the server binary", which on a desktop already running
  gamescope games picked one of *those* — the user watched their own game minimize when a session started. The
  ambiguity guard didn't help: it only fired at two candidates, and with one stranger's window and our own not
  yet mapped there was exactly one. Class now identifies the *kind* and the title's display name identifies the
  *instance*: `find` takes the nested `:N` and requires both (`namesDisplay` compares whole display numbers, so
  `:2` doesn't match `:20`). Where neither signal fires we leave the bring-up visible, which is the pre-feature
  behaviour. Xephyr titles its window `Xephyr on :2 …`, so the feature keeps working where it was doing
  anything; on gamescope the hide was already a no-op (its host window is unmapped until it has content).
- **The session follows the launcher chain instead of driving the store page.** Heroic launching Firestone maps
  its library window first, keeps it alive and mapped behind the game, and the game's window arrives minutes
  later — so `SessionAttachment`'s replace-when-dead rule never fired and every capture, every keystroke and
  Pilot's whole stream went to the store page while the game sat visibly on top of it. `followLauncherChain` is
  armed by `launchAndAttach` for kinds that route through a store launcher (`HostLauncherProbe
  .routesThroughDaemon`) and lets the newest top-level take over from a live attachment, re-scanned at most
  every 500ms because `resolve()` is on the capture path. Off for every other kind and cleared by an explicit
  `attach` — a bot that named its window is not asking us to guess. The rule is symmetric, so exiting the game
  back to the launcher promotes back.

### Deferred / next

- **The black blip on close survives**, reported on the same run. `repaintHostBehind` (previous entry) was
  always a nudge; a blip at teardown is the host compositor's own frame, and the next move is to read the
  timestamped transition log from a real close rather than to add another X call blind.

---

## 2026-08-06 — gamescope teardown: name every transition, then close the races we own

**87 tests (+2).** Changed: `impl/SessionHostWindow.java`, `impl/NestedSession.java`,
`process/SessionMembers.java`, `impl/SessionHostWindowTest.java`, `impl/SessionHostWindowLiveTest.java`.
Improvements plan phase 4. Needs `botmaker-shared`'s `X11.XClearArea` (same date, shared ROADMAP).

Reported symptoms: several black flashes while a game loads, a black screen on close that took BotMaker and
Heroic down with it, and a gray rectangle left behind when the gamescope window is dragged. Only some of that
is ours, so the phase is deliberately half instrumentation — the log has to be able to say which.

### Done

- **`SessionHostWindow.Visibility` (`PENDING`/`HIDDEN`/`REVEALED`) replaces a `boolean revealed`.** "Not
  revealed" and "may still be hidden" are not the same question, and reading one flag for both let the hider
  thread hide a window *after* an attach revealed it — a minimized session with a game in it, findable only
  from the taskbar. The old code patched the common ordering with a re-check *after* the hide, which left the
  case where the hide lands later still. `REVEALED` is now terminal, every mutator is `synchronized`, and
  `NestedSession` holds the same enum for the window it hasn't found yet, under one lock with the field —
  publishing the window and deciding what to do with it are one step.
- **`mappedCountOn(display)` beside `anythingMappedOn`.** The count is what makes a run of flashes legible:
  each flash is a client unmapping and the next mapping, so `3 → 0 → 1` names the moment gamescope had
  nothing left to show. `-1` is "couldn't ask" — previously indistinguishable from the boolean's `true`, and
  the direction matters, because unknown must read as *leave the window alone*.
- **Every hide/reveal logs its transition** with a wall-clock `HH:mm:ss.SSS` stamp and the client count the
  decision was taken on, so a user saying "it flashed three times" has three lines to point at.
- **`SessionMembers.shutdown` logs the order it chose** before acting on it, plus every `destroyForcibly`.
  Which process was judged eldest *is* the decision this class exists to make, and it was invisible after the
  fact: a crash on close reads as "the launcher died badly" when the real story is a helper sorting ahead of
  it.
- **`close()` waits for the payload to be *gone*, not merely for the list it signalled.** New
  `awaitNoMembers` re-asks the environment until nothing carries the session's `DISPLAY`/bus, because a
  launcher shutting down routinely spawns one last helper and reaping the display server out from under it is
  exactly the X IO error that produced the `SIGTRAP`. It also reveals the host window on the way out — a
  session torn down while minimized is a window the user never gets back.
- **`repaintHostBehind()`**, after reveal and after the reap: `XClearArea(root, …, exposures=true)` over the
  window's last known root-relative bounds (translated via `XTranslateCoordinates`, not the parent-relative
  attributes, or the rectangle lands at the frame's offset). A nudge, not a fix — see below.

### Deferred / next

- **The gray drag trail may well survive this.** `repaintHostBehind` clears the *root* and asks for `Expose`;
  a compositor keeping its own damage bookkeeping is free to ignore it, and it does nothing at all for the
  case that prompted the report (dragging, which we never see). If it persists, the next thing to try is host
  compositor state, not more X calls from here.
- **`GamescopeDisplay.defaultCommand` was left alone**, deliberately. If the transition log shows the flashes
  are gamescope's own output window cycling rather than anything we do, the experiment is `--backend headless`
  plus a real capture check — measured, on a live box, not blind.

---

## 2026-08-04 — a session can host native Wayland clients

**85 tests (+2).** Changed: `Capability.java`, `display/SessionDisplay.java`, `display/GamescopeDisplay.java`,
`impl/NestedSession.java`, `impl/NestedSessionTest.java`. Improvements plan phase 6, session half.

### Done

- **`Capability.WAYLAND_CLIENTS`.** Every nested session hands children a private `DISPLAY=:N` and blanks
  `WAYLAND_DISPLAY`, because a dual-stack client offered both usually picks Wayland — the *host* compositor,
  which is exactly what a private session exists to stay out of. That is right for a game and wrong for a
  client with no X11 path at all: Waydroid's `show-full-ui` is Wayland-only, and blanking the variable leaves
  it with nothing to connect to.
- **`SessionDisplay.waylandDisplay()`**, defaulting to `null` on the seam so an X server backend cannot
  accidentally advertise the capability. `GamescopeDisplay` answers it by parsing gamescope's
  `Running compositor on wayland display 'gamescope-0'` banner — a *different* banner from the Xwayland one,
  and confusing the two would hand a Wayland client `":1"` as its socket: unable to connect while looking
  configured. Read live off the stderr watcher rather than snapshotted at construction, because bring-up
  completes on the Xwayland banner and nothing in gamescope's output promises the two arrive in that order.
- **`--expose-wayland` is now in `GamescopeDisplay.defaultCommand`**, and `NestedSession.sessionEnv()` puts
  gamescope's own socket in `WAYLAND_DISPLAY` when the backend declares one, blanking it otherwise. Handing
  over the *session's* compositor keeps the client inside the session just as effectively as blanking did.

### Deferred / next

- **Capturing the gamescope output window instead of `adb screencap`.** gamescope composites its native
  Wayland clients into its own X output window, so `SessionHostWindow` + shared's X11 `captureWindow` could
  replace the ADB capture path entirely at a much higher framerate, keeping ADB only for `input tap`. Note
  `--backend headless` is incompatible with it — there is no output window to grab. This is the *same*
  mechanism as the host-window capture deferred for the Windows emulators in
  `../botmaker-shared/ROADMAP.md`; one backend should serve both rather than being written twice.

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
