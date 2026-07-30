package com.botmaker.session.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of {@link AdoptedSession} that need no X server: the hand-off shape (both halves of which must agree
 * on a property name, which is why they live in the same class) and the "nothing to adopt" answers. Joining a real
 * display is covered by {@link AdoptedSessionLiveTest}.
 */
class AdoptedSessionTest {

	@Test
	void nothingToAdoptIsNullRatherThanAnException() {
		// The caller's fallback is its own launch, so a missing offer has to be cheap and quiet — and must not even
		// touch X11 (this runs on a box with no display at all).
		assertNull(AdoptedSession.adopt(null, null, NestedSession.Backend.GAMESCOPE));
		assertNull(AdoptedSession.adopt("  ", "123", NestedSession.Backend.GAMESCOPE));
	}

	@Test
	void aNullSessionOffersNothing() {
		// So a spawn site can compose the arguments unconditionally instead of branching around them.
		assertEquals(List.of(), AdoptedSession.handoffArguments(null));
	}

	@Test
	void theHandoffPropertiesAreTheOnesTheReaderReads() {
		// Named constants rather than literals at either end: a rename that touched only the writer would silently
		// stop every bot adopting anything, with no error anywhere.
		assertEquals("botmaker.session.display", AdoptedSession.DISPLAY_PROPERTY);
		assertEquals("botmaker.session.window", AdoptedSession.WINDOW_PROPERTY);
		assertTrue(AdoptedSession.BACKEND_PROPERTY.startsWith("botmaker.session."));
	}

	@Test
	void aWindowIdParsesFromDecimalOrHexAndNeverThrows() {
		assertEquals(1234L, AdoptedSession.parseWindowId("1234"));
		assertEquals(0x1a00003L, AdoptedSession.parseWindowId("0x1a00003"));
		assertEquals(0x1a00003L, AdoptedSession.parseWindowId("0X1A00003"));
		// 0 is the value no window has, so it doubles as "not a window id" — the attach then falls back to the
		// newest top-level, which is what an absent hand-off should do.
		assertEquals(0L, AdoptedSession.parseWindowId(null));
		assertEquals(0L, AdoptedSession.parseWindowId(""));
		assertEquals(0L, AdoptedSession.parseWindowId("not-a-window"));
	}
}
