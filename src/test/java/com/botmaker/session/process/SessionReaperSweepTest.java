package com.botmaker.session.process;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of <em>our own</em> session slices the orphan sweep may stop. "The owner JVM is alive" used to be enough
 * to spare a slice, which spared the shells of sessions this JVM had already let go of — a private
 * {@code dbus-daemon} was found still running in one whose display server had been gone for hours, and the launch
 * probes counted it as a launcher that was open. The sweep now asks the live registry instead, and the trap that
 * introduces is the one asserted hardest below.
 */
class SessionReaperSweepTest {

	@Test
	void aParentSliceOfALiveSessionIsNotAbandoned() {
		// systemd derives a parent slice from every dash, so a live s123-1 sits inside botmaker-sess-s123.slice —
		// a name no session object is ever keyed by. Stopping it would take the live session down with it.
		assertTrue(SessionReaper.isLive("s123", Set.of("s123-1")),
			"the parent slice of a live session must never be swept");
		assertTrue(SessionReaper.isLive("s123-1", Set.of("s123-1")));
	}

	@Test
	void aSessionWeNoLongerHoldIsAbandoned() {
		assertFalse(SessionReaper.isLive("s123-1", Set.of("s123-2")),
			"a sibling being live says nothing about this one");
		assertFalse(SessionReaper.isLive("s123", Set.of()));
		assertFalse(SessionReaper.isLive("s123-1", List.of()));
	}

	@Test
	void aPrefixThatIsNotASessionIsNotConfusedForOne() {
		// s12 is not the parent of s123-1 — only a whole dash-separated segment counts.
		assertFalse(SessionReaper.isLive("s12", Set.of("s123-1")));
	}

	@Test
	void theSessionIdIsReadBackOutOfTheSliceName() {
		assertEquals("s123-4", SessionReaper.sessionIdOf("botmaker-sess-s123-4.slice"));
		assertEquals("s123", SessionReaper.sessionIdOf("botmaker-sess-s123.slice"));
		assertEquals("s123-4", SessionReaper.sessionIdOf("botmaker-sess-s123-4"));
	}
}
