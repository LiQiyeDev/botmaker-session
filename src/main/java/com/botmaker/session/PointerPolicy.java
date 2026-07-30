package com.botmaker.session;

import com.botmaker.shared.capture.NativeController;

import java.awt.Point;

/**
 * Whether a gesture has to hand the cursor back — the one question every click path has to answer, in the one
 * place that answers it.
 *
 * <p>On the user's own desktop, borrowing the cursor and putting it back is the whole reason synthesized input is
 * tolerable: {@link NativeController#clickRestoringCursor} exists for exactly that. Inside a private session
 * ({@link Capability#BACKGROUND_CLICK}) the pointer is the bot's alone, there is nothing to be polite to, and the
 * warp away is <b>actively harmful</b> — a UI that samples the pointer position per frame rather than reading the
 * event's coordinate sees it somewhere else by the next frame and renders a <em>hover highlight where a click
 * should have registered</em>. That is not a hypothesis: it is what a live bot did on an adopted gamescope
 * session, finding its template, clicking it, and getting nothing but the hover.
 *
 * <p>This type exists because that rule was written down in {@link NativeController}'s javadoc and then
 * implemented in only one of the two consumers — Studio's pilot honoured it, the SDK's {@code Mouse} restored the
 * cursor unconditionally, and every template click in a session threw its own button press away. A policy both
 * modules need is exactly what shared is for, so neither can drift from it again.
 */
public final class PointerPolicy {

	private PointerPolicy() {}

	/**
	 * Whether the pointer belongs to {@code session} rather than to the user. A {@code null} session means
	 * "we're on the user's desktop" — the default everywhere, so callers don't have to branch before asking.
	 */
	public static boolean ownsPointer(DesktopSession session) {
		return session != null && session.has(Capability.BACKGROUND_CLICK);
	}

	/**
	 * Click at an absolute coordinate under the right policy: the pointer stays on the target when the session
	 * owns it, and is handed back when it is the user's.
	 *
	 * <p>{@code controller} is passed in rather than taken from {@code session} on purpose. Studio's pilot drives
	 * a deliberately escalated {@code :0} controller when no session is active, and re-deriving one here would
	 * silently swap it.
	 */
	public static void click(NativeController controller, DesktopSession session, int x, int y, int button) {
		if (controller == null) {
			return;
		}
		if (ownsPointer(session)) {
			controller.click(x, y, button);
		} else {
			controller.clickRestoringCursor(x, y, button);
		}
	}

	/**
	 * Put the pointer back at {@code origin} — the end-of-gesture half of the same policy, for gestures that hold
	 * a button across several steps (a drag, a double-click) and so can only restore once at the end.
	 *
	 * <p>No-op when the session owns the pointer (nothing to hand back, and the warp would cost the last click
	 * exactly as above) or when {@code origin} is {@code null}, which means the position was never readable — an
	 * invented coordinate would park the cursor somewhere the user never left it.
	 */
	public static void restoreTo(NativeController controller, DesktopSession session, Point origin) {
		if (controller == null || origin == null || ownsPointer(session)) {
			return;
		}
		controller.mouseMove(origin.x, origin.y);
	}
}
