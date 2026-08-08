package com.botmaker.session.video;

import com.botmaker.session.PaintedSurface;
import com.botmaker.shared.Diag;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A {@link VideoStream} backed by {@code ffmpeg -f x11grab}: it grabs one drawable on a session's {@code :N}
 * and encodes it to H.264, hardware-first, handing each access unit to the sink.
 *
 * <p><b>One drawable, chosen once.</b> The caller says which — see {@link PaintedSurface} — and it is the root
 * only when the root is what gets painted. That distinction is the whole difference between this working and
 * not on a compositing backend, and it cannot be revisited later: an encoder is pointed at a drawable when it
 * starts, so a surface that changes is a stream that ends and a new one that opens.
 *
 * <h2>Why this is worth a process</h2>
 *
 * <p>The JPEG path this sits beside re-encodes a whole picture every frame and ships it whole — at 24 fps and
 * 1280 px that is megabytes a second of intra-only frames over a phone's link. H.264 sends the <em>difference</em>
 * between pictures, and on this machine it sends it from the GPU: a mostly-static game screen costs a few
 * kilobytes a frame instead of a few hundred. The JPEG path does not go away — it is what a client without
 * WebCodecs, a host without {@code ffmpeg}, and every non-session route still use.
 *
 * <h2>Choosing an encoder</h2>
 *
 * <p>{@link Encoder} is tried in order — NVENC, then VAAPI, then libx264 — and the test is not "is this encoder
 * listed in {@code ffmpeg -encoders}". A hardware encoder is listed on a machine whose driver refuses to
 * initialise it, whose device node isn't there, or whose session is already at its NVENC stream limit, and each
 * of those fails at run time. So a candidate is accepted only once it has actually produced a packet, and any
 * candidate that dies or stays silent for {@value #FIRST_PACKET_TIMEOUT_MS} ms is killed and the next tried.
 *
 * <p>That probing is why {@link #open} returns immediately and {@link #alive()} starts false: the caller is a
 * frame loop, and three candidates that each take a second to fail is not something to block it on.
 */
public final class FfmpegVideoStream implements VideoStream {

    /**
     * How long a candidate encoder gets to produce its first access unit before it is written off. Generous
     * because a cold hardware encoder initialising a context is not instant, and short enough that walking all
     * three candidates cannot stall the pilot for longer than a viewer will wait for a first picture.
     */
    private static final int FIRST_PACKET_TIMEOUT_MS = 4000;

    /**
     * Constrained baseline, level 3.0 — the profile the arguments below pin, and the string a WebCodecs
     * {@code VideoDecoder} is configured with. Baseline because it is the one profile every hardware decoder in
     * a phone supports, and because its absence of B-frames is also what makes the {@link AnnexB} boundary rule
     * a one-liner.
     */
    public static final String CODEC = "avc1.42E01E";

    /** The encoders tried, in order. Each is the argument list that goes between the input and the output. */
    enum Encoder {
        /** NVIDIA's on-GPU encoder: {@code p1}/{@code ll} is its lowest-latency preset pair. */
        NVENC("h264_nvenc", List.of("-preset", "p1", "-tune", "ll", "-rc", "cbr", "-b:v", "6M")),
        /** The Mesa/Intel/AMD path. Needs the frame uploaded to the GPU, hence its own filter chain. */
        VAAPI("h264_vaapi", List.of("-rc_mode", "CBR", "-b:v", "6M")),
        /** The one that always works. {@code ultrafast}+{@code zerolatency} is CPU-cheap and never reorders. */
        X264("libx264", List.of("-preset", "ultrafast", "-tune", "zerolatency", "-crf", "26"));

        final String codecName;
        final List<String> extra;

        Encoder(String codecName, List<String> extra) {
            this.codecName = codecName;
            this.extra = extra;
        }
    }

    /** How a candidate's command line becomes a running process — the reaper in production, a stub in tests. */
    @FunctionalInterface
    public interface Spawner {
        Process spawn(List<String> command) throws IOException;
    }

    private final Consumer<VideoPacket> sink;
    private final Spawner spawner;
    private final PaintedSurface surface;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Process current;
    private volatile boolean producing;

    private FfmpegVideoStream(Consumer<VideoPacket> sink, Spawner spawner, PaintedSurface surface) {
        this.sink = sink;
        this.spawner = spawner;
        this.surface = surface;
    }

    /**
     * Starts encoding {@code surface} on {@code display}, downscaled so its long edge is at most
     * {@code maxEdge}, at {@code fps}. Returns at once; the encoder is chosen on a background thread and
     * {@link #alive()} turns true when one of them produces a picture.
     *
     * <p><b>A surface, not the display.</b> This grabbed {@code -i :N} — the root — until a gamescope session
     * showed what that is worth: its compositor never paints the root, so the stream was valid, healthy and
     * black. {@link PaintedSurface} is the same choice the JPEG path makes per frame, made once here, and it
     * becomes {@code -window_id} when it is a client window.
     *
     * <p>The surface's <em>size</em> is what is grabbed and its rect is what {@link #surface()} reports;
     * neither changes with the downscale, because the pilot's client fits and maps touches through the
     * declared rect rather than through the bitmap's own pixels — exactly as on the JPEG path.
     */
    public static FfmpegVideoStream open(String display, PaintedSurface surface, int maxEdge, int fps,
                                         Consumer<VideoPacket> sink, Spawner spawner) {
        FfmpegVideoStream stream = new FfmpegVideoStream(sink, spawner, surface);
        Thread t = new Thread(() -> stream.run(display, surface, maxEdge, fps), "session-video");
        t.setDaemon(true);
        t.start();
        return stream;
    }

    @Override
    public boolean alive() {
        Process p = current;
        return producing && !closed.get() && p != null && p.isAlive();
    }

    @Override
    public String codec() {
        return CODEC;
    }

    @Override
    public Rectangle surface() {
        return new Rectangle(surface.rect());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        producing = false;
        Process p = current;
        current = null;
        if (p != null) {
            p.destroy();
        }
    }

    // --- the candidate walk ---

    private void run(String display, PaintedSurface target, int maxEdge, int fps) {
        for (Encoder encoder : Encoder.values()) {
            if (closed.get()) {
                return;
            }
            if (attempt(encoder, display, target, maxEdge, fps)) {
                return;
            }
        }
        Diag.error("[Session] no ffmpeg encoder produced a frame for " + display + " — the pilot stays on JPEG");
    }

    /**
     * Runs one candidate to completion. Returns true if it produced video at all — in which case this call has
     * already blocked for the whole life of the stream and there is nothing left to fall back to.
     */
    private boolean attempt(Encoder encoder, String display, PaintedSurface target, int maxEdge, int fps) {
        List<String> command = command(encoder, display, target, maxEdge, fps);
        Process process;
        try {
            process = spawner.spawn(command);
        } catch (Exception cannotStart) {
            Diag.log("[Session] video: " + encoder.codecName + " would not start (" + cannotStart + ")");
            return false;
        }
        current = process;
        CountDownLatch first = new CountDownLatch(1);
        AnnexB splitter = new AnnexB(packet -> {
            producing = true;
            first.countDown();
            if (!closed.get()) {
                sink.accept(packet);
            }
        });
        Thread reader = new Thread(() -> pump(process, splitter), "session-video-read");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean produced = first.await(FIRST_PACKET_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!produced) {
                Diag.log("[Session] video: " + encoder.codecName + " produced nothing in "
                        + FIRST_PACKET_TIMEOUT_MS + "ms — trying the next encoder");
                process.destroy();
                return false;
            }
            Diag.log("[Session] video: encoding " + display + " with " + encoder.codecName);
            reader.join();               // this candidate owns the stream now; stay until its encoder ends
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroy();
            return true;                 // a shutdown, not a failed candidate — do not start another encoder
        } finally {
            producing = false;
        }
    }

    private void pump(Process process, AnnexB splitter) {
        byte[] chunk = new byte[1 << 16];
        try (InputStream in = process.getInputStream()) {
            int read;
            while ((read = in.read(chunk)) > 0) {
                splitter.feed(chunk, read);
            }
            splitter.finish();
        } catch (IOException pipeClosed) {
            // The encoder went away — close() or a crash. Either way there is nothing more to read.
        }
    }

    // --- the command line ---

    /**
     * The full {@code ffmpeg} invocation for one candidate. Package-visible so the argument shape is asserted
     * in a test rather than discovered when a stream silently produces nothing.
     *
     * <p>Notable choices: {@code -bf 0} (no B-frames — they reorder, and the point here is latency), a GOP of
     * two seconds so a client joining mid-stream waits at most that long for its entry point, {@code -nostdin}
     * so a backgrounded ffmpeg never fights for the terminal, and {@code -f h264 -} for raw Annex-B on stdout
     * rather than a container that would buffer to find its own frame boundaries.
     *
     * <p>{@code -window_id} is the one argument that decides whether this produces a picture at all on a
     * compositing backend, and it is only emitted for a client window: x11grab's default is the root, and
     * passing {@code 0} explicitly is not the same thing to every ffmpeg build. The grab is at the surface's
     * own size and its offset is <em>not</em> passed as {@code +x,y} — that syntax crops the root, whereas a
     * window grab is already in the window's own coordinates.
     */
    static List<String> command(Encoder encoder, String display, PaintedSurface target, int maxEdge, int fps) {
        Rectangle rect = target.rect();
        int[] size = fit(rect.width, rect.height, maxEdge);
        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin"));
        if (encoder == Encoder.VAAPI) {
            cmd.addAll(List.of("-vaapi_device", "/dev/dri/renderD128"));
        }
        cmd.addAll(List.of(
                "-f", "x11grab",
                "-framerate", String.valueOf(fps),
                "-video_size", rect.width + "x" + rect.height,
                "-draw_mouse", "0"));
        if (!target.isRoot()) {
            cmd.addAll(List.of("-window_id", String.valueOf(target.windowId())));
        }
        cmd.addAll(List.of("-i", display));
        if (encoder == Encoder.VAAPI) {
            // The scale has to happen on the GPU side of the upload, or the upload is of a full-size frame.
            cmd.addAll(List.of("-vf", "format=nv12,hwupload,scale_vaapi=" + size[0] + ":" + size[1]));
        } else {
            cmd.addAll(List.of("-vf", "scale=" + size[0] + ":" + size[1] + ":flags=fast_bilinear",
                    "-pix_fmt", "yuv420p"));
        }
        cmd.addAll(List.of("-c:v", encoder.codecName));
        cmd.addAll(encoder.extra);
        cmd.addAll(List.of(
                "-profile:v", encoder == Encoder.VAAPI ? "constrained_baseline" : "baseline",
                "-g", String.valueOf(Math.max(1, fps * 2)),
                "-bf", "0",
                "-flush_packets", "1",
                "-f", "h264", "-"));
        return cmd;
    }

    /**
     * {@code width}×{@code height} shrunk so its long edge is at most {@code maxEdge}, both dimensions rounded
     * to an even number — H.264's 4:2:0 chroma is subsampled by two, so an odd dimension is not encodable and
     * ffmpeg rejects the filter outright rather than rounding for us.
     */
    static int[] fit(int width, int height, int maxEdge) {
        int w = Math.max(2, width);
        int h = Math.max(2, height);
        int longest = Math.max(w, h);
        if (maxEdge > 0 && longest > maxEdge) {
            double scale = (double) maxEdge / longest;
            w = (int) Math.round(w * scale);
            h = (int) Math.round(h * scale);
        }
        return new int[]{Math.max(2, w - (w % 2)), Math.max(2, h - (h % 2))};
    }
}
