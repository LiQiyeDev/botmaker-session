package com.botmaker.session;

/**
 * Process-wide holder for the {@link DesktopSession} a bot (or the pilot) is currently driving — the
 * SDK-reachable twin of Studio's {@code PilotSession}. When set, the SDK's input facades
 * ({@code Mouse}/{@code Keyboard}) route their controller through it and the ambient capture
 * {@code Source} follows the session's owned window, so a bot drives its private nested {@code :N} display
 * exactly as it would drive {@code :0} — with no call-site change.
 *
 * <p>Deliberately a tiny mutable singleton (the one kind this codebase keeps, like
 * {@code NativeControllerFactory}): the bot runtime is a graph of static facades with no object to thread a
 * session through, so registration has to be ambient. {@code null} means "no session" — the default, which
 * keeps every non-isolated bot on today's global {@code :0} behaviour byte-for-byte.
 *
 * <p>This holder does <b>not</b> own the session's lifecycle: whoever {@link #set(DesktopSession) set} it
 * (the SDK's isolated-launch bootstrap, or a test) is responsible for {@link DesktopSession#close() closing}
 * the session and {@link #clear() clearing} the holder. Clearing does not close, and closing does not clear —
 * they are separate concerns joined only by the caller.
 */
public final class ActiveSession {

    private static volatile DesktopSession current;

    private ActiveSession() {}

    /** Register {@code session} as the active one, or {@code null} to detach (equivalent to {@link #clear()}). */
    public static void set(DesktopSession session) {
        current = session;
    }

    /** The active session, or {@code null} when none is registered (the default — today's {@code :0} behaviour). */
    public static DesktopSession get() {
        return current;
    }

    /** Whether a session is currently registered. */
    public static boolean isActive() {
        return current != null;
    }

    /** Detach any active session. Does <b>not</b> close it — that is the setter's responsibility. */
    public static void clear() {
        current = null;
    }
}
