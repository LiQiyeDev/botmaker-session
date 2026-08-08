package com.botmaker.session.remote;

import com.botmaker.shared.capture.GenericWindow;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The framing and escaping of the agent wire, in isolation — the encode/decode pairs that
 * {@link RemoteDisplay} and {@link DisplayAgent} each half of. {@link DisplayAgentWireTest} runs the two
 * halves against each other; this one pins the primitives they both build on.
 *
 * <p>What is actually at stake is desynchronisation. Fields are tab-separated and a response is one line plus
 * a declared payload length, so a single unescaped tab in an X window title does not corrupt one field — it
 * shifts every field after it, and the reader then parses a payload length out of a window's geometry.
 */
class AgentProtocolTest {

    /** The title X gives you is arbitrary bytes; all three separators can and do appear in one. */
    private static final String HOSTILE = "Fire\tstone\nv2\r\\Chapter \\t 3";

    @Test
    void everySeparatorSurvivesARoundTripThroughOneField() {
        assertEquals(HOSTILE, AgentProtocol.unescape(AgentProtocol.escape(HOSTILE)));
        // The point of escaping: the encoded form contains none of the characters the framing reads.
        String escaped = AgentProtocol.escape(HOSTILE);
        assertFalse(escaped.contains("\t"), escaped);
        assertFalse(escaped.contains("\n"), escaped);
        assertFalse(escaped.contains("\r"), escaped);
    }

    @Test
    void aHostileFieldDoesNotShiftTheFieldsAfterIt() {
        String[] fields = AgentProtocol.split(AgentProtocol.join("windows", HOSTILE, 12, 34));
        assertEquals(4, fields.length, "a tab inside the title must not create a fifth field");
        assertEquals(HOSTILE, fields[1]);
        assertEquals(12, AgentProtocol.asInt(fields, 2, -1));
        assertEquals(34, AgentProtocol.asInt(fields, 3, -1));
    }

    @Test
    void emptyFieldsKeepTheirPositions() {
        // split(-1) rather than the default: a trailing empty field is a field, and dropping it renumbers
        // nothing on the request side but silently shortens a response the caller indexes by position.
        assertArrayEquals(new String[]{"type", "", ""}, AgentProtocol.split(AgentProtocol.join("type", null, "")));
        assertEquals(0, AgentProtocol.split("").length);
        assertEquals(0, AgentProtocol.split(null).length);
    }

    @Test
    void aTruncatedEscapeIsDroppedRatherThanThrowing() {
        // A line cut mid-escape is a broken agent, not a reason to take the caller down with it.
        assertEquals("abc", AgentProtocol.unescape("abc\\"));
        assertEquals("", AgentProtocol.unescape(null));
        assertEquals("", AgentProtocol.escape(null));
    }

    @Test
    void everyAccessorHasATotalFallback() {
        String[] fields = {"OK", "notanumber"};
        assertEquals("OK", AgentProtocol.field(fields, 0, "?"));
        assertEquals("?", AgentProtocol.field(fields, 9, "?"), "past the end");
        assertEquals("?", AgentProtocol.field(fields, -1, "?"), "before the start");
        assertEquals(-1, AgentProtocol.asLong(fields, 1, -1), "unparseable");
        assertEquals(-1, AgentProtocol.asInt(fields, 9, -1), "missing");
        assertFalse(AgentProtocol.asBool(fields, 9));
        assertTrue(AgentProtocol.asBool(new String[]{"OK", "true"}, 1));
        assertFalse(AgentProtocol.asBool(new String[]{"OK", "TRUE"}, 1), "only the literal lowercase is true");
    }

    @Test
    void aWindowRoundTripsIncludingItsHostileTitle() {
        GenericWindow sent = new GenericWindow(0x1400007L, HOSTILE, new Rectangle(10, 20, 1080, 1920));
        GenericWindow back = AgentProtocol.decodeWindow(AgentProtocol.encodeWindow(sent));
        assertEquals(0x1400007L, WindowIds.of(back));
        assertEquals(HOSTILE, back.getTitle());
        assertEquals(new Rectangle(10, 20, 1080, 1920), back.getRect());
    }

    @Test
    void aWindowWithNoRectEncodesAsZerosRatherThanFailing() {
        GenericWindow back = AgentProtocol.decodeWindow(
                AgentProtocol.encodeWindow(new GenericWindow(7L, "", null)));
        assertEquals(new Rectangle(0, 0, 0, 0), back.getRect());
        assertNull(AgentProtocol.decodeWindow(AgentProtocol.encodeWindow(null)), "a null window names no id");
    }

    @Test
    void aWindowListRoundTripsAndUnparseableLinesAreSkipped() {
        List<GenericWindow> sent = List.of(
                new GenericWindow(1L, "a\tb", new Rectangle(0, 0, 800, 600)),
                new GenericWindow(2L, "second", new Rectangle(1, 2, 3, 4)));
        String payload = AgentProtocol.encodeWindows(sent);
        assertEquals(2, payload.lines().count(), "one window per line");

        // A blank line and an id-less line are dropped: half a list beats no list, because the caller's
        // fallback for an empty answer is "the display has no windows" — an invented death.
        List<GenericWindow> back = AgentProtocol.decodeWindows(payload + "\n" + AgentProtocol.join(0, "ghost") + "\n");
        assertEquals(2, back.size());
        assertEquals("a\tb", back.get(0).getTitle());
        assertEquals(2L, WindowIds.of(back.get(1)));

        assertTrue(AgentProtocol.decodeWindows("").isEmpty());
        assertTrue(AgentProtocol.decodeWindows(null).isEmpty());
        assertTrue(AgentProtocol.encodeWindows(null).isEmpty());
    }

    // --- framing ---

    @Test
    void readLineStopsAtTheNewlineAndReportsEofAsNull() throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream("first\nsecond\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("first", DisplayAgent.readLine(in));
        assertEquals("second", DisplayAgent.readLine(in));
        assertNull(DisplayAgent.readLine(in), "EOF is null — that is how both sides learn the peer has gone");
    }

    @Test
    void anUnterminatedLastLineIsStillDelivered() throws IOException {
        // The agent is killed between writing a header and its newline often enough to matter: returning null
        // here would lose a response the caller is already blocked on.
        assertEquals("OK\t0", DisplayAgent.readLine(new ByteArrayInputStream("OK\t0".getBytes(StandardCharsets.UTF_8))));
        assertNull(DisplayAgent.readLine(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void readFullyRefusesToInventAShortPayload() throws IOException {
        byte[] four = {1, 2, 3, 4};
        assertArrayEquals(four, DisplayAgent.readFully(new ByteArrayInputStream(four), 4));
        assertEquals(0, DisplayAgent.readFully(new ByteArrayInputStream(four), 0).length);
        assertEquals(0, DisplayAgent.readFully(new ByteArrayInputStream(four), -1).length);
        // A truncated payload must throw, not return a half-read frame: the alternative is a stream left
        // mid-payload, where the next response header is parsed out of image bytes.
        IOException e = assertThrows(IOException.class,
                () -> DisplayAgent.readFully(new ByteArrayInputStream(four), 8));
        assertTrue(e.getMessage().contains("short"), e.getMessage());
    }
}
