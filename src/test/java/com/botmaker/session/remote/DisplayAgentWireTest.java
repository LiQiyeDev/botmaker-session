package com.botmaker.session.remote;

import com.botmaker.shared.capture.GenericWindow;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The out-of-process seam, run in one process: real requests in, {@link DisplayAgent#serve} in the middle, real
 * response lines out — with a recording stub in place of the {@code :N} connection.
 *
 * <p>This is the test the seam did not have. {@link RemoteDisplay} encodes a request by hand and the agent
 * decodes it by hand; nothing but agreement between two hand-written halves keeps a verb's arguments landing in
 * the right parameters, and a live session cannot tell you which half is wrong — a mismatched argument arrives
 * as a click at (0,0) or a window that "doesn't exist". Here the stub records exactly what the display was
 * asked to do.
 *
 * <p>It also pins the two paths a live run reaches only by accident: a verb whose display call <b>throws</b>
 * must come back as {@code ERR} with the stream still usable (the caller degrades, it does not lose the
 * session), and <b>EOF</b> must end {@code serve} rather than spin.
 */
class DisplayAgentWireTest {

    /** What the stub display was asked, in order: {@code methodName(arg, arg)}. */
    private final List<String> calls = new ArrayList<>();
    /** Per-method answers; anything unlisted gets the zero of its return type. */
    private final Map<String, Function<Object[], Object>> answers = new HashMap<>();
    /** Methods that throw when called, to drive the ERR path. */
    private final Map<String, RuntimeException> failures = new HashMap<>();

    /** Run {@code requests} through a real agent and return its response lines (payloads stripped). */
    private List<String> serve(String... requests) throws IOException {
        StringBuilder in = new StringBuilder();
        for (String request : requests) {
            in.append(request).append('\n');
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DisplayAgent(stubDisplay(), new ByteArrayInputStream(in.toString().getBytes(StandardCharsets.UTF_8)), out)
                .serve();
        return headers(out.toByteArray());
    }

    /**
     * Split the agent's output back into response headers, honouring the declared payload length — i.e. the
     * same framing {@link RemoteDisplay#call} uses, so a payload full of newlines cannot be read as headers.
     */
    private static List<String> headers(byte[] output) throws IOException {
        List<String> out = new ArrayList<>();
        ByteArrayInputStream in = new ByteArrayInputStream(output);
        String header;
        while ((header = DisplayAgent.readLine(in)) != null) {
            out.add(header);
            DisplayAgent.readFully(in, AgentProtocol.asInt(AgentProtocol.split(header), 1, 0));
        }
        return out;
    }

    /** The payload of the single response in {@code output}, as text. */
    private static String payload(byte[] output) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(output);
        String header = DisplayAgent.readLine(in);
        return new String(DisplayAgent.readFully(in, AgentProtocol.asInt(AgentProtocol.split(header), 1, 0)),
                StandardCharsets.UTF_8);
    }

    @Test
    void aStateVerbAnswersOkAndItsValue() throws IOException {
        answers.put("mappedCount", a -> 3);
        answers.put("hasWindowManager", a -> true);
        assertEquals(List.of("OK\t0\t3", "OK\t0\ttrue"), serve(AgentProtocol.join("mapped"), AgentProtocol.join("hasWm")));
    }

    @Test
    void screenAndCursorAnswerTheirFieldsInTheOrderTheCallerReadsThem() throws IOException {
        answers.put("screenSize", a -> new Rectangle(0, 0, 1080, 1920));
        answers.put("cursorPosition", a -> new Point(12, 34));
        // Field 1 is the payload length even for a fields-only answer, so the caller's field(n) is offset by
        // two — RemoteDisplay.Response.asInt(index) encodes that same +1. Order here is width, height.
        assertEquals(List.of("OK\t0\t1080\t1920", "OK\t0\t12\t34"),
                serve(AgentProtocol.join("screen"), AgentProtocol.join("cursor")));
    }

    @Test
    void aNullCursorIsAnAnswerRatherThanAnError() throws IOException {
        // RemoteDisplay reads "fewer than four fields" as "no position"; the agent must produce exactly that
        // rather than an ERR, because a display with no pointer yet is normal.
        assertEquals(List.of("OK\t0"), serve(AgentProtocol.join("cursor")));
    }

    @Test
    void inputVerbsCarryTheirArgumentsIntoTheRightParameters() throws IOException {
        serve(AgentProtocol.join("click", 640, 360, 3),
                AgentProtocol.join("mouseMove", -5, 7),
                AgentProtocol.join("button", 1, true),
                AgentProtocol.join("keyDown", 65),
                AgentProtocol.join("type", "hello\tworld"),
                AgentProtocol.join("scroll", -2));
        assertEquals(List.of(
                        "click(640, 360, 3)",
                        "mouseMove(-5, 7)",
                        "mouseButton(1, true)",
                        "keyDown(65)",
                        "typeText(hello\tworld)",
                        "scroll(-2)"),
                calls, "an argument landing in the wrong parameter is a click 2 fields off, not a crash");
    }

    @Test
    void aWindowArgumentArrivesAsAHandleAndZeroMeansNone() throws IOException {
        serve(AgentProtocol.join("focus", 0x1400007L),
                AgentProtocol.join("move", 0x1400007L, 10, 20),
                AgentProtocol.join("focus", 0));
        assertEquals(List.of("focusWindow(20971527)", "moveWindow(20971527, 10, 20)", "focusWindow(null)"), calls);
    }

    @Test
    void theDrivenWindowIsRememberedForTheInputBackendsThatNeedAnOrigin() throws IOException {
        assertEquals(List.of("OK\t0"), serve(AgentProtocol.join("driven", 0x99L)));
        // It is state on the agent, not a call through to the display — nothing to record but the OK.
        assertTrue(calls.isEmpty(), calls.toString());
    }

    @Test
    void aWindowListRidesInThePayloadWithItsLengthDeclared() throws IOException {
        answers.put("getAllWindows", a -> List.of(
                new GenericWindow(1L, "Firestone\tv2", new Rectangle(0, 0, 800, 600)),
                new GenericWindow(2L, "tray", new Rectangle(4, 4, 16, 16))));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DisplayAgent(stubDisplay(),
                new ByteArrayInputStream((AgentProtocol.join("windows", true) + "\n").getBytes(StandardCharsets.UTF_8)),
                out).serve();

        assertEquals(List.of("getAllWindows(true)"), calls, "the include-hidden flag must not be lost");
        List<GenericWindow> back = AgentProtocol.decodeWindows(payload(out.toByteArray()));
        assertEquals(2, back.size());
        assertEquals("Firestone\tv2", back.get(0).getTitle(), "a tabbed title survives the payload framing");
        assertEquals(new Rectangle(4, 4, 16, 16), back.get(1).getRect());
    }

    @Test
    void aVerbThatThrowsBecomesErrAndTheStreamSurvives() throws IOException {
        failures.put("focusWindow", new IllegalStateException("BadWindow"));
        answers.put("mappedCount", a -> 5);
        List<String> responses = serve(AgentProtocol.join("focus", 7L), AgentProtocol.join("mapped"));

        assertEquals(2, responses.size(), "one failed verb must not cost the session its link");
        assertTrue(responses.get(0).startsWith(AgentProtocol.ERR + "\t"), responses.get(0));
        assertTrue(responses.get(0).contains("BadWindow"), responses.get(0));
        assertFalse(responses.get(0).contains("\n"), "the error text is escaped into one line");
        assertEquals("OK\t0\t5", responses.get(1), "the next request is answered normally");
    }

    @Test
    void anUnknownVerbIsAnErrRatherThanSilence() throws IOException {
        // A caller from a newer build asking for a verb this agent has never heard of must get an answer:
        // silence would leave it blocked on a read that never completes, holding the request lock.
        List<String> responses = serve(AgentProtocol.join("teleport", 1), AgentProtocol.join("alive"));
        assertTrue(responses.get(0).startsWith(AgentProtocol.ERR + "\t"), responses.get(0));
        assertTrue(responses.get(0).contains("teleport"), responses.get(0));
        assertEquals("OK\t0\tfalse", responses.get(1));
    }

    @Test
    void byeEndsTheExchangeAndAnythingAfterItIsNotServed() throws IOException {
        assertEquals(List.of("OK\t0\t0"),
                serve(AgentProtocol.join("mapped"), AgentProtocol.join("bye"), AgentProtocol.join("mapped")));
    }

    @Test
    void eofEndsTheExchangeWithoutAResponse() throws IOException {
        // The parent going away is the agent's cue to exit — there is no supervision protocol, so serve()
        // returning on EOF is the whole of it. A blank line before EOF must not be mistaken for a verb.
        assertTrue(serve().isEmpty());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DisplayAgent(stubDisplay(), new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)), out).serve();
        assertTrue(headers(out.toByteArray()).get(0).startsWith(AgentProtocol.ERR), "an empty verb is unknown, not fatal");
        assertNull(DisplayAgent.readLine(new ByteArrayInputStream(new byte[0])));
    }

    /**
     * A {@link DisplayLink} that records rather than connects. A proxy rather than a hand-written stub because
     * the interface extends {@code NativeController} — some forty methods, of which a wire test cares about
     * six, and thirty-four empty overrides would bury the four lines that matter.
     */
    private DisplayLink stubDisplay() {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("setDrivenWindow") || name.equals("close")) {
                return null;
            }
            calls.add(name + "(" + describe(args) + ")");
            RuntimeException failure = failures.get(name);
            if (failure != null) {
                throw failure;
            }
            Function<Object[], Object> answer = answers.get(name);
            return answer != null ? answer.apply(args) : zeroOf(method.getReturnType());
        };
        return (DisplayLink) Proxy.newProxyInstance(DisplayLink.class.getClassLoader(),
                new Class<?>[]{DisplayLink.class}, handler);
    }

    private static String describe(Object[] args) {
        if (args == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Object arg : args) {
            if (out.length() > 0) {
                out.append(", ");
            }
            // A window is recorded by id: that is the only part of it that crossed the wire.
            out.append(arg instanceof GenericWindow w ? String.valueOf(WindowIds.of(w)) : String.valueOf(arg));
        }
        return out.toString();
    }

    private static Object zeroOf(Class<?> type) {
        if (!type.isPrimitive()) {
            return List.class.isAssignableFrom(type) ? List.of() : null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }
}
