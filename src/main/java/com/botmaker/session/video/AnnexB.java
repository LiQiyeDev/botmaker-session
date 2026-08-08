package com.botmaker.session.video;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/**
 * Cuts a raw H.264 Annex-B byte stream into {@link VideoPacket access units}. Pure and stateful-but-local: no
 * process, no threads, no I/O — feed it whatever came off the pipe, in whatever sizes it came off in, and it
 * emits one packet per coded picture.
 *
 * <p>It exists as its own type because it is the one part of the video path that can be tested without a GPU,
 * an X server or an {@code ffmpeg} binary. Everything else in {@link FfmpegVideoStream} is process plumbing.
 *
 * <h2>Where an access unit ends</h2>
 *
 * <p>The general rule in the spec (7.4.1.2.3) needs the slice header parsed to find {@code first_mb_in_slice},
 * because a picture may be split across several slice NALs. This class does not parse slice headers and does
 * not need to: the encoder is <em>ours</em> ({@link FfmpegVideoStream} pins one slice per picture and no
 * B-frames), so a second VCL NAL is unambiguously the next picture. The boundary rules are therefore:
 *
 * <ul>
 *   <li>a VCL NAL (types 1–5) when the unit already holds one — the next picture has started;</li>
 *   <li>an access-unit delimiter (9), SPS (7) or PPS (8) when the unit already holds a VCL NAL — the
 *       parameter sets that precede an IDR belong to <em>that</em> IDR's unit, not the previous picture's.</li>
 * </ul>
 *
 * <p>A unit with no VCL NAL in it is never emitted; trailing parameter sets simply wait for the slice they
 * describe. That is also why {@link #feed} can be called with a single byte at a time without changing the
 * output — a start code straddling two chunks is held back until it is whole.
 *
 * <h2>Emitting as early as the boundary is known</h2>
 *
 * <p>A picture is complete once the <em>type byte</em> of the next NAL has arrived — one byte past a start
 * code — not once that whole NAL has. The distinction is a frame of latency: waiting for the next NAL to be
 * delimited in turn means the viewer is always looking at the picture before the newest one, and that the last
 * picture of a still scene sits here until the scene moves again. So the boundary is evaluated on the type
 * byte, and deferred ({@link #boundaryPending}) only for as long as that single byte has not arrived.
 */
public final class AnnexB {

    /** NAL types, by the 5 low bits of a NAL's first byte. */
    private static final int NAL_IDR = 5;
    private static final int NAL_SPS = 7;
    private static final int NAL_PPS = 8;
    private static final int NAL_AUD = 9;

    private final Consumer<VideoPacket> sink;

    /** Bytes seen but not yet resolved into a whole NAL — at most one NAL plus a partial start code. */
    private byte[] buf = new byte[1 << 16];
    private int len;
    /** Where in {@link #buf} the NAL being accumulated starts (at its start code), or -1 before the first one. */
    private int nalStart = -1;
    /** How far {@link #scan()} has already looked, so a chunk boundary doesn't re-walk what it read. */
    private int scanned;
    /** A NAL has begun but its type byte has not arrived, so whether it closes the picture is still unknown. */
    private boolean boundaryPending;

    private final ByteArrayOutputStream unit = new ByteArrayOutputStream(1 << 16);
    private boolean hasVcl;
    private boolean hasIdr;
    private boolean hasSps;
    private boolean hasPps;

    public AnnexB(Consumer<VideoPacket> sink) {
        this.sink = sink;
    }

    /** Feed {@code count} bytes from the front of {@code chunk}. Emits whatever became complete. */
    public void feed(byte[] chunk, int count) {
        if (count <= 0) {
            return;
        }
        ensure(len + count);
        System.arraycopy(chunk, 0, buf, len, count);
        len += count;
        scan();
        compact();
    }

    /**
     * End of stream: the NAL still in hand has no following start code to delimit it, so take it as complete
     * and emit whatever picture it finishes. Called once when the encoder's pipe closes.
     */
    public void finish() {
        if (nalStart >= 0 && len > nalStart) {
            byte[] nal = new byte[len - nalStart];
            System.arraycopy(buf, nalStart, nal, 0, nal.length);
            append(nal);
        }
        nalStart = -1;
        len = 0;
        scanned = 0;
        boundaryPending = false;
        emit();
    }

