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

## 2026-08-24 — a released tag stops being a bare ref: `CHANGELOG.md` (phase 5 of 12)

**Changed:** `CHANGELOG.md` (new).

**Done**

- **A few bullets per released version**, seeded from each tag's own commits, including the honest record
  of the same-day re-tags (v0.0.5, v0.0.6 exist so JitPack rebuilt session against a new shared, and say
  so). The umbrella `release.sh` **refuses a `--session` release with no section for the version being
  cut** (`check_changelog`, decide pass, no network) and publishes that section as the tag's GitHub
  Release body (`publish_release`).
- Not copied into the jar — that is the SDK's arrangement, and only the SDK has a reader for it (Studio's
  upgrade dialog reads `META-INF/botmaker/whats-new.md` out of the SDK jar it diffs).

---

## 2026-08-22 — the published session pom names a real shared tag (it never did)

**Changed:** `pom.xml` (new `<build>` with flatten-maven-plugin, property comment), `.gitignore`.

**Done**

- **Fixed the same defect the SDK had** (see `../botmaker-sdk/ROADMAP.md` for the full write-up, and for
  how it surfaced): `mvn install -Dbotmaker.shared.version=v0.0.18` installs the **committed** pom, so
  every published `botmaker-session:<tag>` declared `botmaker-shared:0.0.0-SNAPSHOT` and could not be
  resolved by anyone without a local build in `~/.m2`. The `-D` was steering the build and nothing else.
- **`flatten-maven-plugin` 1.6.0**, in this module's first-ever `<build>` section. `flattenMode=oss` with
  `<repositories>keep</repositories>`. Verified the flattened pom preserves the OpenCV/Tess4J
  `<exclusions>` on the shared dependency — those are what make "standalone" true, and losing them would
  have quietly re-added a 100 MB OpenCV payload to every consumer.
- **`jitpack.yml` now requires `SHARED_TAG` instead of defaulting it to `v0.0.15`.** Flatten changes what a
  stale fallback costs: it used to affect only the build (the published pom kept the unresolved property),
  and it would now be baked into the published pom. A tag cut by hand without `.deps.env` fails with a
  readable message rather than publishing a pin at a long-dead shared.

---

## 2026-08-21 — session's JitPack build stops guessing its shared tag

**Changed:** `jitpack.yml`, new `.deps.env`.

**Done**

- **`jitpack.yml` reads `SHARED_TAG` from a committed `.deps.env` instead of resolving it with
  `git ls-remote --tags | sort -V | tail -1`.** Same change, same reasoning, as `botmaker-sdk`'s — see
  that ROADMAP's entry of the same date. The short version: the guess meant "newest published shared
  tag", so the umbrella `release.sh` had to wait for shared's JitPack build before tagging session;
  with the ref pinned in session's own release commit, JitPack builds that shared tag on demand and the
  two tags go out back to back.
- The committed pom is unchanged: `botmaker.shared.version` stays `0.0.0-SNAPSHOT` and the ref arrives
  via `-D`. `${SHARED_TAG:-v0.0.15}` still covers a tag cut by hand without `release.sh`.

---

## 2026-08-08 — backlog: measure Waydroid on gamescope instead of Xephyr

Not implemented — recorded from a user report that **Waydroid is laggy while a gamescope-only launch is
not**, so the next session doesn't have to re-derive it.

Today a Waydroid session is Wayland → inner `gamescope --backend sdl` → **Xephyr** → the host X server.
Xephyr reports `hardwareAccelerated() == false` (`NestedDisplay:77`): it is a software X server CPU-blitting
every frame, and it sits between two composited layers. That is the structural suspect.

**Dropping to a single gamescope does not work**, and the reason is already documented in
`docs/display-pipeline.md` §6: a lone Wayland client on a gamescope display maps no X11 window, so
`x11Capturable()` goes false, the capture reads an unpainted root, and both the bot and the pilot lose the
picture. **What should be measured instead is swapping the *outer* display to gamescope**, keeping the inner
`--backend sdl` — that window is still a mapped X11 client on the outer's Xwayland, so `PaintedSurface` and
`DisplayLink.paintedSurface()` are unchanged, while the compositing becomes hardware.

Two things fall out if it works: the lag, and **resizability** — gamescope scales between output `-W/-H` and
internal `-w/-h`, so dragging its edge changes presentation without touching the framebuffer, which is
exactly what Xephyr's deliberately-omitted `-resizeable` cannot offer (`NestedDisplay:95`, and §3).

`SessionBackends.preferredBackend`'s note calls gamescope-in-gamescope "nesting for no gain" — that predates
the lag report, and hardware compositing is the gain. Revisit it **with numbers**, on a box that has
gamescope; the dev machine has neither it nor a real GPU, which is why this is recorded rather than done.

---

## 2026-08-08 — the class inventory audited, and the untested seam gets its test

The question was "are all 40 types in this module pulling weight?". Audited by grep across all four
modules: **no type is unreferenced**, so there is nothing to delete. `LocalDisplay` in particular reads
like dead debug code and is not — it is the agent child's *own* X connection (`DisplayAgent.run`), and
also the documented fallback when no agent can be spawned. The real finding was coverage, not deletion.

