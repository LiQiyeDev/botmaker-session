package com.botmaker.session;

import java.awt.Point;

/**
 * The pointer of one {@link DesktopSession}. Exposes <b>both</b> absolute and relative motion from day one on
 * purpose: mouselook / FPS-camera targets read pointer <em>deltas</em> (a warp-to-centre loop), so a
 * session that only ever offered {@code moveAbsolute} would force a later retrofit through every call site.
 *
 * <p>Which real pointer this drives depends on the session: a host session moves the user's shared cursor; a
 * nested {@code :N} session moves that display's private pointer, invisibly to the user.
 */
public interface SessionPointer {

    /** Move to an absolute screen coordinate within this session's {@link DesktopSession#screen()}. */
    void moveAbsolute(int x, int y);

    /**
     * Move by a relative delta from the current position. The day-one implementation reads the current
     * position and warps to {@code pos + (dx, dy)}; Phase 4 replaces this with true relative injection for
     * grab/warp (mouselook) modes where reading the position is unreliable.
     */
    void moveRelative(int dx, int dy);

    /** Press ({@code true}) or release ({@code false}) a button — 1=left, 2=middle, 3=right. */
    void button(int button, boolean press);

    /** Scroll: {@code +} = up/away, {@code -} = down/toward. */
    void scroll(int amount);

    /** Press then release {@code button} at the current position. */
    default void click(int button) {
        button(button, true);
        button(button, false);
    }

    /** The pointer's current absolute position, or {@code null} if it can't be read. */
    Point position();
}
