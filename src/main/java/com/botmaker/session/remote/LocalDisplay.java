package com.botmaker.session.remote;

import com.botmaker.session.display.SessionBackends;
import com.botmaker.session.impl.NestedSession;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.linux.LinuxController;
import com.botmaker.shared.capture.linux.X11;
import com.botmaker.shared.capture.linux.X11Utils;
import com.botmaker.shared.capture.linux.input.LinuxInputBackendId;
import com.sun.jna.Pointer;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A {@link DisplayLink} that opens {@code :N} <b>in this process</b> — the original behaviour, kept for the two
 * places that want it: {@link DisplayAgent}, which is the process the connection is supposed to live in, and a
 * fallback for when no agent can be spawned at all.
 *
 * <p>Holding one of these in a long-lived process is the hazard {@link DisplayLink} documents: when the display
 * server goes away, Xlib's default I/O handler calls {@code exit(1)} right here. That is fine in the agent —
 * exiting <em>is</em> its way of reporting the display gone — and it is why nothing else should choose it.
 *
 * <p>It is also the boundary where handle representations meet. The controller underneath speaks JNA
 * {@link Pointer}s; everything above a {@code DisplayLink} speaks plain {@code Long} ids, so this class
 * translates in both directions and no {@code Pointer} escapes upwards.
 */
public final class LocalDisplay implements DisplayLink {

    private final String displayName;
    private final LinuxController controller;
    /** A second connection for EWMH reads (pid, geometry, WM check), separate from the controller's own. */
    private final Pointer ewmh;
    private volatile boolean closed;

    private LocalDisplay(String displayName, LinuxController controller, Pointer ewmh) {
        this.displayName = displayName;
        this.controller = controller;
        this.ewmh = ewmh;
    }

