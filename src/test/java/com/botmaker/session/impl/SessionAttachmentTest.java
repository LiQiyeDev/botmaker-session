package com.botmaker.session.impl;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Which window a session drives while the one it attached to is still alive — the launcher-chain promotion.
 *
 * <p>Runs with no X server at all: passing a {@code null} display pointer turns the liveness probe into "never
 * invent a death", which is exactly the state under test. The other half of the type — replacing a window that
 * really died — is not covered here, because it needs a real display to kill a window on.
 *
 * <p>The live bug these pin: Heroic launching Firestone maps its store page first and keeps it mapped behind the
 * game, so the death rule never fires and the session streamed the store page for the whole run.
 */
class SessionAttachmentTest {

    private static final GenericWindow LAUNCHER = window(0x100, "Heroic Games Launcher");
    private static final GenericWindow GAME = window(0x200, "Firestone");

    @Test
    void aLiveAttachmentIsLeftAloneUnlessTheLauncherChainIsBeingFollowed() {
        SessionAttachment attachment = attachedTo(LAUNCHER, List.of(LAUNCHER, GAME));

        // The default, and every non-launcher launch: a window that is alive is the window we drive, even with a
        // newer one beside it. Guessing here would let a notification steal an `exe:` session.
        assertSame(LAUNCHER, attachment.resolve());
    }

    @Test
    void aLauncherChainPromotesToTheWindowThatArrivedAfterIt() {
        SessionAttachment attachment = attachedTo(LAUNCHER, List.of(LAUNCHER, GAME));
        attachment.followLauncherChain(true);

        assertSame(GAME, attachment.resolve(), "the game's window arrived after the store page — drive that");
        assertSame(GAME, attachment.current(), "and the promotion sticks, rather than being recomputed per call");
    }

    @Test
    void promotingBackIsTheSameRule() {
        // The game exits to the launcher: the newest window is the store page again, and nothing about the rule
        // is one-way. This is why the promotion stays armed rather than disarming itself on first success.
        SessionAttachment attachment = attachedTo(GAME, List.of(GAME, LAUNCHER));
        attachment.followLauncherChain(true);

        assertSame(LAUNCHER, attachment.resolve());
    }

    @Test
    void aChainWithNothingNewerStaysWhereItIs() {
        SessionAttachment attachment = attachedTo(GAME, List.of(LAUNCHER, GAME));
        attachment.followLauncherChain(true);

        assertSame(GAME, attachment.resolve(), "already on the newest window — a promotion to itself is a no-op");
    }

    @Test
    void anEmptyDisplayNeverUnsetsAnAttachment() {
        // Between windows: the controller sees nothing for a moment during a mode switch. Answering null here
        // would read as "the launch failed", which is a different and much louder thing than "wait a beat".
        SessionAttachment attachment = attachedTo(GAME, List.of());
        attachment.followLauncherChain(true);

        assertSame(GAME, attachment.resolve());
    }

    @Test
    void aSessionThatNeverAttachedStaysUnattached() {
        SessionAttachment attachment = new SessionAttachment(new FakeController(List.of(GAME)), null, "test");
        attachment.followLauncherChain(true);

        assertNull(attachment.resolve(), "a failed launch must not be handed the first window on the display");
    }

    @Test
    void theScanIsRateLimitedSoAStreamingCaptureDoesNotPayForItPerFrame() {
        CountingController controller = new CountingController(List.of(LAUNCHER, GAME));
        SessionAttachment attachment = new SessionAttachment(controller, null, "test");
        attachment.attach(LAUNCHER);
        attachment.followLauncherChain(true);

        for (int i = 0; i < 50; i++) {
            attachment.resolve();
        }
        assertEquals(1, controller.scans, "50 frames inside one interval must cost one window scan");
    }

    private static SessionAttachment attachedTo(GenericWindow window, List<GenericWindow> onDisplay) {
        SessionAttachment attachment = new SessionAttachment(new FakeController(onDisplay), null, "test");
        attachment.attach(window);
        return attachment;
    }

    private static GenericWindow window(long id, String title) {
        return new GenericWindow(new Pointer(id), title, null);
    }

    /** Minimal {@link NativeController} that only answers {@code getAllWindows}; everything else is a no-op. */
    private static class FakeController implements NativeController {
        private final List<GenericWindow> windows;

        FakeController(List<GenericWindow> windows) {
            this.windows = windows;
        }

        @Override public List<GenericWindow> getAllWindows() { return windows; }
        @Override public GenericWindow getForegroundWindow() { return null; }
        @Override public List<GenericWindow> getChildWindows(GenericWindow parent) { return List.of(); }
        @Override public BufferedImage captureWindow(GenericWindow window) { return null; }
        @Override public void postLeftClick(GenericWindow window, int relativeX, int relativeY) { }
        @Override public void focusWindow(GenericWindow window) { }
        @Override public void moveWindow(GenericWindow window, int x, int y) { }
        @Override public void resizeWindow(GenericWindow window, int width, int height) { }
        @Override public void keyDown(int nativeKeyCode) { }
        @Override public void keyUp(int nativeKeyCode) { }
        @Override public void typeText(String text) { }
        @Override public void mouseMove(int xAbs, int yAbs) { }
        @Override public void mouseButton(int button, boolean press) { }
        @Override public void scroll(int amount) { }
    }

    /** The same, counting how often the window list is actually asked for. */
    private static final class CountingController extends FakeController {
        private int scans;

        CountingController(List<GenericWindow> windows) {
            super(windows);
        }

        @Override
        public List<GenericWindow> getAllWindows() {
            scans++;
            return super.getAllWindows();
        }
    }
}