**The gap**: the entire out-of-process seam — `DisplayAgent`, `AgentProtocol`, `DisplayAgentProcess`,
`RemoteDisplay`, `LocalDisplay`, five types and ~1,200 lines — had **zero tests**. That is the module's
one hand-written wire: `RemoteDisplay` encodes a request by hand and `DisplayAgent` decodes it by hand,
and nothing but agreement between those two halves keeps a verb's arguments landing in the right
parameters. A live session cannot tell you which half is wrong; a mismatch arrives as a click at (0,0)
or a window that "doesn't exist".

**Done**

- **`AgentProtocolTest`** (11 tests) — the framing primitives. What is at stake is *desynchronisation*,
  not a mangled string: fields are tab-separated and a response is one line plus a declared payload
  length, so a single unescaped tab in an X title does not corrupt one field, it shifts every field after
  it and the reader then parses a payload length out of a window's geometry. Pinned: all three separators
  round-tripping through one field, a hostile title not creating a fifth field, empty fields keeping
  their positions (`split(-1)`), a truncated escape dropped rather than thrown, every accessor's
  fallback, window/window-list round trips, and the framing itself — EOF as `null`, an unterminated last
  line still delivered, `readFully` refusing to invent a short payload.
- **`DisplayAgentWireTest`** (11 tests) — the seam run in one process: real request lines in, the real
  `DisplayAgent.serve` in the middle, real response lines out, with a recording stub in place of the
  `:N` connection. It asserts what the display was *asked to do*, so an argument landing one parameter
  over fails here instead of on a live display. Covers the two paths a live run reaches only by accident:
  a verb whose display call **throws** comes back as `ERR` with the stream still usable (the next request
  is answered normally), and **EOF** ends `serve` rather than spinning. Also: `bye`, an unknown verb
  answered rather than met with silence (silence leaves the caller blocked on a read while holding the
  request lock), the `cursor` null-position answer that is deliberately `OK` and not `ERR`, and the
  field-offset convention where `field(1)` is the payload length so `Response.asInt(n)` reads `n+1`.
- **The stub is a `java.lang.reflect.Proxy`**, not a hand-written class: `DisplayLink` extends
  `NativeController`, some forty methods, of which a wire test cares about six — thirty-four empty
  overrides would bury the four lines that matter.
- **`DisplayAgent`'s constructor and `serve()` went from private to package-private**, with the reason in
  their javadoc. That is the whole production change; the alternative was spawning a real child process
  in a unit test to exercise code that needs no display at all.

**Deferred / next**

- The other four seam types are still untested. `RemoteDisplay`'s **caller** half is the next cheapest
  and the natural pair to this one — point it at a pipe served by a stub agent and assert the request
  lines it emits, which would close the loop on both hand-written halves rather than one.
- `DisplayAgentProcess`'s spawn forms (re-exec vs. `java -cp`) still need a real process to test, so they
  belong in a live-gated suite rather than this one.

---

## 2026-08-08 — the scale filter that wasn't scaling, and a cursor for `:N`

The fidelity probe's first baseline said the H.264 path was *worse* than the JPEG floor it replaces on
pixel-fine content — 12.9 dB against 34.0. It was, and the cause was not the encoder.

**Done**

- **No `-vf` when the surface needs no resize** (`FfmpegVideoStream.command`). swscale's `fast_bilinear`
  is not a pass-through at 1:1: on a 1280×720 session (where `fit` is the identity) it cost **33 dB** of
  pixel-fine detail for a resize that was not happening, while moving the smooth regions by 0.15 dB. Live
  after the fix: checkerboard 12.9 → **38.9 dB**, whole frame 22.8 → **42.3 dB**, and H.264 now beats
  JPEG in *every* region. The guard is on the sizes, not on "did `maxEdge` apply" — `fit` also rounds an
  odd dimension down (4:2:0 cannot encode one) and a rounding is a real resize.
- **`bicubic` instead of `fast_bilinear`** when there *is* a downscale (`SCALER`). Measured on a genuine
  0.67× reduction all four kernels are within 0.6 dB, while `fast_bilinear` saves ~1 ms per frame at
  200 fps — nothing at 24. The cheap kernel bought a saving nobody can spend.
- **How it was found**, because the method is the reusable part: the error was `r:12.946840 g:12.946842
  b:12.946836`, and three channels agreeing to six digits is not quantisation noise. Re-encoding the same
  captured frame offline through every candidate (NVENC p1/p4/p5, CBR 6M/12M, VBR, libx264) put the band
  at 46–51 dB in all of them. Adding the one filter reproduced the live number exactly: 12.93 vs 12.95.
