package com.botmaker.session.impl;

import com.botmaker.session.Capability;

import com.botmaker.shared.capture.linux.LinuxController;
import com.botmaker.shared.capture.linux.X11;
import com.botmaker.shared.launch.LaunchSpec;
import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The live end-to-end proof of the whole plan on a real box: our {@link NestedSession} supervisor brings up a
 * private Xephyr {@code :N}, launches a client into it, drives that display's pointer, and the user's real
 * {@code :0} cursor never moves — background input, flawlessly. Also asserts the two lifecycle guarantees:
 * concurrent sessions own distinct displays (no {@code -displayfd} collision), and {@link NestedSession#close()}
 * reaps the whole tree (no orphan Xephyr).
 *
 * <p>Opt-in and self-skipping so it never surprises a plain {@code mvn test}: it runs only with
 * {@code -Dbotmaker.live=true} <em>and</em> a usable {@code DISPLAY} + {@code Xephyr}/{@code openbox} on
 * {@code PATH}. CI runs it under {@code Xvfb} (see {@code .github/workflows/session-live.yml}); everywhere else
 * it is skipped, not failed. Replaces the "manual live run recorded in the ROADMAP" note on {@link
 * NestedSessionTest} — the run is now reproducible.
 */
class NestedSessionLiveTest {

    @Test
    void aNestedSessionDrivesItsPrivatePointerWithoutTouchingTheRealCursor() throws Exception {
        assumeLive();
        String hostDisplay = System.getenv("DISPLAY");
        LinuxController hostRead = LinuxController.forDisplay(hostDisplay);
        try {
            assertNotNull(hostRead.cursorPosition(), "should be able to read the real cursor on " + hostDisplay);

            // No explicit window manager: a Xephyr session runs the backend default (openbox when installed),
            // which is what makes the display carry EWMH — and therefore focus — at all.
            NestedSession session = NestedSession.start(NestedSession.Options.xephyr(1280, 720));
            String nested = session.displayName();
            try {
                assertNotEquals(hostDisplay, nested, "the nested display must not be the real one");
                assertTrue(nested.matches(":\\d+"), "nested display should look like :N, was " + nested);
                assertTrue(session.has(Capability.BACKGROUND_CLICK),
                    "a nested session must honestly advertise BACKGROUND_CLICK");

                // A client with a private DISPLAY, launched as our own child, maps a window on :N.
                session.launch(LaunchSpec.parse("cli:xmessage -center BotMakerNestedLiveTest"));
                assertNotNull(session.attached(), "a window should have appeared on " + nested);

                // Drive :N's private pointer to a distinctive target and confirm the injection landed there.
                Point target = new Point(640, 360);
                session.pointer().moveAbsolute(target.x, target.y);
                Point onNested = session.pointer().position();
                assertNotNull(onNested, "should be able to read the :N pointer");
                assertEquals(target, onNested, "the :N pointer should be exactly where our injection put it");
                session.pointer().click(1);

                // Capture flows through the :N-bound controller.
                assertNotNull(session.capture(), "capturing the attached :N window should yield a frame");

                // The isolation assertion: our injection reached :N (above) but did NOT leak to the real display —
                // the real cursor is not at the :N target. Deterministic under Xvfb (nothing else moves the mouse)
                // and robust on a live, in-use desktop where an ambient hand can nudge the real cursor mid-run;
                // a leak would instead teleport the real cursor onto the :N target. (A stricter before==after run
                // on an idle box showed the real cursor perfectly still — see the ROADMAP live-run note.)
                Point after = hostRead.cursorPosition();
                assertNotNull(after, "should still be able to read the real cursor");
                assertNotEquals(target, after,
                    "driving the :N pointer must not move the real " + hostDisplay + " cursor (input leaked to :0)");
            } finally {
                session.close();
            }
            // close() reaps the whole tree — the private display is gone.
            assertTrue(displayGoneWithin(nested, 5_000), nested + " should be torn down after close()");
        } finally {
            hostRead.close();
        }
    }

    @Test
    void concurrentNestedSessionsOwnDistinctDisplays() throws Exception {
        assumeLive();
        List<NestedSession> sessions = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                sessions.add(NestedSession.start(NestedSession.Options.xephyr(640, 480)));
            }
            Set<String> displays = new HashSet<>();
            for (NestedSession s : sessions) {
                displays.add(s.displayName());
            }
            assertEquals(3, displays.size(),
                "each concurrent session must own a distinct display (no -displayfd collision): " + displays);
        } finally {
            for (NestedSession s : sessions) {
                try { s.close(); } catch (Exception ignored) { /* best-effort teardown */ }
            }
        }
    }

    // --- guards & helpers ---

    /** Opt-in (so a plain {@code mvn test} never spins up Xephyr) and only where the live stack is actually present. */
    private static void assumeLive() {
        assumeTrue(Boolean.getBoolean("botmaker.live"),
            "opt-in live test — run with -Dbotmaker.live=true (CI runs it under Xvfb)");
        String display = System.getenv("DISPLAY");
        assumeTrue(display != null && !display.isBlank(), "needs a DISPLAY");
        assumeTrue(onPath("Xephyr") && onPath("openbox"), "needs Xephyr and openbox on PATH");
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

    /** Poll until {@code name} can no longer be opened (Xephyr has exited), or the deadline passes. */
    private static boolean displayGoneWithin(String name, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            Pointer d = X11.INSTANCE.XOpenDisplay(name);
            if (d == null) {
                return true;
            }
            X11.INSTANCE.XCloseDisplay(d);
            Thread.sleep(150);
        }
        return false;
    }
}
