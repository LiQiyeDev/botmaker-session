package com.botmaker.session.video;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Annex-B splitter, which is the only part of the video path that can be tested without a GPU, an X server
 * or an {@code ffmpeg} binary — so it is where the bitstream reasoning is pinned down.
 *
 * <p>The stakes are worth naming. Get the access-unit boundary wrong and a decoder is handed half a picture,
 * which does not throw: it produces nothing, or it produces green mush, and the failure looks like "the pilot
 * is black" from every side. Get the {@code keyframe} flag wrong and a client that joins mid-stream is fed a
 * slice whose parameter sets it never saw, which fails the same silent way.
 */
class AnnexBTest {

    private final List<VideoPacket> packets = new ArrayList<>();
    private final AnnexB splitter = new AnnexB(packets::add);

    /** A NAL of {@code type} with {@code payload} filler bytes, behind a 4-byte start code. */
    private static byte[] nal(int type, int payload) {
        byte[] out = new byte[4 + 1 + payload];
        out[2] = 0;
        out[3] = 1;
        out[4] = (byte) (type & 0x1F);
        for (int i = 0; i < payload; i++) {
            out[5 + i] = (byte) (0x40 + i);   // never 0, so no accidental start code in the filler
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private void feed(byte[] bytes) {
        splitter.feed(bytes, bytes.length);
    }

    @Test
    void aPictureIsEmittedOnlyOnceTheNextOneStarts() {
        // Deliberately no finish(): the point is that a NAL with no following start code is *not* yet a
        // picture. A splitter that emitted eagerly would hand the decoder a truncated slice on every frame.
        feed(concat(nal(7, 8), nal(8, 4), nal(5, 40)));
        assertTrue(packets.isEmpty(), "nothing is complete until the next start code delimits it");

        feed(nal(1, 30));
        assertEquals(1, packets.size(), "the second slice closed the first picture");
        assertArrayEquals(concat(nal(7, 8), nal(8, 4), nal(5, 40)), packets.getFirst().annexB(),
                "the picture is its parameter sets and its slice, byte for byte as the encoder wrote them");
    }

    /**
     * The picture is released on the <em>type byte</em> of the next NAL, not on the whole next NAL. Waiting for
     * the whole one costs a frame of latency — the viewer permanently one picture behind — and leaves the last
     * picture of a still scene stuck here until the scene moves again.
     */
    @Test
    void aPictureIsReleasedOnTheNextNalsTypeByteAlone() {
        feed(concat(nal(7, 8), nal(8, 4), nal(5, 40)));
        feed(new byte[]{0, 0, 0, 1, (byte) 1});   // the next slice's start code and type byte, nothing more

        assertEquals(1, packets.size(), "five bytes of the next picture is all it takes to complete this one");
        assertTrue(packets.getFirst().keyframe());
    }

    @Test
    void parameterSetsBelongToTheKeyframeTheyPrecede() {
        feed(concat(nal(1, 20), nal(7, 8), nal(8, 4), nal(5, 40), nal(1, 20)));

        assertEquals(2, packets.size());
        assertFalse(packets.get(0).keyframe(), "the leading inter picture is not an entry point");
        assertArrayEquals(concat(nal(7, 8), nal(8, 4), nal(5, 40)), packets.get(1).annexB(),
                "an SPS after a slice opens the next picture rather than trailing the previous one");
        assertTrue(packets.get(1).keyframe(), "SPS + PPS + IDR together is what a fresh decoder can start on");
    }

    @Test
    void anIdrWithoutItsParameterSetsIsNotAnEntryPoint() {
        // A decoder that has seen nothing cannot decode this: the IDR references an SPS it was never given.
        // Calling it a keyframe would let a joining client start here and then show nothing, with no error.
        feed(concat(nal(5, 40), nal(1, 20)));

        assertEquals(1, packets.size());
        assertFalse(packets.getFirst().keyframe());
    }

    @Test
    void theSplitIsIdenticalWhateverTheChunkBoundaries() {
        byte[] stream = concat(nal(7, 8), nal(8, 4), nal(5, 40), nal(1, 20), nal(1, 20));

        // One byte at a time is the worst case: every start code straddles three chunk boundaries, so a
        // splitter that scanned only whole chunks would miss all of them.
        List<VideoPacket> byteAtATime = new ArrayList<>();
        AnnexB drip = new AnnexB(byteAtATime::add);
        for (byte b : stream) {
            drip.feed(new byte[]{b}, 1);
        }
        drip.finish();

        feed(stream);
        splitter.finish();

        assertEquals(packets.size(), byteAtATime.size());
        for (int i = 0; i < packets.size(); i++) {
            assertArrayEquals(packets.get(i).annexB(), byteAtATime.get(i).annexB(), "picture " + i);
            assertEquals(packets.get(i).keyframe(), byteAtATime.get(i).keyframe(), "picture " + i);
        }
        assertEquals(3, packets.size());
    }

    @Test
    void threeByteStartCodesAreAccepted() {
        // x264 writes 4-byte start codes before parameter sets and 3-byte ones before slices; both appear in
        // the same stream, so recognising only one length would merge every picture into its neighbour.
        byte[] shortCoded = {0, 0, 1, (byte) 1, 0x41, 0x42};
        feed(concat(nal(5, 8), shortCoded, shortCoded));

        assertEquals(2, packets.size());
    }

    @Test
    void trailingBytesAreEmittedOnFinish() {
        feed(concat(nal(7, 8), nal(8, 4), nal(5, 40)));
        splitter.finish();

        assertEquals(1, packets.size(), "the encoder's last picture is not thrown away when its pipe closes");
        assertTrue(packets.getFirst().keyframe());
    }

    @Test
    void parameterSetsWithNoSliceAreNeverAPicture() {
        feed(concat(nal(7, 8), nal(8, 4)));
        splitter.finish();

        assertTrue(packets.isEmpty(), "an SPS/PPS pair on its own decodes to no picture at all");
    }
}
