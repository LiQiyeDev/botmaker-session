package com.botmaker.session.impl;

import com.botmaker.session.Capability;
import com.botmaker.session.SessionHealth;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The live proof of the hand-off: a second consumer joins a session it did not start, drives the same window, and
 * lets go of it without taking the session down.
 *
 * <p>That last assertion is the one that matters. An adopted session owns nothing — the display server, the window
 * manager, the private bus and the game all belong to whoever started them — so a {@code close()} on the adopting
 * side must not reap any of it. The failure it guards against is a bot finishing its run and taking the display
 * Studio is still showing with it.
 *
 * <p>Opt-in and self-skipping, exactly like {@link NestedSessionLiveTest}: needs {@code -Dbotmaker.live=true}, a
 * usable {@code DISPLAY}, and {@code Xephyr}/{@code openbox}/{@code xterm} on {@code PATH}.
 */
class AdoptedSessionLiveTest {

    @Test
    void aSecondConsumerJoinsTheSameDisplayAndLetsGoWithoutReapingIt() throws Exception {
        assumeLive();
        NestedSession owner = NestedSession.start(NestedSession.Options.xephyr(800, 600));
        try {
            owner.launch(LaunchSpec.parse("cli:xterm -e sleep 300"));
            GenericWindow ownerWindow = owner.attached();
            assertNotNull(ownerWindow, "the owner must have something up before anyone can adopt it");

            List<String> handoff = AdoptedSession.handoffArguments(owner);
            assertTrue(handoff.contains("-D" + AdoptedSession.DISPLAY_PROPERTY + "=" + owner.displayName()),
                "the hand-off must name the display: " + handoff);

            AdoptedSession adopted = AdoptedSession.adopt(owner.displayName(),
                Long.toString(owner.attachedWindowId()), owner.backend());
            assertNotNull(adopted, "a live display offered to us must be adoptable");
            try {
                assertEquals(owner.displayName(), adopted.displayName());
                assertEquals(new java.awt.Rectangle(0, 0, 800, 600), adopted.screen(),
                    "the size is read off the display, not remembered from a launch we didn't make");
                assertNotNull(adopted.attached(), "it must attach to the window the owner named");
                assertEquals(ownerWindow.getTitle(), adopted.attached().getTitle());
                assertNotNull(adopted.capture(), "an adopted session must be able to read the window it joined");
                assertEquals(SessionHealth.HEALTHY, adopted.health());
                assertFalse(adopted.has(Capability.WINDOW_LAUNCH), "launching stays the owner's job");
                assertTrue(adopted.has(Capability.BACKGROUND_CLICK), "a private display is private whoever made it");
            } finally {
                adopted.close();
            }

            // The point of the whole class: letting go took nothing with it.
            assertEquals(SessionHealth.HEALTHY, owner.health(), "closing an adopted session must not reap the owner's");
            assertNotNull(owner.capture(), "the owner must still be able to read its own window");
        } finally {
            owner.close();
        }
    }

    private static void assumeLive() {
        assumeTrue(Boolean.getBoolean("botmaker.live"),
            "opt-in live test — run with -Dbotmaker.live=true (CI runs it under Xvfb)");
        String display = System.getenv("DISPLAY");
        assumeTrue(display != null && !display.isBlank(), "needs a DISPLAY");
        assumeTrue(onPath("Xephyr") && onPath("openbox") && onPath("xterm"),
            "needs Xephyr, openbox and xterm on PATH");
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
