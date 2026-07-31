package com.botmaker.session;

import com.botmaker.session.impl.HostSession;

/**
 * Thrown when a nested session cannot be brought up — the X server never reported a display, a display never
 * became connectable, or a required step failed. Distinct from a bot-logic error: the caller catches this to
 * fall back to a {@link HostSession} (or to report "background input unavailable on this machine") rather than
 * to abort a run. The partially-started tree is always reaped before this is thrown.
 */
public final class SessionStartException extends Exception {

    public SessionStartException(String message) {
        super(message);
    }

    public SessionStartException(String message, Throwable cause) {
        super(message, cause);
    }
}