- **`:N` gets an ordinary arrow cursor** (`LocalDisplay.open` → `setRootCursor`, with `XCreateFontCursor`
  / `XDefineCursor` / `XFreeCursor` added to shared's `X11`). A bare X server's root cursor is
  `XC_X_cursor`, the black cross; on `:0` a desktop environment replaces it at login, and a private
  display has none — nor, for an emulator session, even a window manager. Purely cosmetic (`-draw_mouse 0`
  kept it out of the stream) but it read as a broken session. In `LocalDisplay` rather than `DisplayAgent`
  so it covers both topologies, and best-effort so a session never fails to start over a cursor.
- **`RootCursorLiveTest`** — because the best-effort `catch (Throwable)` that makes the above safe would
  equally hide a mistyped JNA signature. The test calls the bindings against a real `:N` and `XSync`s, so
  a link error is a failure rather than a log line.
- **`SessionBackends.DisplaySize` + `SizeSource` + `sizeFor` + `FIXED_SIZE_NOTE`** — a display's size *and
  where it came from*. Three call sites (Studio's `BackgroundLauncher`, its `BackgroundModeBox`, the SDK's
  `SessionBootstrap`) each had the "project resolution, else the default" rule, and the box resolved its
  fallback before handing the numbers down — so nothing downstream could tell an authored 1280×720 from an
  unauthored one, and no surface could explain why the display is the size it is. Studio's status line now
  says the size and its source with the full explanation on hover; a bot logs the same at bring-up.

**Deferred / next**

- The fixed size is now legible but still only changeable via the project's reference resolution. That is
  the right constraint (see `NestedDisplay.startXephyr`) — revisit only if a real workflow needs otherwise.
- Re-run the probe at `-Dbotmaker.fidelity.size=1080x1920` to baseline the portrait downscale, which is the
  one case the identity-guard does not cover.

---

## 2026-08-08 — the pilot's picture has a number now (`FidelityProbeTest`)

"Does the pilot look right" had no answer, because it is two questions with opposite ones. The new
live-gated harness separates them and treats them differently — geometry asserted, samples reported.

**Done**

- **`FidelityProbeTest` + `FidelityPattern`** (`src/test/java/…/video/`), opt-in behind
  `-Dbotmaker.live=true` exactly like the other live suites, `-Dbotmaker.fidelity.size=1080x1920` for the
  portrait case. It brings up a WM-less Xephyr session, paints a known pattern on it from a child JVM, and
  measures three stages against one truth: `captureScreen()` (lossless — and the frame a *bot* matches
  against), `Preview.jpeg`, and a real `openVideoStream` decoded back with ffmpeg. Nothing stubbed; a stage
  that is not the production path measures nothing. Artefacts to `~/.botmaker/fidelity/<ts>/`.
- **Geometry is the assertion**: five fiducials located by search (not by seed — a seeded search reports a
  hit on whatever green is nearest and cannot catch a crop), ≤1px lossless, ≤2px lossy. Measured 0.71px
  worst at every stage on the first live run, i.e. rounding.
- **The locator is plain Java.** OpenCV would do it in a line and is excluded from this module on purpose
  (`NoOcvOnTheClasspathTest` asserts it is not resolvable even in tests). A hundred lines of flood fill is
  the cheaper side of that trade — noted here because the plan that led to this work assumed the opposite.
- **`FfmpegVideoStream.encoder()`** — which rung of the candidate walk actually won. Not the same question
  as `codec()`, which is the pinned bitstream profile; this is only knowable at run time, and a fidelity
  number without it is not reproducible.
- **Per-region metrics, after the first run showed why.** Whole-frame PSNR said 22.8 dB while SSIM said
  0.97 — the two disagreeing because a 1px checkerboard at a tenth of the frame dominates a squared-error
  figure and barely touches a structural one. Reporting the band and a fiducial-free gradient slab
  separately is what makes the numbers mean anything.

**What it found on the first run** (1280×720, `h264_nvenc`; table in `docs/display-pipeline.md` §10):
on game-like content **H.264 beats JPEG** (46.8 dB vs 43.3, at a fraction of the bytes), and on pixel-fine
detail it is far worse (12.9 dB vs 34.0) — `-preset p1 -rc cbr -b:v 6M` flattens a 1px pattern completely.
In a session that is thin UI rules and small text, and it is the first thing to suspect behind a "glitchy"
Waydroid screen that does not actually tear.

> **Corrected the same day** — see the entry above. The symptom was real and the localisation ("blur, not
> tearing") held, but the attribution to the encoder settings was wrong: it was a `fast_bilinear` scale
> filter running at 1:1. Every encoder candidate put that band at 46–51 dB. Left here because the wrong
> guess is instructive — the numbers said "the encode", and only re-running the encode *without the rest of
> the command* said which part of it.

**Deferred / next**

- The out-of-process seam (`DisplayAgent`, `AgentProtocol`, `DisplayAgentProcess`, `RemoteDisplay`,
  `LocalDisplay`) still has no tests — see the inventory audit note.

---

## 2026-08-08 — a launch prepares the host before it builds an argv

**Done**

- `NestedSession.launchAndAttach` calls `LaunchPreparation.prepare(spec, display.width(), display.height())`
  before the ladder. For Waydroid that makes the container's own framebuffer match the display it is about to
  render into — the step that makes the private display's grab 1:1 rather than letterboxed. Every other kind
  prepares nothing. It runs ahead of `stopHostInstance` so the container it may cycle is the one being stopped
  anyway.

---

## 2026-08-08 — Waydroid runs on a private display, at the resolution the project asked for

Waydroid was black in the pilot and took ~1 s a frame, because every one of its frames came from ADB
`screencap` — which under a GPU-composited container returns black. Grabbing gamescope's desktop window
instead is not the fix: it is occlusion-prone, and the desktop's window manager sizes that window, so a
1080×1920 container arrived as a ~372×661 letterboxed strip (measured, about a third of the linear
resolution). Waydroid is a Wayland-only client and maps no X window of its own, so its pixels exist only in
gamescope's output — the question is really *who decides how big that output is*. On the desktop, the WM. On a
private display, us.

### Done

- **`SessionBackends.preferredBackend` answers XEPHYR for `EMULATOR_APP`** — the one exception to
  "always gamescope", and not a fallback: a Waydroid launch *is* a gamescope (it is the child command), so
  choosing gamescope as the display too would nest one in the other for nothing. Xephyr is the plain X server
  it opens its window on, and a real X server's framebuffer is the size we asked for regardless of how the
  window showing it is treated. Without gamescope the ladder is empty and the launch is still refused loudly.
- **`SessionBackends.optionsFor(spec, backend, w, h)`** — one place deciding backend + window manager + size,
  now shared by Studio's `BackgroundLauncher` and the SDK's `SessionBootstrap` instead of each building
  `Options` itself. An emulator app gets `withoutWindowManager()`: the only client on that display is
  gamescope's window, and openbox would frame and resize it exactly as the desktop's WM does.
  `Options.hasExplicitWindowManager()` is public because it is the only thing distinguishing "none,
  deliberately" from "unstated, use the default" — `windowManagerCommand()` is empty for both.
- **Xephyr no longer runs with `-resizeable`.** That flag ties the root to Xephyr's *window*, and the window
  belongs to the user's desktop: asked for 1080×1920 on a 1080-tall screen, the WM clamped it and the
  framebuffer shrank with it — a session whose `screen()` said 1080×1920 handed back **1080×661** frames.
  A private display's size is the project's reference resolution, which is what every template was captured
  at; it is not the desktop's to negotiate.
- **`NestedSession.stopHostEmulator`** — an emulator app stops the whole host *session* first, not the app.
  There is one container per machine and `waydroid app launch` talks to whichever session is up, so leaving
  the host's running means the app appears on the user's desktop while the private display waits out its
  window budget for something that was never coming.
- `NestedSession.launchAndAttach` asks for a ladder sized to `display.width()/height()` (see
  `botmaker-shared/ROADMAP.md`).

### Verified live

Driven end to end through the real path against a running container, not asserted from the code:

```
kind=EMULATOR_APP → backend=XEPHYR → isolatable=true
ladder  = env -u WAYLAND_DISPLAY gamescope --backend sdl -W 1080 -H 1920 -w 1080 -h 1920 --expose-wayland
          waydroid app launch com.zjcs.android.us
options = XEPHYR 1080x1920 wm=[]     display = :1  screen=1080x1920
attached to 'gamescope' on :1   x11Capturable=true   videoSurface=1080x1920
FRAME = 1080x1920  blank=false
```

Two defects on the way there were found only by running it, and both are now covered by the notes above: the
empty `WAYLAND_DISPLAY` that killed gamescope at startup, and `-resizeable` shrinking the framebuffer.

### Deferred / next

- **Input is unverified.** The route now wins `PilotRoutes` rung 1, so taps go through the session pointer
  (XTest on `:N` → gamescope → Waydroid) rather than ADB. gamescope's window covers the root at (0,0) so
  `ROOT_ABSOLUTE` warping should land 1:1, but no tap has been landed in Android yet. If it doesn't, the
  fallback is ADB gestures for this route — `AdbEmulatorSurface`'s `tap`/`drag`/`scroll` are unchanged and
  correct; only its `grab()` was ever the problem.
- The container still boots at whatever `persist.waydroid.width/height` say. Making *those* follow the project
  is the next phase (`WaydroidResolution.apply()` has been written and unused since it was added).

---

## 2026-08-08 — the H.264 encoder is aimed at the same surface the preview is

Completes the entry below, whose **Deferred / next** this was: the JPEG path had learned that the pixels are
in a window, and `ffmpeg` was still grabbing the root.

### Done

- **`PaintedSurface(long windowId, Rectangle rect)`** (`com.botmaker.session`) names the choice — `windowId 0`
  is the root, which is a real answer (Xephyr paints it) and not a "none". `DisplayLink.paintedSurface()`
  makes it by the *same rule* `previewFrame` applies per frame, so the two paths encode one surface rather
  than two that agree by coincidence. It answers `null` for a display with nothing painted, rather than
  falling back to the root: an encoder opened on an unpainted root is a healthy stream of black.
- **The agent serves it as a line, not a picture** (`surface` verb → `OK\t0\t<id>\t<x>\t<y>\t<w>\t<h>`). The
  inherited default would work over `RemoteDisplay` only by pulling a full-size PNG of the root across the
  pipe to ask whether it is black, and the video path asks this once a second for as long as it streams.
- **`FfmpegVideoStream.open(display, PaintedSurface, …)`** emits `-window_id <xid>` for a client window (and
  omits the flag entirely for the root — x11grab defaults there, and `-window_id 0` is not uniformly the same
  thing across builds). The grab is at the surface's own size; its offset is *not* passed as `+x,y`, which
  crops the root, because a window grab already arrives in the window's own coordinates.
- **`VideoStream.surface()`** — the stream reports what it is a picture of, and `PilotVideo.rect()` is that
  instead of `session.screen()`. Same Interact reason as the JPEG path: a window's pixels tagged with the
  screen's origin misplace every tap by the window's offset.
- **A surface that moves ends the stream.** `DesktopSession.videoSurface()` (memoised 1 s in `NestedSession`,
  sharing the TTL with `x11Capturable`) is what `PilotVideo` compares against each tick. This is a case the
  root grab did not have: a root cannot vanish, but the window a launcher chain was showing does, and an
  encoder holding a destroyed drawable has no way to say so except by dying — if it dies at all. The reopen
  re-announces with the new rect through the path `announceVideo` already had.
- **A decline is no longer permanent.** `PilotVideo` latched "this session gives no video" for the session's
  life, which was right for "every encoder failed" and wrong for "nothing is painted yet" — a pilot opened one
  second before the game mapped its window would have stayed on JPEG until the session restarted. The two are
  now distinguished; the temporary one is retried after 2 s.
- Tests: `FfmpegVideoStreamTest` +2 (the `-window_id` shape and its absence for a root), `PreviewSurfaceTest`
  +3 (the video surface is the preview's choice; a painted root is the root; nothing painted is no surface),
  and Studio's new `PilotVideoTest` (5) over the rect, the decline retry and the reopen-on-move.

### Verified live

Against a real `:9` (Xephyr, an xterm mapped), through `FfmpegVideoStream` itself:
`paintedSurface` chose the root (`windowId=0`, 800×600 — the non-compositing case), and a stream opened on the
xterm's `PaintedSurface[windowId=2097164, rect=31,41 259×160]` spawned
`… -video_size 259x160 -draw_mouse 0 -window_id 2097164 -i :9 …`, was accepted by `h264_nvenc`, and produced
**116 access units / 3 keyframes / 3.4 MB in 5 s** with `surface()` reporting the window's rect, not the
screen's.

### Deferred / next

The gamescope half is unverified live — the `:1` session was gone by the time this landed. The measurement it
rests on is the one in the entry below (`-i :1` → a frame mean of 0.04; `-window_id <xid>` → 28619).

---

## 2026-08-08 — a preview is a picture of a *surface*, and under gamescope that is never the root

### The bug

The pilot went black on every gamescope session. Measured on a live `:1` hosting a fullscreen game:
an X11 root grab returned **0 of 8160 sampled pixels non-black**, while `captureWindow` on the game's window
returned a full picture, and `ffmpeg -f x11grab -i :1` averaged **0.04/65535** where `-window_id <xid>`
averaged **28619**. gamescope's built-in compositor (`steamcompmgr`) redirects every client to its own Wayland
output and never paints the X root pixmap. Xephyr has no compositor, so its root *is* the picture — which is
why root-first was right when it was written and silently wrong the moment gamescope became the default
backend. `Preview.isBlank` then turned "a black frame is sent" into "no frame is sent", which is why the
symptom was a canvas that stopped updating rather than a black one.

### Done

- **`DisplayLink.previewFrame(maxEdge, quality)` replaces `previewJpeg`** and returns a new
  `com.botmaker.session.PreviewFrame(byte[] jpeg, Rectangle surface)`. It grabs the root and, when that is
  blank, the **largest mapped window** instead. Content-driven rather than flagged on the backend: no gamescope
  knowledge, self-healing if gamescope ever paints its root, and — unlike keying on the session's *attached*
  window — it still finds a picture during the seconds a launcher chain has swapped one window out.
- **The rect travels with the bytes.** It used to be assumed to be `screen()`. Under
  `--force-windows-fullscreen` those coincide, which is exactly what made the assumption survive: on a windowed
  client it would have misplaced every Interact tap by the window's offset, silently.
- **The agent serves the verb by calling that same default** (`DisplayAgent.preview`, renamed from
  `previewRoot`), so the in-process and out-of-process links cannot answer differently. The response line
  carries the rect: `OK\t<len>\t<x>\t<y>\t<w>\t<h>`.
- **`NestedSession.x11Capturable()` counts mapped clients** (`link.mappedCount() != 0`, memoised 1 s because a
  frame loop asks it 24×/s) instead of asking whether the display serves a Wayland socket. That probe was
  *constant* — `GamescopeDisplay` passes `--expose-wayland` unconditionally — so it answered `false` for every
  gamescope session including plain X11 games, which demoted the pilot's rung 1 for all of them and made
  `openVideoStream` dead code on the only backend it was built for. An unaskable display (`-1`) still counts as
  capturable: that is a broken link, not an empty display.
- Tests: `remote/PreviewSurfaceTest` (4) pins the choice with no display — painted root wins and grabs no
  window; black root falls to the largest window *and is tagged with its rect*; a black root with no windows is
  no frame; a window that grabs black is no frame either.

### Deferred / next

`FfmpegVideoStream` still grabs the root, so H.264 on a gamescope session encodes black. It needs
`-window_id <xid>` from the same choice this entry makes, plus a stream that ends when that window changes
(the root could not vanish; a window can).

---

## 2026-08-08 — H.264 off the session's display, with JPEG as the floor

### The cost

The previous entry cut a pilot frame from three codec passes to one. One is still a *whole picture* per
frame: a 1280-px JPEG at 24 fps is intra-only, so a game screen that barely changes costs the same megabytes
a second as one that does. Nothing in the JPEG path can send a difference, because JPEG has no notion of one.

### Done

- **`com.botmaker.session.video`** — a live H.264 encode of a session's `:N` root:
  - **`AnnexB`** cuts the encoder's raw byte stream into access units. Pure, no threads and no I/O, which is
    why it is a type of its own: it is the only part of this path testable without a GPU, an X server or an
    `ffmpeg`. It emits a picture on the *type byte* of the next NAL rather than the next whole NAL — the
    difference is a permanent frame of latency and a still scene whose last picture never arrives.
  - **`FfmpegVideoStream`** spawns `ffmpeg -f x11grab -i :N` and walks `h264_nvenc` → `h264_vaapi` →
    `libx264 -tune zerolatency`. A candidate is accepted only once it has **produced a packet**, never on
    being listed by `ffmpeg -encoders`: a hardware encoder is listed on a machine whose driver refuses it,
    whose device node is missing, or that is at its stream limit, and all three fail at run time. Opening is
    therefore asynchronous — the caller is a frame loop and cannot block on three candidates failing.
  - **`VideoStream`/`VideoPacket`** are the contract. `keyframe` means *SPS + PPS + IDR together*, not "has
    an IDR": a client joining mid-stream has never seen the parameter sets, so the looser reading would hand
    it an undecodable picture and produce a black canvas with no error anywhere.
- **`DesktopSession.openVideoStream(maxEdge, fps, sink)`**, defaulting to `null` — the same shape as
  `previewJpeg`, one step further. `NestedSession` implements it and **declines** (null, not an error) with no
  `ffmpeg` on PATH or a display that is not `x11Capturable()`; both are sessions the JPEG path already serves.
- **`SessionUnit.VIDEO`** — the encoder is launched into the session's reap group, so it dies with the display
  it is reading and a `kill -9`'d Studio leaves no `ffmpeg` holding an X connection.

### Worth knowing

- **`-bf 0` is load-bearing twice.** B-frames reorder output (latency), and their absence is also what lets
  `AnnexB` decide a picture boundary without parsing slice headers for `first_mb_in_slice`.
- The surface rect a consumer tags frames with stays `screen()` regardless of the downscale, exactly as on the
  JPEG path — the pilot's client fits and maps taps through the declared rect, never the bitmap's pixels.

---

## 2026-08-08 — one codec pass for a preview frame

### The cost

A pilot frame off a nested session paid **three** codec passes to reach a phone: the agent PNG-encoded the
`:N` root (`captureRoot`, lossless on purpose — the vision stack matches templates against those pixels),
Studio decoded that PNG, and then JPEG-encoded it again at `ImageIO` defaults, full size, allocating a writer
per frame. Two of the three ran on the single `pilot-frame` thread *while it held `RemoteDisplay`'s request
lock* — the same lock Interact's taps queue behind. On top, the loop was a fixed **delay**, so a route that
captured in 5 ms still waited the whole 83 ms period afterwards.

### Done

- **`com.botmaker.session.Preview`** — the shared preview policy: `isBlank` (the coarse 16-px grid that reads
  an empty X11 root as "no capture"), `jpeg(img, maxEdge, quality)` with a **thread-local cached
  `ImageWriter`**, and the constants `MAX_EDGE = 1280` / `QUALITY = 0.6`. It lives beside the session contract
  because both ends of the path are here: the agent child and Studio's pilot were each doing a different
  subset of this, disagreeing on quality, size and what "no frame" means.
- **`previewRoot <maxEdge> <qualityPercent>`** in `DisplayAgent`/`AgentProtocol`: grab, blank-test, downscale,
  JPEG — *in the process that already holds the display*. An empty payload is "no frame". `captureRoot`'s PNG
  is untouched and stays lossless.
- **`DisplayLink.previewJpeg(maxEdge, quality)`**, `default`-implemented as grab-then-encode (correct for
  `LocalDisplay` and tests, where there is no pipe to save), overridden by `RemoteDisplay` with the verb.
  Surfaced on **`DesktopSession.previewJpeg`** (`default null` — no shortcut) and forwarded by `NestedSession`
  and `AdoptedSession`.

Net: PNG encode + pipe + PNG decode + JPEG encode → **one** JPEG encode, off Studio's frame thread entirely,
over a payload several times smaller, holding the link's lock for a fraction of the time.

### Deferred / next

- The agent still re-encodes from scratch every frame. Phase 5 of the plan replaces this path with `ffmpeg
  -f x11grab` → `h264_nvenc`/`h264_vaapi`/`libx264` over the same WebSocket, keeping this JPEG path as the
  negotiated fallback for no-ffmpeg / no-WebCodecs clients.
- `previewJpeg` has no equivalent for a *window* (only the root). Nothing needs one — the pilot streams the
  root precisely so a launcher chain's window swap is invisible — but a window preview would be the same shape.

---

## 2026-08-08 — a session says whether its pixels are on X11

**Changed:** `DesktopSession.java`, `impl/NestedSession.java`.

**The bug.** Waydroid is a Wayland-only client. Under `gamescope --expose-wayland` its surface never reaches
the embedded Xwayland, so grabbing the session's `:N` root *succeeds* and returns a full-size, entirely black
frame — no exception, no null, nothing to tell it apart from a game on a dark screen. Both consumers preferred
a live session unconditionally (the pilot's `PilotRoutes.current()` rung 1, the SDK's `Source.current()`), so
the one route that *could* see those pixels — the emulator's ADB surface — was suppressed in favour of one
that never could. The pilot showed nothing and said nothing.

**Done**

- `DesktopSession.x11Capturable()`, `default true` — the host desktop and an Xephyr session are X11 all the
  way down. `NestedSession` overrides it as `display.waylandDisplay() == null`, i.e. the same condition that
  earns `Capability.WAYLAND_CLIENTS`, read as its consequence rather than its benefit.
- It is deliberately the *capability* and not "is a Wayland client actually connected", which nothing on this
  side can observe. The two errors cost wildly different amounts: a false `false` for a gamescope session
  hosting an ordinary X11 game costs one look at the consumer's next-best source; a false `true` for one
  hosting Waydroid costs a black stream with no error anywhere. Consumers keep the session as their *floor*
  (see `PilotRoutes` rung 4), so the conservative answer never degrades past the user's real desktop.

**Deferred / next**

- A session could answer this from *observation* — whether anything has ever mapped on its Xwayland root —
  which would let an X11 game under `--expose-wayland` be recognised as capturable rather than merely
  outranked. It needs a client-count probe through `DisplayLink` that does not exist yet, and the floor rule
  above makes the current answer safe without it.

---

## 2026-08-08 — the driven-window sync stopped recursing into itself

**Why.** Launching a Heroic-routed game (Firestone) into a gamescope session died with a `StackOverflowError`
the moment the session attached to the launcher's window: `RemoteDisplay.call` → `syncDrivenWindow` →
`NestedSession.attachedWindowId` → `SessionAttachment.resolve` → `isViewable` → `RemoteDisplay.windowViewable`
→ `call` → … forever. The callback was known and documented — moving the supplier *outside* the request lock
is what keeps it from deadlocking — but nothing bounded it, and since "prove the host window is ours, and
follow the launcher chain" the supplier's own path hits the link twice (`windowViewable`, and `getAllWindows`
via `promoted()` once `followLauncherChain` is armed). `sentDrivenWindow` couldn't break the cycle: it is
only written *after* the supplier returns, which it never did.

**Done**
- `RemoteDisplay.syncDrivenWindow()` is now a no-op while the same thread is already inside it
  (`ThreadLocal<Boolean> resolvingDrivenWindow`, set around `supplier.get()` in a `try/finally`). The nested
  call goes out with the id the agent was last told, which is correct — the outer call is on its way to
  sending the fresh one. Per-thread rather than one flag because the sync runs outside the request lock, so
  two threads can legitimately be in it at once.
- The class javadoc paragraph now states both halves of the contract (outside the lock *and* never
  re-entered) rather than only the deadlock half.
- No change in `SessionAttachment`/`NestedSession`/`AdoptedSession` — the callback into the link is the
  design there, and `AdoptedSession` wires the same supplier, so it inherits the fix.

**Deferred / next**
- No regression test: `RemoteDisplay` is only constructible through a spawned `DisplayAgentProcess`, so
  pinning this needs a test seam (a package-private factory over a fake agent process) that doesn't exist
  yet. Worth adding the next time this class is opened — recursion bugs regress silently.

---

## 2026-08-07 — gamescope is the default backend for every launch kind; Xephyr is a pin

**Why.** `SessionBackends.preferredBackend` chose by `LaunchKind`: gamescope for the game kinds, Xephyr for
`cli:`/`emu-app:`/unknown. The split bought nothing — "lighter" is not a property anyone measured — and cost
a second bring-up path that only the *least*-exercised launch kinds ever ran, on the one backend whose
software GL is what crashes what a session usually hosts. gamescope is also its own window manager (no
openbox to install or to be missing), owns focus, and forces its client fullscreen, which is what makes a
session's screen capture and its input geometry agree — the property Studio's pilot now depends on.

**Done**
- `preferredBackend(spec)` returns `GAMESCOPE` unconditionally (it still takes the spec: the question is
  per-target, and a future backend for `EMULATOR_APP` would be decided there). `availableBackendFor` therefore
  goes empty whenever gamescope is missing — the loud-failure signal, never a silent drop to Xephyr.
- `Backend.XEPHYR` survives **only** as an explicit pin: `Session.useBackend("xephyr")`,
  `-Dbotmaker.session.backend=xephyr`, or the project's `session.backend` key. `Backend.fromId` is unchanged
  and still total, so `auto` and typos fall through to the default rather than onto Xephyr.
- Studio's `BackgroundModeBox` lists gamescope first, preselects it, and its tooltip says what Xephyr is now
  for (bisecting a gamescope problem). `Options.xephyr(...)`, `windowManagerFor(XEPHYR)` and
  `revealHostWindow`/`raiseXephyrHostWindow` all stay — the pin has to keep working.
- Tests restated in both modules: `SessionBackendsTest` asserts the answer over every `LaunchKind`; the SDK's
  ladder tests now prove fall-through with a *parseable* `xephyr` (the only value a match could produce that
  the default cannot), which is a sharper assertion than the kind-driven pair it replaced.

**Deferred / next**
- **Xephyr is deprecated, not removed.** Remove it once a gamescope-only run has soaked: `NestedDisplay`,
  `SessionHostWindow`'s raise path, `windowManagerFor`, the openbox dependency and the `xephyr` wire id all go
  with it. Keep the wire id parsing forever if any project file ever stored it.

---

## 2026-08-07 — the `:N` connection moves out of process (`com.botmaker.session.remote`)

**The bug.** Closing a game's window took Studio down with it. Not the reaper, not the slice: an untrapped
Xlib **I/O** error. `X11ErrorTrap` installs `XSetErrorHandler` (protocol errors) but never
`XSetIOErrorHandler`, and Xlib's default I/O handler calls `exit(1)` **in the process holding the
connection**. A nested session held two open `Display*` handles to `:N` inside Studio's own JVM — the
`LinuxController`'s and a second one for EWMH reads — so when the display server went away, Studio vanished
mid-frame with no exception and no `hs_err`. Reproduced end to end, and it is not really trappable in
process either: an `XSetIOErrorHandler` handler is not allowed to return, so the best an in-process trap can
do is choose how to die.

**Done**
- New package `com.botmaker.session.remote`, built around **`DisplayLink`** — one seam for everything a
  session does to `:N`. Beyond `NativeController` it carries the reads a session used to make against its
  own EWMH connection (`windowViewable`, `windowPid`, `hasWindowManager`, `mappedCount`, `screenSize`,
  `alive`) plus `captureScreen()`, the display root.
- **`DisplayAgent`** is a `main` that opens `:N` and serves one request at a time over stdin/stdout; EOF
  means the parent has gone and it exits. Dying is its feature: when `:N` dies it is the *agent* Xlib exits.
  **`RemoteDisplay`** is the caller-side proxy — it reads EOF, marks itself dead and degrades every later
  call to `null`/`0`/empty/no-op, so the death arrives through the path that already existed
  (`health()` → `NestedSession.closeIfDead`). **`LocalDisplay`** keeps the old in-process behaviour for the
  agent itself and as a fallback, selectable with `-Dbotmaker.session.display.local=true`.
- **`DisplayAgentProcess`** spawns it two ways: `$JAVA_HOME/bin/java -cp <our classpath>` first (covers a dev
  `javafx:run`, a `java -jar` bot and a jpackage app-image), else a re-exec of *this program* with
  `DisplayAgent.ARG_MARKER` in front — which is why `BotMakerStudio.main` now dispatches on
  `DisplayAgent.isAgentInvocation` before JavaFX. The child's stderr goes to a **file**, never a pipe: an
  undrained pipe blocks its writer, and the writer here is the process holding the display.
- **`AgentProtocol`** is a hand-rolled escaped line protocol, not JSON — this module's footprint is a rule
  (`CLAUDE.md`), and the payload that matters is a binary frame anyway. Frames travel as PNG (lossless: these
  are the pixels the vision stack matches templates against).
- Handles are now plain `Long` ids above the link and JNA `Pointer`s only inside the agent; **`WindowIds`** is
  the one place that converts, and `LocalDisplay` translates at its boundary. `NestedSession`,
  `AdoptedSession` and `SessionAttachment` lost their `Pointer` casts and their second X connection —
  `SessionAttachment`'s constructor is now `(DisplayLink, String)`.
- `DesktopSession.captureScreen()` added (defaulting to `capture()`), for the Phase 2 pilot work: a frame that
  does not depend on which window a launcher chain is currently showing.

**Verified**
- Against a real Xephyr: spawn, enumerate, root capture, `mouseMove`, `cursorPosition`, clean close.
- Kill the server under a live link → caller logs "the agent closed its output — :N is gone", every later
  call degrades, and the JVM **survives**. The same program with `-Dbotmaker.session.display.local=true`
  prints `XIO: fatal IO error` and exits — the reported Studio death, reproduced and then fixed.

**Deferred / next**
- `DisplayReadiness.await` still opens and closes a short-lived connection to `:N` in the caller's process
  during bring-up. The window is microseconds wide and predates anything worth crashing over, but it is the
  last in-process `:N` open and should move behind the agent when convenient.
- The agent is one process per session. Fine at today's session counts; a shared agent multiplexing displays
  is the obvious next step if that stops being true.

---

## 2026-08-06 — `SessionUnit`: the reaper's roles and its unit names are one type

**94 tests (unchanged).** Added: `process/SessionUnit.java`. Changed: `process/SessionReaper.java`,
`process/SessionBus.java`, `process/SessionMembers.java`, `display/NestedDisplay.java`,
`display/GamescopeDisplay.java`, `impl/NestedSession.java`, `impl/SessionHostWindow.java`,
`process/SessionReaperRaceTest.java`. Pairs with the shared entry of the same date.

**Done.** Three closed sets in this stack were spelled as bare strings.

- **`SessionUnit`** (`XEPHYR`, `GAMESCOPE`, `WM`, `DBUS`, `APP`) now types the reaper's `role` parameter.
  Before, `SessionReaper.launch` took a `String role` and `unitNamesExcept` took another, and nothing
  connected the token a caller *recorded* to the one `NestedSession` later *excluded* — a mismatch there
  leaves the display server in the list of processes to shut down before the display goes away, which is the
  precise ordering the shutdown exists to prevent. `NestedSession`'s private `APP_ROLE` constant is gone; it
  is `SessionUnit.APP`.
- **The `botmaker-sess-` prefix is no longer retyped.** `SessionReaper` rebuilt it **seven** times, plus an
  eighth copy hardcoded inside the `SESSION_SLICE` regex — while `ProcessOrigin.SESSION_UNIT_PREFIX`, the
  reader that parses it back out of a cgroup path, already owned it in shared. `SessionUnit` takes the prefix
  from there and offers the constructions around it: `unitName(id)`, `scopeName(id)`, `sliceName(id)`, the
  `list-units` globs, `probeUnitName(pid)`, `isSessionUnit` and `stripPrefix`. The regex is now built with
  `Pattern.quote(SESSION_UNIT_PREFIX)`.
- **Binary names come from `NestedSession.Backend.binaryName()`.** `NestedDisplay` spelled `"Xephyr"` in its
  argv and `GamescopeDisplay` spelled `"gamescope"` in two places, all beside a `Backend` that already
  single-sources both. `Backend` in turn now returns `Executables.XEPHYR` / `Executables.GAMESCOPE` from
  shared, so shared's own gamescope spawn (Waydroid's UI) and this one can no longer drift. `Backend.id()`
  stays separate and lowercase, as its javadoc already explains: the persisted `session.backend` value must
  survive a rename of the executable.
- **Env var names come from `shared`'s new `platform/SessionEnv`** — `NestedSession.sessionEnv()` and its
  bus start, `SessionMembers`' two `NAME=value` matchers, `SessionHostWindow`'s host-`DISPLAY` read and
  `SessionReaper`'s `XDG_RUNTIME_DIR` systemd probe.

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
