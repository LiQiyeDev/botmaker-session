package com.botmaker.session.display;

import com.botmaker.session.SessionStartException;
import com.botmaker.session.impl.NestedSession;
import com.botmaker.session.impl.SessionHostWindow;
import com.botmaker.session.process.SessionReaper;

import com.botmaker.shared.Diag;

import java.io.File;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
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
 * capture that stderr, and {@link #parseDisplayNumber parse the number} back out. That keeps
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
    private static final long POLL_MS = 150;

    /** gamescope's stderr banner for its embedded server, e.g. {@code wlserver: Starting Xwayland on :1}. */
    private static final Pattern XWAYLAND_ON = Pattern.compile("(?i)xwayland on (:\\d+)");

    private final String displayName;
    private final int width;
    private final int height;
    private final Process server;

    private GamescopeDisplay(String displayName, int width, int height, Process server) {
        this.displayName = displayName;
        this.width = width;
        this.height = height;
        this.server = server;
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
     * The default standalone gamescope argv for a {@code width}x{@code height} host (no {@code --} child).
     *
     * <p>{@code -W/-H} are the output (the nested window on the real desktop) and {@code -w/-h} the internal
     * resolution apps see; both are the project's authored resolution, so what the bot captures is 1:1 with what
     * its templates were made at — no upscaler in between. {@code --force-windows-fullscreen} makes the game
     * fill the display rather than open some default-sized window in a corner of it, which is what the capture
     * and the click coordinates assume.
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
        return List.of("gamescope", "-W", w, "-H", h, "-w", w, "-h", h, "--force-windows-fullscreen");
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
        File err;
        Process server;
        try {
            err = SessionReaper.tempOutputFile("botmaker-gamescope-");
            // gamescope announces its Xwayland on stderr; capture it (stdout stays discarded).
            server = reaper.launch("gamescope", command, Map.of(), Redirect.DISCARD, Redirect.appendTo(err));
        } catch (Exception e) {
            throw new SessionStartException("could not launch gamescope (is it installed?): " + e.getMessage(), e);
        }

        String display = awaitDisplay(err, server);
        DisplayReadiness.awaitConnectable(display, server, START_TIMEOUT_MS);
        Diag.log("[Session] nested gamescope display " + display + " up (" + width + "x" + height + ")");
        return new GamescopeDisplay(display, width, height, server);
    }

    /** Poll gamescope's stderr file until its {@code Starting Xwayland on :N} banner appears, or time out / it dies. */
    private static String awaitDisplay(File err, Process server) throws SessionStartException {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String display = parseDisplayNumber(readAll(err));
            if (display != null) {
                return display;
            }
            if (!server.isAlive()) {
                throw new SessionStartException("gamescope exited before announcing an Xwayland display "
                    + "(exit " + server.exitValue() + ") — check that it can start a nested compositor here");
            }
            sleep();
        }
        throw new SessionStartException("gamescope did not announce an Xwayland display within "
            + START_TIMEOUT_MS + "ms");
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

    private static String readAll(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
