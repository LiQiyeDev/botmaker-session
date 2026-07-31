package com.botmaker.session.display;

import com.botmaker.session.Capability;
import com.botmaker.session.SessionHealth;
import com.botmaker.session.impl.NestedSession;
import com.botmaker.session.impl.SessionHostWindow;

/**
 * A private display a nested session owns — the X server the bot's {@link NestedSession} drives so its global
 * pointer and focus are the bot's alone. Two implementations sit behind this seam: {@link NestedDisplay}
 * (Xephyr, the cheap 2D host) and {@link GamescopeDisplay} (gamescope, the hardware-3D host with an embedded
 * Xwayland). {@link NestedSession} depends only on this narrow surface, so the same supervisor — launch the
 * game, find its window, inject XTest, reap the tree — drives both backends unchanged.
 *
 * <p>The one thing that genuinely differs between the two, beyond how the server is spawned, is 3D capability:
 * {@link #hardwareAccelerated()} is what makes a session advertise {@link Capability#HARDWARE_GL}/
 * {@link Capability#VULKAN}. Everything else a session needs is the display name (to bind a controller and set
 * children's {@code DISPLAY}), its size, and whether the server is still alive.
 */
public interface SessionDisplay {

    /** The display this server owns, e.g. {@code ":9"} — bound by the controller and set in every child's env. */
    public String displayName();

    /** The display width in pixels. */
    public int width();

    /** The display height in pixels. */
    public int height();

    /** Whether the server process is still running — the signal a session's {@link SessionHealth#DEAD} rests on. */
    public boolean alive();

    /**
     * The pid of the process this session launched to get the server up — under the systemd strategy that is the
     * {@code systemd-run --scope} wrapper rather than the server itself, so treat it as the <em>root</em> of the
     * server's tree, not the server. {@link SessionHostWindow} uses it to find the output window the server maps
     * on the host desktop (matching {@code _NET_WM_PID} against the pid or any of its descendants).
     */
    public long serverPid();

    /**
     * Whether games in this display get a real GPU (vs. a software rasteriser). Xephyr is 2D-only here;
     * gamescope carries hardware GL/Vulkan. Drives {@link Capability#HARDWARE_GL}/{@link Capability#VULKAN}.
     */
    public boolean hardwareAccelerated();
}
