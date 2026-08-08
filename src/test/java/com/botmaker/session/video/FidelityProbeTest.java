package com.botmaker.session.video;

import com.botmaker.session.Preview;
import com.botmaker.session.impl.NestedSession;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>What the pilot's picture actually costs</b>, measured per stage on a real display — the harness behind
 * §10 of {@code docs/display-pipeline.md}.
 *
 * <p>"Is the pilot pixel-faithful?" has no answer, because it is two questions with opposite ones. This suite
 * separates them and treats them differently, which is the whole design:
 *
 * <ul>
 *   <li><b>Geometry is asserted.</b> Does source pixel (x,y) still land at (x·scale, y·scale)? That is exact,
 *       binary, and when it is wrong every tap is silently misplaced while template matching keeps succeeding
 *       — the worst shape a bug in this pipeline can have. The fiducial assertions below are the real test.
 *   <li><b>Samples are reported, never asserted.</b> PSNR/SSIM depend on which encoder the box actually has;
 *       an assertion on them would encode this machine's GPU into the build. They go to a report so a change
 *       can be compared against a baseline by a human who knows what changed.
 * </ul>
 *
 * <p>Three stages against one truth. Stage 0 is {@code captureScreen()} — lossless, full resolution, and the
 * pixels a <em>bot</em> matches templates against, so it is both the truth for stages 1–2 and the proof that
 * the bot's own path is exact. Stage 1 is {@link Preview#jpeg} (the transport floor). Stage 2 is a real
 * {@code openVideoStream} — the surface walk, the encoder walk and the Annex-B framing, decoded back with
 * {@code ffmpeg}. Nothing is stubbed: a stage that is not the production path measures nothing.
 *
 * <p>Artefacts land in {@code ~/.botmaker/fidelity/<timestamp>/} — every stage as a PNG plus {@code report.txt}
 * — because the numbers say <em>how much</em> was lost and only the images say <em>what</em>. Torn horizontal
 * bands are upstream of the encoder (a grab mid-composite); blockiness under motion is the encoder itself.
 * One look at stage 1 beside stage 2 separates them, and no metric does.
 *
 * <p>Opt-in and self-skipping exactly like the other live suites: {@code -Dbotmaker.live=true} plus a usable
 * {@code DISPLAY}, {@code Xephyr} and {@code ffmpeg}. Size defaults to 1280×720 and takes
 * {@code -Dbotmaker.fidelity.size=1080x1920} to measure the portrait case, where the downscale is steepest.
 */
class FidelityProbeTest {

    /** How long the pattern client is given to map and paint before the probe gives up on it. */
    private static final long PATTERN_TIMEOUT_MS = 20_000;
    /** How long to collect Annex-B for. Long enough to include a keyframe and some inter frames at 24fps. */
    private static final long VIDEO_COLLECT_MS = 3_000;
    /** Fiducial centre tolerance for the lossless stage: an exact answer, so anything above rounding is a bug. */
    private static final double TOLERANCE_LOSSLESS_PX = 1.0;
    /**
     * Fiducial centre tolerance for a lossy stage, in that stage's own pixels. A downscale and a 4:2:0 encode
     * blur a square's edges by a pixel or two and not symmetrically, so the centroid moves; 2px is well inside
     * that and far outside the offsets that actually break taps (a crop or a letterbox move tens of pixels).
     */
    private static final double TOLERANCE_LOSSY_PX = 2.0;

