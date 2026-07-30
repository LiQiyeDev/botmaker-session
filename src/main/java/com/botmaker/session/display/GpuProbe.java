package com.botmaker.session.display;

import com.botmaker.shared.Diag;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Phase-0 go/no-go probe for the bot-owned-display plan: on <em>this machine</em>, can a nested
 * <b>Xephyr</b> server render hardware 3D, or is it software-only (so a modern-3D target needs
 * <b>gamescope</b> instead)?
 *
 * <p>The whole "flawless background input" design hinges on running the game in its own display so that
 * display's global pointer is exclusively the bot's. Xephyr is the cheap 2D host; whether it can also carry a
 * GL/Vulkan game depends entirely on the host's drivers (glamor over a real GPU vs. a llvmpipe/lavapipe
 * software fallback). Rather than guess, this spins up a throwaway Xephyr, asks {@code glxinfo}/{@code
 * vulkaninfo} what it actually gets, and reports the verdict. Everything is best-effort and never throws —
 * a missing tool or an unbootable Xephyr is a documented "unavailable", not an exception (this runs in
 * Studio's diagnostics panel, where a crash would be worse than a "can't tell").
 *
 * <p>Display allocation uses {@code -displayfd} (the server picks a free number and writes it back), never a
 * scan of {@code /tmp/.X11-unix} — the same race-free choice Phase 2's supervisor needs, proven here first.
 */
public final class GpuProbe {

    /** Scratch resolution for the probe server — small and universally supported. */
    private static final String SCREEN = "1280x720";

    /** How long to wait for Xephyr to pick a display number and write it back on {@code -displayfd}. */
    private static final long DISPLAY_WAIT_MS = 6_000;

    /** Per-command cap for {@code glxinfo}/{@code vulkaninfo} — they can hang on a wedged driver. */
    private static final long CMD_TIMEOUT_MS = 10_000;

    private GpuProbe() {}

    /** How well an API renders on the probed nested display. */
    public enum Support {
        /** A real GPU path (glamor/GL direct rendering, or a non-CPU Vulkan device). */
        HARDWARE,
        /** Only a software rasteriser (llvmpipe / lavapipe / swrast) answered. */
        SOFTWARE,
        /** The API could not be queried at all (tool missing, or no device enumerated). */
        UNAVAILABLE
    }

    /**
     * The probe verdict. {@link #recommendation()} is the one-line human answer; the rest is the evidence it
     * was drawn from, so Studio's panel (and a bug report) can show <em>why</em>.
     *
     * @param xephyrAvailable   whether a nested Xephyr could be started and bound at all
     * @param display           the nested display it bound to (e.g. {@code ":9"}), or {@code null}
     * @param glRenderer        the raw {@code GL_RENDERER} string, or {@code null}
     * @param directRendering   whether {@code glxinfo} reported {@code direct rendering: Yes}
     * @param glSupport         classified OpenGL support on the nested display
     * @param vulkanSupport     classified Vulkan support on the nested display
     * @param gamescopeInstalled whether {@code gamescope} is on {@code PATH} (the 3D fallback)
     * @param recommendation    the one-line decision
     * @param detail            multi-line raw evidence (renderer/device lines, failures)
     */
    public record Result(boolean xephyrAvailable,
                         String display,
                         String glRenderer,
                         boolean directRendering,
                         Support glSupport,
                         Support vulkanSupport,
                         boolean gamescopeInstalled,
                         String recommendation,
                         String detail) {

        /** Whether Xephyr can host a hardware-3D game here (both GL and Vulkan on a real GPU). */
        public boolean xephyrCanDo3d() {
            return glSupport == Support.HARDWARE && vulkanSupport == Support.HARDWARE;
        }

        /** A compact multi-line summary suitable for a diagnostics panel or a log. */
        public String summary() {
            StringBuilder b = new StringBuilder();
            b.append("GPU probe — nested-display 3D capability\n");
            b.append("  Xephyr available : ").append(xephyrAvailable);
            if (display != null) b.append("  (display ").append(display).append(')');
            b.append('\n');
            b.append("  OpenGL           : ").append(glSupport);
            if (glRenderer != null) b.append("  [").append(glRenderer).append(", direct=")
                    .append(directRendering).append(']');
            b.append('\n');
            b.append("  Vulkan           : ").append(vulkanSupport).append('\n');
            b.append("  gamescope on PATH: ").append(gamescopeInstalled).append('\n');
            b.append("  → ").append(recommendation);
            return b.toString();
        }
    }

    /**
     * Runs the full probe: start a throwaway Xephyr, query GL/Vulkan against it, classify, tear it down.
     * Never throws — returns a {@link Result} whose fields describe whatever could (and couldn't) be learned.
     */
    public static Result probe() {
        boolean gamescope = onPath("gamescope");
        if (!onPath("Xephyr")) {
            return new Result(false, null, null, false, Support.UNAVAILABLE, Support.UNAVAILABLE,
                    gamescope, "Xephyr not installed — install xorg-x11-server-Xephyr to use the "
                    + "nested 2D backend. " + gamescopeNote(gamescope), "Xephyr missing from PATH.");
        }

        Process xephyr = null;
        try {
            String[] displayBox = new String[1];
            xephyr = startXephyr(displayBox);
            String display = displayBox[0];
            if (xephyr == null || display == null) {
                return new Result(false, null, null, false, Support.UNAVAILABLE, Support.UNAVAILABLE,
                        gamescope, "Xephyr installed but would not start — is there a host display (DISPLAY) "
                        + "to nest into? " + gamescopeNote(gamescope),
                        "Xephyr did not report a display number within " + DISPLAY_WAIT_MS + "ms.");
            }
            Diag.log("[GpuProbe] Xephyr up on " + display + ", querying GL/Vulkan");

            StringBuilder detail = new StringBuilder();
            GlFacts gl = queryGl(display, detail);
            Support vulkan = queryVulkan(display, detail);

            String recommendation = decide(gl.support(), vulkan, gamescope);
            return new Result(true, display, gl.renderer(), gl.direct(), gl.support(), vulkan,
                    gamescope, recommendation, detail.toString().trim());
        } finally {
            reap(xephyr);
        }
    }

    // --- Xephyr lifecycle ---------------------------------------------------------------------------------

    /**
     * Starts Xephyr with {@code -displayfd 1} so it picks a free display and writes the number to its stdout,
     * then reads that number back. Puts the resolved {@code ":N"} into {@code displayOut[0]}. Returns the live
     * process (or {@code null} if it never reported a number, in which case it has already been reaped).
     */
    private static Process startXephyr(String[] displayOut) {
        ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gpuprobe-xephyr-displayfd");
            t.setDaemon(true);
            return t;
        });
        try {
            // -displayfd 1: server writes the chosen display number to fd 1 (stdout) and closes it; race-free.
            ProcessBuilder pb = new ProcessBuilder("Xephyr", "-displayfd", "1", "-glamor",
                    "-screen", SCREEN, "-nolisten", "tcp", "-noreset");
            pb.redirectError(Redirect.DISCARD);
            Process p = pb.start();
            Future<String> firstLine = reader.submit(readFirstLine(p.getInputStream()));
            String number;
            try {
                number = firstLine.get(DISPLAY_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Diag.log("[GpuProbe] Xephyr display number not received: " + e.getMessage());
                reap(p);
                return null;
            }
            if (number == null || number.isBlank()) {
                reap(p);
                return null;
            }
            displayOut[0] = ":" + number.trim();
            return p;
        } catch (Exception e) {
            Diag.log("[GpuProbe] Xephyr failed to start: " + e.getMessage());
            return null;
        } finally {
            reader.shutdownNow();
        }
    }

    private static Callable<String> readFirstLine(InputStream in) {
        return () -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return r.readLine();
            }
        };
    }

    private static void reap(Process p) {
        if (p == null) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    // --- Queries ------------------------------------------------------------------------------------------

    private record GlFacts(String renderer, boolean direct, Support support) {}

    /** Runs {@code glxinfo} against the nested display and classifies the renderer. */
    private static GlFacts queryGl(String display, StringBuilder detail) {
        if (!onPath("glxinfo")) {
            detail.append("glxinfo not installed — OpenGL support unknown.\n");
            return new GlFacts(null, false, Support.UNAVAILABLE);
        }
        Exec r = run(display, CMD_TIMEOUT_MS, "glxinfo", "-B");
        if (r.exit() != 0 && r.out().isBlank()) {
            detail.append("glxinfo failed on ").append(display).append(": ").append(oneLine(r.err()))
                    .append('\n');
            return new GlFacts(null, false, Support.UNAVAILABLE);
        }
        String renderer = firstMatch(r.out(), "OpenGL renderer string:");
        boolean direct = r.out().toLowerCase().contains("direct rendering: yes");
        Support support = renderer == null ? Support.UNAVAILABLE
                : isSoftware(renderer) ? Support.SOFTWARE
                : Support.HARDWARE;
        detail.append("GL renderer: ").append(renderer == null ? "(none)" : renderer)
                .append("  direct=").append(direct).append('\n');
        return new GlFacts(renderer, direct, support);
    }

    /** Runs {@code vulkaninfo --summary} against the nested display and classifies the device. */
    private static Support queryVulkan(String display, StringBuilder detail) {
        if (!onPath("vulkaninfo")) {
            detail.append("vulkaninfo not installed — Vulkan support unknown.\n");
            return Support.UNAVAILABLE;
        }
        Exec r = run(display, CMD_TIMEOUT_MS, "vulkaninfo", "--summary");
        String out = r.out();
        if (out.isBlank()) {
            detail.append("vulkaninfo produced no device list on ").append(display).append(": ")
                    .append(oneLine(r.err())).append('\n');
            return Support.UNAVAILABLE;
        }
        String lower = out.toLowerCase();
        boolean hasGpu = lower.contains("physical_device_type_discrete_gpu")
                || lower.contains("physical_device_type_integrated_gpu")
                || lower.contains("physical_device_type_virtual_gpu");
        boolean cpuOnly = !hasGpu
                && (lower.contains("physical_device_type_cpu") || lower.contains("lavapipe")
                    || lower.contains("llvmpipe"));
        String deviceName = firstMatch(out, "deviceName");
        detail.append("Vulkan device: ").append(deviceName == null ? "(none enumerated)" : deviceName)
                .append('\n');
        if (hasGpu) {
            return Support.HARDWARE;
        }
        return cpuOnly ? Support.SOFTWARE : Support.UNAVAILABLE;
    }

    // --- Decision -----------------------------------------------------------------------------------------

    private static String decide(Support gl, Support vulkan, boolean gamescope) {
        boolean hwGl = gl == Support.HARDWARE;
        boolean hwVk = vulkan == Support.HARDWARE;
        if (hwGl && hwVk) {
            return "Xephyr gets hardware GL + Vulkan here — usable for 2D and (with care) 3D; "
                    + "gamescope remains the recommended 3D backend for Proton/DXVK titles. "
                    + gamescopeNote(gamescope);
        }
        if (hwGl) {
            return "Xephyr gets hardware GL but no hardware Vulkan — fine for 2D/GL, use gamescope for "
                    + "Vulkan/Proton 3D. " + gamescopeNote(gamescope);
        }
        return "Xephyr is software-rendered here (2D-only) — modern 3D needs gamescope. "
                + gamescopeNote(gamescope);
    }

    private static String gamescopeNote(boolean installed) {
        return installed ? "gamescope is installed." : "gamescope is NOT installed (install it for the 3D backend).";
    }

    // --- Process helpers ----------------------------------------------------------------------------------

    private record Exec(int exit, String out, String err) {}

    /** Runs a command with {@code DISPLAY} set to the nested display, draining both streams, with a timeout. */
    private static Exec run(String display, long timeoutMs, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("DISPLAY", display);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread to = drain(p.getInputStream(), out);
            Thread te = drain(p.getErrorStream(), err);
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                Diag.log("[GpuProbe] " + cmd[0] + " timed out after " + timeoutMs + "ms");
            }
            to.join(1_000);
            te.join(1_000);
            return new Exec(done ? p.exitValue() : -1, out.toString(), err.toString());
        } catch (Exception e) {
            return new Exec(-2, "", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static Thread drain(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sink.append(line).append('\n');
                }
            } catch (Exception ignored) {
                // Best-effort drain; a closed/short stream just ends the capture.
            }
        }, "gpuprobe-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static boolean isSoftware(String renderer) {
        String r = renderer.toLowerCase();
        return r.contains("llvmpipe") || r.contains("softpipe") || r.contains("swrast")
                || r.contains("software rasterizer") || r.contains("lavapipe");
    }

    /** The trimmed remainder of the first line containing {@code needle}, or {@code null}. */
    private static String firstMatch(String haystack, String needle) {
        for (String line : haystack.split("\\R")) {
            int i = line.indexOf(needle);
            if (i >= 0) {
                String rest = line.substring(i + needle.length()).trim();
                // vulkaninfo prints "deviceName = <x>"; strip a leading '=' if present.
                if (rest.startsWith("=")) {
                    rest = rest.substring(1).trim();
                }
                if (!rest.isEmpty()) {
                    return rest;
                }
            }
        }
        return null;
    }

    private static String oneLine(String s) {
        if (s == null || s.isBlank()) {
            return "(no output)";
        }
        return s.strip().split("\\R")[0];
    }

    /** Whether {@code exe} is an executable file on any {@code PATH} entry. */
    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File f = new File(dir, exe);
            if (f.canExecute() && f.isFile()) {
                return true;
            }
        }
        return false;
    }

    /** Runs the probe and prints the verdict — the Phase-0 "documented decision" entry point. */
    public static void main(String[] args) {
        System.out.println(probe().summary());
    }
}
