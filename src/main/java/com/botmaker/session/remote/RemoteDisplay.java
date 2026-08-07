package com.botmaker.session.remote;

import com.botmaker.session.impl.NestedSession;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

/**
 * A {@link DisplayLink} that answers by asking a {@link DisplayAgent} in another process — the implementation
 * that makes a dying {@code :N} survivable. See {@link DisplayLink} for why that matters.
 *
 * <p><b>Degrade, never throw.</b> The moment a round trip fails — the agent exited because Xlib exited it, the
 * pipe broke, the response didn't parse — this link marks itself {@link #dead} and every later call returns the
 * empty answer for its type. That is deliberate: the display really is gone, and the caller already has a path
 * for that ({@code health()} → {@link NestedSession#closeIfDead()}). Throwing would only relocate the crash.
 *
 * <p><b>The driven window is resolved outside the lock.</b> A session's driven-window supplier resolves its
 * attachment, which calls back into this link to enumerate windows; evaluating it inside the request lock would
 * deadlock on the first input call. So {@link #syncDrivenWindow()} runs before the lock is taken, and only
 * sends a {@code driven} request when the answer actually changed.
 */
public final class RemoteDisplay implements DisplayLink {

    private final String displayName;
    private final DisplayAgentProcess agent;
    private final OutputStream requests;
    private final InputStream responses;
    /** One round trip at a time: the protocol is strictly serial and the streams are not shareable. */
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Supplier<Long> drivenWindow;
    /** The id last pushed to the agent, so an unchanged driven window costs no round trip. */
    private volatile long sentDrivenWindow = -1;
    private volatile boolean dead;
    private volatile boolean closed;

    private RemoteDisplay(String displayName, DisplayAgentProcess agent) {
        this.displayName = displayName;
        this.agent = agent;
        this.requests = agent.process().getOutputStream();
        this.responses = new BufferedInputStream(agent.process().getInputStream());
    }

    /** Start an agent for {@code displayName} and connect to it, or {@code null} when one couldn't be started. */
    static RemoteDisplay open(String displayName, NestedSession.Backend backend) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        DisplayAgentProcess agent = DisplayAgentProcess.start(displayName.trim(), backend);
        if (agent == null) {
            return null;
        }
        RemoteDisplay link = new RemoteDisplay(displayName.trim(), agent);
        // One real round trip before anyone depends on this: a spawn form that starts but can't open :N would
        // otherwise be discovered on the first capture, minutes into a launch.
        if (!link.alive()) {
            Diag.error("[Session] the display agent for " + displayName + " started but cannot serve "
                + displayName + (agent.log() == null ? "" : " — see " + agent.log().getAbsolutePath()));
            link.close();
            return null;
        }
        return link;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    // --- the extra :N reads ---

    @Override
    public BufferedImage captureScreen() {
        return image(call("captureRoot"));
    }

    @Override
    public Rectangle screenSize() {
        Response r = call("screen");
        return r == null ? new Rectangle() : new Rectangle(0, 0, r.asInt(1, 0), r.asInt(2, 0));
    }

    @Override
    public boolean windowViewable(long windowId) {
        if (windowId == 0) {
            return false;
        }
        Response r = call("viewable", windowId);
        // A link that can't answer must not invent a death: an attachment is only ever replaced on a real one.
        return r == null || r.asBool(1);
    }

    @Override
    public long windowPid(long windowId) {
        Response r = call("pid", windowId);
        return r == null ? 0 : r.asLong(1, 0);
    }

    @Override
    public boolean hasWindowManager() {
        Response r = call("hasWm");
        return r != null && r.asBool(1);
    }

    @Override
    public int mappedCount() {
        Response r = call("mapped");
        return r == null ? -1 : r.asInt(1, -1);
    }

    @Override
    public boolean alive() {
        if (closed || dead || !agent.alive()) {
            return false;
        }
        Response r = call("alive");
        return r != null && r.asBool(1);
    }

