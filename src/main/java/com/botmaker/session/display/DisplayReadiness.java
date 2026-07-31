package com.botmaker.session.display;

import com.botmaker.session.SessionStartException;

import com.botmaker.shared.capture.linux.X11;
import com.sun.jna.Pointer;

/**
 * The readiness gate both nested-display backends share: block until a freshly-spawned X server actually
 * accepts a connection, rather than sleeping a fixed guess. Extracted so {@link NestedDisplay} (Xephyr) and
 * {@link GamescopeDisplay} (gamescope) use one implementation — the "wait on the real thing, never a
 * {@code sleep}" rule is the same regardless of which server wrote the display number.
 */
public final class DisplayReadiness {

    private static final long POLL_MS = 100;

    private DisplayReadiness() {}

    /**
     * Block until {@code XOpenDisplay(display)} succeeds — the moment the server is ready to be driven.
     *
     * @param display  the display to connect to, e.g. {@code ":9"}
     * @param server   the server process; if it dies before accepting, we fail fast rather than wait out the clock
     * @param timeoutMs how long to keep retrying before giving up
     * @throws SessionStartException if the server dies first, or never accepts within {@code timeoutMs}
     */
    public static void awaitConnectable(String display, Process server, long timeoutMs) throws SessionStartException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Pointer d = null;
            try {
                d = X11.INSTANCE.XOpenDisplay(display);
            } catch (Throwable ignored) {
                // X11 not linkable / transient — treated the same as "not ready yet".
            }
            if (d != null) {
                X11.INSTANCE.XCloseDisplay(d);
                return;
            }
            if (!server.isAlive()) {
                throw new SessionStartException("display server on " + display
                    + " died before it accepted connections");
            }
            sleep();
        }
        throw new SessionStartException("nested display " + display + " never accepted a connection within "
            + timeoutMs + "ms");
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
