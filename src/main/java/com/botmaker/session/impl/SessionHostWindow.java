package com.botmaker.session.impl;

import com.botmaker.session.display.SessionDisplay;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.linux.X11;
import com.botmaker.shared.capture.linux.X11Utils;
import com.botmaker.shared.platform.SessionEnv;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.awt.Rectangle;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
 * are only a handful of them over a whole session (hide once, reveal once, a repaint nudge at teardown) and a
 * session should not carry an X connection it almost never uses.
 *
 * <p><b>{@link Visibility} is the state, and it is authoritative.</b> hide and reveal run on different threads
 * — the hider and whatever attaches — and every method that changes it is {@code synchronized} on this
 * instance, so "may this still be hidden?" is answered once rather than inferred twice from a flag.
 */
public final class SessionHostWindow {

    private static final long POLL_MS = 100;
    /** Wall-clock, millisecond resolution: these transitions are read against a user saying "it flashed there". */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /**
     * What this window has had done to it — the one authority on whether it may still be hidden.
     *
     * <p>It replaced a {@code boolean revealed} that {@link #hide} and {@link #reveal} each read and wrote
     * independently, on two threads. "Not revealed" is not the same question as "may be hidden", and conflating
     * them let a window be hidden <em>after</em> a reveal (the hider thread wins the race with an attach, having
     * decided to hide before the reveal arrived) — a black rectangle the user has to find and un-minimize by
     * hand. With the state explicit, {@link #REVEALED} is terminal and the transition is decided once, under a
     * lock, rather than inferred from a flag twice.
     */
    public enum Visibility {
        /** Found, untouched — the only state a hide is allowed from. */
        PENDING,
        /** We minimized it, and are waiting for the session to have something worth showing. */
        HIDDEN,
        /** The session has content (or teardown asked): shown, and never hidden again. */
        REVEALED
    }

