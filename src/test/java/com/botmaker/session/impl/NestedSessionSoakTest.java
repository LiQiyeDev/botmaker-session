package com.botmaker.session.impl;

import com.botmaker.session.SessionHealth;

import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Phase 6 soak & chaos coverage for {@link NestedSession}, driving the real supervisor. Two guarantees:
 *
 * <ul>
 *   <li><b>No leak over time</b> — repeated bring-up/teardown returns every resource: after each cycle there
 *       are zero orphan {@code Xephyr} processes and the JVM's open file-descriptor count does not climb (the
 *       {@code :N} X connections — controller + EWMH — are actually closed). Scale the cycle count for a real
 *       soak with {@code -Dbotmaker.soak.iterations=N} (a 24h run is just a large N).</li>
 *   <li><b>Health reflects chaos</b> — a session whose game dies but whose display survives reports
 *       {@link SessionHealth#DEGRADED} (recoverable by relaunch), and reports {@link SessionHealth#DEAD} once
 *       closed. Triggered without any fragile external kill by launching a self-closing client.</li>
 * </ul>
 *
 * <p>Opt-in and self-skipping exactly like {@link NestedSessionLiveTest} ({@code -Dbotmaker.live=true} plus a
 * usable {@code DISPLAY}/{@code Xephyr}/{@code openbox}); CI runs it under {@code Xvfb}.
 */
class NestedSessionSoakTest {

	@Test
	void repeatedBringUpAndTearDownLeaksNoProcessesOrFds() throws Exception {
		assumeLive();
		int iterations = Math.max(1, Integer.getInteger("botmaker.soak.iterations", 4));
		int baselineXephyr = xephyrCount();
		int baselineFds = openFdCount();

		for (int i = 1; i <= iterations; i++) {
			NestedSession session = NestedSession.start(
				NestedSession.Options.xephyr(800, 600).withWindowManager("openbox", "--sm-disable"));
			String display = session.displayName();
			try {
				session.launch(LaunchSpec.parse("cli:xmessage -center soak-" + i));
				assertNotNull(session.attached(), "cycle " + i + ": a window should have mapped on " + display);
				session.pointer().moveAbsolute(400, 300);
				assertNotNull(session.capture(), "cycle " + i + ": capture should yield a frame");
			} finally {
				session.close();
			}
			assertTrue(displayGoneWithin(display, 5_000), "cycle " + i + ": " + display + " should be reaped");
			assertEquals(baselineXephyr, xephyrCount(),
				"cycle " + i + ": a Xephyr process leaked (orphan after close)");
			System.out.printf("[soak] cycle %d/%d ok — Xephyr=%d fds=%d%n",
				i, iterations, xephyrCount(), openFdCount());
		}

		// A few descriptors of slack absorbs JVM/JIT/GC bookkeeping; a real connection leak grows with N.
		int grown = openFdCount() - baselineFds;
		assertTrue(grown <= 16, "file descriptors climbed by " + grown + " over " + iterations
			+ " cycles — a :N X connection is not being closed");
	}

	@Test
	void healthGoesDegradedWhenTheGameDiesButTheDisplayLives() throws Exception {
		assumeLive();
		NestedSession session = NestedSession.start(
			NestedSession.Options.xephyr(800, 600).withWindowManager("openbox", "--sm-disable"));
		try {
			// -timeout makes xmessage map a window, then close itself after ~2s — the game dies, display stays up.
			session.launch(LaunchSpec.parse("cli:xmessage -timeout 2 -center dying"));
			assertNotNull(session.attached(), "the client should map a window before it self-closes");
			assertEquals(SessionHealth.HEALTHY, session.health(), "freshly launched, everything is up");

			assertTrue(reachesHealthWithin(session, SessionHealth.DEGRADED, 8_000),
				"after the game self-exits (display still alive) health should be DEGRADED");
		} finally {
			session.close();
		}
		assertEquals(SessionHealth.DEAD, session.health(), "a closed session is DEAD");
	}

	// --- guards & helpers ---

	private static void assumeLive() {
		assumeTrue(Boolean.getBoolean("botmaker.live"),
			"opt-in live test — run with -Dbotmaker.live=true (CI runs it under Xvfb)");
		String display = System.getenv("DISPLAY");
		assumeTrue(display != null && !display.isBlank(), "needs a DISPLAY");
		assumeTrue(onPath("Xephyr") && onPath("openbox"), "needs Xephyr and openbox on PATH");
	}

	private static boolean reachesHealthWithin(NestedSession session, SessionHealth want, long ms)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < deadline) {
			if (session.health() == want) {
				return true;
			}
			Thread.sleep(150);
		}
		return false;
	}

	private static boolean displayGoneWithin(String name, long ms) throws InterruptedException {
		long deadline = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < deadline) {
			com.sun.jna.Pointer d = com.botmaker.shared.capture.linux.X11.INSTANCE.XOpenDisplay(name);
			if (d == null) {
				return true;
			}
			com.botmaker.shared.capture.linux.X11.INSTANCE.XCloseDisplay(d);
			Thread.sleep(150);
		}
		return false;
	}

	/** Number of live {@code Xephyr} processes, via {@code pgrep}; the orphan-leak signal after each cycle. */
	private static int xephyrCount() throws Exception {
		Process p = new ProcessBuilder("pgrep", "-x", "Xephyr").redirectErrorStream(true).start();
		List<String> lines = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))
			.lines().filter(s -> !s.isBlank()).toList();
		p.waitFor();
		return lines.size();
	}

	/** This JVM's open file-descriptor count (Linux {@code /proc/self/fd}); climbs if X connections leak. */
	private static int openFdCount() throws Exception {
		Path fd = Path.of("/proc/self/fd");
		try (var s = Files.list(fd)) {
			return (int) s.count();
		}
	}

	private static boolean onPath(String exe) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(File.pathSeparator)) {
			if (new File(dir, exe).canExecute()) {
				return true;
			}
		}
		return false;
	}
}
