package com.botmaker.session;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The process-wide active-session holder: set / get / isActive / clear, defaulting to none. */
class ActiveSessionTest {

    @AfterEach
    void tearDown() {
        ActiveSession.clear();
    }

    @Test
    void defaultsToNoSession() {
        assertNull(ActiveSession.get());
        assertFalse(ActiveSession.isActive());
    }

    @Test
    void setThenGetReturnsTheSession() {
        StubSession session = new StubSession();
        ActiveSession.set(session);
        assertSame(session, ActiveSession.get());
        assertTrue(ActiveSession.isActive());
    }

    @Test
    void clearDetachesWithoutClosing() {
        StubSession session = new StubSession();
        ActiveSession.set(session);
        ActiveSession.clear();
        assertNull(ActiveSession.get());
        assertFalse(ActiveSession.isActive());
        // clear() is detach-only — it must not have closed the session (that's the setter's job).
        assertFalse(session.closed, "clear() must not close the session");
    }

    /** A do-nothing {@link DesktopSession} that only records whether it was closed — enough for the holder tests. */
    private static final class StubSession implements DesktopSession {
        boolean closed;

        @Override public Set<Capability> capabilities() { return EnumSet.noneOf(Capability.class); }
        @Override public Rectangle screen() { return new Rectangle(); }
        @Override public SessionPointer pointer() { return null; }
        @Override public SessionKeyboard keyboard() { return null; }
        @Override public void attach(GenericWindow window) { }
        @Override public GenericWindow attached() { return null; }
        @Override public void launch(LaunchSpec spec) { }
        @Override public BufferedImage capture() { return null; }
        @Override public NativeController controller() { return null; }
        @Override public void close() { closed = true; }
    }
}
