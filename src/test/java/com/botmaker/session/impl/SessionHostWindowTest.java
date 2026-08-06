package com.botmaker.session.impl;

import com.botmaker.session.display.SessionDisplay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The part of {@link SessionHostWindow} that needs no display server of our own: "there is no host window" must
 * be an answer, not an exception, because the caller's fallback is simply to leave the bring-up visible.
 * Minimizing and restoring a real window — and the question that actually decides whether this feature is safe,
 * whether capture keeps producing frames while the server is iconified — are covered by
 * {@link SessionHostWindowLiveTest}.
 *
 * <p>Driven by a real short-lived child rather than a made-up pid: {@code pid 1} would be wrong here for a reason
 * worth recording — on a systemd box every process is a descendant of pid 1, so the descendant match (which
 * exists because {@code systemd-run --scope} may sit between us and the server) would claim the first window on
 * the user's desktop. A process we spawned has the shape a real {@link SessionDisplay#serverPid()} has.
 */
@DisabledOnOs(OS.WINDOWS)
class SessionHostWindowTest {

    @Test
    void aProcessThatOwnsNoHostWindowIsNullRatherThanAnException() throws Exception {
        Process sleeper = new ProcessBuilder(List.of("sleep", "5")).start();
        try {
            assertNull(SessionHostWindow.find(sleeper.pid(), "definitely-not-a-display-server", 200));
        } finally {
            sleeper.destroyForcibly();
        }
    }

    @Test
    void aNameHintIsOptional() throws Exception {
        // The name is only the fallback match, so a backend that reports nothing usable must still get an answer
        // instead of a NullPointerException out of the string compare.
        Process sleeper = new ProcessBuilder(List.of("sleep", "5")).start();
        try {
            assertNull(SessionHostWindow.find(sleeper.pid(), null, 200));
        } finally {
            sleeper.destroyForcibly();
        }
    }

    /**
     * A display that can't be asked answers {@code -1}, and the boolean built on it answers "there is content".
     *
     * <p>The direction is the safety property, not a detail: the caller's response to "there is content" is to
     * leave the window alone, so an unreadable display costs a visible bring-up. Reading the same failure as
     * {@code 0} would minimize a window on no evidence at all — and {@code 0} is now a value the count really
     * returns, which is exactly why the unknown case had to stop sharing it.
     */
    @Test
    void adisplayThatCannotBeAskedIsUnknownAndCountsAsContent() {
        assertEquals(-1, SessionHostWindow.mappedCountOn(":does-not-exist"),
            "an unopenable display is unknown, not empty");
        assertTrue(SessionHostWindow.anythingMappedOn(":does-not-exist"),
            "and unknown must read as 'leave the window alone'");
    }
}
