package com.botmaker.session.impl;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.linux.X11Utils;
import com.sun.jna.Pointer;

/**
 * Which window a session drives, and the one rule for keeping that answer true: <b>re-resolve when the window we
 * attached to has gone</b>.
 *
 * <p><b>Why this can't be a plain field read.</b> A launcher chain does not map the game's window first. A live
 * Heroic run mapped a {@code ProtonFixes} setup dialog, which the attach (correctly, at the time) took; the dialog
 * then closed, the game's own window appeared beside it, and the session was left holding a destroyed window —
 * {@code capture()} returned {@code null} for the rest of the run and every keystroke went to a window that no
 * longer existed, while the game sat there perfectly capturable. The session reported {@code HEALTHY} throughout,
 * because it <em>was</em>: only the attachment had rotted.
 *
 * <p>The recovery is one cheap round trip ({@code XGetWindowAttributes} on the current window) and, when it fails,
 * the "most recently mapped top-level" rule the initial attach uses. Deliberately narrow: it only ever
 * <em>replaces</em> a window that has died, so a session that never attached stays unattached (a failed launch
 * must not look like a successful one), and a live attachment is never second-guessed.
 *
 * <p>Extracted from {@link NestedSession} when {@link AdoptedSession} arrived: a bot that adopts a session someone
 * else brought up watches the same launcher chain swap windows under it, and this bug must not exist in two
 * places.
 */
public final class SessionAttachment {

	private final NativeController controller;
	/** Second X connection used only for the cheap liveness probe; {@code null} disables it (nothing to probe). */
	private final Pointer x11Display;
	/** How this attachment names itself in a log line — a session id and its display. */
	private final String label;

	private volatile GenericWindow attached;

	public SessionAttachment(NativeController controller, Pointer x11Display, String label) {
		this.controller = controller;
		this.x11Display = x11Display;
		this.label = label;
	}

	/** Make {@code window} the target. */
	public void attach(GenericWindow window) {
		this.attached = window;
	}

	/** The target as last resolved, with no round trip — what a closed session answers. */
	public GenericWindow current() {
		return attached;
	}

	/** The target, re-resolved when the attached window has died. See the class note. */
	public GenericWindow resolve() {
		GenericWindow current = attached;
		if (current == null || isViewable(current)) {
			return current;
		}
		GenericWindow replacement = newestWindow();
		if (replacement == null) {
			// Between windows — the game may be mid-transition. Keep the old reference so the session still reads
			// as attached; this call's capture/input simply finds nothing, and the next one retries.
			return current;
		}
		attached = replacement;
		Diag.log("[Session] " + label + ": re-attached to '" + replacement.getTitle()
			+ "' (the previous window was destroyed)");
		return replacement;
	}

	/** Whether {@code window} still exists and is mapped. */
	private boolean isViewable(GenericWindow window) {
		if (x11Display == null) {
			return true; // nothing to probe with: never invent a death, so a live attachment is left alone
		}
		try {
			return X11Utils.isWindowViewable(x11Display, (Pointer) window.getNativeHandle());
		} catch (Exception e) {
			return false;
		}
	}

	/** The most recently mapped top-level on the display, or {@code null} when there is none. */
	private GenericWindow newestWindow() {
		GenericWindow newest = null;
		try {
			for (GenericWindow w : controller.getAllWindows()) {
				newest = w;
			}
		} catch (Exception e) {
			Diag.log("[Session] " + label + ": could not re-scan: " + e.getMessage());
		}
		return newest;
	}
}
