package com.botmaker.session.remote;

import com.botmaker.session.Preview;
import com.botmaker.session.impl.NestedSession;

import com.botmaker.shared.capture.GenericWindow;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

/**
 * The child process that holds the {@code :N} connection, so the process that <em>asks</em> doesn't have to.
 *
 * <p>It is a plain {@code main} over a {@link LocalDisplay}: read one {@link AgentProtocol} request line from
 * stdin, serve it, write one response line and its payload to stdout, repeat until EOF. EOF means the parent
 * has gone, which is the agent's cue to exit — no supervision protocol, no orphan.
 *
 * <p><b>Dying is a feature here.</b> When the display server goes away, Xlib's default I/O handler calls
 * {@code exit(1)} in whatever process holds the connection. That used to be Studio (see {@link DisplayLink});
 * now it is this, whose entire job is that connection, and whose death the parent reads as a closed pipe.
 *
 * <p>stdout is the wire and nothing else may touch it: the very first thing {@link #run} does is repoint
 * {@link System#out} at stderr, so a stray {@code println} from anywhere below — including JNA's or a shared
 * {@code Diag} line — lands in the log the parent forwards instead of corrupting a frame.
 */
public final class DisplayAgent {

    /**
     * The argv marker identifying an agent invocation. It exists for the re-exec spawn form, where the child is
     * <em>this very program</em> started again ({@link DisplayAgentProcess}); a host application whose {@code main}
     * would otherwise boot a whole UI checks {@link #isAgentInvocation} first and hands over here.
     */
    public static final String ARG_MARKER = "--botmaker-display-agent";

    private final DisplayLink display;
    private final InputStream in;
    private final OutputStream out;
    /** The window the caller declared it is driving; the input backends that need an origin read it. */
    private volatile long drivenWindow;

    private DisplayAgent(DisplayLink display, InputStream in, OutputStream out) {
        this.display = display;
        this.in = in;
        this.out = out;
        display.setDrivenWindow(() -> drivenWindow);
    }

    /**
     * Whether {@code args} is an agent invocation — {@code --botmaker-display-agent <display> [backend]}. Host
     * applications that share a {@code main} with the agent call this before doing anything else.
     */
    public static boolean isAgentInvocation(String[] args) {
        return args != null && args.length >= 2 && ARG_MARKER.equals(args[0]);
    }

    /**
     * Serve the display named in {@code args} until stdin closes, then exit the JVM. Never returns — it is the
     * whole of this process's life, and a return would drop it back into a {@code main} that has other ideas.
     */
    public static void main(String[] args) {
        run(args);
    }

