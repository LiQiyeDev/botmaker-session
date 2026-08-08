package com.botmaker.session.remote;

import com.botmaker.session.PreviewFrame;
import com.botmaker.shared.capture.GenericWindow;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which surface a preview is a picture of — {@link DisplayLink#previewFrame}'s choice, tested without a display.
 *
 * <p>This is the regression the pilot's black screen turned out to be. Under gamescope the {@code :N} root is
 * never painted (its built-in compositor redirects every client to its own output), measured live as 0 of 8160
 * sampled pixels black while a fullscreen game was mapped on that very display. A preview that grabs the root
 * is therefore correct on Xephyr and permanently black on gamescope, and nothing about the frame says which.
 */
class PreviewSurfaceTest {

    private static BufferedImage painted(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static BufferedImage black(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    }

    @Test
    void aPaintedRootIsThePreviewAndIsTaggedWithTheScreen() {
        FakeLink link = new FakeLink(painted(1920, 1080), new Rectangle(0, 0, 1920, 1080));
        link.windows.add(new GenericWindow(1L, "Game", new Rectangle(10, 10, 640, 480)));
        link.frames.put(1L, painted(640, 480));

        PreviewFrame frame = link.previewFrame(1280, 0.6f);

        assertNotNull(frame);
        assertEquals(new Rectangle(0, 0, 1920, 1080), frame.surface(), "the root had pixels, so it is the frame");
        assertTrue(link.windowGrabs.isEmpty(), "no window is grabbed when the root already answered");
    }

    /** The gamescope case: a black root, a painted window, and the frame must be the window — at its own rect. */
    @Test
    void aBlackRootFallsToTheLargestWindowAndIsTaggedWithItsRect() {
        FakeLink link = new FakeLink(black(1920, 1080), new Rectangle(0, 0, 1920, 1080));
        link.windows.add(new GenericWindow(7L, "Default IME", new Rectangle(0, 0, 16, 16)));
        link.windows.add(new GenericWindow(9L, "Firestone", new Rectangle(307, 239, 1280, 661)));
        link.frames.put(7L, painted(16, 16));
        link.frames.put(9L, painted(1280, 661));

        PreviewFrame frame = link.previewFrame(1280, 0.6f);

        assertNotNull(frame);
        assertEquals(new Rectangle(307, 239, 1280, 661), frame.surface(),
                "the window's own rect travels with the bytes — Interact maps taps through it");
        assertEquals(List.of(9L), link.windowGrabs, "the 16px launcher leftover is not the biggest window");
    }

    /** A display with a black root and nothing mapped is Waydroid: no frame at all, rather than a black one. */
    @Test
    void aBlackRootWithNoWindowsIsNoFrame() {
        FakeLink link = new FakeLink(black(1920, 1080), new Rectangle(0, 0, 1920, 1080));

        assertNull(link.previewFrame(1280, 0.6f));
    }

    /** A window that enumerates but grabs black is no better than the root it replaced. */
    @Test
    void aWindowThatGrabsBlackIsNotAFrameEither() {
        FakeLink link = new FakeLink(black(1920, 1080), new Rectangle(0, 0, 1920, 1080));
        link.windows.add(new GenericWindow(3L, "Game", new Rectangle(0, 0, 800, 600)));
        link.frames.put(3L, black(800, 600));

        assertNull(link.previewFrame(1280, 0.6f));
    }

    /**
     * A {@link DisplayLink} over fixed images. Only the four members {@code previewFrame}'s default calls are
     * real; everything else is the empty answer, which is also the contract every link keeps for a dead display.
     */
    private static final class FakeLink implements DisplayLink {
        private final BufferedImage root;
        private final Rectangle screen;
        final List<GenericWindow> windows = new ArrayList<>();
        final java.util.Map<Long, BufferedImage> frames = new java.util.HashMap<>();
        /** Which windows were actually grabbed, so "the root answered" is distinguishable from "it didn't". */
        final List<Long> windowGrabs = new ArrayList<>();

        FakeLink(BufferedImage root, Rectangle screen) {
            this.root = root;
            this.screen = screen;
        }

        @Override public String displayName() { return ":9"; }
        @Override public BufferedImage captureScreen() { return root; }
        @Override public Rectangle screenSize() { return screen; }
        @Override public List<GenericWindow> getAllWindows() { return windows; }
        @Override public List<GenericWindow> getAllWindows(boolean includeMinimized) { return windows; }

        @Override
        public BufferedImage captureWindow(GenericWindow window) {
            windowGrabs.add(WindowIds.of(window));
            return frames.get(WindowIds.of(window));
        }

        @Override public GenericWindow getForegroundWindow() { return null; }
        @Override public List<GenericWindow> getChildWindows(GenericWindow parent) { return List.of(); }
        @Override public void postLeftClick(GenericWindow window, int x, int y) { }
        @Override public void focusWindow(GenericWindow window) { }
        @Override public void moveWindow(GenericWindow window, int x, int y) { }
        @Override public void resizeWindow(GenericWindow window, int width, int height) { }
        @Override public void keyDown(int nativeKeyCode) { }
        @Override public void keyUp(int nativeKeyCode) { }
        @Override public void typeText(String text) { }
        @Override public void mouseMove(int xAbs, int yAbs) { }
        @Override public void mouseButton(int button, boolean press) { }
        @Override public void scroll(int amount) { }
        @Override public boolean windowViewable(long windowId) { return true; }
        @Override public long windowPid(long windowId) { return 0; }
        @Override public boolean hasWindowManager() { return false; }
        @Override public int mappedCount() { return windows.size(); }
        @Override public boolean alive() { return true; }
        @Override public void setDrivenWindow(Supplier<Long> windowId) { }
        @Override public void close() { }
    }
}
