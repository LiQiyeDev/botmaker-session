package com.botmaker.session.impl;

import com.botmaker.session.Capability;
import com.botmaker.session.DesktopSession;
import com.botmaker.session.SessionHealth;
import com.botmaker.session.SessionKeyboard;
import com.botmaker.session.SessionPointer;
import com.botmaker.session.input.ControllerKeyboard;
import com.botmaker.session.input.ControllerPointer;
import com.botmaker.session.remote.DisplayLink;
import com.botmaker.session.remote.WindowIds;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.launch.LaunchSpec;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link DesktopSession} over a private display <b>someone else brought up</b> — the session a bot joins
 * instead of starting its own.
 *
 * <p><b>The workflow this exists for.</b> Bring the game up once in Studio ("▶ Launch now" / Remote Pilot
 * background mode), watch it, tune the bot, then run the bot: without this, the bot's own
 * {@code SessionBootstrap} would try to bring up a *second* private display and launch the game into it — and the
 * launcher, being single-instance, would hand that launch to the copy already running in the first session. The
 * game would end up somewhere nobody was watching. So the bot adopts the live session instead: same display, same
 * window, no second launch.
 *
 * <p><b>It owns nothing.</b> The display server, the window manager, the private bus and the game all belong to
 * whoever started them; {@link #close()} drops this session's own two X connections and stops there. That is the
 * whole difference from {@link NestedSession}, and it is why this is a separate class rather than a flag: a reap
 * from the wrong side would tear down the session its owner is still using.
 *
 * <p><b>What it can't do.</b> No {@link Capability#WINDOW_LAUNCH}: launching is the owner's job, and the target is
 * expected to be up already ({@link #launch} logs and does nothing rather than starting a rival copy). Everything
 * else a nested session offers, it offers — the display genuinely is private, so background clicks and isolated
 * focus hold.
 */
public final class AdoptedSession implements DesktopSession {

    /** System property naming a live private display to adopt, e.g. {@code :1}. */
    public static final String DISPLAY_PROPERTY = "botmaker.session.display";
    /** System property naming the X window id (decimal or {@code 0x} hex) the owner has attached, if known. */
    public static final String WINDOW_PROPERTY = "botmaker.session.window";
    /** System property naming the owner's backend id ({@code gamescope}/{@code xephyr}) — it sets input policy. */
    public static final String BACKEND_PROPERTY = "botmaker.session.owner.backend";

    private final String displayName;
    /** Everything this session does to the adopted display, held in another process — see {@link DisplayLink}. */
    private final DisplayLink link;
    private final SessionAttachment attachment;
    private final ControllerPointer pointer;
    private final ControllerKeyboard keyboard;
    private volatile boolean closed;

    private AdoptedSession(String displayName, DisplayLink link) {
        this.displayName = displayName;
        this.link = link;
        this.attachment = new SessionAttachment(link, "adopted " + displayName);
        this.pointer = new ControllerPointer(link);
        this.keyboard = new ControllerKeyboard(link, this::attached);
        // Same contract as a nested session: the input backend asks for the driven window on every use, because the
        // attachment re-resolves when the launcher chain swaps windows under us.
        link.setDrivenWindow(this::attachedWindowId);
    }

    /**
     * What the properties say to adopt, or {@code null} when nothing does. This is the whole hand-off: the process
     * that owns the session passes {@link #DISPLAY_PROPERTY} (and, when it has them, the attached window and its
     * backend) to the JVM it spawns.
     */
    public static AdoptedSession fromProperties() {
        return adopt(System.getProperty(DISPLAY_PROPERTY),
            System.getProperty(WINDOW_PROPERTY),
            NestedSession.Backend.fromId(System.getProperty(BACKEND_PROPERTY))
                .orElse(NestedSession.Backend.GAMESCOPE));
    }

    /**
     * The JVM arguments that offer {@code session} to a process we are about to spawn — the producing half of
     * {@link #fromProperties()}, kept beside it so the two can't drift on a property name. Empty for a
     * {@code null} session, so a caller composes it unconditionally.
     *
     * <p>The window id is included because the owner knows which window it settled on after the launcher chain
     * finished swapping them; the adopting bot would otherwise just take the newest top-level and could pick up a
     * leftover dialog. It is advisory — {@link #adopt} falls back to the newest when that window has since gone.
     */
    public static List<String> handoffArguments(NestedSession session) {
        if (session == null) {
            return List.of();
        }
        List<String> args = new ArrayList<>(List.of(
            "-D" + DISPLAY_PROPERTY + "=" + session.displayName(),
            "-D" + BACKEND_PROPERTY + "=" + session.backend().id()));
        long window = session.attachedWindowId();
        if (window != 0) {
            args.add("-D" + WINDOW_PROPERTY + "=" + window);
        }
        return args;
    }

    /**
     * Adopt {@code displayName}, attaching to {@code windowId} when it names a live window and otherwise to the
     * newest top-level there. Returns {@code null} — never throws — when there is nothing to adopt: no display
     * given, or it doesn't accept a connection (its owner has gone away since we were told about it). The caller's
     * fallback is its normal launch, so a failure here has to be cheap and quiet.
     *
     * @param backend the owner's backend, which decides the pointer-warp convention and input timing: gamescope's
     *                Xwayland reads an absolute warp as window-relative, and getting that wrong puts every click
     *                at the wrong place rather than failing outright
     */
    public static AdoptedSession adopt(String displayName, String windowId, NestedSession.Backend backend) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        String display = displayName.trim();
        DisplayLink link = DisplayLink.open(display, backend);
        if (link == null || !link.alive()) {
            if (link != null) {
                link.close();
            }
            Diag.log("[Session] can't adopt " + display + ": it doesn't accept a connection (owner gone?)");
            return null;
        }
        AdoptedSession session = new AdoptedSession(display, link);
        GenericWindow window = session.windowById(windowId);
        if (window == null) {
            window = session.newestWindow();
        }
        if (window != null) {
            session.attach(window);
        }
        Diag.log("[Session] adopted the live " + backend + " display " + display + " — attached to "
            + (window == null ? "nothing yet" : "'" + window.getTitle() + "'"));
        return session;
    }

    @Override
    public Set<Capability> capabilities() {
        // A private display is a private display, whoever started it — so the guarantees a nested session makes
        // hold here too. WINDOW_LAUNCH is the one that doesn't: launching belongs to the owner.
        return EnumSet.of(
            Capability.ABSOLUTE_POINTER,
            Capability.RELATIVE_POINTER,
            Capability.BACKGROUND_CLICK,
            Capability.ISOLATED_FOCUS,
            Capability.MULTI_SESSION,
            Capability.SCREEN_CAPTURE,
            Capability.WINDOW_ATTACH);
    }

    @Override
    public Rectangle screen() {
        // Read off the display rather than remembered from a launch: the owner chose the size, and we were only
        // told which display to join.
        return link.screenSize();
    }

    @Override
    public SessionPointer pointer() {
        return pointer;
    }

    @Override
    public SessionKeyboard keyboard() {
        return keyboard;
    }

    @Override
    public void attach(GenericWindow window) {
        attachment.attach(window);
    }

    @Override
    public GenericWindow attached() {
        return closed ? attachment.current() : attachment.resolve();
    }

    /**
     * Does nothing but say so. The target is the owner's to start, and it is expected to be running already — this
     * is the class that exists precisely because a second launch would be handed to the first one by a
     * single-instance launcher and end up on a display nobody is watching.
     */
    @Override
    public void launch(LaunchSpec spec) {
        Diag.log("[Session] adopted " + displayName + ": not launching "
            + (spec == null ? "anything" : spec.describe()) + " — the session's owner already has it up");
    }

    @Override
    public BufferedImage capture() {
        GenericWindow target = attached();
        return target == null ? null : link.captureWindow(target);
    }

    /** The whole adopted screen — see {@link com.botmaker.session.DesktopSession#captureScreen()}. */
    @Override
    public BufferedImage captureScreen() {
        return link.captureScreen();
    }

    /** Straight through to the link — see {@link com.botmaker.session.remote.DisplayLink#previewJpeg}. */
    @Override
    public byte[] previewJpeg(int maxEdge, float quality) {
        return link.previewJpeg(maxEdge, quality);
    }

    @Override
    public SessionHealth health() {
        if (closed) {
            return SessionHealth.DEAD;
        }
        // We have no process handles here — the display answering a connection is the liveness we can observe, and
        // it is the one that matters: when the owner's server dies, this session is over.
        return link.alive() ? SessionHealth.HEALTHY : SessionHealth.DEAD;
    }

    @Override
    public NativeController controller() {
        return link;
    }

    /** The display this session joined, e.g. {@code ":1"}. */
    public String displayName() {
        return displayName;
    }

    /**
     * Drop our own two X connections. It does <b>not</b> touch the display server, the window manager, the private
     * bus or the game: they belong to whoever brought the session up, and are very likely still in use.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try { link.close(); } catch (Throwable t) { Diag.error("[Session] adopted close: " + t.getMessage()); }
        Diag.log("[Session] released the adopted display " + displayName + " (its owner keeps it)");
    }

    /** The X id of the window this session drives, or {@code 0} — the driven-window origin the input backends read. */
    private long attachedWindowId() {
        return WindowIds.of(attached());
    }

    /** The window {@code id} names (decimal, or {@code 0x…} hex) among the display's top-levels, or {@code null}. */
    private GenericWindow windowById(String id) {
        long wanted = parseWindowId(id);
        if (wanted == 0) {
            return null;
        }
        try {
            for (GenericWindow w : link.getAllWindows(true)) {
                if (WindowIds.of(w) == wanted) {
                    return w;
                }
            }
        } catch (Exception e) {
            Diag.log("[Session] adopted " + displayName + ": window scan failed: " + e.getMessage());
        }
        // Not an error: the owner told us what it had attached, and the launcher chain may have replaced it since.
        Diag.log("[Session] adopted " + displayName + ": window " + id + " is gone — taking the newest instead");
        return null;
    }

    /** The newest top-level on the display, or {@code null} when it has none yet. */
    private GenericWindow newestWindow() {
        GenericWindow newest = null;
        try {
            for (GenericWindow w : link.getAllWindows()) {
                newest = w;
            }
        } catch (Exception e) {
            Diag.log("[Session] adopted " + displayName + ": scan failed: " + e.getMessage());
        }
        return newest;
    }

    /** {@code 0} for anything that isn't a window id — the value no window has. */
    static long parseWindowId(String id) {
        return WindowIds.parse(id);
    }
}