    // --- internals ---

    /**
     * Walks the unscanned tail for start codes, closing the previous NAL at each one. Stops three bytes short
     * of the end: {@code 00 00 01} cannot be recognised from a prefix of itself, so those bytes wait for the
     * next chunk rather than being mistaken for payload.
     */
    private void scan() {
        resolveBoundary();   // the type byte we were short of may have arrived in this chunk
        for (int i = Math.max(scanned, 0); i + 2 < len; i++) {
            if (buf[i] != 0 || buf[i + 1] != 0 || buf[i + 2] != 1) {
                continue;
            }
            // A 4-byte start code is a 3-byte one with a leading zero; take the zero with it so the NAL's
            // bytes are handed on exactly as the encoder wrote them.
            int start = (i > 0 && buf[i - 1] == 0) ? i - 1 : i;
            if (nalStart >= 0 && start > nalStart) {
                byte[] nal = new byte[start - nalStart];
                System.arraycopy(buf, nalStart, nal, 0, nal.length);
                append(nal);
            }
            nalStart = start;
            boundaryPending = true;
            resolveBoundary();
            i += 2;
        }
        scanned = Math.max(0, len - 3);
    }

    /**
     * Closes the current picture if the NAL that has just begun opens the next one. Does nothing while that
     * NAL's type byte is still short of the buffer — the next {@link #feed} calls this again.
     */
    private void resolveBoundary() {
        if (!boundaryPending || nalStart < 0) {
            return;
        }
        int header = nalStart + 2 < len && buf[nalStart] == 0 && buf[nalStart + 1] == 0 && buf[nalStart + 2] == 0
                ? 4 : 3;
        if (nalStart + header >= len) {
            return;
        }
        boundaryPending = false;
        int type = buf[nalStart + header] & 0x1F;
        if (hasVcl && (isVcl(type) || type == NAL_AUD || type == NAL_SPS || type == NAL_PPS)) {
            emit();
        }
    }

    /** Drops everything before the NAL in hand, so the buffer holds one NAL rather than the whole stream. */
    private void compact() {
        int keepFrom = nalStart >= 0 ? nalStart : Math.max(0, len - 3);
        if (keepFrom <= 0) {
            return;
        }
        System.arraycopy(buf, keepFrom, buf, 0, len - keepFrom);
        len -= keepFrom;
        scanned = Math.max(0, scanned - keepFrom);
        if (nalStart >= 0) {
            nalStart = 0;
        }
    }

    /**
     * One complete NAL, start code included, joins the picture being accumulated. It does <em>not</em> decide
     * boundaries: by the time a NAL is complete the next one has already begun, and {@link #resolveBoundary}
     * closed the picture then — a NAL never closes the picture it is part of.
     */
    private void append(byte[] nal) {
        int type = nalType(nal);
        if (type < 0) {
            return;
        }
        boolean vcl = isVcl(type);
        unit.write(nal, 0, nal.length);
        hasVcl |= vcl;
        hasIdr |= type == NAL_IDR;
        hasSps |= type == NAL_SPS;
        hasPps |= type == NAL_PPS;
    }

    private void emit() {
        if (hasVcl && unit.size() > 0) {
            sink.accept(new VideoPacket(unit.toByteArray(), hasIdr && hasSps && hasPps));
        }
        unit.reset();
        hasVcl = false;
        hasIdr = false;
        hasSps = false;
        hasPps = false;
    }

    /** Types 1–5 are the coded slices — the NALs a picture is actually made of. */
    private static boolean isVcl(int type) {
        return type >= 1 && type <= 5;
    }

    /** The NAL type after the start code, or -1 if this NAL is nothing but a start code. */
    private static int nalType(byte[] nal) {
        int header = nal.length >= 4 && nal[0] == 0 && nal[1] == 0 && nal[2] == 0 ? 4 : 3;
        return nal.length > header ? nal[header] & 0x1F : -1;
    }

    private void ensure(int capacity) {
        if (capacity <= buf.length) {
            return;
        }
        byte[] bigger = new byte[Math.max(capacity, buf.length * 2)];
        System.arraycopy(buf, 0, bigger, 0, len);
        buf = bigger;
    }
}
