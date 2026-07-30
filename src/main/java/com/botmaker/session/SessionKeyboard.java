package com.botmaker.session;

/**
 * The keyboard of one {@link DesktopSession}. Keys are per-OS native codes (X keysym on Linux, virtual-key
 * code on Windows) — the same currency {@link com.botmaker.shared.capture.NativeController} speaks, so callers
 * keep resolving them from the SDK's platform-neutral {@code Key} the way they do today.
 */
public interface SessionKeyboard {

	/** Press a key by its native code. */
	void keyDown(int nativeKeyCode);

	/** Release a key by its native code. */
	void keyUp(int nativeKeyCode);

	/** Type a string, pressing/releasing each character (Shift held for uppercase). */
	void type(String text);
}
