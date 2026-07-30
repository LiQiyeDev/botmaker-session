package com.botmaker.session.impl;

import com.botmaker.session.display.GamescopeDisplay;
import com.botmaker.session.display.NestedDisplay;
import com.botmaker.session.display.SessionBackends;

import com.botmaker.shared.launch.LaunchCommands;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the parts of {@link NestedSession} that don't need a live X server: the launch-kind → argv
 * mapping (which kinds can be handed a private {@code DISPLAY}) and the immutable {@link NestedSession.Options}
 * builder. The end-to-end supervisor behaviour (Xephyr allocation, XTest isolation, tree reaping) is verified
 * by the guarded live suite — {@link NestedSessionLiveTest} and {@link NestedSessionSoakTest} — which runs on
 * a real box and under {@code Xvfb} in CI ({@code .github/workflows/session-live.yml}), not by this pure unit.
 */
class NestedSessionTest {

	@Test
	void exeAndCliLaunchAsOwnChildrenWithSplitArgs() {
		// A single-rung ladder: exe:/cli: already are the child process handed DISPLAY=:N.
		assertEquals(List.of(List.of("/opt/game/run.sh")),
			LaunchCommands.childLadder(LaunchSpec.parse("exe:/opt/game/run.sh")));
		assertEquals(List.of(List.of("xterm", "-e", "sleep", "300")),
			LaunchCommands.childLadder(LaunchSpec.parse("cli:xterm -e sleep 300")));
	}

	@Test
	void storeLaunchersRunTheirChildLaunchableCliLadder() {
		// Heroic: run the CLI form as our child (inheriting DISPLAY=:N) — the heroic:// URL is Heroic's own
		// argv, not a handoff to the desktop's URL opener, which a launcher already on :0 would swallow.
		assertEquals(LaunchCommands.heroic("AbC123"), LaunchCommands.childLadder(LaunchSpec.parse("heroic:AbC123")));
		assertEquals(LaunchCommands.steam("570"), LaunchCommands.childLadder(LaunchSpec.parse("steam:570")));
	}

	@Test
	void storeKindsGetTheLongWindowBudgetAndOwnChildrenTheShortOne() {
		NestedSession.Options options = NestedSession.Options.gamescope(1280, 720);
		// A launcher kind: we're waiting on the game Heroic/Steam starts, which can take minutes on a cold prefix.
		assertEquals(NestedSession.LAUNCHER_WINDOW_TIMEOUT_MS,
			NestedSession.windowTimeoutFor(LaunchSpec.parse("heroic:AbC123"), options));
		assertEquals(NestedSession.LAUNCHER_WINDOW_TIMEOUT_MS,
			NestedSession.windowTimeoutFor(LaunchSpec.parse("steam:570"), options));
		// exe:/cli: ARE the process we spawned — no window in 20s means no window.
		assertEquals(NestedSession.WINDOW_TIMEOUT_MS,
			NestedSession.windowTimeoutFor(LaunchSpec.parse("exe:/opt/game/run.sh"), options));
		assertEquals(NestedSession.WINDOW_TIMEOUT_MS, NestedSession.windowTimeoutFor(null, options));
	}

	@Test
	void anExplicitWindowTimeoutOverridesThePerKindDefault() {
		NestedSession.Options tuned = NestedSession.Options.gamescope(1280, 720).withWindowTimeout(300_000);
		assertEquals(300_000, tuned.windowTimeoutMs());
		assertEquals(300_000, NestedSession.windowTimeoutFor(LaunchSpec.parse("exe:/opt/game/run.sh"), tuned));
		// Zero/negative means "no override" rather than "wait for nothing".
		assertEquals(0, tuned.withWindowTimeout(-1).windowTimeoutMs());
		assertEquals(NestedSession.WINDOW_TIMEOUT_MS,
			NestedSession.windowTimeoutFor(LaunchSpec.parse("exe:/opt/game/run.sh"), tuned.withWindowTimeout(0)));
	}

	@Test
	void kindsWithNoChildLaunchFormHaveNoNestedCommand() {
		// Epic is URL-only (no supported CLI); an emulator app runs over ADB, not on the host desktop.
		assertTrue(LaunchCommands.childLadder(LaunchSpec.parse("epic:Fortnite")).isEmpty());
		assertTrue(LaunchCommands.childLadder(LaunchSpec.parse("emu-app:com.foo@Main")).isEmpty());
	}

