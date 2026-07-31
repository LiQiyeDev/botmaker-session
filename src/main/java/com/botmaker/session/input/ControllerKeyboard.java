package com.botmaker.session.input;

import com.botmaker.session.DesktopSession;
import com.botmaker.session.SessionKeyboard;
import com.botmaker.session.impl.HostSession;
import com.botmaker.session.impl.NestedSession;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;

import java.util.function.Supplier;

/**
 * A {@link SessionKeyboard} backed by a {@link NativeController} — shared by {@link HostSession} and
 * {@code NestedSession}. Input is routed to the session's currently-{@link DesktopSession#attached() attached}
 * window (via the window-targeted controller calls) when there is one, else to the focused window (the
 * window-less calls). The attached window is read through a {@link Supplier} each call, so the keyboard always
 * reflects the session's <em>current</em> target rather than whatever was attached when it was built.
 */
public final class ControllerKeyboard implements SessionKeyboard {

    private final NativeController controller;
    private final Supplier<GenericWindow> target;

    public ControllerKeyboard(NativeController controller, Supplier<GenericWindow> target) {
        this.controller = controller;
        this.target = target;
    }

    @Override
    public void keyDown(int nativeKeyCode) {
        GenericWindow w = target.get();
        if (w != null) controller.keyDown(w, nativeKeyCode); else controller.keyDown(nativeKeyCode);
    }

    @Override
    public void keyUp(int nativeKeyCode) {
        GenericWindow w = target.get();
        if (w != null) controller.keyUp(w, nativeKeyCode); else controller.keyUp(nativeKeyCode);
    }

    @Override
    public void type(String text) {
        GenericWindow w = target.get();
        if (w != null) controller.typeText(w, text); else controller.typeText(text);
    }
}
