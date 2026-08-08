package com.botmaker.session.process;

import com.botmaker.shared.launch.ProcessOrigin;

/**
 * The roles a nested session launches — and, with them, the systemd unit names those roles get.
 *
 * <p>Two things were spelled by hand before. The <b>role token</b> ({@code "xephyr"}, {@code "wm"}, …) was a
 * bare string at each call site, so nothing connected the token {@link SessionReaper} records to the one
 * {@code NestedSession} later excludes when it asks for "everything except the payload" — a mismatch there
 * leaves the display server in the list of processes to shut down, which is precisely the ordering the
 * shutdown exists to prevent. The <b>{@code botmaker-sess-} prefix</b> was rebuilt seven times inside
 * {@link SessionReaper} alone, plus once more inside a regex, while
 * {@link ProcessOrigin#SESSION_UNIT_PREFIX} — the reader that parses it back out of a cgroup path — already
 * owned it in shared.
 *
 * <p>So the prefix is taken from {@code ProcessOrigin} rather than redeclared: producer and reader are in
 * different modules and shared is the only one both can see.
 */
public enum SessionUnit {

    /** The Xephyr X server of a 2D session. */
    XEPHYR("xephyr"),
    /** The gamescope compositor of a hardware-accelerated session, Xwayland included. */
    GAMESCOPE("gamescope"),
    /** The window manager Xephyr needs for EWMH and focus; never present on gamescope. */
    WM("wm"),
    /** The session's private {@code dbus-daemon}, and with it its own Flatpak portal. */
    DBUS("dbus"),
    /**
     * The {@code ffmpeg} encoding the session's screen for a remote viewer. Infrastructure, not payload: it
     * grabs the display and must go down with it, and a session that has no viewer never launches one.
     */
    VIDEO("video"),
    /**
     * The payload — the game or launcher the session was started for. The one role that is <em>not</em>
     * infrastructure, which is what {@link SessionReaper#unitNamesExcept} is asked to exclude.
     */
    APP("app");

    private final String role;

    SessionUnit(String role) {
        this.role = role;
    }

    /** The role token as it appears in a unit name and in the session's log lines. */
    public String role() {
        return role;
    }

    /** {@code botmaker-sess-<sessionId>-<role>} — the transient unit this role runs as. */
    public String unitName(String sessionId) {
        return ProcessOrigin.SESSION_UNIT_PREFIX + sessionId + "-" + role;
    }

    /** The same unit with systemd's {@code .scope} suffix, as {@code systemctl stop} wants it. */
    public String scopeName(String sessionId) {
        return unitName(sessionId) + ".scope";
    }

    /** {@code botmaker-sess-<sessionId>.slice} — the group every role of one session is launched into. */
    public static String sliceName(String sessionId) {
        return ProcessOrigin.SESSION_UNIT_PREFIX + sessionId + ".slice";
    }

    /** A {@code systemctl list-units} pattern matching every unit of one session, whatever its type. */
    public static String unitGlob(String sessionId) {
        return ProcessOrigin.SESSION_UNIT_PREFIX + sessionId + "*";
    }

    /** A {@code systemctl list-units} pattern matching every session slice on the machine. */
    public static String sliceGlob() {
        return ProcessOrigin.SESSION_UNIT_PREFIX + "*.slice";
    }

    /** The throwaway unit name of the "does {@code systemd-run --user --scope} work here?" probe. */
    public static String probeUnitName(long pid) {
        return ProcessOrigin.SESSION_UNIT_PREFIX + "probe-" + pid;
    }

    /** Whether {@code unitName} is one of ours at all — the filter on a {@code list-units} line. */
    public static boolean isSessionUnit(String unitName) {
        return unitName != null && unitName.startsWith(ProcessOrigin.SESSION_UNIT_PREFIX);
    }

    /** {@code unitName} without the {@code botmaker-sess-} prefix, or unchanged when it doesn't carry one. */
    public static String stripPrefix(String unitName) {
        return isSessionUnit(unitName)
            ? unitName.substring(ProcessOrigin.SESSION_UNIT_PREFIX.length())
            : unitName;
    }
}
