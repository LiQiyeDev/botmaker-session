package com.botmaker.session.remote;

import com.botmaker.shared.capture.GenericWindow;

import com.sun.jna.Pointer;

/**
 * The one place that turns a {@link GenericWindow}'s opaque native handle into an X window id.
 *
 * <p>It exists because the handle now has <em>two</em> representations. In the {@link DisplayAgent} — the only
 * process that opens {@code :N} — it is a JNA {@link Pointer}, as it has always been. On the caller's side it
 * is a plain {@link Long} carried over the wire, because the whole point of moving the display connection out
 * of process is that this JVM holds no Xlib state at all. Every call site that used to cast to {@code Pointer}
 * goes through here instead, so neither representation leaks into code that shouldn't care.
 */
public final class WindowIds {

    private WindowIds() {
    }

    /** {@code window}'s X id, or {@code 0} when it has none — two windows without one are not the same window. */
    public static long of(GenericWindow window) {
        Object handle = window == null ? null : window.getNativeHandle();
        if (handle instanceof Pointer p) {
            return Pointer.nativeValue(p);
        }
        if (handle instanceof Number n) {
            return n.longValue();
        }
        return 0;
    }

    /** {@code 0} for anything that isn't a window id (decimal or {@code 0x…} hex) — the value no window has. */
    public static long parse(String id) {
        if (id == null || id.isBlank()) {
            return 0;
        }
        String s = id.trim();
        try {
            return s.toLowerCase(java.util.Locale.ROOT).startsWith("0x")
                ? Long.parseLong(s.substring(2), 16)
                : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