    /**
     * Open {@code displayName} in this process, or return {@code null} when it doesn't accept a connection.
     * Never throws: the caller's fallback for "no display" is always cheaper than an exception here.
     */
    public static LocalDisplay open(String displayName, NestedSession.Backend backend) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        String display = displayName.trim();
        LinuxController controller;
        try {
            // XTest is pinned: on a private display device-level input is both accepted and non-intrusive, and
            // the process-wide botmaker.linux.input property (which steers :0) must not decide :N's backend.
            controller = LinuxController.forDisplay(display, LinuxInputBackendId.XTEST,
                SessionBackends.pointerWarpFor(backend), SessionBackends.inputTimingFor(backend));
        } catch (Exception e) {
            Diag.error("[Session] cannot open " + display + ": " + e.getMessage());
            return null;
        }
        Pointer ewmh = X11.INSTANCE.XOpenDisplay(display);
        if (ewmh == null) {
            try { controller.close(); } catch (Throwable ignored) { }
            Diag.error("[Session] cannot open a second connection to " + display);
            return null;
        }
        setRootCursor(display, ewmh);
        return new LocalDisplay(display, controller, ewmh);
    }

    /**
     * Gives {@code :N}'s root the ordinary arrow instead of the black cross it comes up with.
     *
     * <p>Purely cosmetic, and only visible in a Xephyr window someone is watching — {@code ffmpeg} grabs with
     * {@code -draw_mouse 0}, so the cursor was never in the stream and never in a capture. It is worth the six
     * lines anyway because the cross reads as a broken session to anyone who has not been told otherwise, and
     * because the reason it is there is a gap rather than a choice: a bare X server's root cursor is
     * {@code XC_X_cursor}, replaced at login by a desktop environment that a private display does not have.
     * A session with a window manager might have got one from openbox; an emulator session runs
     * {@code withoutWindowManager()} and so has nobody at all.
     *
     * <p>Here rather than in {@code DisplayAgent} because this is the class that owns the {@code :N}
     * connection in <em>either</em> topology — the agent child normally, and this same process under
     * {@code -Dbotmaker.session.display.local=true}. Best-effort throughout: a session must never fail to start
     * over a cursor.
     */
    private static void setRootCursor(String display, Pointer ewmh) {
        try {
            Pointer cursor = X11.INSTANCE.XCreateFontCursor(ewmh, X11.XC_left_ptr);
            if (cursor == null) {
                return;
            }
            X11.INSTANCE.XDefineCursor(ewmh, X11.INSTANCE.XDefaultRootWindow(ewmh), cursor);
            X11.INSTANCE.XFreeCursor(ewmh, cursor);
            X11.INSTANCE.XFlush(ewmh);
        } catch (Throwable cosmetic) {
            Diag.log("[Session] " + display + ": could not set the root cursor (" + cosmetic + ")");
        }
    }

    @Override
    public String displayName() {
        return displayName;
    }

    // --- the extra :N reads ---

    @Override
    public BufferedImage captureScreen() {
        if (closed) {
            return null;
        }
        try {
            return controller.captureWindow(new GenericWindow(X11.INSTANCE.XDefaultRootWindow(ewmh), "", null));
        } catch (Exception e) {
            Diag.error("[Session] " + displayName + ": root capture failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public Rectangle screenSize() {
        if (closed) {
            return new Rectangle();
        }
        try {
            return new Rectangle(0, 0, X11.INSTANCE.XDisplayWidth(ewmh, 0), X11.INSTANCE.XDisplayHeight(ewmh, 0));
        } catch (Exception e) {
            return new Rectangle();
        }
    }

    @Override
    public boolean windowViewable(long windowId) {
        if (closed || windowId == 0) {
            return false;
        }
        try {
            return X11Utils.isWindowViewable(ewmh, new Pointer(windowId));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long windowPid(long windowId) {
        if (closed || windowId == 0) {
            return 0;
        }
        try {
            return X11Utils.getWindowPid(ewmh, new Pointer(windowId));
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public boolean hasWindowManager() {
        try {
            return !closed && X11Utils.hasWindowManager(ewmh);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int mappedCount() {
        return closed ? -1 : com.botmaker.session.impl.SessionHostWindow.mappedCountOn(displayName);
    }

    @Override
    public boolean alive() {
        if (closed) {
            return false;
        }
        Pointer probe = X11.INSTANCE.XOpenDisplay(displayName);
        if (probe == null) {
            return false;
        }
        X11.INSTANCE.XCloseDisplay(probe);
        return true;
    }

    @Override
    public void setDrivenWindow(Supplier<Long> windowId) {
        controller.setDrivenWindow(windowId == null ? null : () -> {
            Long id = windowId.get();
            return id == null || id == 0 ? null : new Pointer(id);
        });
    }

    // --- NativeController, translated at the handle boundary ---

    @Override
    public GenericWindow getForegroundWindow() {
        return closed ? null : externalise(controller.getForegroundWindow());
    }

    @Override
    public List<GenericWindow> getChildWindows(GenericWindow parent) {
        return closed ? List.of() : externalise(controller.getChildWindows(internalise(parent)));
    }

    @Override
    public List<GenericWindow> getAllWindows() {
        return closed ? List.of() : externalise(controller.getAllWindows());
    }

    @Override
    public List<GenericWindow> getAllWindows(boolean includeMinimized) {
        return closed ? List.of() : externalise(controller.getAllWindows(includeMinimized));
    }

    @Override
    public void restoreWindow(GenericWindow window) {
        if (!closed) {
            controller.restoreWindow(internalise(window));
        }
    }

    @Override
    public BufferedImage captureWindow(GenericWindow window) {
        return closed ? null : controller.captureWindow(internalise(window));
    }

    @Override
    public void promoteOverlayAboveFullscreen(String windowTitle) {
        if (!closed) {
            controller.promoteOverlayAboveFullscreen(windowTitle);
        }
    }

    @Override
    public void postLeftClick(GenericWindow window, int relativeX, int relativeY) {
        if (!closed) {
            controller.postLeftClick(internalise(window), relativeX, relativeY);
        }
    }

    @Override
    public boolean supportsBackgroundInput() {
        return !closed && controller.supportsBackgroundInput();
    }

    @Override
    public boolean useReliableInput() {
        return !closed && controller.useReliableInput();
    }

    @Override
    public void focusWindow(GenericWindow window) {
        if (!closed) {
            controller.focusWindow(internalise(window));
        }
    }

    @Override
    public void moveWindow(GenericWindow window, int x, int y) {
        if (!closed) {
            controller.moveWindow(internalise(window), x, y);
        }
    }

    @Override
    public void resizeWindow(GenericWindow window, int width, int height) {
        if (!closed) {
            controller.resizeWindow(internalise(window), width, height);
        }
    }

    @Override
    public void keyDown(int nativeKeyCode) {
        if (!closed) {
            controller.keyDown(nativeKeyCode);
        }
    }

    @Override
    public void keyUp(int nativeKeyCode) {
        if (!closed) {
            controller.keyUp(nativeKeyCode);
        }
    }

    @Override
    public void typeText(String text) {
        if (!closed) {
            controller.typeText(text);
        }
    }

    @Override
    public void keyDown(GenericWindow window, int nativeKeyCode) {
        if (!closed) {
            controller.keyDown(internalise(window), nativeKeyCode);
        }
    }

    @Override
    public void keyUp(GenericWindow window, int nativeKeyCode) {
        if (!closed) {
            controller.keyUp(internalise(window), nativeKeyCode);
        }
    }

    @Override
    public void typeText(GenericWindow window, String text) {
        if (!closed) {
            controller.typeText(internalise(window), text);
        }
    }

    @Override
    public void mouseMove(int xAbs, int yAbs) {
        if (!closed) {
            controller.mouseMove(xAbs, yAbs);
        }
    }

    @Override
    public void mouseMoveRelative(int dx, int dy) {
        if (!closed) {
            controller.mouseMoveRelative(dx, dy);
        }
    }

    @Override
    public void mouseButton(int button, boolean press) {
        if (!closed) {
            controller.mouseButton(button, press);
        }
    }

    @Override
    public void scroll(int amount) {
        if (!closed) {
            controller.scroll(amount);
        }
    }

    @Override
    public Point cursorPosition() {
        return closed ? null : controller.cursorPosition();
    }

    @Override
    public void click(int xAbs, int yAbs, int button) {
        if (!closed) {
            controller.click(xAbs, yAbs, button);
        }
    }

    @Override
    public void clickRestoringCursor(int xAbs, int yAbs, int button) {
        if (!closed) {
            controller.clickRestoringCursor(xAbs, yAbs, button);
        }
    }

    @Override
    public int pressHoldMs() {
        return controller.pressHoldMs();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try { controller.close(); } catch (Throwable t) { Diag.error("[Session] " + displayName + " close: " + t.getMessage()); }
        try { X11.INSTANCE.XCloseDisplay(ewmh); } catch (Throwable t) { Diag.error("[Session] " + displayName + " ewmh close: " + t.getMessage()); }
    }

    // --- handle translation ---

    /** A window as the controller wants it: a JNA {@link Pointer} handle, whatever the caller handed us. */
    private static GenericWindow internalise(GenericWindow window) {
        if (window == null) {
            return null;
        }
        Object handle = window.getNativeHandle();
        if (handle instanceof Pointer) {
            return window;
        }
        long id = WindowIds.of(window);
        return id == 0 ? null : new GenericWindow(new Pointer(id), window.getTitle(), window.getRect());
    }

    /** A window as everything above a {@code DisplayLink} wants it: a plain {@code Long} handle. */
    private static GenericWindow externalise(GenericWindow window) {
        if (window == null) {
            return null;
        }
        long id = WindowIds.of(window);
        return id == 0 ? null : new GenericWindow(id, window.getTitle(), window.getRect());
    }

    private static List<GenericWindow> externalise(List<GenericWindow> windows) {
        List<GenericWindow> out = new ArrayList<>();
        if (windows != null) {
            for (GenericWindow w : windows) {
                GenericWindow external = externalise(w);
                if (external != null) {
                    out.add(external);
                }
            }
        }
        return out;
    }
}