    @Override
    public void setDrivenWindow(Supplier<Long> windowId) {
        this.drivenWindow = windowId;
        this.sentDrivenWindow = -1;
    }

    // --- NativeController ---

    @Override
    public GenericWindow getForegroundWindow() {
        Response r = call("foreground");
        List<GenericWindow> windows = r == null ? List.of() : AgentProtocol.decodeWindows(r.text());
        return windows.isEmpty() ? null : windows.get(0);
    }

    @Override
    public List<GenericWindow> getChildWindows(GenericWindow parent) {
        Response r = call("children", WindowIds.of(parent));
        return r == null ? List.of() : AgentProtocol.decodeWindows(r.text());
    }

    @Override
    public List<GenericWindow> getAllWindows() {
        return getAllWindows(false);
    }

    @Override
    public List<GenericWindow> getAllWindows(boolean includeMinimized) {
        Response r = call("windows", includeMinimized);
        return r == null ? List.of() : AgentProtocol.decodeWindows(r.text());
    }

    @Override
    public void restoreWindow(GenericWindow window) {
        call("restore", WindowIds.of(window));
    }

    @Override
    public BufferedImage captureWindow(GenericWindow window) {
        long id = WindowIds.of(window);
        return id == 0 ? null : image(call("capture", id));
    }

    @Override
    public void promoteOverlayAboveFullscreen(String windowTitle) {
        call("promote", windowTitle);
    }

    @Override
    public void postLeftClick(GenericWindow window, int relativeX, int relativeY) {
        call("postClick", WindowIds.of(window), relativeX, relativeY);
    }

    @Override
    public boolean supportsBackgroundInput() {
        Response r = call("bgInput");
        return r != null && r.asBool(1);
    }

    @Override
    public boolean useReliableInput() {
        Response r = call("reliable");
        return r != null && r.asBool(1);
    }

    @Override
    public void focusWindow(GenericWindow window) {
        call("focus", WindowIds.of(window));
    }

    @Override
    public void moveWindow(GenericWindow window, int x, int y) {
        call("move", WindowIds.of(window), x, y);
    }

    @Override
    public void resizeWindow(GenericWindow window, int width, int height) {
        call("resize", WindowIds.of(window), width, height);
    }

    @Override
    public void keyDown(int nativeKeyCode) {
        call("keyDown", nativeKeyCode);
    }

    @Override
    public void keyUp(int nativeKeyCode) {
        call("keyUp", nativeKeyCode);
    }

    @Override
    public void typeText(String text) {
        call("type", text);
    }

    @Override
    public void keyDown(GenericWindow window, int nativeKeyCode) {
        call("keyDownW", WindowIds.of(window), nativeKeyCode);
    }

    @Override
    public void keyUp(GenericWindow window, int nativeKeyCode) {
        call("keyUpW", WindowIds.of(window), nativeKeyCode);
    }

    @Override
    public void typeText(GenericWindow window, String text) {
        call("typeW", WindowIds.of(window), text);
    }

    @Override
    public void mouseMove(int xAbs, int yAbs) {
        call("mouseMove", xAbs, yAbs);
    }

    @Override
    public void mouseMoveRelative(int dx, int dy) {
        call("mouseRel", dx, dy);
    }

    @Override
    public void mouseButton(int button, boolean press) {
        call("button", button, press);
    }

    @Override
    public void scroll(int amount) {
        call("scroll", amount);
    }

    @Override
    public Point cursorPosition() {
        Response r = call("cursor");
        if (r == null || r.fields.length < 4) {   // OK, length, x, y — a null position sends only the first two
            return null;
        }
        return new Point(r.asInt(1, 0), r.asInt(2, 0));
    }

    @Override
    public void click(int xAbs, int yAbs, int button) {
        // One verb rather than the interface's default move/press/hold/release: four round trips would put the
        // pipe's latency *inside* the click timing the backends tune.
        call("click", xAbs, yAbs, button);
    }

