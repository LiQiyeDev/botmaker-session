# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working in **botmaker-session**.

**botmaker-session** is the private-display stack: a bot drives a nested `:N` X server (Xephyr or gamescope)
that owns its own pointer, keyboard focus and window stack, so input is background-safe and the user keeps
using their machine while the bot runs. It was extracted from `botmaker-shared` (where it lived as
`com.botmaker.shared.session`) once it became the part of the codebase changing every week.

## Two rules that define this module

**1. It is a standalone library.** It must never depend on `botmaker-sdk` or `botmaker-studio` — both depend
on *it*. `botmaker-shared` is the one BotMaker dependency allowed. Check with:

```bash
grep -rl "com\.botmaker\.\(sdk\|studio\)" src     # must print nothing
```

**2. Its dependency footprint stays small.** shared puts OpenCV (~100 MB of natives) and Tess4J at compile
scope for Studio's benefit; this module's pom **excludes both**, because nothing under `capture/` or
`launch/` links an `org.opencv`/`net.sourceforge.tess4j` type. dadb is *not* excluded — `HostSession.launch`
delegates to shared's `Launcher.start`, which reaches `EmulatorAppLauncher` → `AdbDevice` for `emu-app:`
targets. Check with:

```bash
mvn dependency:tree | grep -Ei "opencv|tess4j"    # must print nothing
```

Both checks belong in any review of a pom change here.

## Layout

Packages are by role. Note the consequence of splitting: 12 types that were package-private in the old
single-package layout are now `public` purely because Java has no module-internal visibility below JPMS.
**`public` here does not mean "API"** — the javadoc on each says whether it is contract or plumbing, and that
is now the only thing keeping a caller out.

| Package | Holds | Status |
|---------|-------|--------|
| `com.botmaker.session` | `DesktopSession`, `Capability`, `SessionHealth`, `SessionPointer`, `SessionKeyboard`, `ActiveSession`, `PointerPolicy`, `SessionStartException` | the contract |
| `…session.display` | `SessionDisplay` + the two backends (`NestedDisplay`/Xephyr, `GamescopeDisplay`), `DisplayReadiness`, `GpuProbe`, `SessionBackends` | plumbing |
| `…session.impl` | `NestedSession`, `AdoptedSession`, `HostSession`, `SessionAttachment`, `SessionHostWindow` | `NestedSession`/`AdoptedSession`/`HostSession` are API; the other two are plumbing |
| `…session.process` | `SessionReaper`, `SessionMembers`, `SessionBus`, `AppOutputLog` | plumbing |
| `…session.input` | `ControllerPointer`, `ControllerKeyboard` | plumbing |

Adding JPMS (`module-info.java` exporting only the root package) is the way to make that enforced rather than
documented. It is deliberately deferred: shared and JNA would become automatic modules named after their jar
files, and nothing else in this repo is modular.

## The model

- **`DesktopSession`** is the seam: the same bot code targets the user's real desktop (`HostSession`) or a
  private `:N` (`NestedSession`) without knowing which. A session either `attach`es to an existing window or
  `launch`es a target into itself.
- **`Capability`** is how a session advertises what it can actually do, so a caller fails fast instead of
  silently no-op'ing. `BACKGROUND_CLICK` is the load-bearing one — only a nested session can offer it,
  because only there is the global pointer the bot's alone.
- **Two backends, one seam.** Xephyr (2D, needs a window manager for EWMH/focus) and gamescope (hardware 3D,
  *is* its own WM, hosts an embedded Xwayland). `SessionBackends.preferredBackend(spec)` picks by launch kind:
  a store-launcher/Proton/exe game gets gamescope for a real GPU, a plain command gets Xephyr. Falling back to
  Xephyr for a game is the software-GL crash this auto-selection exists to prevent.
- **Everything spawned lives in one reap group** (`SessionReaper`, systemd transient scopes under a
  per-session slice), so `close()` takes the whole tree down and a `kill -9`'d JVM leaves no orphans.
- **`PointerPolicy`** owns "does this gesture hand the cursor back?" — on the user's desktop yes, inside a
  session no (warping away turns a click into a hover). Both consumers call it; that is the whole point of it
  living here rather than being re-implemented in each.

## Testing

JUnit Jupiter. Live suites that need a real X server are gated on `-Dbotmaker.live=true`, with
`-Dbotmaker.live.backend=xephyr|gamescope` choosing the backend under test; they skip otherwise (8 skips in a
normal run). Prefer asserting X state (is the window iconified? viewable?) over asserting log lines.

Watch out for `ProcessHandle`-based tests: on systemd **every** process descends from pid 1, so a test that
passes pid 1 as "the tree to search" matches real desktop windows and passes or fails by accident. Spawn a
real child and use its pid — `SessionHostWindowTest`'s javadoc records this.

## Contract stability

Consumed by the SDK and Studio. **No published bot consumes it yet, so the API is currently freely
breakable** — change signatures when it makes the contract cleaner. The cost is the ordered cross-module
release (`shared → session → sdk/studio`, see the umbrella `../CLAUDE.md` and `../release.sh`). Reinstate
stability discipline once real bots ship.

## Planning

For large changes, write the plan to a dedicated plan file first, so work survives an interrupted session.
**Always update `ROADMAP.md`** when you add a feature or refactor — append a dated entry, newest first.
