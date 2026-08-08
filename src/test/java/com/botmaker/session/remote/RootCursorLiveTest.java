package com.botmaker.session.remote;

import com.botmaker.session.impl.NestedSession;

import com.botmaker.shared.capture.linux.X11;
import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * That the root-cursor bindings actually bind — the one thing {@link LocalDisplay}'s own call site cannot tell
 * you.
 *
 * <p>Setting the cursor is cosmetic, so it is wrapped in a {@code catch (Throwable)}: a session must never fail
 * to start over the shape of a pointer. That swallow is right, and it is also exactly what would hide a
 * mistyped JNA signature — an {@code UnsatisfiedLinkError} on {@code XCreateFontCursor} looks identical, from
 * the outside, to a session that quietly kept the black cross. So the guard belongs here instead: call the
 * bindings directly against a real {@code :N} and let a link error be a failure rather than a log line.
 *
 * <p>It asserts the calls <em>work</em>, not that the pointer looks like an arrow. X11 has no way to read a
 * window's cursor back — {@code XDefineCursor} has no {@code XGetWindowCursor} — so the visual half is a human
 * looking at a Xephyr window, and the machine-checkable half is this.
 */
class RootCursorLiveTest {

    @Test
    void theCursorBindingsResolveAndTheServerAcceptsThem() throws Exception {
        assumeTrue(Boolean.getBoolean("botmaker.live"), "opt-in live test — run with -Dbotmaker.live=true");
        String display = System.getenv("DISPLAY");
        assumeTrue(display != null && !display.isBlank(), "needs a DISPLAY");
        assumeTrue(onPath("Xephyr"), "needs Xephyr on PATH");

        NestedSession session = NestedSession.start(
                NestedSession.Options.xephyr(640, 480).withoutWindowManager());
        try {
            Pointer x = X11.INSTANCE.XOpenDisplay(session.displayName());
            assertNotNull(x, "should be able to open " + session.displayName());
            try {
                Pointer cursor = X11.INSTANCE.XCreateFontCursor(x, X11.XC_left_ptr);
                assertNotNull(cursor, "XCreateFontCursor returned nothing for XC_left_ptr");
                assertNotEquals(Pointer.NULL, cursor, "XC_left_ptr should be a real cursor XID");
                X11.INSTANCE.XDefineCursor(x, X11.INSTANCE.XDefaultRootWindow(x), cursor);
                X11.INSTANCE.XFreeCursor(x, cursor);
                // Round-trips the connection: a bad request on any call above surfaces as an X error here
                // rather than being buffered away unnoticed.
                X11.INSTANCE.XSync(x, false);
            } finally {
                X11.INSTANCE.XCloseDisplay(x);
            }
        } finally {
            session.close();
        }
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, exe).canExecute()) {
                return true;
            }
        }
        return false;
    }
}
