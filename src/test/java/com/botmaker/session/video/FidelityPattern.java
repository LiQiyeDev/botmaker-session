package com.botmaker.session.video;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * The known picture {@link FidelityProbeTest} measures the pipeline against: a generator, a locator, and a
 * {@code main} that puts it on a display.
 *
 * <p>It is built rather than photographed because every stage of the pipeline destroys a different thing, and
 * a screenshot of a game destroys none of them visibly. The three regions each answer one question:
 *
 * <ul>
 *   <li><b>Fiducials</b> — five saturated green squares at known coordinates. Green, on black pads, because
 *       geometry has to survive 4:2:0 chroma subsampling and a bilinear downscale: a centroid over a large
 *       flat patch moves far less than an edge does. These carry the only assertions in the suite.
 *   <li><b>A 1px checkerboard</b> — the worst case for both the {@code fast_bilinear} downscale and the
 *       encoder's DCT, and the one region where a fidelity regression is visible by eye rather than only in
 *       a number.
 *   <li><b>A smooth two-channel gradient</b> — the opposite case: banding here means quantisation, not
 *       resampling. Red and blue only, so nothing outside a fiducial is ever green-dominant and the locator
 *       needs no seed position to find them.
 * </ul>
 *
 * <p><b>The locator is plain Java on purpose.</b> Blob detection is the one thing OpenCV would do in a line,
 * and this module's pom excludes it from shared so a consumer that wants a private display does not download
 * an OCR engine to get one — {@code NoOcvOnTheClasspathTest} asserts it is not resolvable even in tests. A
 * hundred lines of flood fill is the cheaper side of that trade.
 *
 * <p>The {@code main} is spawned as a child JVM with {@code DISPLAY=:N} — an undecorated AWT {@link Frame} at
 * the display's exact size, on a session started {@code withoutWindowManager()} so nothing reframes or
 * repositions it. It is the cheapest X client that paints a chosen image at 1:1, and being our own process it
 * needs nothing installed. It exits on its own timer so a test that throws cannot leave it holding a display.
 */
final class FidelityPattern {

    /** Side of a fiducial square, in source pixels. Large enough that a 0.5× downscale leaves ~20px of core. */
    static final int FIDUCIAL = 40;
    /** Distance from each edge to the outer fiducials' centres. */
    static final int INSET = 70;

    private FidelityPattern() {}

    /** The pattern at {@code w}×{@code h}: gradient, checkerboard band, and the five fiducials on black pads. */
    static BufferedImage build(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            int b = (int) (255.0 * y / Math.max(1, h - 1));
            for (int x = 0; x < w; x++) {
                int r = (int) (255.0 * x / Math.max(1, w - 1));
                img.setRGB(x, y, (r << 16) | b);   // green stays 0 — see the locator
            }
        }
        int bandTop = (int) (h * 0.35), bandBottom = (int) (h * 0.45);
        for (int y = bandTop; y < bandBottom; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, ((x + y) & 1) == 0 ? 0xFFFFFF : 0x000000);
            }
        }
        for (Point2D.Double c : expectedCentres(w, h)) {
            fill(img, (int) c.x, (int) c.y, FIDUCIAL * 2, 0x000000);
            fill(img, (int) c.x, (int) c.y, FIDUCIAL, 0x00FF00);
        }
        return img;
    }

    /** Centres of the five fiducials in a {@code w}×{@code h} pattern: four corners inset, plus the middle. */
    static List<Point2D.Double> expectedCentres(int w, int h) {
        return List.of(
                new Point2D.Double(INSET, INSET),
                new Point2D.Double(w - INSET, INSET),
                new Point2D.Double(INSET, h - INSET),
                new Point2D.Double(w - INSET, h - INSET),
                new Point2D.Double(w / 2.0, h / 2.0));
    }

    /**
     * The centroids of the five largest green blobs in {@code img}, largest first — <b>found, not looked up</b>.
     *
     * <p>Searching the whole image rather than a window around each expected point is the difference between a
     * test that catches a crop and one that cannot: seeded from where the fiducial ought to be, a picture
     * shifted by 30px still reports a hit on whatever green is nearest. This reports what is actually there,
     * and the caller matches it to the expectation afterwards.
     *
     * <p>The predicate is dominance ({@code g} well above both {@code r} and {@code b}) rather than a fixed
     * green threshold: after 4:2:0 and a CRF-26 encode a fiducial's core is nowhere near {@code #00FF00}, while
     * the checkerboard's white — which a plain {@code g > 150} would swallow whole — never becomes dominant.
     */
    static List<Point2D.Double> locate(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        boolean[] hit = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                hit[y * w + x] = g > 90 && g > r + 50 && g > b + 50;
            }
        }
        List<Blob> blobs = new ArrayList<>();
        boolean[] seen = new boolean[w * h];
        Deque<Integer> stack = new ArrayDeque<>();
        for (int start = 0; start < hit.length; start++) {
            if (!hit[start] || seen[start]) {
                continue;
            }
            long sx = 0, sy = 0, n = 0;
            seen[start] = true;
            stack.push(start);
            while (!stack.isEmpty()) {
                int i = stack.pop();
                int x = i % w, y = i / w;
                sx += x;
                sy += y;
                n++;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                            continue;
                        }
                        int j = ny * w + nx;
                        if (hit[j] && !seen[j]) {
                            seen[j] = true;
                            stack.push(j);
                        }
                    }
                }
            }
            blobs.add(new Blob(sx / (double) n, sy / (double) n, n));
        }
        blobs.sort(Comparator.comparingLong(Blob::area).reversed());
        return blobs.stream().limit(5).map(b -> new Point2D.Double(b.x(), b.y())).toList();
    }

    private record Blob(double x, double y, long area) {}

    /** A {@code side}-wide square of {@code rgb} centred on {@code (cx,cy)}, clipped to the image. */
    private static void fill(BufferedImage img, int cx, int cy, int side, int rgb) {
        int half = side / 2;
        for (int y = Math.max(0, cy - half); y < Math.min(img.getHeight(), cy + half); y++) {
            for (int x = Math.max(0, cx - half); x < Math.min(img.getWidth(), cx + half); x++) {
                img.setRGB(x, y, rgb);
            }
        }
    }

    /**
     * Shows {@code args[0]} (a PNG) fullscreen at 1:1 on {@code $DISPLAY} for {@code args[1]} seconds, then
     * exits. Run as a child JVM by the probe; never by a human.
     */
    public static void main(String[] args) throws Exception {
        BufferedImage img = ImageIO.read(new File(args[0]));
        long seconds = Long.parseLong(args[1]);
        Frame frame = new Frame() {
            @Override
            public void paint(Graphics g) {
                g.drawImage(img, 0, 0, null);   // 1:1 — any scaling here would be measured as pipeline loss
            }
        };
        frame.setUndecorated(true);
        frame.setBackground(Color.BLACK);
        frame.setBounds(0, 0, img.getWidth(), img.getHeight());
        frame.setVisible(true);
        // Its own deadline, because the parent may die without reaping it and an X client outliving its test is
        // exactly the orphan the session stack exists to prevent.
        Thread.sleep(seconds * 1000);
        System.exit(0);
    }
}
