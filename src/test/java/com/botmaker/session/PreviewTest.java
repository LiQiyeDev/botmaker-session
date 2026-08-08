package com.botmaker.session;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The preview encode: what counts as a blank frame, and that downscaling caps the long edge without distorting. */
class PreviewTest {

    private static BufferedImage painted(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    @Test
    void anUntouchedImageIsBlankBecauseAFreshRasterIsBlack() {
        assertTrue(Preview.isBlank(new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)));
        assertTrue(Preview.isBlank(null));
    }

    /** The sampling grid is coarse, so the pixel that proves the frame must be one the grid actually visits. */
    @Test
    void oneLitPixelOnTheGridIsEnoughToNotBeBlank() {
        BufferedImage img = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        img.setRGB(320, 240, 0x00FF00);

        assertFalse(Preview.isBlank(img));
    }

    @Test
    void anOversizedFrameIsCappedOnItsLongEdgeAndKeepsItsAspect() throws IOException {
        byte[] jpeg = Preview.jpeg(painted(3840, 2160), 1280, 0.6f);

        assertNotNull(jpeg);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertEquals(1280, decoded.getWidth());
        assertEquals(720, decoded.getHeight());
    }

    /** Already small enough is left alone — a downscale that always ran would soften a phone-sized frame. */
    @Test
    void aFrameUnderTheCapIsNotResized() throws IOException {
        byte[] jpeg = Preview.jpeg(painted(800, 600), 1280, 0.6f);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertEquals(800, decoded.getWidth());
        assertEquals(600, decoded.getHeight());
    }

    /**
     * The writer is cached per thread and reused, so an encode that left it holding a closed stream would break
     * the <em>next</em> frame rather than itself — the failure this asserts against is a second call, not a first.
     */
    @Test
    void theCachedWriterSurvivesRepeatedEncodes() {
        for (int i = 0; i < 5; i++) {
            assertNotNull(Preview.jpeg(painted(64, 48), 1280, 0.6f), "encode " + i);
        }
    }

    @Test
    void thereIsNothingToEncodeForAMissingFrame() {
        assertNull(Preview.jpeg(null, 1280, 0.6f));
    }
}
