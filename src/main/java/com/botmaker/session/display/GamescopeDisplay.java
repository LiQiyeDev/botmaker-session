package com.botmaker.session.display;

import com.botmaker.session.SessionStartException;
import com.botmaker.session.impl.NestedSession;
import com.botmaker.session.impl.SessionHostWindow;
import com.botmaker.session.process.SessionReaper;

import com.botmaker.shared.Diag;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A nested <b>gamescope</b> compositor the bot owns — the hardware-3D counterpart to {@link NestedDisplay}'s
 * Xephyr. gamescope embeds its own Xwayland, so a game inside it gets a real GPU (GL/Vulkan/DXVK/Proton) that
 * Xephyr's software path can't carry, while still giving the bot a private display whose global pointer and
 * focus are its alone. It slots behind the same {@link SessionDisplay} seam, so {@link NestedSession}'s
 * supervisor — launch the game, find its window, inject XTest, reap the tree — drives it unchanged; the only
 * differences are how the server is spawned and that this one reports {@link #hardwareAccelerated()}.
 *
 * <p><b>Display-number discovery.</b> Unlike Xephyr, gamescope has no {@code -displayfd}: it sets {@code DISPLAY}
 * only for a child it launches, and otherwise announces its embedded Xwayland on <em>stderr</em>
 * ({@code Starting Xwayland on :N}). We run gamescope in its standalone-compositor form (no {@code --} child —
 * the SteamOS session model, where gamescope hosts an Xwayland that apps connect to with {@code DISPLAY=:N}),
 * read that stderr line by line on a {@link StderrWatcher} thread, and {@link #parseDisplayNumber parse the
 * number} back out of the banner as it arrives rather than polling for it. That keeps
 * {@link NestedSession}'s "start the display, then launch the game into it" flow identical to the Xephyr path.
 * Readiness is still gated on a real {@link DisplayReadiness#awaitConnectable}, never a {@code sleep}.
 *
 * <p><b>Bring-up note (unverified on the dev box).</b> This backend is implemented and unit-tested against
 * gamescope's known stderr formats, but has <em>not</em> been live-run — the development machine has no
 * {@code gamescope} binary (and only software GL). On a real GPU+gamescope box, if the standalone-host form
 * proves fragile (a gamescope build that exits without a {@code --} child, or a stderr banner this parser
 * doesn't match), the documented fallback is the child form: launch the game <em>as</em> gamescope's child so
 * it inherits {@code DISPLAY}, and read the number from the same stderr. The default gamescope argv is
 * overridable via {@link NestedSession.Options}, so that switch needs no code change here.
 */
public final class GamescopeDisplay implements SessionDisplay {

    /** How long to wait for gamescope to announce its Xwayland display, then for that display to accept a connection. */
    private static final long START_TIMEOUT_MS = 15_000;

    /**
     * How many recent stderr lines to keep for a failure message. gamescope's reason for not coming up (no DRM
     * master, no Vulkan device, an argv it doesn't understand) is on stderr and used to be dropped on the floor:
     * the banner was parsed out of a temp file that nothing ever read back. A bounded tail costs nothing and is
     * the difference between "did not announce a display" and knowing why.
     */
    private static final int KEPT_STDERR_LINES = 40;

    /** gamescope's stderr banner for its embedded server, e.g. {@code wlserver: Starting Xwayland on :1}. */
    private static final Pattern XWAYLAND_ON = Pattern.compile("(?i)xwayland on (:\\d+)");

    /**
     * gamescope's banner for the Wayland socket {@code --expose-wayland} serves, e.g.
     * {@code wlserver: Running compositor on wayland display 'gamescope-0'}.
     */
    private static final Pattern WAYLAND_ON = Pattern.compile("(?i)on wayland display '([^']+)'");

    private final String displayName;
    private final int width;
    private final int height;
    private final Process server;
    /** Kept live rather than snapshotted: the two banners have no guaranteed order (see {@link #waylandDisplay}). */
    private final StderrWatcher stderr;

    private GamescopeDisplay(String displayName, int width, int height, Process server, StderrWatcher stderr) {
        this.displayName = displayName;
        this.width = width;
        this.height = height;
        this.server = server;
        this.stderr = stderr;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public boolean alive() {
        return server.isAlive();
    }

    @Override
    public long serverPid() {
        return server.pid();
    }

    @Override
    public boolean hardwareAccelerated() {
        return true; // gamescope's embedded Xwayland renders on the real GPU — the whole reason to use it.
    }

    /**
     * The {@code --expose-wayland} socket, e.g. {@code gamescope-0}, or {@code null} if gamescope hasn't
     * announced one (an older build, or an argv that dropped the flag).
     *
     * <p>Read from the watcher on each call rather than captured at construction: bring-up completes on the
     * <em>Xwayland</em> banner, and while gamescope in practice announces its Wayland socket first (the
     * compositor is up before it starts an Xwayland on top of it), nothing in its output contract promises
     * that order. Reading live costs a volatile load and removes the race entirely.
     */
    @Override
    public String waylandDisplay() {
        return stderr.waylandDisplay();
    }

    /**
     * The default standalone gamescope argv for a {@code width}x{@code height} host (no {@code --} child).
     *
     * <p>{@code -W/-H} are the output (the nested window on the real desktop) and {@code -w/-h} the internal
     * resolution apps see; both are the project's authored resolution, so what the bot captures is 1:1 with what
     * its templates were made at — no upscaler in between. {@code --force-windows-fullscreen} makes the game
     * fill the display rather than open some default-sized window in a corner of it, which is what the capture
     * and the click coordinates assume.
     *
     * <p>{@code --expose-wayland} makes gamescope serve native Wayland clients over an {@code xdg-shell} socket
     * alongside its Xwayland, at no cost to X11 clients. It is on by default because the alternative is a
     * session that silently cannot host one: Waydroid's {@code show-full-ui} has no X11 path at all, and on an
     * X11 desktop a compositor of its own is the only place it can run. See {@link Capability#WAYLAND_CLIENTS}.
     *
     * <p>The nested window is <b>visible</b> on purpose: a background session you cannot look at is impossible
     * to debug, and seeing the bot play is half the point. It is not visible <em>immediately</em>, though:
     * gamescope maps its output window the instant it starts, and nothing is drawn into it until the game — or a
     * store launcher, minutes later — maps a window on the Xwayland, so {@link SessionHostWindow} minimizes it
     * for that stretch and restores it on the session's first attach. For a genuinely invisible run, override this argv
     * (via {@link NestedSession.Options#withGamescopeCommand}) with {@code --backend headless} — gamescope still
     * hosts a GPU-backed Xwayland with no output window. That path is documented, not verified: whether an
     * X11 window capture of a headless gamescope reads real pixels is exactly the sort of thing to confirm on a
     * live box before relying on it.
     */
    public static List<String> defaultCommand(int width, int height) {
        String w = Integer.toString(width);
        String h = Integer.toString(height);
        // No child command, so gamescope stays up hosting its Xwayland for apps we launch afterwards with
        // DISPLAY=:N. A caller can override this whole argv via Options.
        return List.of("gamescope", "-W", w, "-H", h, "-w", w, "-h", h,
            "--force-windows-fullscreen", "--expose-wayland");
    }

    /**
     * Launch gamescope via {@code reaper}, capture its stderr, parse the Xwayland display number it announces,
     * and block until that display accepts a connection.
     *
     * @param command the full gamescope argv (see {@link #defaultCommand}); a caller may override it
     * @throws SessionStartException if gamescope never announces a display, or it never becomes connectable
     */
    public static GamescopeDisplay start(SessionReaper reaper, List<String> command, int width, int height)
            throws SessionStartException {
        Process server;
        try {
            // gamescope announces its Xwayland on stderr; pipe it to a reader (stdout stays discarded).
            server = reaper.launch("gamescope", command, Map.of(), Redirect.DISCARD, Redirect.PIPE);
        } catch (Exception e) {
            throw new SessionStartException("could not launch gamescope (is it installed?): " + e.getMessage(), e);
        }

        StderrWatcher stderr = StderrWatcher.watch(server);
        long spawned = System.currentTimeMillis();
        String display = awaitDisplay(stderr, server);
        long announced = System.currentTimeMillis();
        DisplayReadiness.awaitConnectable(display, server, START_TIMEOUT_MS);
        // Split because the two halves have entirely different owners: the first is gamescope's own bring-up and
        // nothing here can shorten it, the second is ours to poll for. Whenever a session start feels slow, this
        // line says which of the two to go and look at.
        Diag.log("[Session] nested gamescope display " + display + " up (" + width + "x" + height + ") — announced "
            + (announced - spawned) + "ms after spawn, connectable " + (System.currentTimeMillis() - announced)
            + "ms later");
        return new GamescopeDisplay(display, width, height, server, stderr);
    }

    /**
     * Wait for the {@code Starting Xwayland on :N} banner the reader thread is watching for, or fail when
     * gamescope dies or the budget runs out.
     *
     * <p>Waiting on the stream rather than polling a file is worth the reader thread: the banner is acted on the
     * moment gamescope writes it instead of up to a poll interval later, and that interval sat on the critical
     * path of every session bring-up. It also means a launch that fails can quote what gamescope said.
     */
    private static String awaitDisplay(StderrWatcher stderr, Process server) throws SessionStartException {
        try {
            return stderr.display().get(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new SessionStartException("gamescope did not announce an Xwayland display within "
                + START_TIMEOUT_MS + "ms" + stderr.tail());
        } catch (ExecutionException e) {
            throw new SessionStartException("gamescope exited before announcing an Xwayland display (exit "
                + (server.isAlive() ? "?" : server.exitValue()) + ") — check that it can start a nested "
                + "compositor here" + stderr.tail());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionStartException("interrupted while waiting for gamescope to announce a display", e);
        }
    }

    /**
     * Drains gamescope's stderr for the whole life of the process, completing {@link #display()} on the Xwayland
     * banner and keeping the last {@link #KEPT_STDERR_LINES} lines for a failure message.
     *
     * <p><b>The draining is not optional.</b> A piped stream nobody reads fills its pipe buffer and then blocks
     * the writer — so a reader that stopped at the banner would hang gamescope a few thousand log lines into a
     * session. That is why this outlives the bring-up it exists for, on a daemon thread that ends at EOF.
     */
    private static final class StderrWatcher implements Runnable {

        private final Process server;
        private final CompletableFuture<String> display = new CompletableFuture<>();
        /** Guarded by itself — written by the reader thread, read by whoever is building a failure message. */
        private final Deque<String> recent = new ArrayDeque<>();
        /**
         * The {@code --expose-wayland} socket name. Not a future like {@link #display}: nothing waits for it,
         * because a session without one is merely a session that cannot host Wayland clients, not a failed
         * start. Volatile for the reader-thread → caller handoff.
         */
        private volatile String waylandDisplay;

        private StderrWatcher(Process server) {
            this.server = server;
        }

        static StderrWatcher watch(Process server) {
            StderrWatcher watcher = new StderrWatcher(server);
            Thread t = new Thread(watcher, "gamescope-stderr-" + server.pid());
            t.setDaemon(true);
            t.start();
            // Belt and braces for the one case EOF doesn't cover: a grandchild holding the stderr fd open past
            // gamescope's own exit would otherwise leave the wait to time out instead of failing fast.
            server.onExit().thenRun(() -> watcher.display.completeExceptionally(
                new IllegalStateException("gamescope exited")));
            return watcher;
        }

        CompletableFuture<String> display() {
            return display;
        }

        /** The announced Wayland socket, or null if gamescope hasn't named one. */
        String waylandDisplay() {
            return waylandDisplay;
        }

        /** The kept stderr tail, formatted for a failure message, or empty when gamescope said nothing at all. */
        String tail() {
            List<String> lines;
            synchronized (recent) {
                lines = List.copyOf(recent);
            }
            return lines.isEmpty() ? "" : "; gamescope said:\n" + String.join("\n", lines);
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(server.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (recent) {
                        recent.addLast(line);
                        if (recent.size() > KEPT_STDERR_LINES) {
                            recent.removeFirst();
                        }
                    }
                    if (!display.isDone()) {
                        String announced = parseDisplayNumber(line);
                        if (announced != null) {
                            display.complete(announced);
                        }
                    }
                    if (waylandDisplay == null) {
                        String socket = parseWaylandDisplay(line);
                        if (socket != null) {
                            waylandDisplay = socket;
                        }
                    }
                }
            } catch (Exception ignored) {
                // The stream went away; the completion below turns that into the same failure as an exit.
            }
            display.completeExceptionally(new IllegalStateException("gamescope's stderr ended"));
        }
    }

    /**
     * Extract the {@code :N} display from gamescope's stderr, or {@code null} if it hasn't announced one yet.
     * Matches the {@code Starting Xwayland on :N} banner case-insensitively across gamescope versions (some
     * prefix it with {@code wlserver:}); the first match wins (the primary Xwayland).
     */
    public static String parseDisplayNumber(String stderr) {
        if (stderr == null || stderr.isEmpty()) {
            return null;
        }
        Matcher m = XWAYLAND_ON.matcher(stderr);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Extract the {@code --expose-wayland} socket name from gamescope's
     * {@code Running compositor on wayland display 'gamescope-0'} banner, or {@code null} for any other line.
     *
     * <p>Matched on the quoted name rather than the whole sentence: the prefix differs between builds
     * ({@code wlserver:}, a timestamp, nothing at all) and the socket name is the only part that is contract.
     */
    public static String parseWaylandDisplay(String stderr) {
        if (stderr == null || stderr.isEmpty()) {
            return null;
        }
        Matcher m = WAYLAND_ON.matcher(stderr);
        return m.find() ? m.group(1) : null;
    }

}
