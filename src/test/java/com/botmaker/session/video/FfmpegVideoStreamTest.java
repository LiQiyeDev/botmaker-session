package com.botmaker.session.video;

import com.botmaker.session.PaintedSurface;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code ffmpeg} command line, asserted rather than discovered. Every mistake available here fails the
 * same way at run time — the encoder starts, prints an error to a discarded stderr, and produces no packets,
 * so the pilot silently stays on JPEG and nothing says why.
 */
class FfmpegVideoStreamTest {

    /** The root of a 1920×1080 display — the surface every non-compositing backend paints. */
    private static PaintedSurface root(int w, int h) {
        return new PaintedSurface(0, new Rectangle(0, 0, w, h));
    }

    private static String argAfter(List<String> command, String flag) {
        int i = command.indexOf(flag);
        return i >= 0 && i + 1 < command.size() ? command.get(i + 1) : null;
    }

    @Test
    void everyEncoderGrabsTheDisplayAndWritesRawAnnexBToStdout() {
        for (FfmpegVideoStream.Encoder encoder : FfmpegVideoStream.Encoder.values()) {
            List<String> cmd = FfmpegVideoStream.command(encoder, ":9", root(1920, 1080), 1280, 24);

            assertEquals("x11grab", argAfter(cmd, "-f"), encoder + " must grab an X display");
            assertEquals(":9", argAfter(cmd, "-i"), encoder + " must grab the session's display");
            assertEquals(encoder.codecName, argAfter(cmd, "-c:v"));
            // Raw H.264 on stdout: a container would buffer to find its own frame boundaries, which is the
            // latency this whole path exists to avoid.
            assertEquals(List.of("-f", "h264", "-"), cmd.subList(cmd.size() - 3, cmd.size()), encoder.toString());
            // No B-frames: they reorder output, and AnnexB's boundary rule assumes they are absent.
            assertEquals("0", argAfter(cmd, "-bf"), encoder.toString());
            // A two-second GOP is the longest a client joining mid-stream waits for its entry point.
            assertEquals("48", argAfter(cmd, "-g"), encoder.toString());
        }
    }

    @Test
    void theVaapiPathUploadsToTheGpuBeforeItScales() {
        List<String> cmd = FfmpegVideoStream.command(FfmpegVideoStream.Encoder.VAAPI, ":9", root(1920, 1080), 1280, 24);

        assertTrue(cmd.indexOf("-vaapi_device") < cmd.indexOf("-i"),
                "the device is a global option and is ignored if it comes after the input");
        // scale_vaapi has to sit after hwupload; scaling on the CPU first would upload a full-size frame and
        // give back the software cost this encoder was chosen to avoid.
        assertEquals("format=nv12,hwupload,scale_vaapi=1280:720", argAfter(cmd, "-vf"));
    }

    @Test
    void theSoftwareAndNvencPathsScaleOnTheCpuInYuv420() {
        for (FfmpegVideoStream.Encoder encoder : List.of(FfmpegVideoStream.Encoder.NVENC,
                FfmpegVideoStream.Encoder.X264)) {
            List<String> cmd = FfmpegVideoStream.command(encoder, ":9", root(1920, 1080), 1280, 24);

            assertEquals("scale=1280:720:flags=fast_bilinear", argAfter(cmd, "-vf"), encoder.toString());
            assertEquals("yuv420p", argAfter(cmd, "-pix_fmt"), encoder.toString());
        }
    }

    @Test
    void theEncoderOrderIsHardwareFirst() {
        // The order is the policy: a GPU encode costs the CPU nothing, and libx264 on a machine already running
        // a game is the fallback precisely because it is the one that always works, not the one to prefer.
        assertEquals(List.of("h264_nvenc", "h264_vaapi", "libx264"),
                java.util.Arrays.stream(FfmpegVideoStream.Encoder.values())
                        .map(e -> e.codecName).toList());
    }

    @Test
    void bothDimensionsComeBackEven() {
        // 4:2:0 chroma is subsampled by two, so an odd dimension is not encodable — ffmpeg rejects the filter
        // outright rather than rounding, which is a stream that never starts.
        assertArrayEquals(new int[]{1280, 720}, FfmpegVideoStream.fit(1920, 1080, 1280));
        assertArrayEquals(new int[]{720, 1280}, FfmpegVideoStream.fit(1080, 1920, 1280), "portrait too");
        assertArrayEquals(new int[]{1078, 606}, FfmpegVideoStream.fit(1079, 607, 4000), "already small enough");
        assertArrayEquals(new int[]{2, 2}, FfmpegVideoStream.fit(1, 1, 1280), "never degenerate");
    }

    /**
     * The gamescope case, and the one argument that decides whether this path produces a picture at all. On a
     * compositing backend the X root is never painted: the same display measured 0.04 out of 65535 as a frame
     * mean grabbed as {@code -i :1}, and 28619 grabbed with {@code -window_id} — a stream that runs perfectly
     * and shows black, against the game.
     */
    @Test
    void aWindowSurfaceIsGrabbedByIdAtItsOwnSize() {
        PaintedSurface window = new PaintedSurface(52428803L, new Rectangle(307, 239, 1280, 661));

        List<String> cmd = FfmpegVideoStream.command(FfmpegVideoStream.Encoder.X264, ":9", window, 1280, 24);

        assertEquals("52428803", argAfter(cmd, "-window_id"));
        assertTrue(cmd.indexOf("-window_id") < cmd.indexOf("-i"),
                "-window_id is an input option and is ignored if it comes after the input it applies to");
        assertEquals(":9", argAfter(cmd, "-i"), "the display is still the input — the id selects within it");
        // The grab is the window's own size, not the display's, and its offset is not passed: +x,y crops the
        // root, whereas a window grab already arrives in the window's own coordinates.
        assertEquals("1280x661", argAfter(cmd, "-video_size"));
        assertEquals("scale=1280:660:flags=fast_bilinear", argAfter(cmd, "-vf"),
                "661 is odd and 4:2:0 cannot encode it");
    }

    /**
     * x11grab defaults to the root, and passing {@code -window_id 0} is not uniformly the same thing across
     * ffmpeg builds — so the flag is absent rather than zero for a root grab.
     */
    @Test
    void aRootSurfaceCarriesNoWindowId() {
        List<String> cmd = FfmpegVideoStream.command(FfmpegVideoStream.Encoder.X264, ":9", root(1920, 1080), 1280, 24);

        assertFalse(cmd.contains("-window_id"));
        assertEquals("1920x1080", argAfter(cmd, "-video_size"));
    }

    @Test
    void aDisplayTooSmallToScaleIsPassedThroughUnscaled() {
        List<String> cmd = FfmpegVideoStream.command(FfmpegVideoStream.Encoder.X264, ":9", root(800, 600), 1280, 24);

        assertEquals("scale=800:600:flags=fast_bilinear", argAfter(cmd, "-vf"));
    }
}
