package com.botmaker.session.display;

import com.botmaker.session.impl.NestedSession;

import com.botmaker.shared.capture.linux.input.InputTiming;
import com.botmaker.shared.capture.linux.input.PointerWarp;
import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single-sourced backend chooser: kind→backend, and availability filtered by an injected {@code PATH} probe.
 * Pure — no real X server or {@code PATH} involved.
 */
class SessionBackendsTest {

    private static LaunchSpec spec(LaunchKind kind, String token) {
        return new LaunchSpec(kind, token);
    }

    @Test
    void everyGameKindPrefersGamescope() {
        for (LaunchKind kind : LaunchKind.values()) {
            if (kind == LaunchKind.EMULATOR_APP) {
                continue;   // brings its own gamescope; see theEmulatorAppIsTheOneKindThatWantsXephyr
            }
            assertEquals(NestedSession.Backend.GAMESCOPE, SessionBackends.preferredBackend(spec(kind, "token")),
                    kind + " must resolve to gamescope — Xephyr is reachable only through the explicit pin");
        }
    }

    /**
     * The one exception, and not a fallback: an emulator app's child command <em>is</em> a gamescope (Waydroid
     * is a Wayland client and needs a compositor), so the display it opens on has to be a plain X server or
     * gamescope would be nested inside gamescope for nothing.
     */
    @Test
    void theEmulatorAppIsTheOneKindThatWantsXephyr() {
        assertEquals(NestedSession.Backend.XEPHYR,
                SessionBackends.preferredBackend(spec(LaunchKind.EMULATOR_APP, "com.foo@Waydroid")));
    }

    /**
     * And that display must not manage windows: openbox would frame and resize gamescope's window exactly as
     * the desktop's window manager does on {@code :0} — measured there at 1280×661 for a 1080×1920 container,
     * which is the letterboxed third-resolution image the private display exists to avoid.
     */
    @Test
    void anEmulatorAppGetsAnExplicitlyUnmanagedDisplay() {
        NestedSession.Options emulator = SessionBackends.optionsFor(
                spec(LaunchKind.EMULATOR_APP, "com.foo@Waydroid"), NestedSession.Backend.XEPHYR, 1080, 1920);
        assertTrue(emulator.hasExplicitWindowManager(), "must be a stated 'none', not an unstated default");
        assertTrue(emulator.windowManagerCommand().isEmpty());

        NestedSession.Options game =
                SessionBackends.optionsFor(spec(LaunchKind.EXE, "/usr/bin/game"), NestedSession.Backend.XEPHYR, 800, 600);
        assertFalse(game.hasExplicitWindowManager(), "a game keeps the backend default (openbox when installed)");
    }

    @Test
    void nullSpecDefaultsToGamescope() {
        assertEquals(NestedSession.Backend.GAMESCOPE, SessionBackends.preferredBackend(null));
    }

    @Test
    void availableWhenTheRequiredBinaryIsOnPath() {
        Predicate<String> installed = onPath("gamescope", "Xephyr");
        assertEquals(Optional.of(NestedSession.Backend.GAMESCOPE),
                SessionBackends.availableBackendFor(spec(LaunchKind.HEROIC, "Firestone"), installed));
        assertEquals(Optional.of(NestedSession.Backend.GAMESCOPE),
                SessionBackends.availableBackendFor(spec(LaunchKind.CLI, "echo hi"), installed));
    }

    @Test
    void emptyWhenGamescopeIsMissing() {
        // Only Xephyr installed: nothing has an available backend — the loud-failure signal, never a silent
        // drop to the Xephyr whose software GL is what crashes a game.
        Predicate<String> onlyXephyr = onPath("Xephyr");
        assertTrue(SessionBackends.availableBackendFor(spec(LaunchKind.HEROIC, "Firestone"), onlyXephyr).isEmpty());
        assertTrue(SessionBackends.availableBackendFor(spec(LaunchKind.CLI, "echo hi"), onlyXephyr).isEmpty());
        assertFalse(SessionBackends.availableBackendFor(spec(LaunchKind.CLI, "echo hi"),
                onPath("gamescope")).isEmpty());
    }

