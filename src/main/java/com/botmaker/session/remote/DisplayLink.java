package com.botmaker.session.remote;

import com.botmaker.session.impl.NestedSession;

import com.botmaker.session.Preview;
import com.botmaker.session.PreviewFrame;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.function.Supplier;

/**
 * Everything a session does to its private {@code :N} display, behind one seam — so that <em>where</em> the
 * Xlib connection lives becomes an implementation detail.
 *
 * <p><b>Why this exists.</b> A nested session used to hold two open {@code Display*} handles to {@code :N}
 * inside the caller's own JVM: the {@code LinuxController}'s and a second one for EWMH reads. When the display
 * server died — a user closing the game's window is enough — Xlib took an <em>I/O</em> error on those handles,
 * and Xlib's default I/O handler calls {@code exit(1)} <b>in the calling process</b>. There is no exception to
 * catch and no {@code hs_err} to read: Studio simply vanished mid-frame, taking the user's unsaved work with
 * it. Trapping it in-process is not really available either — {@code XSetIOErrorHandler}'s handler is not
 * allowed to return, so the best an in-process trap can do is choose how to die.
 *
 * <p>So the connection moves out of process. {@link RemoteDisplay} runs a {@link DisplayAgent} child that holds
 * the {@code :N} handles and answers over a pipe; when {@code :N} dies it is the <em>agent</em> that Xlib exits,
 * and the proxy simply reads EOF and reports the display gone through the path that already existed
 * ({@link NestedSession#closeIfDead()}). {@link LocalDisplay} keeps the old in-process behaviour for the cases
 * that want it — the live tests, and a fallback when an agent can't be spawned at all.
 *
 * <p>Beyond {@link NativeController} it adds the reads a session used to make against its own EWMH connection
 * ({@link #windowViewable}, {@link #windowPid}, {@link #hasWindowManager}, {@link #mappedCount},
 * {@link #screenSize}) plus {@link #captureScreen()}, the display's root — the frame that does not depend on
 * which window a launcher chain happens to be showing.
 *
 * <p><b>Nothing here throws for a dead display.</b> Every method degrades: {@code null}, {@code 0}, an empty
 * list, a no-op. Liveness is asked for explicitly, via {@link #alive()}.
 */
public interface DisplayLink extends NativeController, AutoCloseable {

    /**
     * System property forcing the in-process {@link LocalDisplay} instead of an out-of-process agent. Set it to
     * {@code true} to bisect an agent problem — accepting that a dying {@code :N} then takes this JVM with it.
     */
    String LOCAL_PROPERTY = "botmaker.session.display.local";

    /** The display this link drives, e.g. {@code ":1"}. */
    String displayName();

    /**
     * A frame of the display's <b>root</b> window, at {@link #screenSize()}, or {@code null} when none can be
     * produced. Unlike {@link #captureWindow}, this does not depend on an attachment: a store launcher swapping
     * its own window for the game's is invisible here, which is exactly why the pilot streams it.
     */
    BufferedImage captureScreen();

    /**
     * <b>The frame on this display that actually has pixels</b>, downscaled to {@code maxEdge} and JPEG-encoded,
     * together with the rect it covers — or {@code null} when there is nothing to send.
     *
     * <p>It exists so a preview can be produced <em>where the pixels already are</em>. Over an out-of-process
     * link the default below is the wrong shape: it decodes a PNG this side of the pipe only to re-encode it,
     * which is three codec passes and a payload several times larger than it needs to be, all inside the lock
     * that input calls queue behind. {@link RemoteDisplay} therefore overrides it with a verb the agent serves —
     * and the agent serves it by calling <em>this very default</em>, so the choice below is made once for both.
     *
     * <p><b>Why it is not simply the root.</b> A session backend that composites — gamescope, whose built-in
     * {@code steamcompmgr} redirects every client window and paints it to its Wayland output — never paints the
     * X root pixmap at all. Grabbing the root there returns a perfectly valid, permanently black frame, measured
     * as 0 of 8160 sampled pixels while a fullscreen game was mapped on the same display. Xephyr has no such
     * compositor and its root is the whole picture. So the choice is made on <em>content</em> rather than on a
     * backend flag: take the root, and if it is {@link com.botmaker.session.Preview#isBlank blank}, take the
     * largest window instead. That needs no knowledge of which backend is running, it self-heals if gamescope
     * ever paints its root, and — unlike keying on the session's <em>attached</em> window — it still finds a
     * picture in the seconds a launcher chain has swapped one window out and not yet mapped the next.
     */
    default PreviewFrame previewFrame(int maxEdge, float quality) {
        BufferedImage root = captureScreen();
        // The blank test belongs on both sides of the seam or the contract differs by implementation: an
        // in-process link would answer a black JPEG where the agent answers "no frame".
        if (!Preview.isBlank(root)) {
            return encoded(root, screenSize(), maxEdge, quality);
        }
        GenericWindow window = largestWindow();
        return window == null ? null : encoded(captureWindow(window), window.getRect(), maxEdge, quality);
    }

