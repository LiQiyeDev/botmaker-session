package com.botmaker.session;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one decision {@link PointerPolicy} exists to make, asserted from the call list: a click in a session leaves
 * the pointer on the target, and a click on the user's desktop hands the cursor back. The trailing
 * {@code move 7,9} is the whole difference — and its presence in a session is what left a game rendering a hover
 * highlight where a click should have registered.
 */
class PointerPolicyTest {

	private static final Point ORIGIN = new Point(7, 9);

	@Test
	void aSessionThatOwnsThePointerKeepsItOnTheTarget() {
		Recording controller = new Recording(ORIGIN);

		PointerPolicy.click(controller, session(Capability.BACKGROUND_CLICK), 100, 120, 1);

		assertEquals(List.of("move 100,120", "button 1 true", "button 1 false"), controller.calls);
	}

	@Test
	void aHostSessionHandsTheCursorBack() {
		Recording controller = new Recording(ORIGIN);

		// A session object without BACKGROUND_CLICK is the host desktop: the courtesy warp is the point there.
		PointerPolicy.click(controller, session(Capability.ABSOLUTE_POINTER), 100, 120, 1);

		assertEquals(List.of("move 100,120", "button 1 true", "button 1 false", "move 7,9"), controller.calls);
	}

	@Test
	void noSessionAtAllIsTheUsersDesktop() {
		// The default everywhere in the SDK, so "null" must not need a branch at the call site.
		Recording controller = new Recording(ORIGIN);

		PointerPolicy.click(controller, null, 100, 120, 1);

		assertEquals(List.of("move 100,120", "button 1 true", "button 1 false", "move 7,9"), controller.calls);
		assertFalse(PointerPolicy.ownsPointer(null));
	}

	@Test
	void restoringIsSkippedWhenThePointerIsOursAndWhenTheOriginIsUnknown() {
		Recording inSession = new Recording(ORIGIN);
		PointerPolicy.restoreTo(inSession, session(Capability.BACKGROUND_CLICK), ORIGIN);
		assertTrue(inSession.calls.isEmpty(), "a session's own pointer has nowhere to be handed back to");

		Recording unknownOrigin = new Recording(null);
		PointerPolicy.restoreTo(unknownOrigin, null, null);
		assertTrue(unknownOrigin.calls.isEmpty(), "a null origin is 'don't know', not a coordinate to warp to");

		Recording onHost = new Recording(ORIGIN);
		PointerPolicy.restoreTo(onHost, null, ORIGIN);
		assertEquals(List.of("move 7,9"), onHost.calls);
	}

	@Test
	void ownsPointerIsExactlyTheBackgroundClickCapability() {
		assertTrue(PointerPolicy.ownsPointer(session(Capability.BACKGROUND_CLICK)));
		assertFalse(PointerPolicy.ownsPointer(session(Capability.SCREEN_CAPTURE)));
	}

	private static DesktopSession session(Capability... capabilities) {
		Set<Capability> set = capabilities.length == 0 ? EnumSet.noneOf(Capability.class)
			: EnumSet.copyOf(List.of(capabilities));
		return new StubSession(set);
	}

	/** Records the calls the policy makes; everything it doesn't touch is a stub. */
	private static final class Recording implements NativeController {
		final List<String> calls = new ArrayList<>();
		private final Point cursor;

		Recording(Point cursor) {
			this.cursor = cursor;
		}

		@Override public GenericWindow getForegroundWindow() { return null; }
		@Override public List<GenericWindow> getChildWindows(GenericWindow parent) { return List.of(); }
		@Override public List<GenericWindow> getAllWindows() { return List.of(); }
		@Override public BufferedImage captureWindow(GenericWindow window) { return null; }
		@Override public void postLeftClick(GenericWindow window, int x, int y) { }
		@Override public void focusWindow(GenericWindow window) { }
		@Override public void moveWindow(GenericWindow window, int x, int y) { }
		@Override public void resizeWindow(GenericWindow window, int width, int height) { }
		@Override public void keyDown(int nativeKeyCode) { }
		@Override public void keyUp(int nativeKeyCode) { }
		@Override public void typeText(String text) { }
		@Override public void mouseMove(int xAbs, int yAbs) { calls.add("move " + xAbs + "," + yAbs); }
		@Override public void mouseButton(int button, boolean press) { calls.add("button " + button + " " + press); }
		@Override public void scroll(int amount) { }
		@Override public Point cursorPosition() { return cursor; }
		@Override public int pressHoldMs() { return 0; }   // no real waiting in a unit test
	}

	/** A session that is nothing but its capability set — the only thing the policy reads. */
	private record StubSession(Set<Capability> capabilities) implements DesktopSession {
		@Override public Rectangle screen() { return new Rectangle(); }
		@Override public SessionPointer pointer() { return null; }
		@Override public SessionKeyboard keyboard() { return null; }
		@Override public void attach(GenericWindow window) { }
		@Override public GenericWindow attached() { return null; }
		@Override public void launch(LaunchSpec spec) { }
		@Override public BufferedImage capture() { return null; }
		@Override public NativeController controller() { return null; }
		@Override public void close() { }
	}
}
