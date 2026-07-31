package com.botmaker.session.input;

import com.botmaker.session.SessionPointer;
import com.botmaker.session.impl.HostSession;
import com.botmaker.session.impl.NestedSession;

import com.botmaker.shared.capture.NativeController;

import java.awt.Point;

/**
 * A {@link SessionPointer} backed by a {@link NativeController} — the one pointer implementation both a
 * {@link HostSession} (over {@code :0}) and a {@code NestedSession} (over {@code :N}) use. What differs between
 * the two is only <em>which</em> controller it wraps: on {@code :N} the controller's global cursor is the bot's
 * alone, so the very same device-level motion that is intrusive on {@code :0} is flawless in the background.
 *
 * <p>{@link #moveRelative} delegates to {@link NativeController#mouseMoveRelative}: a device-level backend
 * (XTest on {@code :N}) injects a true relative motion event that survives a game's pointer grab/warp
 * (mouselook); other backends fall back to reading the position and warping by the delta.
 */
public final class ControllerPointer implements SessionPointer {

    private final NativeController controller;

    public ControllerPointer(NativeController controller) {
        this.controller = controller;
    }

    @Override
    public void moveAbsolute(int x, int y) {
        controller.mouseMove(x, y);
    }

    @Override
    public void moveRelative(int dx, int dy) {
        controller.mouseMoveRelative(dx, dy);
    }

    @Override
    public void button(int button, boolean press) {
        controller.mouseButton(button, press);
    }

    /**
     * Press and release with the backend's own hold in between, instead of the interface's back-to-back pair.
     * A press shorter than a frame (~16 ms at 60 fps) can be sampled away entirely by a game that reads input
     * once per frame, which is the "the click registered as a hover" symptom.
     *
     * <p>It deliberately does <em>not</em> route through {@link NativeController#click}: this gesture is a
     * click <em>where the pointer already is</em>, so re-deriving a coordinate from
     * {@link NativeController#cursorPosition()} only to warp back to it would add a round trip through the
     * warp path for no gain.
     */
    @Override
    public void click(int button) {
        controller.mouseButton(button, true);
        try {
            Thread.sleep(controller.pressHoldMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        controller.mouseButton(button, false);
    }

    @Override
    public void scroll(int amount) {
        controller.scroll(amount);
    }

    @Override
    public Point position() {
        return controller.cursorPosition();
    }
}