    /**
     * The biggest window this display will show us, or {@code null} when it has none. Biggest by area rather
     * than the foreground one: a session's game is fullscreen and its launcher's leftovers (a tray icon, an
     * input-method window — both 16&nbsp;px squares on a live {@code :1}) are not, and asking for the foreground
     * costs an EWMH round trip to answer a question area already answers.
     */
    private GenericWindow largestWindow() {
        GenericWindow best = null;
        long bestArea = 0;
        for (GenericWindow w : getAllWindows(false)) {
            Rectangle r = w == null ? null : w.getRect();
            if (r == null) {
                continue;
            }
            long area = (long) r.width * r.height;
            if (area > bestArea) {
                bestArea = area;
                best = w;
            }
        }
        return best;
    }

    /** {@code img} as a {@link PreviewFrame} over {@code surface}, or {@code null} if either is unusable. */
    private static PreviewFrame encoded(BufferedImage img, Rectangle surface, int maxEdge, float quality) {
        if (Preview.isBlank(img) || surface == null || surface.isEmpty()) {
            return null;
        }
        byte[] jpeg = Preview.jpeg(img, maxEdge, quality);
        return jpeg == null || jpeg.length == 0 ? null : new PreviewFrame(jpeg, surface);
    }

    /** The display's size as a rectangle at the origin, or a zero rectangle when it can't be read. */
    Rectangle screenSize();

    /** Whether the window still exists and is mapped. {@code true} when it can't be probed — never invent a death. */
    boolean windowViewable(long windowId);

    /** The window's {@code _NET_WM_PID}, or {@code 0} when it has none. */
    long windowPid(long windowId);

    /** Whether a window manager has claimed this display (EWMH {@code _NET_SUPPORTING_WM_CHECK}). */
    boolean hasWindowManager();

    /** How many windows on this display count as content, or {@code -1} when the display couldn't be asked. */
    int mappedCount();

    /** Whether the display still answers. This is the liveness a session's health check reads. */
    boolean alive();

    /**
     * Declare which window this link is driving — the coordinate origin the input backends that need one use.
     * The supplier is re-read on each use because a launcher chain swaps the window out from under us; a
     * {@code null} supplier, or one answering {@code 0}, means "no particular window".
     *
     * <p>Implementations must evaluate it <em>outside</em> any lock they hold: the session's supplier resolves
     * the attachment, which itself calls back into this link.
     */
    void setDrivenWindow(Supplier<Long> windowId);

    @Override
    void close();

    /**
     * Open a link to {@code displayName}: an out-of-process {@link RemoteDisplay} normally, the in-process
     * {@link LocalDisplay} when {@link #LOCAL_PROPERTY} asks for it or when no agent could be started.
     *
     * @param backend the display's backend, which fixes the pointer-warp convention and input timing
     * @return a usable link, or {@code null} when even the local fallback could not open the display
     */
    static DisplayLink open(String displayName, NestedSession.Backend backend) {
        if (Boolean.getBoolean(LOCAL_PROPERTY)) {
            return LocalDisplay.open(displayName, backend);
        }
        DisplayLink remote = RemoteDisplay.open(displayName, backend);
        if (remote != null) {
            return remote;
        }
        Diag.error("[Session] " + displayName + ": no display agent could be started — falling back to an"
            + " in-process connection. A display that dies will now take this process with it.");
        return LocalDisplay.open(displayName, backend);
    }
}
