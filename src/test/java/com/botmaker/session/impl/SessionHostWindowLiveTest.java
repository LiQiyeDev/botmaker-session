package com.botmaker.session.impl;

import com.botmaker.session.SessionStartException;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.capture.linux.X11;
import com.botmaker.shared.capture.linux.X11Utils;
import com.botmaker.shared.launch.LaunchSpec;
import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * A revealed window is never hidden again, asserted against X rather than against the flag.
     *
     * <p>This is the race the {@link SessionHostWindow.Visibility} state replaced a boolean to close: the hider
     * runs on its own thread and can decide to hide while an attach is revealing, and the old code patched that
     * with a re-check <em>after</em> the hide — which fixed the common ordering and left the window minimized
     * whenever the hide landed later still. A minimized session with a game running in it is invisible and
     * unfindable; the user's only recourse is the taskbar.
     */
    @Test
    void aRevealedHostWindowRefusesToBeHiddenAgain() throws Exception {
        assumeLive();
        NestedSession session = startSession();
        try {
            session.launch(LaunchSpec.parse("cli:xterm -e sleep 300"));
            SessionHostWindow hostWindow = findHostWindow(session);
            hostWindow.reveal();
            assertTrue(awaitViewable(hostWindow.windowId(), 16_000), "the host window should be on screen");

            hostWindow.hide();   // a hider thread that lost the race, arriving late

            assertEquals(SessionHostWindow.Visibility.REVEALED, hostWindow.state(),
                "a reveal is terminal — nothing may move the window out of it");
            assertTrue(awaitViewable(hostWindow.windowId(), 2_000),
                "the window must still be on screen: a late hide is the bug, not the behaviour");
        } finally {
            session.close();
        }
    }

    /**
     * The other direction, and the one Studio's overlay editor rests on: a <em>host-side</em> X capture of the
     * session's host window reads the session's real pixels. The test above proves the session can read itself;
     * that says nothing about whether the host window is a real drawable rather than a compositor placeholder,
     * and under gamescope it is not obvious — the frames are composited by gamescope's own Vulkan swapchain, and
     * a host capture that came back black would mean the overlay draws over a window it cannot see into.
     *
     * <p>Asserted as "not uniformly black" rather than against a reference image: the point is that pixels arrive
     * at all. A capture that fails this returns a perfectly black frame, not a slightly wrong one.
     */
    @Test
    void theHostWindowReadsRealPixelsFromTheHostSide() throws Exception {
        assumeLive();
        NestedSession session = startSession();
        try {
            session.launch(LaunchSpec.parse("cli:xterm -e sleep 300"));
            assertNotNull(session.attached(), "a window should have appeared on " + session.displayName());
            SessionHostWindow hostWindow = findHostWindow(session);

            // The overlay reveals before it captures, for exactly this reason: XGetImage on an unmapped drawable
            // is a BadMatch, so a still-minimized window reads as a failure rather than as black.
            hostWindow.reveal();
            assertTrue(awaitViewable(hostWindow.windowId(), 16_000), "the host window should be back on screen");
            Thread.sleep(1_000);   // let the host compositor put a frame in it

            long id = session.hostWindowId();
            assertEquals(hostWindow.windowId(), id, "the session should publish the window the search found");

            BufferedImage frame = NativeControllerFactory.get().captureWindow(
                new GenericWindow(new Pointer(id), backend().binaryName(), null));
            assertNotNull(frame, "a host-side capture of the session's host window should return a frame");
            assertTrue(hasContent(frame), "the host-side capture came back uniformly black — the overlay would "
                + "be drawing over a window it cannot see into");
        } finally {
            session.close();
        }
    }

    /** Whether {@code frame} has any non-black pixel — sampled, since one is enough to answer the question. */
    private static boolean hasContent(BufferedImage frame) {
        for (int y = 0; y < frame.getHeight(); y += 4) {
            for (int x = 0; x < frame.getWidth(); x += 4) {
                if ((frame.getRGB(x, y) & 0xFFFFFF) != 0) {
                    return true;
                }
            }
        }
        return false;
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
        SessionHostWindow hostWindow = SessionHostWindow.find(session.serverPid(), backend().binaryName(),
            session.displayName(), 16_000);
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
