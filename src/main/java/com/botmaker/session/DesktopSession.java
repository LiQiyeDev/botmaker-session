package com.botmaker.session;

import com.botmaker.session.impl.HostSession;
import com.botmaker.session.impl.NestedSession;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.session.video.VideoPacket;
import com.botmaker.session.video.VideoStream;

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
     * A <b>lossy, already-encoded</b> preview of this session — JPEG bytes downscaled to {@code maxEdge} on the
     * long side, <em>and the rect they are a picture of</em> — or {@code null} when this session cannot produce
     * one that way.
     *
     * <p>It is not a convenience over {@code captureScreen()}: the point is that the encode can happen where
     * the pixels already are. A nested session's display lives in another process ({@code DisplayAgent}), so the
     * bytes that reach a phone used to be a PNG encode, a pipe, a PNG decode and a JPEG encode — three codec
     * passes for a preview, two of them on the caller's frame thread while it held the link's request lock.
     * A session that can answer this replaces all three with one, in the process that holds the display.
     *
     * <p><b>The rect is not always {@link #screen()}</b>, which is why it is returned rather than assumed — see
     * {@link PreviewFrame}. A compositing backend never paints its X root, so the frame with pixels on it is a
     * window, and a caller that tagged it with the screen's origin would misplace every Interact tap on a
     * client that is not fullscreen.
     *
     * <p>{@code null} is the honest answer for a session that has no such shortcut, and every caller must have
     * the full-frame path anyway (the {@code :0} desktop and an emulator are not encoded remotely). It is
     * <b>never</b> the frame the vision stack matches against — that stays lossless; see {@link Preview}.
     */
    default PreviewFrame previewFrame(int maxEdge, float quality) {
        return null;
    }

    /**
     * A live <b>H.264 encode</b> of this session's screen, pushing access units to {@code sink} — or
     * {@code null} when this session cannot produce one.
     *
     * <p>It is the same argument as {@link #previewFrame}, one step further. That one moved the encode to where
     * the pixels are; this one stops re-encoding the picture at all. A session screen is mostly static between
     * frames, and an inter-coded stream sends the difference: a few kilobytes where a JPEG sends a few hundred,
     * on hardware where there is hardware. What it costs is a decoder on the client, which is why every caller
     * keeps the JPEG path and treats {@code null} — no encoder, no {@code ffmpeg}, a session with no X root to
     * grab — as "use it".
     *
     * <p>The stream opens <em>asynchronously</em>; see {@link VideoStream#alive()}. It belongs to this session
     * and is torn down with it, so a caller that forgets to {@link VideoStream#close()} leaks nothing past
     * {@link #close()}.
     *
     * @param maxEdge the long edge the encode is downscaled to; the surface rect callers tag frames with is
     *                {@link VideoStream#surface()} regardless of it, exactly as on the JPEG path
     */
    default VideoStream openVideoStream(int maxEdge, int fps, java.util.function.Consumer<VideoPacket> sink) {
        return null;
    }

    /**
     * The rect a {@link #openVideoStream} on this session <em>would</em> encode right now, or {@code null}
     * when nothing on it is painted.
     *
     * <p>A stream is pointed at one drawable when it opens and cannot be re-aimed, so this is how a caller
     * notices that the drawable it is streaming is no longer the one with the pixels — a launcher chain
     * swapping its window for the game's, a client resizing. Cheap enough to ask once a frame (implementations
     * memoise), and answering {@link #screen()} is right for any session whose root is what gets grabbed.
     */
    default Rectangle videoSurface() {
        return screen();
    }

    /**
     * Whether this session's pixels can be read off an <b>X11</b> root at all.
     *
     * <p>A session that hosts a compositor of its own (gamescope) can be running a <em>Wayland-only</em> client
     * — Waydroid's {@code show-full-ui} has no X11 path whatsoever — whose surface never reaches the embedded
     * Xwayland. Every X11 read then succeeds and hands back a display with nothing on it: black pixels, no
     * error, nothing to distinguish it from a game that happens to be on a black screen. Consumers that pick a
     * capture source (the pilot's route resolver, the SDK's
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
