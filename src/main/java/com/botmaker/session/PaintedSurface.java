package com.botmaker.session;

import java.awt.Rectangle;

/**
 * <b>Which drawable on a display actually has pixels on it</b>, and where it sits — the choice
 * {@link com.botmaker.session.remote.DisplayLink#previewFrame} makes per frame, named so the video path can
 * make the <em>same</em> one without encoding a JPEG to find out.
 *
 * <p>{@code windowId} is {@code 0} for the display's root. That is not a "none" value: it is the surface a
 * non-compositing backend (Xephyr) paints, and the one every grab used to assume. A non-zero id is the
 * gamescope case, where {@code steamcompmgr} redirects every client to its own Wayland output and the X root
 * pixmap is never painted at all — there the pixels exist only inside a client window, and an {@code ffmpeg}
 * pointed at the root encodes black for as long as it runs (measured: a frame mean of 0.04 against 28619 out
 * of 65535 for the same display grabbed with {@code -window_id}).
 *
 * <p>The two fields travel together for the reason {@link PreviewFrame} records: {@code rect} is what the
 * pilot's client fits its canvas to and maps taps through, and it is only {@code screen()} when the surface
 * happens to be the root or a forced-fullscreen client. Deriving one from the other is the bug.
 */
public record PaintedSurface(long windowId, Rectangle rect) {

    /** Whether this is the display's root rather than a client window. */
    public boolean isRoot() {
        return windowId == 0;
    }
}