    @Override
    public void clickRestoringCursor(int xAbs, int yAbs, int button) {
        call("clickRestore", xAbs, yAbs, button);
    }

    @Override
    public int pressHoldMs() {
        Response r = call("holdMs");
        return r == null ? CLICK_HOLD_MS : r.asInt(1, CLICK_HOLD_MS);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        lock.lock();
        try {
            if (!dead) {
                try {
                    requests.write((AgentProtocol.join("bye") + "\n").getBytes(StandardCharsets.UTF_8));
                    requests.flush();
                } catch (IOException ignored) {
                    // Already gone — the destroy below is the only step that still matters.
                }
            }
        } finally {
            lock.unlock();
        }
        agent.close();
        Diag.log("[Session] display agent for " + displayName + " stopped");
    }

    // --- the round trip ---

    /**
     * Send {@code verb} with {@code args} and read one response, or {@code null} when this link is (now) dead.
     * Every public method above funnels through here, which is what makes "degrade, never throw" one decision
     * rather than forty.
     */
    private Response call(String verb, Object... args) {
        if (closed || dead) {
            return null;
        }
        syncDrivenWindow();
        return request(verb, args);
    }

    private Response request(String verb, Object... args) {
        lock.lock();
        try {
            if (dead) {
                return null;
            }
            Object[] line = new Object[args.length + 1];
            line[0] = verb;
            System.arraycopy(args, 0, line, 1, args.length);
            requests.write((AgentProtocol.join(line) + "\n").getBytes(StandardCharsets.UTF_8));
            requests.flush();

            String header = DisplayAgent.readLine(responses);
            if (header == null) {
                die("the agent closed its output — " + displayName + " is gone");
                return null;
            }
            String[] fields = AgentProtocol.split(header);
            if (!AgentProtocol.OK.equals(AgentProtocol.field(fields, 0, ""))) {
                // A refused verb is the agent working correctly; it says so and the caller degrades.
                Diag.log("[Session] " + displayName + ": " + verb + " → " + AgentProtocol.field(fields, 1, "error"));
                return null;
            }
            int length = AgentProtocol.asInt(fields, 1, 0);
            byte[] payload = length > 0 ? DisplayAgent.readFully(responses, length) : new byte[0];
            return new Response(fields, payload);
        } catch (IOException e) {
            die("the link to " + displayName + " broke: " + e.getMessage());
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Push the driven window to the agent when it has changed. Runs <b>outside</b> the request lock on purpose:
     * the supplier resolves the session's attachment, which enumerates windows through this very link.
     */
    private void syncDrivenWindow() {
        Supplier<Long> supplier = drivenWindow;
        if (supplier == null) {
            return;
        }
        long id;
        try {
            Long value = supplier.get();
            id = value == null ? 0 : value;
        } catch (Exception e) {
            return;
        }
        if (id != sentDrivenWindow) {
            sentDrivenWindow = id;
            request("driven", id);
        }
    }

    private void die(String reason) {
        if (dead) {
            return;
        }
        dead = true;
        Diag.error("[Session] " + reason
            + (agent.log() == null ? "" : " (agent output: " + agent.log().getAbsolutePath() + ")"));
    }

    private static BufferedImage image(Response response) {
        if (response == null || response.payload.length == 0) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(response.payload));
        } catch (IOException e) {
            return null;
        }
    }

    /** One response: the header's fields (index 0 is {@code OK}, 1 the payload length) plus the payload. */
    private record Response(String[] fields, byte[] payload) {

        String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }

        int asInt(int index, int fallback) {
            return AgentProtocol.asInt(fields, index + 1, fallback);
        }

        long asLong(int index, long fallback) {
            return AgentProtocol.asLong(fields, index + 1, fallback);
        }

        boolean asBool(int index) {
            return AgentProtocol.asBool(fields, index + 1);
        }
    }
}
