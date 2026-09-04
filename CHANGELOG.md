# Changelog

What each released version of `botmaker-session` changes, in a few bullets. `ROADMAP.md` stays the detailed
engineering log; this is the short answer, and it is what `release.sh` publishes as the GitHub Release body.

**`release.sh` refuses to cut a version with no section here** (`check_changelog`, decide pass, before
anything is tagged). If the top section still says `## [Unreleased]`, rename it to the version being cut and
date it.

Sections are `## [x.y.z] — YYYY-MM-DD`, newest first.

## [Unreleased]

- **The pin `0.0.11` added was itself unbuildable on JitPack**, so that tag is missing too.
  `maven-compiler-plugin` raised its own Maven prerequisite to 3.6.3 in 3.12.0 and JitPack's Maven is
  older, so 3.13.0 failed with `requires Maven version 3.6.3`. Pinned to **3.11.0**, the version
  `botmaker-shared` has always used. Use `0.0.12`; `0.0.11` was never published.

## [0.0.11] — 2026-09-04

- **Resolvable from JitPack again.** `v0.0.10` was published and never built: this pom did not pin
  `maven-compiler-plugin`, and JitPack's Maven defaults it to **3.1** — a version that predates
  `maven.compiler.release` (3.6), ignores it and falls back to `source 5`:

  ```
  [ERROR] Source option 5 is no longer supported. Use 8 or later.
  ```

  It broke on 2026-09-02, with the move to Java 25, and took the SDK with it: a bot resolving
  `botmaker-sdk` got `Could not find artifact com.github.LiQiyeDev:botmaker-session`. The session stack
  itself is unchanged — this tag publishes what `v0.0.10` was meant to.

## [0.0.10] — 2026-09-02

- **Compiled for Java 25 (LTS).** A consumer needs a 25 runtime; the session stack itself is unchanged.

## [0.0.9] — 2026-09-02

_Nothing yet._

## [0.0.8] — 2026-08-22

- Build fix only: `flatten-maven-plugin` pinned to 1.4.1. 1.6.0 needs a Maven newer than JitPack's, so
  v0.0.7's own build never produced an artifact.

## [0.0.7] — 2026-08-22

- **The published pom names a real shared tag.** It declared `botmaker-shared:0.0.0-SNAPSHOT`, which resolves
  on a dev box and nowhere else; the pom is now flattened at publish time with the pinned tag baked in, and
  the JitPack build *requires* `SHARED_TAG` rather than defaulting it.

## [0.0.6] — 2026-08-22

- Re-tagged so JitPack rebuilt it against a new shared. No source change.

## [0.0.5] — 2026-08-21

- Re-tagged so JitPack rebuilt it against a new shared. No source change.

## [0.0.4] — 2026-08-21

- **The session encodes its own screen as H.264**, in one codec pass per preview frame, with JPEG as the
  floor — and the encoder is aimed at the surface that actually has pixels rather than at the root window
  (under gamescope the root is never it).
- **The pilot's picture has a number**: a per-stage fidelity probe, so a degraded stream is attributable
  rather than argued about.
- **Waydroid runs on a display nobody else gets to resize**, at the resolution the project asked for, and a
  launch prepares the host before it builds an argv.
- A session says whether its pixels are on X11; the driven-window sync stopped recursing into itself; the
  scale filter that was not scaling now scales, and `:N` has a cursor.
- The shared tag is pinned in `.deps.env` instead of guessed.

## [0.0.3] — 2026-08-04

- CI only: one `ci.yml` per repo, compile-only.

## Earlier

v0.0.2 and below predate this file — the module was extracted from `botmaker-shared` in 2026-07.
`ROADMAP.md` has the dated log.