    /** {@link #main} as a callable, for a host {@code main} dispatching on {@link #isAgentInvocation}. */
    public static void run(String[] args) {
        // Before anything: stdout is the protocol. Everything that thinks it prints to the console goes to
        // stderr, which the parent forwards into its own log.
        PrintStream console = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true,
            StandardCharsets.UTF_8);
        System.setOut(console);
        String displayName = args[1];
        NestedSession.Backend backend = args.length >= 3
            ? NestedSession.Backend.fromId(args[2]).orElse(NestedSession.Backend.GAMESCOPE)
            : NestedSession.Backend.GAMESCOPE;
        LocalDisplay display = LocalDisplay.open(displayName, backend);
        if (display == null) {
            console.println("[Agent] cannot open " + displayName);
            System.exit(2);
        }
        int code = 0;
        try (InputStream in = new BufferedInputStream(System.in);
             OutputStream out = new BufferedOutputStream(new java.io.FileOutputStream(java.io.FileDescriptor.out))) {
            new DisplayAgent(display, in, out).serve();
        } catch (Exception e) {
            console.println("[Agent] " + displayName + ": " + e);
            code = 3;
        } finally {
            try { display.close(); } catch (Throwable ignored) { }
        }
        System.exit(code);
    }

    private void serve() throws IOException {
        String line;
        while ((line = readLine(in)) != null) {
            String[] request = AgentProtocol.split(line);
            String verb = AgentProtocol.field(request, 0, "");
            if ("bye".equals(verb)) {
                return;
            }
            try {
                dispatch(verb, request);
            } catch (Exception e) {
                // A verb that failed is not a reason to lose the stream — the caller degrades on ERR.
                respond(AgentProtocol.ERR + "\t" + AgentProtocol.escape(String.valueOf(e)), null);
            }
        }
    }

    private void dispatch(String verb, String[] a) throws IOException {
        switch (verb) {
            // --- enumeration ---
            case "windows" -> payload(AgentProtocol.encodeWindows(
                display.getAllWindows(AgentProtocol.asBool(a, 1))));
            case "children" -> payload(AgentProtocol.encodeWindows(display.getChildWindows(window(a, 1))));
            case "foreground" -> {
                GenericWindow w = display.getForegroundWindow();
                payload(w == null ? "" : AgentProtocol.encodeWindow(w) + "\n");
            }

            // --- capture ---
            case "capture" -> png(display.captureWindow(window(a, 1)));
            case "captureRoot" -> png(display.captureScreen());
            case "previewRoot" -> preview(AgentProtocol.asInt(a, 1, Preview.MAX_EDGE),
                AgentProtocol.asInt(a, 2, 60));

            // --- window management ---
            case "focus" -> ok(() -> display.focusWindow(window(a, 1)));
            case "move" -> ok(() -> display.moveWindow(window(a, 1), AgentProtocol.asInt(a, 2, 0),
                AgentProtocol.asInt(a, 3, 0)));
            case "resize" -> ok(() -> display.resizeWindow(window(a, 1), AgentProtocol.asInt(a, 2, 0),
                AgentProtocol.asInt(a, 3, 0)));
            case "restore" -> ok(() -> display.restoreWindow(window(a, 1)));
            case "promote" -> ok(() -> display.promoteOverlayAboveFullscreen(AgentProtocol.field(a, 1, "")));

            // --- input ---
            case "driven" -> ok(() -> drivenWindow = AgentProtocol.asLong(a, 1, 0));
            case "keyDown" -> ok(() -> display.keyDown(AgentProtocol.asInt(a, 1, 0)));
            case "keyUp" -> ok(() -> display.keyUp(AgentProtocol.asInt(a, 1, 0)));
            case "keyDownW" -> ok(() -> display.keyDown(window(a, 1), AgentProtocol.asInt(a, 2, 0)));
            case "keyUpW" -> ok(() -> display.keyUp(window(a, 1), AgentProtocol.asInt(a, 2, 0)));
            case "type" -> ok(() -> display.typeText(AgentProtocol.field(a, 1, "")));
            case "typeW" -> ok(() -> display.typeText(window(a, 1), AgentProtocol.field(a, 2, "")));
            case "mouseMove" -> ok(() -> display.mouseMove(AgentProtocol.asInt(a, 1, 0), AgentProtocol.asInt(a, 2, 0)));
            case "mouseRel" -> ok(() -> display.mouseMoveRelative(AgentProtocol.asInt(a, 1, 0),
                AgentProtocol.asInt(a, 2, 0)));
            case "button" -> ok(() -> display.mouseButton(AgentProtocol.asInt(a, 1, 1), AgentProtocol.asBool(a, 2)));
            case "scroll" -> ok(() -> display.scroll(AgentProtocol.asInt(a, 1, 0)));
            case "click" -> ok(() -> display.click(AgentProtocol.asInt(a, 1, 0), AgentProtocol.asInt(a, 2, 0),
                AgentProtocol.asInt(a, 3, 1)));
            case "clickRestore" -> ok(() -> display.clickRestoringCursor(AgentProtocol.asInt(a, 1, 0),
                AgentProtocol.asInt(a, 2, 0), AgentProtocol.asInt(a, 3, 1)));
            case "postClick" -> ok(() -> display.postLeftClick(window(a, 1), AgentProtocol.asInt(a, 2, 0),
                AgentProtocol.asInt(a, 3, 0)));
            case "cursor" -> {
                Point p = display.cursorPosition();
                respond(AgentProtocol.OK + "\t0" + (p == null ? "" : "\t" + p.x + "\t" + p.y), null);
            }

            // --- state ---
            case "bgInput" -> value(display.supportsBackgroundInput());
            case "reliable" -> value(display.useReliableInput());
            case "holdMs" -> value(display.pressHoldMs());
            case "viewable" -> value(display.windowViewable(AgentProtocol.asLong(a, 1, 0)));
            case "pid" -> value(display.windowPid(AgentProtocol.asLong(a, 1, 0)));
            case "hasWm" -> value(display.hasWindowManager());
            case "mapped" -> value(display.mappedCount());
            case "screen" -> {
                Rectangle r = display.screenSize();
                respond(AgentProtocol.OK + "\t0\t" + r.width + "\t" + r.height, null);
            }
            case "alive" -> value(display.alive());

            default -> respond(AgentProtocol.ERR + "\tunknown verb " + AgentProtocol.escape(verb), null);
        }
    }

    /** The window argument at {@code index}, resolved to a handle the underlying controller understands. */
    private GenericWindow window(String[] a, int index) {
        long id = AgentProtocol.asLong(a, index, 0);
        return id == 0 ? null : new GenericWindow(id, "", null);
    }

    private void ok(Runnable action) throws IOException {
        action.run();
        respond(AgentProtocol.OK + "\t0", null);
    }

    private void value(Object v) throws IOException {
        respond(AgentProtocol.OK + "\t0\t" + AgentProtocol.escape(String.valueOf(v)), null);
    }

    private void payload(String text) throws IOException {
        byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        respond(AgentProtocol.OK + "\t" + bytes.length, bytes);
    }

    /**
     * A root frame as a downscaled JPEG — the pilot's preview, encoded <b>here</b> rather than in the parent.
     *
     * <p>This is the whole point of the verb: {@code captureRoot} above must stay lossless for the vision
     * stack, so a preview taken through it paid a PNG encode here, a PNG decode there and a JPEG encode after
     * that, all on the caller's single frame thread and all while it held the link's request lock — the same
     * lock the pilot's Interact taps contend with. One encode, in this process, on a payload several times
     * smaller.
     *
     * <p>An empty payload means "no frame": either the grab failed or the root is entirely blank, which for a
     * session hosting a Wayland-only client it always is (see {@link Preview#isBlank}). Answering it from here
     * saves shipping a black JPEG the caller would only throw away.
     *
     * <p>{@code quality} arrives as a percentage because every protocol field is an integer; see
     * {@link AgentProtocol}.
     */
    private void preview(int maxEdge, int qualityPercent) throws IOException {
        BufferedImage root = display.captureScreen();
        byte[] bytes = Preview.isBlank(root) ? null : Preview.jpeg(root, maxEdge, qualityPercent / 100f);
        if (bytes == null || bytes.length == 0) {
            respond(AgentProtocol.OK + "\t0", null);
            return;
        }
        respond(AgentProtocol.OK + "\t" + bytes.length, bytes);
    }

    /** A frame as PNG — lossless, because these pixels are what the vision stack matches templates against. */
    private void png(BufferedImage image) throws IOException {
        if (image == null) {
            respond(AgentProtocol.OK + "\t0", null);
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 18);
        ImageIO.write(image, "png", buffer);
        byte[] bytes = buffer.toByteArray();
        respond(AgentProtocol.OK + "\t" + bytes.length, bytes);
    }

    private void respond(String header, byte[] payload) throws IOException {
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        if (payload != null && payload.length > 0) {
            out.write(payload);
        }
        out.flush();
    }

    /** Read one {@code \n}-terminated UTF-8 line, or {@code null} at EOF — the signal that the parent has gone. */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                return buffer.toString(StandardCharsets.UTF_8);
            }
            buffer.write(c);
        }
        return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8);
    }

    /** Read exactly {@code length} bytes — the payload half of the framing, used by {@link RemoteDisplay}. */
    static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] bytes = new byte[Math.max(0, length)];
        int read = 0;
        while (read < bytes.length) {
            int n = in.read(bytes, read, bytes.length - read);
            if (n < 0) {
                throw new IOException("stream ended " + (bytes.length - read) + " byte(s) short");
            }
            read += n;
        }
        return bytes;
    }
}