    private final String hostDisplay;
    private final long windowId;
    private final String label;
    private volatile Visibility state = Visibility.PENDING;
    /**
     * Where the window last was on the host desktop, in root coordinates — remembered while it still exists so
     * {@link #repaintHostBehind} can ask the desktop to repaint that rectangle once it doesn't.
     */
    private volatile Rectangle lastBounds;

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
     * so the server itself is a child. Failing that, a window whose {@code WM_CLASS} mentions {@code nameHint}
     * <em>and</em> whose title names {@code nestedDisplay}, which covers a server that publishes no EWMH pid at all
     * (Xephyr titles its output window {@code "Xephyr on :2 …"}).
     *
     * <p><b>The class alone is not enough, measured.</b> The fallback used to accept any window whose
     * {@code WM_CLASS} matched, as long as only one did — and a user running their own gamescope games beside the
     * Studio had one of them minimized by a session that had not yet mapped its own window. A window we are about
     * to iconify has to be provably ours, so "one candidate" was replaced by "names our display": a stranger's
     * gamescope never mentions {@code :N}. Where neither signal fires we simply leave the bring-up visible, which
     * is the pre-feature behaviour and costs nothing.
     *
     * @param serverPid     the pid the session launched to get the server up (see {@link SessionDisplay#serverPid()})
     * @param nameHint      the server binary's name, e.g. {@code "gamescope"} — only ever half of the fallback match
     * @param nestedDisplay the display the server owns, e.g. {@code ":2"} — the other half; {@code null} disables
     *                      the fallback entirely, leaving only the pid match
     * @param timeoutMs     how long to wait for the server to map its window before giving up
     */
    public static SessionHostWindow find(long serverPid, String nameHint, String nestedDisplay, long timeoutMs) {
        String hostDisplay = System.getenv(SessionEnv.DISPLAY);
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
                Long found = search(display, pids, nameHint, nestedDisplay);
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

    /** The first host top-level owned by {@code pids}, else the first that is provably our server's — see {@link #find}. */
    private static Long search(Pointer display, Set<Long> pids, String nameHint, String nestedDisplay) {
        Long byName = null;
        for (Pointer window : X11Utils.getClientList(display)) {
            if (window == null || Pointer.nativeValue(window) == 0) {
                continue;
            }
            if (pids.contains(X11Utils.getWindowPid(display, window))) {
                return Pointer.nativeValue(window);
            }
            if (byName == null && isOurServer(display, window, nameHint, nestedDisplay)) {
                byName = Pointer.nativeValue(window);
            }
        }
        return byName;
    }

    /**
     * Whether {@code window} is our own display server's, judged without a pid: its {@code WM_CLASS} mentions the
     * server binary <em>and</em> its title names the display that server owns. Both halves are required.
     *
     * <p>The class alone identifies a <em>kind</em> of window, not an instance, and the user may well be running
     * the same kind themselves. The display name identifies the instance: {@code :N} is ours by construction, and
     * a server that puts it in its title is telling us so. Neither half is load-bearing on its own — the class
     * keeps an unrelated window that happens to mention {@code :2} out, the display keeps a stranger's gamescope
     * out — and the window is about to be minimized, so a false positive costs the user a window of theirs.
     */
    private static boolean isOurServer(Pointer display, Pointer window, String nameHint, String nestedDisplay) {
        if (nameHint == null || nameHint.isBlank() || nestedDisplay == null || nestedDisplay.isBlank()) {
            return false;
        }
        String wmClass = X11Utils.getWindowProperty(display, window, "WM_CLASS", "STRING");
        if (wmClass == null || !wmClass.toLowerCase(Locale.ROOT).contains(nameHint.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return namesDisplay(X11Utils.getWindowTitle(display, window), nestedDisplay);
    }

    /**
     * Whether {@code title} mentions {@code nestedDisplay} as a whole display number — {@code ":2"} must not match
     * the {@code ":20"} of a session that happens to be running beside ours.
     */
    private static boolean namesDisplay(String title, String nestedDisplay) {
        if (title == null) {
            return false;
        }
        int at = title.indexOf(nestedDisplay);
        while (at >= 0) {
            int after = at + nestedDisplay.length();
            if (after >= title.length() || !Character.isDigit(title.charAt(after))) {
                return true;
            }
            at = title.indexOf(nestedDisplay, at + 1);
        }
        return false;
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
        int mapped = mappedCountOn(displayName);
        return mapped != 0;
    }

    /**
     * How many windows on {@code displayName} count as content by {@link #anythingMappedOn}'s test, or
     * {@code -1} when the display couldn't be asked.
     *
     * <p>The count rather than the boolean is what makes a sequence of black flashes legible: each flash is a
     * client unmapping and the next one mapping, so a log reading {@code 3 → 0 → 1} names the moment gamescope
     * had nothing left to show and unmapped its own host window. The boolean above answers the only question
     * the hider actually asks; this answers the one the log has to.
     */
    public static int mappedCountOn(String displayName) {
        Pointer display = open(displayName);
        if (display == null) {
            return -1;
        }
        try {
            Pointer root = X11.INSTANCE.XDefaultRootWindow(display);
            PointerByReference rootReturn = new PointerByReference();
            PointerByReference parentReturn = new PointerByReference();
            PointerByReference childrenReturn = new PointerByReference();
            IntByReference count = new IntByReference();
            if (X11.INSTANCE.XQueryTree(display, root, rootReturn, parentReturn, childrenReturn, count) == 0) {
                return -1;
            }
            Pointer children = childrenReturn.getValue();
            int n = count.getValue();
            if (children == null || n <= 0) {
                return 0;
            }
            try {
                int content = 0;
                for (long child : children.getLongArray(0, n)) {
                    if (isContent(display, new Pointer(child))) {
                        content++;
                    }
                }
                return content;
            } finally {
                X11.INSTANCE.XFree(children);
            }
        } catch (Throwable t) {
            return -1;
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

    /** What has been done to this window so far — see {@link Visibility}. */
    public Visibility state() {
        return state;
    }

    /**
     * Minimize the window, and say so. Allowed only from {@link Visibility#PENDING}: a window that has been
     * revealed is never hidden again, and one already hidden is not hidden twice.
     *
     * @param mappedClients what the caller's content check saw on the nested display, purely for the log
     *                      ({@code -1} for "not asked")
     */
    public synchronized void hide(int mappedClients) {
        if (state != Visibility.PENDING) {
            Diag.log(stamp() + " [Session] not hiding the " + label + " — already " + state);
            return;
        }
        Pointer display = open(hostDisplay);
        if (display == null) {
            return;
        }
        try {
            rememberBounds(display);
            X11.INSTANCE.XIconifyWindow(display, new Pointer(windowId), X11.INSTANCE.XDefaultScreen(display));
            X11.INSTANCE.XFlush(display);
            state = Visibility.HIDDEN;
            Diag.log(stamp() + " [Session] PENDING -> HIDDEN: minimized the " + label
                + " until there is something in it (" + clients(mappedClients) + ")");
        } catch (Throwable t) {
            Diag.error(stamp() + " [Session] could not minimize the " + label + ": " + t.getMessage());
        } finally {
            close(display);
        }
    }

    /** {@link #hide(int)} without a content count to report. */
    public void hide() {
        hide(-1);
    }

    /**
     * Restore and raise the window — the session now has a window of its own to show. Idempotent and terminal:
     * only the first call does anything, and after it {@link #hide} can no longer fire, so the per-attach call
     * site stays unconditional.
     *
     * <p>The map/raise runs even from {@link Visibility#PENDING} (we may never have hidden it): the public
     * {@code revealHostWindow} exists partly so a host-side tool can un-minimize a session a <em>user</em>
     * minimized, and refusing that because we weren't the one who hid it would be a regression.
     */
    public synchronized void reveal() {
        if (state == Visibility.REVEALED) {
            return;
        }
        Visibility prior = state;
        state = Visibility.REVEALED;
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
            rememberBounds(display);
            Diag.log(stamp() + " [Session] " + prior + " -> REVEALED: restored the " + label
                + " — the session has a window now");
        } catch (Throwable t) {
            Diag.error(stamp() + " [Session] could not restore the " + label + ": " + t.getMessage()
                + " — un-minimize it by hand to watch the session");
        } finally {
            close(display);
        }
    }

    /**
     * Ask the host desktop to repaint the rectangle this window last occupied — the nudge for the gray trail a
     * dragged or destroyed gamescope window leaves behind.
     *
     * <p>It is a nudge and not a fix, and the distinction is worth keeping straight: {@code XClearArea} on the
     * root clears the <em>root's</em> contents and sends {@code Expose} over that region, which repaints the
     * desktop background and anything that redraws on exposure. A host compositor that keeps its own damage
     * bookkeeping may ignore it entirely, and nothing here can make it not. Best-effort throughout — an
     * unreadable geometry or a closed display simply skips it.
     */
    public void repaintHostBehind() {
        Rectangle bounds = lastBounds;
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return;
        }
        Pointer display = open(hostDisplay);
        if (display == null) {
            return;
        }
        try {
            Pointer root = X11.INSTANCE.XDefaultRootWindow(display);
            X11.INSTANCE.XClearArea(display, root, bounds.x, bounds.y, bounds.width, bounds.height, true);
            X11.INSTANCE.XFlush(display);
            Diag.log(stamp() + " [Session] asked the host to repaint " + bounds.width + "x" + bounds.height
                + " at " + bounds.x + "," + bounds.y + " behind the " + label);
        } catch (Throwable t) {
            Diag.error(stamp() + " [Session] could not ask the host to repaint behind the " + label + ": "
                + t.getMessage());
        } finally {
            close(display);
        }
    }

    /** Cache the window's root-relative geometry, on a connection the caller already has open. */
    private void rememberBounds(Pointer display) {
        try {
            Pointer window = new Pointer(windowId);
            X11.XWindowAttributes attributes = new X11.XWindowAttributes();
            if (X11.INSTANCE.XGetWindowAttributes(display, window, attributes) == 0
                || attributes.width <= 0 || attributes.height <= 0) {
                return;
            }
            // The attributes' x/y are relative to the parent, which under a reparenting WM is the frame and not
            // the root — translate rather than trusting them, or the cleared rectangle lands at the frame's
            // offset from wherever the user dragged the window to.
            IntByReference rootX = new IntByReference();
            IntByReference rootY = new IntByReference();
            PointerByReference child = new PointerByReference();
            Pointer root = X11.INSTANCE.XDefaultRootWindow(display);
            if (X11.INSTANCE.XTranslateCoordinates(display, window, root, 0, 0, rootX, rootY, child) == 0) {
                return;
            }
            lastBounds = new Rectangle(rootX.getValue(), rootY.getValue(), attributes.width, attributes.height);
        } catch (Throwable ignored) {
            // Geometry is only ever used for a cosmetic repaint; not knowing it costs nothing.
        }
    }

    private static String clients(int mappedClients) {
        return mappedClients < 0 ? "content unknown" : mappedClients + " mapped client(s) on the session";
    }

    private static String stamp() {
        return LocalTime.now().format(STAMP);
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