    @Test
    void everyStageOfThePilotPathKeepsItsGeometry() throws Exception {
        assumeLive();
        int[] size = requestedSize();
        Path out = outputDirectory();
        List<String> report = new ArrayList<>();
        report.add("BotMaker display-pipeline fidelity probe");
        report.add("when:    " + LocalDateTime.now());
        report.add("session: " + size[0] + "x" + size[1] + " (Xephyr, no window manager)");
        report.add("");

        NestedSession session = NestedSession.start(
                NestedSession.Options.xephyr(size[0], size[1]).withoutWindowManager());
        Process pattern = null;
        try {
            BufferedImage source = FidelityPattern.build(size[0], size[1]);
            Path sourcePng = out.resolve("stage0-source.png");
            ImageIO.write(source, "png", sourcePng.toFile());
            pattern = showPattern(session.displayName(), sourcePng);

            // --- stage 0: the lossless capture. Also the bot's own frame, and the truth for everything below.
            BufferedImage truth = awaitPaintedCapture(session);
            assertNotNull(truth, "the pattern client never painted anything on " + session.displayName());
            ImageIO.write(truth, "png", out.resolve("stage0-capture.png").toFile());
            report.add(geometry("stage 0  captured losslessly", source, truth, TOLERANCE_LOSSLESS_PX));

            // --- stage 1: the JPEG transport floor, at the same cap and quality the pilot ships.
            byte[] jpeg = Preview.jpeg(truth, Preview.MAX_EDGE, Preview.QUALITY);
            assertNotNull(jpeg, "Preview.jpeg produced nothing for a " + truth.getWidth() + "px frame");
            Files.write(out.resolve("stage1-preview.jpg"), jpeg);
            BufferedImage decodedJpeg = ImageIO.read(new ByteArrayInputStream(jpeg));
            ImageIO.write(decodedJpeg, "png", out.resolve("stage1-preview.png").toFile());
            report.add(geometry("stage 1  JPEG (" + jpeg.length / 1024 + " KiB)",
                    source, decodedJpeg, TOLERANCE_LOSSY_PX));

            // --- stage 2: the real video path, encoder walk and all.
            BufferedImage decodedVideo = null;
            String encoder = captureVideo(session, out);
            Path videoPng = out.resolve("stage2-h264.png");
            if (Files.exists(videoPng)) {
                decodedVideo = ImageIO.read(videoPng.toFile());
                report.add(geometry("stage 2  H.264 via " + encoder, source, decodedVideo, TOLERANCE_LOSSY_PX));
            } else {
                // Not a failure: openVideoStream declines by contract when no encoder produces a picture, and
                // the pilot's answer to that is the JPEG floor stage 1 just measured.
                report.add("stage 2  H.264: no encoder produced a picture — skipped (the pilot would stay on JPEG)");
            }

            report.add("");
            report.add("samples (reported, not asserted — upscaled back to the truth's size, as the canvas does)");
            report.add(samples("stage 1  JPEG ", out.resolve("stage1-preview.png"), out.resolve("stage0-capture.png")));
            if (decodedVideo != null) {
                report.add(samples("stage 2  H.264", videoPng, out.resolve("stage0-capture.png")));
            }

            Files.writeString(out.resolve("report.txt"), String.join(System.lineSeparator(), report));
            System.out.println(String.join(System.lineSeparator(), report));
            System.out.println("artefacts: " + out);
        } finally {
            if (pattern != null) {
                pattern.destroy();
            }
            session.close();
        }
    }

    // --- stages ---------------------------------------------------------------------------------------

    /**
     * Polls {@code captureScreen()} until the pattern client has mapped and painted — "painted" meaning all
     * five fiducials are findable, not merely that the frame is non-blank. A frame taken between the map and
     * the first expose is a real capture of a half-drawn window, and measuring one would report the toolkit's
     * timing as the pipeline's fidelity.
     */
    private static BufferedImage awaitPaintedCapture(NestedSession session) throws InterruptedException {
        long deadline = System.currentTimeMillis() + PATTERN_TIMEOUT_MS;
        BufferedImage last = null;
        while (System.currentTimeMillis() < deadline) {
            BufferedImage frame = session.captureScreen();
            if (frame != null) {
                last = frame;
                if (FidelityPattern.locate(frame).size() == 5) {
                    return frame;
                }
            }
            Thread.sleep(250);
        }
        return last;
    }