	@Test
	void theWindowManagerIsResolvedFromTheBackendUnlessStated() {
		// Stated wins, whatever the backend policy would have been.
		NestedSession.Options stated = NestedSession.Options.xephyr(800, 600).withWindowManager("i3");
		assertEquals(List.of("i3"), NestedSession.windowManagerCommandFor(stated));
		// Stated "none" is an opt-out of the Xephyr default, not an absence of an opinion.
		assertTrue(NestedSession.windowManagerCommandFor(
			NestedSession.Options.xephyr(800, 600).withoutWindowManager()).isEmpty());
		// Unstated defers to the backend policy — which for gamescope is always none.
		assertTrue(NestedSession.windowManagerCommandFor(NestedSession.Options.gamescope(1280, 720)).isEmpty());
		// …and it refuses one even when asked: gamescope already manages its Xwayland.
		assertTrue(NestedSession.windowManagerCommandFor(
			NestedSession.Options.gamescope(1280, 720).withWindowManager("openbox")).isEmpty());
		// Unstated on Xephyr is whatever SessionBackends says for this machine (openbox when installed).
		assertEquals(SessionBackends.windowManagerFor(NestedSession.Backend.XEPHYR),
			NestedSession.windowManagerCommandFor(NestedSession.Options.xephyr(800, 600)));
	}

	@Test
	void optionsAreImmutableAndCarryTheirConfig() {
		NestedSession.Options base = NestedSession.Options.xephyr(1280, 720);
		assertEquals(1280, base.width());
		assertEquals(720, base.height());
		assertTrue(base.windowManagerCommand().isEmpty());
		assertTrue(base.extraEnv().isEmpty());

		NestedSession.Options withWm = base.withWindowManager("openbox", "--sm-disable");
		assertEquals(List.of("openbox", "--sm-disable"), withWm.windowManagerCommand());
		// The builder returns a new value; the base is untouched.
		assertTrue(base.windowManagerCommand().isEmpty());

		NestedSession.Options withEnv = base.withExtraEnv(Map.of("WINEPREFIX", "/tmp/pfx"));
		assertEquals("/tmp/pfx", withEnv.extraEnv().get("WINEPREFIX"));
		// Defensive copy: mutating the returned view is refused.
		assertThrows(UnsupportedOperationException.class,
			() -> withEnv.extraEnv().put("X", "y"));
	}

	@Test
	void backendNamesTheBinaryItSpawns() {
		// The PATH probe in Studio's pilot UI keys off these — they must equal what NestedDisplay/GamescopeDisplay run.
		assertEquals("Xephyr", NestedSession.Backend.XEPHYR.binaryName());
		assertEquals("gamescope", NestedSession.Backend.GAMESCOPE.binaryName());
	}

	@Test
	void backendPicksTheDisplayServer() {
		NestedSession.Options xephyr = NestedSession.Options.xephyr(1280, 720);
		assertEquals(NestedSession.Backend.XEPHYR, xephyr.backend());

		NestedSession.Options gs = NestedSession.Options.gamescope(1920, 1080);
		assertEquals(NestedSession.Backend.GAMESCOPE, gs.backend());
		// Default gamescope argv is standalone (no "--" child) and carries the requested size twice: the output
		// window (-W/-H) and the internal resolution apps see (-w/-h), so capture is 1:1 with the templates.
		// --force-windows-fullscreen makes the game fill the display the click coordinates are computed against.
		assertEquals(List.of("gamescope", "-W", "1920", "-H", "1080", "-w", "1920", "-h", "1080",
				"--force-windows-fullscreen"),
			gs.displayServerCommand());
		assertTrue(gs.displayServerCommand().stream().noneMatch("--"::equals));

		// An explicit override wins over the default argv.
		NestedSession.Options custom = gs.withGamescopeCommand("gamescope", "--backend", "sdl", "-W", "800", "-H", "600");
		assertEquals(List.of("gamescope", "--backend", "sdl", "-W", "800", "-H", "600"),
			custom.displayServerCommand());
	}

	@Test
	void gamescopeDisplayNumberParsedFromStderrBanner() {
		// The forms gamescope prints across versions (some prefix wlserver:, casing varies).
		assertEquals(":1", GamescopeDisplay.parseDisplayNumber(
			"wlserver: [xwayland/server.c:1146] Starting Xwayland on :1"));
		assertEquals(":2", GamescopeDisplay.parseDisplayNumber(
			"gamescope: starting\nStarting Xwayland on :2, DISPLAY=:2\nmore log"));
		// Not announced yet / unrelated output → no number.
		assertNull(GamescopeDisplay.parseDisplayNumber(""));
		assertNull(GamescopeDisplay.parseDisplayNumber(null));
		assertNull(GamescopeDisplay.parseDisplayNumber("gamescope: creating nested compositor\n"));
	}
}
