package com.botmaker.session;

import java.awt.Rectangle;

/**
 * An encoded preview frame together with <b>the surface it is a picture of</b> — the two halves of one fact,
 * returned as one value so they cannot disagree.
 *
 * <p>They used to be computed apart: the session encoded a frame and the caller tagged it with
 * {@link DesktopSession#screen()}, on the assumption that a session's preview is always its whole root. Under
 * gamescope that assumption is false in the worst way — the root is never painted at all (its compositor
 * redirects every client), so the frame that actually has pixels is a <em>window</em>, at a rect that is only
 * the screen's by coincidence of {@code --force-windows-fullscreen}. A windowed client would then have had
 * every Interact tap mapped through the wrong rect, silently.
 *
 * @param jpeg    the frame, already downscaled and JPEG-encoded; never null and never empty
 * @param surface the absolute rect on {@code :N} that this frame's pixel (0,0) maps to — what the pilot tags
 *                the frame with and what a gesture is clamped to
 */
public record PreviewFrame(byte[] jpeg, Rectangle surface) {
}
