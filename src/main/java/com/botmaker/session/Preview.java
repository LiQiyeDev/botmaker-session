package com.botmaker.session;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * The <b>lossy preview frame</b>: the blank test, the downscale and the JPEG encode that turn a captured root
 * into something worth putting on a wire, owned once because three processes were doing them differently.
 *
 * <p>It lives here, next to the session contract, because both ends of the preview path are in this module or
 * consume it: the {@code DisplayAgent} child encodes a {@code :N} root without ever handing the pixels back,
 * and Studio's pilot encodes the {@code :0} and emulator routes the same way. Before this, a session frame
 * paid <em>three</em> codec passes to reach a phone — PNG in the agent, PNG decode in Studio, JPEG out — and
 * the two JPEG steps that did exist disagreed about quality, size and what "no frame" means.
 *
 * <p><b>Nothing here is for the vision stack.</b> Templates are matched against lossless pixels
 * ({@code captureRoot}'s PNG); this is for a human looking at a phone, where a 1280-px 60 %-quality JPEG is
 * indistinguishable from the truth and several times smaller.
 */
public final class Preview {

    /** The long edge a preview frame is capped at. A phone screen is smaller; the wire pays for the rest. */
    public static final int MAX_EDGE = 1280;

    /** JPEG quality for a preview frame — visually clean on a phone, a fraction of the default's bytes. */
    public static final float QUALITY = 0.6f;

    /** Every {@value #BLANK_STRIDE}th pixel on both axes — see {@link #isBlank}. */
    private static final int BLANK_STRIDE = 16;

    /**
     * One writer per thread. {@link ImageWriter} is stateful (it holds its output) and not thread-safe, so it
     * can be cached but not shared — and caching it is the point: {@code ImageIO.write} looks a writer up and
     * builds one on <em>every</em> call, which on a 24 fps loop is 24 allocations a second of something whose
     * whole configuration is identical each time.
     */
    private static final ThreadLocal<ImageWriter> WRITERS = ThreadLocal.withInitial(() -> {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        return writers.hasNext() ? writers.next() : null;
    });

    private Preview() {
    }

    /**
     * Whether {@code img} is missing, degenerate, or <b>entirely black</b> — which on a session root means "no
     * capture", not "a black frame".
     *
     * <p>An X11 root with nothing mapped on it reads as opaque black, so a session hosting a Wayland-only
     * client (Waydroid under {@code gamescope --expose-wayland}) grabs successfully and forever.
     * {@link DesktopSession#x11Capturable()} is meant to catch that a rung earlier; this is the net for what it
     * cannot see — a compositor build that announces no socket, a client that dies mid-session.
     *
     * <p><b>Coarse on purpose.</b> This runs on every frame, so it samples a {@value #BLANK_STRIDE}-pixel grid
     * rather than scanning the image. A real frame is overwhelmingly likely to hit a non-black sample in the
     * first few reads; only an image that really is blank pays for the whole grid. A genuinely black game frame
     * that slips through costs one dropped frame, which the pilot's client already tolerates.
     */
    public static boolean isBlank(BufferedImage img) {
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
            return true;
        }
        for (int y = 0; y < img.getHeight(); y += BLANK_STRIDE) {
            for (int x = 0; x < img.getWidth(); x += BLANK_STRIDE) {
                if ((img.getRGB(x, y) & 0x00FFFFFF) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * {@code img} downscaled to a long edge of {@code maxEdge} and encoded as JPEG, or {@code null} when there
     * is nothing to encode or the encode failed.
     *
     * <p>It deliberately does <em>not</em> apply {@link #isBlank} — "this frame is blank" is a routing decision
     * with a different answer per caller (a black {@code :0} desktop is a legitimate frame; a black {@code :N}
     * root is not), so each asks for it where it means something rather than inheriting it from an encoder.
     *
     * <p>Downscaling is safe for the pilot because the client fits and maps touches through the frame's
     * declared <em>surface</em> rect, never through the bitmap's pixel size. Changing that on the client would
     * silently misplace every tap.
     */
    public static byte[] jpeg(BufferedImage img, int maxEdge, float quality) {
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
            return null;
        }
        ImageWriter writer = WRITERS.get();
        if (writer == null) {
            return null;
        }
        try {
            BufferedImage rgb = scaledRgb(img, maxEdge);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(Math.min(1f, Math.max(0.05f, quality)));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(rgb, null, null), params);
            } finally {
                writer.setOutput(null); // a writer holding a closed stream is the next frame's failure
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code src} as {@code TYPE_INT_RGB} — JPEG has no alpha channel — at most {@code maxEdge} on its long
     * side. An image that is already small enough and already RGB is handed back untouched.
     */
    private static BufferedImage scaledRgb(BufferedImage src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        double scale = maxEdge > 0 && longest > maxEdge ? (double) maxEdge / longest : 1.0;
        if (scale == 1.0 && src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        int dw = Math.max(1, (int) Math.round(w * scale));
        int dh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, dw, dh, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
