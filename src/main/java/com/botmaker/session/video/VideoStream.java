package com.botmaker.session.video;

import java.awt.Rectangle;

/**
 * A live H.264 encode of a session's screen, pushing {@link VideoPacket}s to the sink it was opened with.
 *
 * <p>It is <b>push</b>, not pull, because the encoder sets the pace: an {@code ffmpeg} grabbing an X display at
 * a fixed frame rate produces a picture when it produces one, and a consumer polling it would only reintroduce
 * the buffering the whole path exists to avoid. The pilot's JPEG loop still ticks on its own schedule for the
 * routes that have no video; this one delivers.
 *
 * <p>Opening is <b>asynchronous</b>. Picking an encoder means starting a process and finding out whether it
 * produces anything — hardware encoders fail at run time, not at probe time — and the caller is a frame loop
 * that must not block for seconds while that is decided. So {@link #alive()} is false until the first packet
 * lands, and a stream that never manages one simply stays false and is closed.
 */
public interface VideoStream extends AutoCloseable {

    /**
     * Whether this stream has produced at least one packet and its encoder is still running. False both before
     * the first packet and after the encoder died — which are the same thing to a caller: no video, use JPEG.
     */
    boolean alive();

    /**
     * The WebCodecs codec string for the bitstream, e.g. {@code "avc1.42E01E"} (constrained baseline, level
     * 3.0). Constant for the life of the stream, since the profile is pinned in the encoder's arguments — a
     * client that configures its decoder from this never has to reconfigure mid-stream.
     */
    String codec();

    /**
     * The rect on the session's screen that this stream's pictures are of — what a viewer fits its canvas to
     * and maps a tap through.
     *
     * <p>It is <b>not</b> always the session's screen, which is why the stream reports it rather than letting
     * the caller assume: a compositing backend never paints its X root, so the drawable with pixels on it is a
     * client window, at that window's own origin and size. Fixed for the life of the stream — the encoder was
     * pointed at one drawable at open — so a surface that changes is a stream that ends and reopens.
     */
    Rectangle surface();

    @Override
    void close();
}
