package com.botmaker.session;

/**
 * A {@link DesktopSession}'s liveness. A host session is always {@link #HEALTHY} (it rides the user's own
 * desktop); a nested supervisor (Phase 2) reports {@link #DEGRADED} when part of its tree (WM, app) died but
 * the display survives, and {@link #DEAD} when the display itself is gone — the signal its chaos-recovery loop
 * watches to rebuild.
 */
public enum SessionHealth {

	/** Everything the session needs is up. */
	HEALTHY,

	/** The display is alive but part of the tree (window manager, target app) died — recoverable. */
	DEGRADED,

	/** The display/session is gone; it must be rebuilt from scratch. */
	DEAD
}
