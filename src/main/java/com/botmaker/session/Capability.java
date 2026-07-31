package com.botmaker.session;

/**
 * What a {@link DesktopSession} can actually do, so a bot can <b>fail fast</b> instead of silently no-op'ing.
 *
 * <p>The whole reason this enum exists: the same "click the game" call has completely different guarantees on a
 * host session (moves the real cursor, forces the window forward) versus a nested one (a private pointer on
 * {@code :N}, truly in the background). Rather than let a bot discover that at runtime by watching its clicks do
 * nothing useful, a session advertises its capabilities up front and the caller checks
 * {@link DesktopSession#has(Capability)} before relying on one.
 */
public enum Capability {

    /** Pointer can be moved to an absolute screen coordinate ({@link SessionPointer#moveAbsolute}). */
    ABSOLUTE_POINTER,

    /** Pointer can be moved by a relative delta ({@link SessionPointer#moveRelative}) — what mouselook reads. */
    RELATIVE_POINTER,

    /**
     * Clicks land on the target while it stays in the background <em>and</em> games accept them as hardware.
     * A host session deliberately does <b>not</b> advertise this: its only background-safe path is
     * {@code XSendEvent}, which Wine/Proton/SDL drop, so a reliable click there means moving the real cursor
     * and foregrounding the window. Only a nested {@code :N} session, whose global pointer is the bot's alone,
     * can offer it.
     */
    BACKGROUND_CLICK,

    /** Input focus is isolated from the user's desktop — driving this session never steals the user's focus. */
    ISOLATED_FOCUS,

    /** Multiple independent sessions can run at once without cross-talk (distinct displays/pointers). */
    MULTI_SESSION,

    /** Hardware-accelerated OpenGL is available in the session (vs. a software rasterizer). */
    HARDWARE_GL,

    /** A working Vulkan device is available in the session. */
    VULKAN,

    /** The session can produce a pixel frame of its target ({@link DesktopSession#capture()}). */
    SCREEN_CAPTURE,

    /** The session can launch a fresh target into itself ({@link DesktopSession#launch}). */
    WINDOW_LAUNCH,

    /** The session can attach to an already-existing window ({@link DesktopSession#attach}). */
    WINDOW_ATTACH
}
