package com.botmaker.session.impl;

import com.botmaker.session.SessionStartException;

import com.botmaker.shared.capture.linux.X11;
import com.botmaker.shared.capture.linux.X11Utils;
import com.botmaker.shared.launch.LaunchSpec;
import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The live gate on hiding the bring-up: minimizing the display server's host window must not cost us the
 * capture. That is the whole risk of the feature — an iconified window is a hint to the host compositor that
 * nobody is looking, and a compositor that acts on it by throttling the server's frames would turn a cosmetic
 * black flash into a stalled capture, which is far worse. So this asserts the two halves in order: the host
 * window really is minimized (the session is out of sight), and the session still reads its own window's pixels
 * while it is.
 *
 * <p>Opt-in and self-skipping like {@link NestedSessionLiveTest}: {@code -Dbotmaker.live=true}, a usable
 * {@code DISPLAY}, {@code openbox}/{@code xterm} and the backend's own binary on {@code PATH}. The backend is
 * chosen with {@code -Dbotmaker.live.backend=xephyr|gamescope} (default Xephyr), because the frame scheduler is
 * the thing under test and gamescope's is not Xephyr's — the claim has to be measured once per backend.
 */
class SessionHostWindowLiveTest {

	/**
	 * The user-visible claim itself: a session that has been brought up but has nothing in it yet is not sitting
	 * on the host desktop as a black rectangle. This is the case a store launcher spends up to two minutes in.
	 */
	@Test
	void aSessionWithNothingInItYetIsNotOnTheHostDesktop() throws Exception {
		assumeLive();
		NestedSession session = startSession();
		try {
			SessionHostWindow hostWindow = findHostWindow(session);
			// The session's own hider runs off the start path, so give it the same budget it gives itself.
			assertTrue(awaitIconified(hostWindow.windowId(), 16_000),
				"the display server's window should have been minimized while the session is empty");
		} finally {
			session.close();
		}
	}

	@Test
	void theSessionStillCapturesWhileItsHostWindowIsMinimized() throws Exception {
		assumeLive();
		NestedSession session = startSession();
		try {
			session.launch(LaunchSpec.parse("cli:xterm -e sleep 300"));
			assertNotNull(session.attached(), "a window should have appeared on " + session.displayName());
			SessionHostWindow hostWindow = findHostWindow(session);

			// The other half of the feature, asserted against X rather than a log line: an attach puts the window
			// back. A hide that raced the attach and won would leave the session permanently invisible.
			assertTrue(awaitViewable(hostWindow.windowId(), 16_000),
				"a session with a window in it must be back on the host desktop");

			// Now hide it again to measure capture in the state a bring-up runs in.
			hostWindow.hide();

			assertTrue(awaitIconified(hostWindow.windowId(), 3_000),
				"the host window should have left the viewable state — nothing was hidden otherwise");

			BufferedImage frame = session.capture();
			assertNotNull(frame, "an out-of-sight session must still read its own window");
			assertTrue(frame.getWidth() > 1 && frame.getHeight() > 1, "captured " + frame.getWidth() + "x"
				+ frame.getHeight() + " while minimized");
			// A second frame after a beat: a compositor that throttles an unwatched server would show up as a
			// capture that stops answering, not as one that never started.
			Thread.sleep(1_000);
			assertNotNull(session.capture(), "capture must keep answering while the host window stays minimized");

			hostWindow.reveal();
		} finally {
			session.close();
		}
	}

	/** The backend under test — the frame scheduler is the thing being measured, so it is a knob, not a constant. */
	private static NestedSession.Backend backend() {
		return NestedSession.Backend.fromId(System.getProperty("botmaker.live.backend", "xephyr")).orElseThrow();
	}

	private static NestedSession startSession() throws SessionStartException {
		assumeTrue(onPath(backend().binaryName()), "needs " + backend().binaryName() + " on PATH");
		return NestedSession.start(backend() == NestedSession.Backend.GAMESCOPE
			? NestedSession.Options.gamescope(800, 600)
			: NestedSession.Options.xephyr(800, 600));
	}

	/**
	 * The session's host window, on the same generous budget the session gives its own search — gamescope
	 * publishes its output window several seconds after its Xwayland is connectable, which is precisely why the
	 * session hunts for it off the start path. Skips (rather than fails) where no host WM publishes a client list.
	 */
	private static SessionHostWindow findHostWindow(NestedSession session) {
		SessionHostWindow hostWindow = SessionHostWindow.find(session.serverPid(), backend().binaryName(), 16_000);
		assumeTrue(hostWindow != null, "needs a host WM that publishes _NET_CLIENT_LIST to find the window");
		return hostWindow;
	}

	/** Poll the host display until {@code windowId} is no longer viewable — the WM acts on the iconify async. */
	private static boolean awaitIconified(long windowId, long timeoutMs) throws InterruptedException {
		return awaitViewability(windowId, false, timeoutMs);
	}

	/** Poll the host display until {@code windowId} is viewable again — the counterpart for the de-iconify. */
	private static boolean awaitViewable(long windowId, long timeoutMs) throws InterruptedException {
		return awaitViewability(windowId, true, timeoutMs);
	}

	private static boolean awaitViewability(long windowId, boolean viewable, long timeoutMs)
			throws InterruptedException {
		Pointer display = X11.INSTANCE.XOpenDisplay(System.getenv("DISPLAY"));
		assertNotNull(display, "should be able to open the host display");
		try {
			long deadline = System.currentTimeMillis() + timeoutMs;
			while (System.currentTimeMillis() < deadline) {
				if (X11Utils.isWindowViewable(display, new Pointer(windowId)) == viewable) {
					return true;
				}
				Thread.sleep(100);
			}
			return false;
		} finally {
			X11.INSTANCE.XCloseDisplay(display);
		}
	}

	private static void assumeLive() {
		assumeTrue(Boolean.getBoolean("botmaker.live"),
			"opt-in live test — run with -Dbotmaker.live=true");
		String display = System.getenv("DISPLAY");
		assumeTrue(display != null && !display.isBlank(), "needs a DISPLAY");
		assumeTrue(onPath("openbox") && onPath("xterm"), "needs openbox and xterm on PATH");
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
