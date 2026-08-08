package com.botmaker.session;

import com.botmaker.session.impl.HostSession;
import com.botmaker.session.impl.NestedSession;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.launch.LaunchSpec;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Set;

/**
 * One display a bot drives — the seam that lets the <em>same</em> bot code target either the user's real
 * desktop or a private nested {@code :N} server without knowing which. It <b>wraps</b> the existing
 * {@link NativeController} + input-backend stack rather than replacing it: a {@link HostSession} wraps the
 * default {@code :0} controller (today's behaviour, unchanged); a future {@code NestedSession} (Phase 2) wraps
 * a controller bound to {@code :N} and adds the {@link Capability#BACKGROUND_CLICK}/{@link Capability#ISOLATED_FOCUS}
 * guarantees the host session can't make.
 *
 * <p>A session either {@link #attach(GenericWindow) attaches} to an existing window or {@link #launch launches}
 * a fresh target into itself; either way the attached window is where {@link #capture()} and the
 * window-targeted input paths point. Closing a session releases its resources (and, for a nested one, reaps
 * the whole process tree).
 */
public interface DesktopSession extends AutoCloseable {

    /** What this session can actually do — check before relying on a behaviour. */
    Set<Capability> capabilities();

    /** Whether this session advertises {@code capability}. */
    default boolean has(Capability capability) {
        return capabilities().contains(capability);
    }

    /** The session's screen bounds (origin + size), or a zero rectangle if it can't be determined. */
    Rectangle screen();

    /** This session's pointer. */
    SessionPointer pointer();

    /** This session's keyboard. */
    SessionKeyboard keyboard();

    /**
     * Make {@code window} the session's active target — the window {@link #capture()} reads and the
     * window-targeted input paths address. A host session attaches to any window the OS enumerates; a nested
     * session attaches to a window it launched into {@code :N}.
     */
    void attach(GenericWindow window);

    /** The currently-attached target window, or {@code null} if none. */
    GenericWindow attached();

    /**
     * Launch {@code spec} into this session and (best-effort) attach to the window it produces. A host session
     * launches onto the user's desktop exactly as {@code Launcher.start} does today; a nested session launches
     * into its private {@code :N} (stopping any {@code :0} instance first).
     */
    void launch(LaunchSpec spec);

    /** A pixel frame of the {@link #attached() attached} window, or {@code null} if none can be produced. */
    BufferedImage capture();

    /**
     * A pixel frame of the whole session <em>screen</em> at {@link #screen()} — not of one window.
     *
     * <p>The difference matters for a launcher chain: {@link #capture()} follows the attachment, so while Heroic
     * is still up and the game's window has not arrived (or has replaced it), the attachment can be stale or
     * absent and the frame {@code null}. The screen has no such dependency — whatever is on the display is in
     * it. A private session hosting one fullscreen client is the common case, where the two are the same pixels.
     *
     * <p>The default is {@link #capture()}, so a session with no notion of a screen of its own (the host
     * desktop) keeps answering exactly as it does today.
     */
    default BufferedImage captureScreen() {
        return capture();
    }

    /**
     * Whether this session's pixels can be read off an <b>X11</b> root at all.
     *
     * <p>A session that hosts a compositor of its own (gamescope with {@code --expose-wayland}) can be running a
     * <em>Wayland-only</em> client — Waydroid's {@code show-full-ui} has no X11 path whatsoever — whose surface
     * never reaches the embedded Xwayland. {@link #captureScreen()} then succeeds and hands back a perfectly
     * valid frame of an empty root: black pixels, no error, nothing to distinguish it from a game that happens
     * to be on a black screen. Consumers that pick a capture source (the pilot's route resolver, the SDK's
     * ambient {@link com.botmaker.session.ActiveSession} source) need to know that <em>before</em> they commit
     * to the session, which is why this is a question the session answers rather than a probe each of them
     * rebuilds — and rebuilds differently.
     *
     * <p>The default is {@code true}: the host desktop and an Xephyr session are X11 all the way down.
     */
    default boolean x11Capturable() {
        return true;
    }

    /** The session's liveness — a nested supervisor reports {@code DEGRADED}/{@code DEAD} for chaos recovery. */
    default SessionHealth health() {
        return SessionHealth.HEALTHY;
    }

    /**
     * The underlying controller this session wraps. This is the migration bridge: today the SDK's
     * {@code Mouse}/{@code Keyboard} facades and the pilot's input service both hold a {@link NativeController}
     * directly; routing them through a session means handing them <em>this</em> controller instead of the
     * global {@code NativeControllerFactory} singleton. Prefer {@link #pointer()}/{@link #keyboard()} for new
     * code.
     */
    NativeController controller();

    @Override
    void close();
}