    /**
     * Opens the session's real video stream, collects {@link #VIDEO_COLLECT_MS} of Annex-B, and decodes the
     * last picture out of it to {@code stage2-h264.png}. Returns the encoder that won, or {@code null} when
     * the session declined to open a stream at all.
     */
    private static String captureVideo(NestedSession session, Path out) throws Exception {
        ByteArrayOutputStream annexB = new ByteArrayOutputStream();
        VideoStream stream = session.openVideoStream(Preview.MAX_EDGE, 24,
                packet -> annexB.writeBytes(packet.annexB()));
        if (stream == null) {
            return null;
        }
        String encoder;
        try {
            Thread.sleep(VIDEO_COLLECT_MS);
            encoder = stream instanceof FfmpegVideoStream f ? f.encoder() : null;
        } finally {
            stream.close();
        }
        byte[] bitstream = annexB.toByteArray();
        if (bitstream.length == 0) {
            return null;
        }
        Path raw = out.resolve("stage2-stream.h264");
        Files.write(raw, bitstream);
        // -update 1 rewrites the same file per picture, so what survives is the last one decoded — the frame
        // the viewer would be looking at when the stream was cut.
        run(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "h264", "-i", raw.toString(),
                "-update", "1", out.resolve("stage2-h264.png").toString()));
        return encoder == null ? "an unknown encoder" : encoder;
    }

    // --- measurement ----------------------------------------------------------------------------------

    /**
     * Asserts every fiducial in {@code stage} sits where {@code source}'s does, scaled by the stage's own size
     * — and returns the line about it for the report.
     *
     * <p>The scale is derived from the images rather than assumed, because deriving it is what makes a stage
     * that letterboxes fail: a picture padded to a different aspect ratio moves its fiducials off the ratio
     * its width implies, and every corner then misses by the pad.
     */
    private static String geometry(String label, BufferedImage source, BufferedImage stage, double tolerance) {
        double scale = stage.getWidth() / (double) source.getWidth();
        List<Point2D.Double> found = FidelityPattern.locate(stage);
        assertEquals(5, found.size(),
                label + ": expected 5 fiducials in a " + stage.getWidth() + "x" + stage.getHeight()
                        + " frame, found " + found.size() + " — the picture is cropped, blank or unrecognisable");
        double worst = 0;
        String worstAt = "";
        for (Point2D.Double expected : FidelityPattern.expectedCentres(source.getWidth(), source.getHeight())) {
            Point2D.Double want = new Point2D.Double(expected.x * scale, expected.y * scale);
            Point2D.Double got = nearest(found, want);
            double off = want.distance(got);
            if (off > worst) {
                worst = off;
                worstAt = "(" + Math.round(want.x) + "," + Math.round(want.y) + ") -> ("
                        + Math.round(got.x) + "," + Math.round(got.y) + ")";
            }
        }
        assertTrue(worst <= tolerance,
                label + ": a fiducial is " + String.format("%.2f", worst) + "px off (tolerance " + tolerance
                        + "px) at " + worstAt + ". The picture is offset, cropped or scaled by something other "
                        + "than the declared surface — every tap through it lands somewhere else.");
        return String.format("%-34s %5dx%-5d scale %.3f   worst fiducial %.2fpx (tolerance %.1f)  OK",
                label, stage.getWidth(), stage.getHeight(), scale, worst, tolerance);
    }

    private static Point2D.Double nearest(List<Point2D.Double> candidates, Point2D.Double to) {
        Point2D.Double best = candidates.get(0);
        for (Point2D.Double c : candidates) {
            if (c.distance(to) < best.distance(to)) {
                best = c;
            }
        }
        return best;
    }

    /**
     * PSNR and SSIM of {@code stage} against {@code truth}, upscaled back to the truth's size first — because
     * that is what the viewer's canvas does, so it is the comparison a user's eye actually makes.
     *
     * <p><b>Three regions, not one number.</b> A whole-frame figure over this pattern is misleading and was,
     * on the first run: the 1px checkerboard is a tenth of the frame at maximum amplitude and an encoder
     * flattens it completely, which dragged whole-frame PSNR to 22.8 dB while SSIM sat at 0.97 — the two
     * metrics disagreeing because one of them was being dominated by a region no game contains. So the band is
     * measured separately (the worst case, and the one that moves when the resampler changes) from a
     * fiducial-free slab of gradient (the typical case, and the one that moves when the bitrate does).
     */
    private static String samples(String label, Path stage, Path truth) throws Exception {
        BufferedImage t = ImageIO.read(truth.toFile());
        int w = t.getWidth(), h = t.getHeight();
        return String.join(System.lineSeparator(),
                region(label + " whole frame", stage, truth, w, h, null),
                region(label + " 1px checkers", stage, truth, w, h,
                        crop(w, (int) (h * 0.35), 0, (int) (h * 0.10))),
                region(label + " gradient", stage, truth, w, h,
                        crop(w, (int) (h * 0.50), 0, (int) (h * 0.25))));
    }

    /** An ffmpeg {@code crop=w:h:x:y} over the truth's pixel space, with even dimensions. */
    private static String crop(int width, int y, int x, int height) {
        return "crop=" + (width & ~1) + ":" + (height & ~1) + ":" + x + ":" + y;
    }

    private static String region(String label, Path stage, Path truth, int w, int h, String crop) {
        String psnr = metric(stage, truth, w, h, crop, "psnr", "average:([0-9.]+)");
        String ssim = metric(stage, truth, w, h, crop, "ssim", "All:([0-9.]+)");
        return String.format("  %-32s PSNR %-10s dB   SSIM %s", label, psnr, ssim);
    }

    /**
     * One metric, one region. Two ffmpeg runs per region rather than one graph: {@code psnr} and {@code ssim}
     * each consume both inputs, and a {@code split} to share them costs more to read than the second run costs
     * to run.
     */
    private static String metric(Path stage, Path truth, int w, int h, String crop,
                                 String filter, String extract) {
        try {
            String tail = crop == null ? "" : "," + crop;
            String output = run(List.of("ffmpeg", "-hide_banner", "-y",
                    "-i", stage.toString(), "-i", truth.toString(),
                    "-lavfi", "[0:v]scale=" + w + ":" + h + ":flags=bicubic" + tail + "[a];"
                            + "[1:v]" + (crop == null ? "null" : crop) + "[b];[a][b]" + filter,
                    "-f", "null", "-"));
            Matcher m = Pattern.compile(extract).matcher(output);
            return m.find() ? m.group(1) : "n/a";
        } catch (Exception e) {
            return "n/a (" + e.getMessage() + ")";
        }
    }

    // --- plumbing -------------------------------------------------------------------------------------

    /** The pattern client: this JVM's own classpath, a child process, pointed at {@code display}. */
    private static Process showPattern(String display, Path png) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        long lifetime = (PATTERN_TIMEOUT_MS + VIDEO_COLLECT_MS) / 1000 + 30;
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                FidelityPattern.class.getName(), png.toString(), String.valueOf(lifetime));
        pb.environment().put("DISPLAY", display);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    /** Runs {@code command} to completion and returns its combined output. */
    private static String run(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output;
        try (InputStream in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        p.waitFor(60, TimeUnit.SECONDS);
        return output;
    }

    private static Path outputDirectory() throws Exception {
        Path dir = Path.of(System.getProperty("user.home"), ".botmaker", "fidelity",
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()));
        Files.createDirectories(dir);
        return dir;
    }

    /** {@code -Dbotmaker.fidelity.size=WxH}, else 1280×720. */
    private static int[] requestedSize() {
        String spec = System.getProperty("botmaker.fidelity.size", "1280x720");
        String[] parts = spec.toLowerCase().split("x");
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("botmaker.fidelity.size should look like 1080x1920, was: " + spec);
        }
    }

    private static void assumeLive() {
        assumeTrue(Boolean.getBoolean("botmaker.live"),
                "opt-in live test — run with -Dbotmaker.live=true");
        String display = System.getenv("DISPLAY");
        assumeTrue(display != null && !display.isBlank(), "needs a DISPLAY");
        assumeTrue(onPath("Xephyr"), "needs Xephyr on PATH");
        assumeTrue(onPath("ffmpeg"), "needs ffmpeg on PATH — it is both the encoder under test and the metric");
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, exe).canExecute()) {
                return true;
            }
        }
        return false;
    }
}
