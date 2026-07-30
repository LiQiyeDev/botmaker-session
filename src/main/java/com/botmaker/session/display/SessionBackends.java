package com.botmaker.session.display;

import com.botmaker.session.impl.NestedSession;

import com.botmaker.shared.Executables;
import com.botmaker.shared.capture.linux.input.InputTiming;
import com.botmaker.shared.capture.linux.input.PointerWarp;
import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Single-sourced choice of nested-display {@link NestedSession.Backend} for a given launch — the one place both
 * the SDK bot runtime ({@code SessionBootstrap}) and Studio's launch surfaces agree on <em>which</em> backend a
 * target needs and whether it is installed. Living here (not in either consumer) is the repo's single-sourcing
 * rule: anything the SDK and Studio would each otherwise compute — backend choice, availability, the install
 * hint — belongs in shared so the two can't drift.
 *
 * <p><b>Why the choice is kind-driven.</b> A store launcher (Steam/Epic/Heroic/Faugus) and the Proton game
 * behind it — and a native {@code exe} game — boot a real GPU stack (Electron/Chromium, Vulkan/GL). Under
 * Xephyr's <em>software</em> GL that stack aborts (the observed Heroic SIGTRAP), so those kinds need
 * {@link NestedSession.Backend#GAMESCOPE}, which puts a real GPU inside the private display. A plain
 * {@code cli:} command has no such need and stays on the lighter {@link NestedSession.Backend#XEPHYR}.
 *
 * <p><b>No silent Xephyr fallback for a game.</b> When a game's required backend (gamescope) isn't on
 * {@code PATH}, {@link #availableBackendFor(LaunchSpec)} is empty — the loud-failure signal. Callers surface
 * {@link #installHint(NestedSession.Backend)} rather than falling back to a Xephyr that would crash the game.
 */
public final class SessionBackends {

    private SessionBackends() {}

    /**
     * The backend a target of this {@code spec} wants, irrespective of what's installed: game kinds
     * ({@link LaunchKind#STEAM STEAM}, {@link LaunchKind#EPIC EPIC}, {@link LaunchKind#HEROIC HEROIC},
     * {@link LaunchKind#FAUGUS FAUGUS}, {@link LaunchKind#EXE EXE}) → {@link NestedSession.Backend#GAMESCOPE};
     * everything else ({@link LaunchKind#CLI CLI}, {@link LaunchKind#EMULATOR_APP EMULATOR_APP},
     * {@link LaunchKind#UNKNOWN UNKNOWN}, and a {@code null} spec) → {@link NestedSession.Backend#XEPHYR}.
     */
    public static NestedSession.Backend preferredBackend(LaunchSpec spec) {
        if (spec == null) {
            return NestedSession.Backend.XEPHYR;
        }
        return switch (spec.kind()) {
            case STEAM, EPIC, HEROIC, FAUGUS, EXE -> NestedSession.Backend.GAMESCOPE;
            case CLI, EMULATOR_APP, UNKNOWN -> NestedSession.Backend.XEPHYR;
        };
    }

    /**
     * The {@link #preferredBackend(LaunchSpec)} for {@code spec}, but only if its host binary is actually on
     * {@code PATH}; empty otherwise. Empty means "the backend this target needs isn't installed" — the caller's
     * cue to fail loudly with {@link #installHint(NestedSession.Backend)}, <em>not</em> to drop to a different
     * backend (a game on Xephyr is exactly the crash this avoids).
     */
    public static Optional<NestedSession.Backend> availableBackendFor(LaunchSpec spec) {
        return availableBackendFor(spec, SessionBackends::onPath);
    }

    /**
     * Availability against an injected {@code binaryOnPath} probe — the testable seam behind
     * {@link #availableBackendFor(LaunchSpec)}, so a test can assert the kind→backend→availability chain without
     * a real {@code PATH}.
     */
    static Optional<NestedSession.Backend> availableBackendFor(LaunchSpec spec, Predicate<String> binaryOnPath) {
        NestedSession.Backend preferred = preferredBackend(spec);
        return binaryOnPath.test(preferred.binaryName()) ? Optional.of(preferred) : Optional.empty();
    }

    /** Whether {@code backend}'s host binary ({@link NestedSession.Backend#binaryName()}) is on {@code PATH}. */
    public static boolean isAvailable(NestedSession.Backend backend) {
        return onPath(backend.binaryName());
    }

    /** The window manager to run inside a {@link NestedSession.Backend#XEPHYR} display, when it is installed. */
    static final List<String> DEFAULT_XEPHYR_WM = List.of("openbox", "--sm-disable");

    /**
     * The window manager a nested display on {@code backend} should run, or an empty list for none — the single
     * place the WM question is answered, so the SDK's bot runtime and Studio's launch surfaces can't each invent
     * a different one.
     *
     * <p><b>Xephyr gets one; gamescope must not.</b> A bare Xephyr has no window manager at all, which means no
     * EWMH: nothing answers {@code _NET_ACTIVE_WINDOW}, so no client ever takes input focus and nothing honours
     * a fullscreen request — and focus is exactly what the session's window-targeted key injection depends on.
     * openbox is the smallest thing that fixes that. gamescope, by contrast, <em>is</em> the window manager for
     * its embedded Xwayland (it owns focus and forces its top-level fullscreen); a second WM inside it would
     * fight the first for the manager selection.
     *
     * <p>An absent openbox is not an error: {@code NestedSession} degrades to a WM-less display with a trace,
     * the same behaviour as before this default existed.
     */
    public static List<String> windowManagerFor(NestedSession.Backend backend) {
        return windowManagerFor(backend, SessionBackends::onPath);
    }

    /** {@link #windowManagerFor(NestedSession.Backend)} against an injected {@code PATH} probe, for tests. */
    static List<String> windowManagerFor(NestedSession.Backend backend, Predicate<String> binaryOnPath) {
        if (backend != NestedSession.Backend.XEPHYR) {
            return List.of();
        }
        return binaryOnPath.test(DEFAULT_XEPHYR_WM.get(0)) ? DEFAULT_XEPHYR_WM : List.of();
    }

    /**
     * How a display on {@code backend} interprets an absolute pointer warp — the same single-sourcing rule as
     * {@link #windowManagerFor(NestedSession.Backend)}: the quirk is a property of the backend, so it is decided
     * once here rather than rediscovered by the SDK and Studio.
     *
     * <p>gamescope's embedded Xwayland routes injected motion through the focused surface, so an
     * {@code XTestFakeMotionEvent} lands <em>window-relative</em>; measured here, its focus window sits at root
     * {@code (2,2)} and every click landed 2px off target until the origin was subtracted. Xephyr — like every
     * real X server — is plain {@link PointerWarp#ROOT_ABSOLUTE}. See {@link PointerWarp} for the measurements.
     */
    public static PointerWarp pointerWarpFor(NestedSession.Backend backend) {
        return backend == NestedSession.Backend.GAMESCOPE
                ? PointerWarp.FOCUS_RELATIVE
                : PointerWarp.ROOT_ABSOLUTE;
    }

    /**
     * How long a session's backend pauses around a click or keystroke — decided here for the same reason
     * {@link #pointerWarpFor(NestedSession.Backend)} is: it is a property of what a session drives, not of the
     * consumer driving it.
     *
     * <p><b>Why longer than the host default.</b> {@code InputTiming.DEFAULT}'s 12 ms press hold is tuned for
     * desktop toolkits, which react to an event queue. What is on the other end of a session is a game
     * sampling input on its own frame timer, where a press shorter than a frame (~16 ms at 60 fps) can be
     * observed as no press at all — the "tap produced a hover highlight instead of a click" symptom. ~40 ms is
     * two frames at 60 fps, comfortably above the sampling interval and still imperceptible.
     *
     * <p>Both backends get the same answer today; it takes the backend anyway because the question is a
     * per-backend one, and gamescope's compositor may yet turn out to want different pacing from Xephyr's.
     */
    public static InputTiming inputTimingFor(NestedSession.Backend backend) {
        return SESSION_TIMING;
    }

    /** ~40 ms press hold — two frames at 60 fps. See {@link #inputTimingFor(NestedSession.Backend)}. */
    static final InputTiming SESSION_TIMING = InputTiming.DEFAULT.withPressHold(40);

    /**
     * Whether a session started with these {@code options} should own a private D-Bus session bus. Policy lives
     * here for the same reason the window-manager and pointer-warp answers do: one place, so the SDK's bot
     * runtime and Studio's launch surfaces can't drift.
     *
     * <p><b>On for every backend.</b> This is not a display-backend property — it is what stops a *launcher*
     * from escaping the session, and both Xephyr and gamescope sessions launch launchers. A private bus gives
     * the session its own Flatpak portal (so a portal re-spawn inherits the private {@code DISPLAY} instead of
     * the host's {@code :0}) and hides the host's launcher instance from a single-instance check. The switch is
     * kept because a bus is the one part of bring-up that can be turned off without losing the display
     * isolation, which makes it the natural thing to bisect when a launcher misbehaves.
     */
    public static boolean usesPrivateBus(NestedSession.Options options) {
        return options == null || options.privateBus();
    }

    /**
     * A one-line, user-facing hint for making {@code backend} available — shown when a launch needs it but it
     * isn't installed. gamescope carries the "real GPU in the private display" rationale (the reason a game
     * can't just fall back to Xephyr); Xephyr the equivalent 2D note.
     */
    public static String installHint(NestedSession.Backend backend) {
        return switch (backend) {
            case GAMESCOPE -> "install gamescope to run games in a private background display "
                    + "(it provides a real GPU inside the nested display; Xephyr's software GL crashes games)";
            case XEPHYR -> "install Xephyr (the Xorg nested X server, e.g. the xorg-x11-server-Xephyr / "
                    + "xserver-xephyr package) to run in a private background display";
        };
    }

    /** Best-effort {@code PATH} probe; single-sourced in {@link Executables} (three copies had drifted). */
    private static boolean onPath(String binary) {
        return Executables.onPath(binary);
    }
}
