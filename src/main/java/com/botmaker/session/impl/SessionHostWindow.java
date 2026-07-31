package com.botmaker.session.impl;

import com.botmaker.session.display.SessionDisplay;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.linux.X11;
import com.botmaker.shared.capture.linux.X11Utils;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The nested display server's own window <em>on the host desktop</em> — the black rectangle a user watches while
 * a store launcher takes its two minutes to boot.
 *
 * <p>Xephyr maps its output window on {@code :0} the instant it starts and nothing is drawn into it until a client
 * maps something on {@code :N} — which for a store launcher is up to two minutes of black rectangle on the user's
 * real desktop. This type minimizes that window and restores it the moment the session has something in it. It
 * deliberately does <em>not</em> hide the launcher: the reveal fires on the <em>first</em> window to appear on
 * {@code :N}, the store launcher's own UI included, because seeing the bot's session is half the point of running
 * it visibly.
 *
 * <p><b>gamescope needs none of this, measured.</b> While its Xwayland has no clients, gamescope's host window is
 * not mapped at all — no {@code WM_STATE}, absent from {@code _NET_CLIENT_LIST} — and it appears the instant a
 * client maps something. So there is no empty black window to hide, and by the time this class can even find the
 * window there is already content in it; {@link #anythingMappedOn} is what keeps us from minimizing that. The
 * short black flash a gamescope user does see is the gap between its window being mapped and its first rendered
 * frame, which is not something we can iconify away.
 *
 * <p><b>Iconify, not unmap.</b> {@code XIconifyWindow} sends the ICCCM {@code WM_CHANGE_STATE} message the host
 * WM acts on, which is reversible with a plain {@code XMapWindow}. A bare {@code XUnmapWindow} would instead take
 * the window out of the WM's management and leave restoring it to us.
 *
 * <p><b>Best-effort by construction.</b> {@link #find} returns {@code null} whenever the host window can't be
 * identified — no host {@code DISPLAY} (a Wayland host with no Xwayland), a host WM that publishes no
 * {@code _NET_CLIENT_LIST}, a server that advertises neither {@code _NET_WM_PID} nor a distinctive class. That
 * case is today's behaviour: a visible window during bring-up, no regression. Every operation opens its own
 * short-lived connection to the host display rather than holding one for the session's lifetime, because there
 * are only two of them (hide once, reveal once) and a session should not carry an X connection it almost never
 * uses.
 */
public final class SessionHostWindow {

    private static final long POLL_MS = 100;

    private final String hostDisplay;
    private final long windowId;
    private final String label;
    private volatile boolean revealed;

    private SessionHostWindow(String hostDisplay, long windowId, String label) {
        this.hostDisplay = hostDisplay;
        this.windowId = windowId;
        this.label = label;
    }

    /**
     * Locate the host-desktop window of the display server rooted at {@code serverPid}, or {@code null} when it
     * can't be identified within {@code timeoutMs}.
     *
     * <p>Two signals, in order. {@code _NET_WM_PID} matched against {@code serverPid} <em>or any of its
     * descendants</em> — under the systemd strategy {@code serverPid} is the {@code systemd-run --scope} wrapper,
     * so the server itself is a child. Failing that, an unambiguous {@code WM_CLASS} match on {@code nameHint} (the
     * server binary's name), which covers a server that publishes no EWMH pid at all.
     *
     * @param serverPid the pid the session launched to get the server up (see {@link SessionDisplay#serverPid()})
     * @param nameHint  the server binary's name, e.g. {@code "gamescope"} — used only as the fallback match
     * @param timeoutMs how long to wait for the server to map its window before giving up
     */
    public static SessionHostWindow find(long serverPid, String nameHint, long timeoutMs) {
        String hostDisplay = System.getenv("DISPLAY");
        if (hostDisplay == null || hostDisplay.isBlank()) {
            return null;   // a Wayland host with no Xwayland: there is no host window to hide
        }
        Pointer display = open(hostDisplay);
        if (display == null) {
            return null;
        }
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            do {
                Set<Long> pids = treeOf(serverPid);
                Long found = search(display, pids, nameHint);
                if (found != null) {
                    return new SessionHostWindow(hostDisplay, found, nameHint + " window 0x"
                        + Long.toHexString(found) + " on " + hostDisplay);
                }
                sleep();
            } while (System.currentTimeMillis() < deadline);
            return null;
        } catch (Throwable t) {
            return null;   // any X or /proc surprise: no host window, so nothing to hide
        } finally {
            close(display);
        }
    }

    /** The first host top-level owned by {@code pids}, else the only one whose {@code WM_CLASS} is {@code nameHint}. */
    private static Long search(Pointer display, Set<Long> pids, String nameHint) {
        Long byClass = null;
        boolean classAmbiguous = false;
        for (Pointer window : X11Utils.getClientList(display)) {
            if (window == null || Pointer.nativeValue(window) == 0) {
                continue;
            }
            if (pids.contains(X11Utils.getWindowPid(display, window))) {
                return Pointer.nativeValue(window);
            }
            if (isClass(display, window, nameHint)) {
                classAmbiguous = byClass != null;
                byClass = Pointer.nativeValue(window);
            }
        }
        // Two windows of the same class and no pid to separate them: that is another session's server (or another
        // gamescope entirely), and minimizing the wrong one is worse than not minimizing at all.
        return classAmbiguous ? null : byClass;
    }

    /**
     * Whether {@code window}'s {@code WM_CLASS} mentions {@code nameHint}, case-insensitively.
     *
     * <p>Class, deliberately not title. A title is whatever the app decides to put there, so a terminal running
     * {@code gamescope …}, or an editor with the word in a filename, would both match — and this window is about to
     * be minimized, so a false positive means minimizing something of the user's.
     */
    private static boolean isClass(Pointer display, Pointer window, String nameHint) {
        if (nameHint == null || nameHint.isBlank()) {
            return false;
        }
        String wmClass = X11Utils.getWindowProperty(display, window, "WM_CLASS", "STRING");
        return wmClass != null && wmClass.toLowerCase(Locale.ROOT).contains(nameHint.toLowerCase(Locale.ROOT));
    }

    /** {@code pid} and every descendant of it that exists right now. */
    private static Set<Long> treeOf(long pid) {
        Set<Long> pids = new HashSet<>();
        pids.add(pid);
        ProcessHandle.of(pid).ifPresent(p -> p.descendants().forEach(d -> pids.add(d.pid())));
        return pids;
    }

    /**
     * Whether anything is mapped on {@code displayName} — "does this session have content yet?", asked on a
     * connection of our own.
     *
     * <p>Three things about its shape, each measured rather than assumed. It must not go through the session's
     * {@link com.botmaker.shared.capture.linux.LinuxController}: Xlib connections are not thread-safe, and this runs
     * on the hider thread while the supervisor polls the same display for the game's window. It walks
     * {@code XQueryTree} rather than {@code _NET_CLIENT_LIST}, because gamescope's Xwayland has no window manager at
     * all — an EWMH client list reads empty there no matter what is on screen. And "mapped" is not enough on its
     * own: an <em>empty</em> Xephyr+openbox display already has a viewable window on it, openbox's own 1x1 support
     * window parked at {@code -100,-100}, so a bare map-state test called every session occupied and this feature
     * silently did nothing. Content therefore means viewable <em>and</em> bigger than a pixel.
     *
     * <p>{@code true} on any doubt (an unreadable display, an X error): the caller's response to "there is
     * content" is to leave the window alone, which is the safe direction.
     */
    public static boolean anythingMappedOn(String displayName) {
        Pointer display = open(displayName);
        if (display == null) {
            return true;
        }
        try {
            Pointer root = X11.INSTANCE.XDefaultRootWindow(display);
            PointerByReference rootReturn = new PointerByReference();
            PointerByReference parentReturn = new PointerByReference();
            PointerByReference childrenReturn = new PointerByReference();
            IntByReference count = new IntByReference();
            if (X11.INSTANCE.XQueryTree(display, root, rootReturn, parentReturn, childrenReturn, count) == 0) {
                return true;
            }
            Pointer children = childrenReturn.getValue();
            int n = count.getValue();
            if (children == null || n <= 0) {
                return false;
            }
            try {
                for (long child : children.getLongArray(0, n)) {
                    if (isContent(display, new Pointer(child))) {
                        return true;
                    }
                }
            } finally {
                X11.INSTANCE.XFree(children);
            }
            return false;
        } catch (Throwable t) {
            return true;
        } finally {
            close(display);
        }
    }

    /** Whether {@code window} is something a user would see: mapped, and larger than a support window's pixel. */
    private static boolean isContent(Pointer display, Pointer window) {
        X11.XWindowAttributes attributes = new X11.XWindowAttributes();
        if (X11.INSTANCE.XGetWindowAttributes(display, window, attributes) == 0) {
            return false;
        }
        return attributes.map_state == X11.IsViewable && attributes.width > 1 && attributes.height > 1;
    }

    /** The host-display window id this instance minimizes — for diagnostics and the live test's own X reads. */
    public long windowId() {
        return windowId;
    }

    /** Minimize the window. No-op once {@link #reveal} has run — a revealed session is never re-hidden. */
    public void hide() {
        if (revealed) {
            return;
        }
        Pointer display = open(hostDisplay);
        if (display == null) {
            return;
        }
        try {
            X11.INSTANCE.XIconifyWindow(display, new Pointer(windowId), X11.INSTANCE.XDefaultScreen(display));
            X11.INSTANCE.XFlush(display);
            Diag.log("[Session] minimized the " + label + " until there is something in it");
        } catch (Throwable t) {
            Diag.error("[Session] could not minimize the " + label + ": " + t.getMessage());
        } finally {
            close(display);
        }
    }

    /**
     * Restore and raise the window — the session now has a window of its own to show. Idempotent: only the first
     * call does anything, so the per-attach call site can stay unconditional.
     */
    public void reveal() {
        if (revealed) {
            return;
        }
        revealed = true;
        Pointer display = open(hostDisplay);
        if (display == null) {
            return;
        }
        try {
            Pointer window = new Pointer(windowId);
            // XMapWindow on an iconified top-level is the ICCCM de-iconify (4.1.4); the raise brings it forward
            // in the host's stacking order, since it was minimized rather than merely lowered.
            X11.INSTANCE.XMapWindow(display, window);
            X11.INSTANCE.XRaiseWindow(display, window);
            X11.INSTANCE.XFlush(display);
            Diag.log("[Session] restored the " + label + " — the session has a window now");
        } catch (Throwable t) {
            Diag.error("[Session] could not restore the " + label + ": " + t.getMessage()
                + " — un-minimize it by hand to watch the session");
        } finally {
            close(display);
        }
    }

    private static Pointer open(String hostDisplay) {
        try {
            return X11.INSTANCE.XOpenDisplay(hostDisplay);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void close(Pointer display) {
        try {
            X11.INSTANCE.XCloseDisplay(display);
        } catch (Throwable ignored) {
            // Best-effort: nothing downstream depends on this connection.
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
