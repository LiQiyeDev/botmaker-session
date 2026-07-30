package com.botmaker.session.impl;

import com.botmaker.session.Capability;
import com.botmaker.session.DesktopSession;
import com.botmaker.session.SessionHealth;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization test for the Phase-1 session seam: {@link HostSession} must be a <em>pure pass-through</em>
 * to the wrapped {@link NativeController}, changing none of today's behaviour. Every assertion here pins a
 * property that a future refactor (routing the SDK/pilot through a {@link DesktopSession}) must not regress —
 * in particular that the host session keeps sending game input down the same targeted controller calls it does
 * today, and does not pretend it can click games in the background.
 */
class HostSessionTest {

	/** A host session advertises exactly what a shared desktop can do — and, crucially, what it cannot. */
	@Test
	void capabilitiesAreHonestAboutASharedDesktop() {
		HostSession session = new HostSession(new RecordingController());

		assertTrue(session.has(Capability.ABSOLUTE_POINTER));
		assertTrue(session.has(Capability.RELATIVE_POINTER));
		assertTrue(session.has(Capability.SCREEN_CAPTURE));
		assertTrue(session.has(Capability.WINDOW_ATTACH));
		assertTrue(session.has(Capability.WINDOW_LAUNCH));

		// The whole point of the enum: a bot that needs these refuses to run on :0 rather than no-op'ing.
		assertFalse(session.has(Capability.BACKGROUND_CLICK));
		assertFalse(session.has(Capability.ISOLATED_FOCUS));
		assertFalse(session.has(Capability.MULTI_SESSION));

		assertEquals(SessionHealth.HEALTHY, session.health());
	}

	/** With a target attached, keyboard input goes down the <em>targeted</em> controller calls — today's game path. */
	@Test
	void attachedKeyboardRoutesToTheTargetedWindowCalls() {
		RecordingController rec = new RecordingController();
		HostSession session = new HostSession(rec);
		GenericWindow game = new GenericWindow(42L, "Game", new Rectangle(0, 0, 800, 600));

		session.attach(game);
		assertSame(game, session.attached());

		session.keyboard().keyDown(65);
		session.keyboard().keyUp(65);
		session.keyboard().type("hi");

		assertEquals(List.of(
			"keyDown(win=Game,65)",
			"keyUp(win=Game,65)",
			"typeText(win=Game,hi)"), rec.calls);
	}

	/** With nothing attached, keyboard input falls back to the window-less (focused-window) controller calls. */
	@Test
	void unattachedKeyboardUsesTheGlobalCalls() {
		RecordingController rec = new RecordingController();
		HostSession session = new HostSession(rec);

		session.keyboard().keyDown(65);
		session.keyboard().type("hi");

		assertEquals(List.of("keyDown(65)", "typeText(hi)"), rec.calls);
	}

	/** Absolute motion, buttons and scroll are verbatim controller calls; a click is press-then-release. */
	@Test
	void pointerDelegatesRawDeviceMotion() {
		RecordingController rec = new RecordingController();
		HostSession session = new HostSession(rec);

		session.pointer().moveAbsolute(100, 200);
		session.pointer().click(1);
		session.pointer().scroll(-3);

		assertEquals(List.of(
			"mouseMove(100,200)",
			"mouseButton(1,true)",
			"mouseButton(1,false)",
			"scroll(-3)"), rec.calls);
	}

	/** A relative move anchors on the read-back position, then warps to position + delta (the day-one impl). */
	@Test
	void relativeMoveAnchorsOnReadBackPosition() {
		RecordingController rec = new RecordingController();
		rec.cursor = new Point(300, 300);
		HostSession session = new HostSession(rec);

		session.pointer().moveRelative(10, -5);

		assertEquals(List.of("mouseMove(310,295)"), rec.calls);
		assertEquals(new Point(300, 300), session.pointer().position());
	}

	/** When the pointer position can't be read, a relative move is skipped — never warped to the bare delta. */
	@Test
	void relativeMoveIsSkippedWhenPositionUnreadable() {
		RecordingController rec = new RecordingController();
		rec.cursor = null;
		HostSession session = new HostSession(rec);

		session.pointer().moveRelative(10, -5);

		assertTrue(rec.calls.isEmpty());
	}

	/** Capture reads the attached window; with nothing attached there is nothing to capture. */
	@Test
	void captureTargetsTheAttachedWindow() {
		RecordingController rec = new RecordingController();
		HostSession session = new HostSession(rec);
		assertNull(session.capture());

		GenericWindow game = new GenericWindow(42L, "Game", new Rectangle(0, 0, 4, 4));
		session.attach(game);
		assertSame(rec.frame, session.capture());
		assertEquals("captureWindow(Game)", rec.calls.get(rec.calls.size() - 1));
	}

	/** Closing a host session must NOT close the shared controller (the class contract). */
	@Test
	void closeDoesNotCloseTheSharedController() {
		RecordingController rec = new RecordingController();
		HostSession session = new HostSession(rec);

		session.close();

		assertTrue(session.isClosed());
		assertFalse(rec.closed);
		assertSame(rec, session.controller());
	}

	/** A minimal {@link NativeController} that records the calls the session makes, asserting the delegation. */
	private static final class RecordingController implements NativeController {
		final List<String> calls = new ArrayList<>();
		final BufferedImage frame = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		Point cursor = new Point(0, 0);
		boolean closed;

		@Override public GenericWindow getForegroundWindow() { return null; }
		@Override public List<GenericWindow> getChildWindows(GenericWindow parent) { return List.of(); }
		@Override public List<GenericWindow> getAllWindows() { return List.of(); }

		@Override public BufferedImage captureWindow(GenericWindow window) {
			calls.add("captureWindow(" + window.getTitle() + ")");
			return frame;
		}

		@Override public void postLeftClick(GenericWindow window, int relativeX, int relativeY) {
			calls.add("postLeftClick(" + window.getTitle() + "," + relativeX + "," + relativeY + ")");
		}

		@Override public void focusWindow(GenericWindow window) { calls.add("focusWindow"); }
		@Override public void moveWindow(GenericWindow window, int x, int y) { calls.add("moveWindow"); }
		@Override public void resizeWindow(GenericWindow window, int width, int height) { calls.add("resizeWindow"); }

		@Override public void keyDown(int nativeKeyCode) { calls.add("keyDown(" + nativeKeyCode + ")"); }
		@Override public void keyUp(int nativeKeyCode) { calls.add("keyUp(" + nativeKeyCode + ")"); }
		@Override public void typeText(String text) { calls.add("typeText(" + text + ")"); }

		@Override public void keyDown(GenericWindow window, int nativeKeyCode) {
			calls.add("keyDown(win=" + window.getTitle() + "," + nativeKeyCode + ")");
		}
		@Override public void keyUp(GenericWindow window, int nativeKeyCode) {
			calls.add("keyUp(win=" + window.getTitle() + "," + nativeKeyCode + ")");
		}
		@Override public void typeText(GenericWindow window, String text) {
			calls.add("typeText(win=" + window.getTitle() + "," + text + ")");
		}

		@Override public void mouseMove(int xAbs, int yAbs) { calls.add("mouseMove(" + xAbs + "," + yAbs + ")"); }
		@Override public void mouseButton(int button, boolean press) { calls.add("mouseButton(" + button + "," + press + ")"); }
		@Override public void scroll(int amount) { calls.add("scroll(" + amount + ")"); }

		@Override public Point cursorPosition() { return cursor; }
	}
}