    @Test
    void xephyrGetsOpenboxWhenInstalledAndGamescopeNeverDoes() {
        // A bare Xephyr has no EWMH, so nothing takes input focus — openbox is what makes key injection land.
        assertEquals(List.of("openbox", "--sm-disable"),
                SessionBackends.windowManagerFor(NestedSession.Backend.XEPHYR, onPath("openbox")));
        // Absent openbox is a soft degrade to WM-less, not a failure.
        assertTrue(SessionBackends.windowManagerFor(NestedSession.Backend.XEPHYR, onPath()).isEmpty());
        // gamescope IS the window manager for its Xwayland; a second one would fight it for the selection.
        assertTrue(SessionBackends.windowManagerFor(NestedSession.Backend.GAMESCOPE, onPath("openbox")).isEmpty());
    }

    @Test
    void onlyGamescopeNeedsTheFocusRelativeWarpCorrection() {
        // gamescope's Xwayland routes injected motion through the focused surface, so a root-absolute target
        // lands offset by the focus window's origin (measured: (2,2), i.e. every click 2px off). Xephyr — like
        // every real X server — honours the root-absolute contract and must not be "corrected".
        assertEquals(PointerWarp.FOCUS_RELATIVE, SessionBackends.pointerWarpFor(NestedSession.Backend.GAMESCOPE));
        assertEquals(PointerWarp.ROOT_ABSOLUTE, SessionBackends.pointerWarpFor(NestedSession.Backend.XEPHYR));
    }

    @Test
    void everyBackendGetsAPrivateBusUnlessExplicitlyOptedOut() {
        // Not a display-backend property: the private bus is what stops a *launcher* escaping the session (its
        // own Flatpak portal, and no host instance for a single-instance check to find), and both backends run
        // launchers. On by default for both; only the explicit bisect opt-out turns it off.
        assertTrue(SessionBackends.usesPrivateBus(NestedSession.Options.gamescope(1280, 720)));
        assertTrue(SessionBackends.usesPrivateBus(NestedSession.Options.xephyr(1280, 720)));
        assertFalse(SessionBackends.usesPrivateBus(
                NestedSession.Options.gamescope(1280, 720).withoutPrivateBus()));
        // A null options is the "nothing stated" case and must not silently drop the protection.
        assertTrue(SessionBackends.usesPrivateBus(null));
    }

    @Test
    void aSessionHoldsAButtonLongerThanOneFrame() {
        // The host default (12 ms) is under one frame at 60 fps, so a game sampling input per frame can observe
        // no press at all — the "tap produced a hover highlight" symptom. Both backends get the longer hold.
        for (NestedSession.Backend backend : NestedSession.Backend.values()) {
            int hold = SessionBackends.inputTimingFor(backend).pressHoldMs();
            assertTrue(hold > 16, backend + " press hold must exceed one 60 fps frame, was " + hold + "ms");
            assertTrue(hold > InputTiming.DEFAULT.pressHoldMs(), backend + " must hold longer than the host default");
        }
        // Only the hold is raised — the motion settle and typing pace stay at the tuned defaults.
        InputTiming session = SessionBackends.inputTimingFor(NestedSession.Backend.GAMESCOPE);
        assertEquals(InputTiming.DEFAULT.motionSettleMs(), session.motionSettleMs());
        assertEquals(InputTiming.DEFAULT.interKeyMs(), session.interKeyMs());
    }

    @Test
    void installHintNamesTheBackend() {
        assertTrue(SessionBackends.installHint(NestedSession.Backend.GAMESCOPE).toLowerCase().contains("gamescope"));
        assertTrue(SessionBackends.installHint(NestedSession.Backend.XEPHYR).toLowerCase().contains("xephyr"));
    }

    private static Predicate<String> onPath(String... installed) {
        Set<String> present = Set.of(installed);
        return present::contains;
    }
}
