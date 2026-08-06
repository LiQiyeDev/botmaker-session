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
 * must not look like a successful one).
 *
 * <p><b>A launcher that does not die is the other half, and it is why a live attachment <em>is</em> sometimes
 * second-guessed.</b> Heroic launching Firestone maps its own library window first, keeps it alive and mapped
 * behind the game, and the game's window arrives minutes later — so the death rule above never fires and the
 * session drives, captures and streams the store page for the whole run while the game sits on top of it,
 * perfectly visible to the user and invisible to the bot. {@link #followLauncherChain} arms the promotion that
 * fixes it: while a launch is known to route through a store launcher, the newest top-level wins, re-checked at
 * most {@value #PROMOTION_INTERVAL_MS}ms apart so a streaming capture doesn't pay for a window scan per frame.
 * It stays off for every other launch and for an explicit {@code attach} — a bot that named its window is not
 * asking us to pick a different one.
 *
 * <p>Extracted from {@link NestedSession} when {@link AdoptedSession} arrived: a bot that adopts a session someone
 * else brought up watches the same launcher chain swap windows under it, and this bug must not exist in two
 * places.
 */
public final class SessionAttachment {

    /** How often the promotion re-scans, at most. A capture loop calls {@link #resolve} on every single frame. */
    static final long PROMOTION_INTERVAL_MS = 500;

    private final NativeController controller;
    /** Second X connection used only for the cheap liveness probe; {@code null} disables it (nothing to probe). */
    private final Pointer x11Display;
    /** How this attachment names itself in a log line — a session id and its display. */
    private final String label;

    private volatile GenericWindow attached;
    /** Whether a newer top-level may take over from a live attachment — see the class note. */
    private volatile boolean followsLauncher;
    /** Earliest wall-clock at which the promotion may scan again; {@code 0} means "on the next call". */
    private volatile long nextPromotionAt;

    public SessionAttachment(NativeController controller, Pointer x11Display, String label) {
        this.controller = controller;
        this.x11Display = x11Display;
        this.label = label;
    }

    /** Make {@code window} the target. */
    public void attach(GenericWindow window) {
        this.attached = window;
    }

    /**
     * Whether the window this session drives may still change while the current one is alive — {@code true} only
     * for a launch routed through a store launcher, whose first window is a stepping stone and not the target.
     *
     * <p>Set it <em>after</em> the attach it applies to: {@code attach} deliberately does not clear it, so the
     * launch path can attach to the launcher's window and then say "keep looking", while a caller who attaches
     * by hand disarms it explicitly.
     */
    public void followLauncherChain(boolean follow) {
        this.followsLauncher = follow;
        this.nextPromotionAt = 0;
    }

    /** The target as last resolved, with no round trip — what a closed session answers. */
    public GenericWindow current() {
        return attached;
    }

    /** The target, re-resolved when the attached window has died — or superseded. See the class note. */
    public GenericWindow resolve() {
        GenericWindow current = attached;
        if (current == null) {
            return null;
        }
        if (isViewable(current)) {
            return promoted(current);
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

    /**
     * The window a launcher chain has moved on to since {@code current}, or {@code current} itself. Off unless
     * {@link #followLauncherChain} armed it, and rate-limited: the scan is an X round trip per window and
     * {@link #resolve} is on the capture path.
     *
     * <p>"Newest" is the same rule the initial attach uses (the last entry of the controller's window list), so a
     * promotion never picks a window the attach itself wouldn't have. It is a live judgement rather than a
     * one-shot upgrade, which also means it undoes itself: when the game closes back to the launcher, the next
     * call promotes back.
     */
    private GenericWindow promoted(GenericWindow current) {
        if (!followsLauncher) {
            return current;
        }
        long now = System.currentTimeMillis();
        if (now < nextPromotionAt) {
            return current;
        }
        nextPromotionAt = now + PROMOTION_INTERVAL_MS;
        GenericWindow newest = newestWindow();
        if (newest == null || idOf(newest) == idOf(current)) {
            return current;
        }
        attached = newest;
        Diag.log("[Session] " + label + ": the launcher chain moved on — now driving '" + newest.getTitle()
            + "' instead of '" + current.getTitle() + "'");
        return newest;
    }

    /** A window's native id, or {@code 0} when it has none — two windows without one are not the same window. */
    private static long idOf(GenericWindow window) {
        Object handle = window == null ? null : window.getNativeHandle();
        return handle instanceof Pointer p ? Pointer.nativeValue(p) : 0;
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
