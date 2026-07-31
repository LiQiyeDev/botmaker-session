package com.botmaker.session.process;

import com.botmaker.shared.Diag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Which processes belong to a nested session — when the cgroup can no longer say.</b>
 *
 * <p>{@link SessionReaper} groups everything it spawns in one systemd slice, which is what makes "{@code kill -9}
 * the JVM ⇒ zero orphans" true. For a Flatpak target that guarantee is <em>false</em>, and the reason is not
 * ours to fix: {@code flatpak run} moves the app into its own transient {@code app-flatpak-<id>-<n>.scope} under
 * {@code app.slice}, and it does so over {@code /run/user/<uid>/systemd/private} — the systemd user manager's
 * private socket, not D-Bus, so the session's private bus cannot intercept it and there is no flag to disable
 * it. The evidence is in the crash itself:
 *
 * <pre>
 * PID: 312266 (heroic)   Signal: 5 (TRAP)
 * Command Line: /app/bin/heroic/heroic --no-gui --no-sandbox heroic://launch/43d4…   &lt;- our launch argv
 * Control Group: /user.slice/…/app.slice/app-flatpak-com.heroicgameslauncher.hgl-3053722396.scope
 * </pre>
 *
 * <p>Our argv, someone else's cgroup. So {@code systemctl --user stop <our slice>} never signalled the launcher
 * or the game at all: it killed gamescope, Xwayland {@code :N} vanished under a live Chromium, and Chromium's
 * X11 IO-error path aborted. That {@code SIGTRAP} coredump on every teardown was not a cosmetic wart — it was
 * the reap path being blind to the processes it most needs to reap, and the <em>only</em> reason nothing was
 * left running afterwards. A launcher that survives losing its X connection would simply have leaked.
 *
 * <p><b>So membership is asked of the environment instead.</b> Every process in the chain carries the session's
 * {@code DISPLAY=:N} — or its unique private bus address — and neither survives being handed to a portal by
 * accident: they are there because we put them there. A live run shows the whole escaped chain that way
 * ({@code pv-adverb}, {@code wineserver}, {@code umu.exe}, {@code Firestone.exe}), cgroup notwithstanding.
 *
 * <p>Best-effort and total: an unreadable or vanished {@code /proc} entry answers "not a member", never throws.
 * Linux-only by construction ({@code /proc}); on any other OS the scan simply finds nothing.
 */
public final class SessionMembers {

    private static final Path PROC = Path.of("/proc");
    private static final long POLL_MS = 100;
    /**
     * How long the launcher gets to shut itself — and its game — down before it is killed. Sized off the thing it
     * has to accommodate: closing a game's windows and flushing a Wine prefix. It is only ever a ceiling; a
     * launcher that exits promptly moves teardown straight on.
     */
    private static final long GRACE_MS = 8_000;

    /**
     * How long each <em>remnant</em> gets after the launcher has had its full {@link #GRACE_MS} — a helper, a
     * detached {@code wineserver}, something that outlived its parent. Deliberately short: these have no
     * shutdown of their own worth waiting for, and the reason they are signalled one at a time is the ordering
     * (nothing live watching a sibling die), not politeness. It also bounds the walk — a session's chain runs to
     * ~35 processes, so a per-process 8 s would be a teardown measured in minutes.
     */
    private static final long REMNANT_GRACE_MS = 500;

    private SessionMembers() {}

    /**
     * Every live process that belongs to this session — the launcher, the game and everything their chain
     * spawned, wherever it ended up in the cgroup tree.
     *
     * <p>Two exclusions carry the correctness of this whole class:
     * <ul>
     *   <li><b>This JVM and its ancestors.</b> A bot process that started the session is very likely to carry the
     *       session's environment itself, and signalling it is how a teardown turns into a suicide — the same
     *       class of bug as the {@code pkill -f} that killed the launching JVM. Descendants are deliberately
     *       <em>not</em> spared: under the no-systemd fallback the session's processes are exactly our
     *       descendants.</li>
     *   <li><b>The session's own infrastructure</b> — {@code dbus-daemon} and the window manager are launched
     *       <em>with</em> {@code DISPLAY=:N}, so they match the environment test and would be killed alongside
     *       the game, which is precisely the ordering this exists to prevent. They are excluded by cgroup rather
     *       than by tracked pid, so the display server's children (gamescope's Xwayland) are covered too.</li>
     * </ul>
     *
     * @param displayName  the session's display, e.g. {@code ":1"}
     * @param busAddress   the session's private bus address, or {@code null} when it has none
     * @param infraUnits   systemd unit names whose members are session infrastructure, not payload
     */
    public static List<ProcessHandle> of(String displayName, String busAddress, Collection<String> infraUnits) {
        String displayEnv = displayName == null ? null : "DISPLAY=" + displayName;
        String busEnv = busAddress == null ? null : "DBUS_SESSION_BUS_ADDRESS=" + busAddress;
        if (displayEnv == null && busEnv == null) {
            return List.of();
        }
        Set<Long> excluded = selfAndAncestors();
        List<ProcessHandle> members = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(p -> {
            if (excluded.contains(p.pid()) || !mentions(environ(p.pid()), displayEnv, busEnv)
                    || isInfrastructure(p.pid(), infraUnits)) {
                return;
            }
            members.add(p);
        });
        return List.copyOf(members);
    }

    /**
     * Ask the session's payload to exit, then insist — <b>oldest process first</b>.
     *
     * <p>The order is the whole fix, and it took three live runs to find the right one. Signalling all 35
     * processes of a launcher's tree at once left the {@code SIGTRAP} coredump exactly where it was; so did
     * walking the process tree parents-first. The reason is that under Flatpak the tree lies: {@code zypak}
     * reparents Chromium's renderer and zygote helpers onto {@code flatpak-portal}, so they are <em>not</em>
     * children of the launcher — a parents-first walk ranks them ahead of it and kills them underneath a live
     * browser process, which Chromium treats as unrecoverable and aborts on.
     *
     * <p>Start time cannot be lied about that way: a process always starts after whatever spawned it, whoever it
     * is later reparented to. So the oldest member — the launcher itself — is asked to exit first and given room
     * to take its own tree down, which is what a clean shutdown looks like.
     *
     * <p>If it refuses that invitation, it is {@link ProcessHandle#destroyForcibly() killed} before anything else
     * is touched. A {@code SIGKILL} cannot be handled, so it cannot abort, so it cannot dump — a launcher that
     * ignores {@code SIGTERM} costs a hard kill, never a coredump.
     *
     * <p>Everything the launcher left behind is then walked the same way, <b>one process at a time</b> — see
     * {@link #terminateOneByOne}. The ordering has to hold for the whole teardown, not just for its first step:
     * a single sweep across the survivors is the same "helpers killed underneath a live supervisor" mistake in
     * miniature.
     *
     * @return the processes still alive at the end — normally empty; a non-empty list is worth logging, because
     *         the slice reap that follows will not reach them either
     */
    public static List<ProcessHandle> shutdown(Collection<ProcessHandle> members, long timeoutMs) {
        List<ProcessHandle> ordered = inStartOrder(members);
        if (ordered.isEmpty()) {
            return List.of();
        }
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        ProcessHandle eldest = ordered.get(0);
        eldest.destroy();
        // Wait on the whole set, not just on it: a launcher that shuts down properly takes the game, the Wine
        // prefix and its own helpers with it, and then there is nothing left to signal at all.
        awaitExit(ordered, Math.min(deadline, start + GRACE_MS));
        Diag.log("[Session] asked " + describe(eldest) + " to exit: " + aliveIn(ordered).size() + " of "
            + ordered.size() + " session process(es) still alive after " + (System.currentTimeMillis() - start) + "ms");
        if (eldest.isAlive()) {
            // Before its children, always: it must not be alive to watch them die.
            eldest.destroyForcibly();
            awaitExit(List.of(eldest), Math.min(deadline, System.currentTimeMillis() + GRACE_MS));
        }
        // Whatever the launcher left behind — a detached wineserver, a helper that outlived its parent. One at a
        // time, oldest first, each finished off before the next is touched (see terminateOneByOne).
        terminateOneByOne(ordered, deadline);
        List<ProcessHandle> stubborn = aliveIn(ordered);
        stubborn.forEach(ProcessHandle::destroyForcibly);
        if (!stubborn.isEmpty()) {
            // A forcible kill is delivered asynchronously; give it the one poll it needs before reporting.
            sleep();
        }
        return aliveIn(ordered);
    }

    /**
     * Terminate the remaining members <b>one at a time</b>, oldest first: ask, give it {@link #REMNANT_GRACE_MS},
     * kill it if it's still there, then move on.
     *
     * <p>What this replaced was a single sweep — {@code SIGTERM} to every survivor at once, then one wait. That
     * is precisely "kill a browser's helpers underneath it", the thing the ordering at the top of
     * {@link #shutdown} exists to avoid, and it is what ran immediately before the coredump on the run that
     * still produced one. Doing the eldest right and then abandoning the ordering for everything behind it kept
     * the bug alive at a smaller scale: any surviving supervisor still got to watch its own helpers die.
     *
     * <p>Each remnant is finished off before the next is signalled, so at no point is a live process watching a
     * sibling be killed. Members that exited on their own — the common case, since killing a parent usually
     * takes its children with it — are skipped rather than re-signalled, and the whole walk stops at
     * {@code deadline} so a slow member can't spend the entire teardown budget. The
     * {@link #shutdown} caller's final {@code destroyForcibly} sweep remains the backstop for anything left.
     */
    public static void terminateOneByOne(Collection<ProcessHandle> ordered, long deadline) {
        for (ProcessHandle p : ordered) {
            if (!p.isAlive()) {
                continue;
            }
            if (System.currentTimeMillis() >= deadline) {
                return;
            }
            p.destroy();
            awaitExit(List.of(p), Math.min(deadline, System.currentTimeMillis() + REMNANT_GRACE_MS));
            if (p.isAlive()) {
                p.destroyForcibly();
                awaitExit(List.of(p), Math.min(deadline, System.currentTimeMillis() + REMNANT_GRACE_MS));
            }
        }
    }

    /**
     * {@code members} oldest first — the only ordering of a process set that survives reparenting, and therefore
     * the only one that reliably puts a launcher ahead of the helpers it spawned. A member whose start time can't
     * be read sorts last: it is more likely a helper than the launcher we are looking for.
     */
    public static List<ProcessHandle> inStartOrder(Collection<ProcessHandle> members) {
        return members.stream()
            .sorted(Comparator.comparing(p -> p.info().startInstant().orElse(Instant.MAX)))
            .toList();
    }

    private static List<ProcessHandle> aliveIn(Collection<ProcessHandle> members) {
        return members.stream().filter(ProcessHandle::isAlive).toList();
    }

    /** Poll until nothing in {@code members} is alive, or {@code deadline} passes. */
    private static void awaitExit(Collection<ProcessHandle> members, long deadline) {
        while (System.currentTimeMillis() < deadline && members.stream().anyMatch(ProcessHandle::isAlive)) {
            sleep();
        }
    }

    /** This process and its ancestors — never our own session's payload, always fatal to signal. */
    private static Set<Long> selfAndAncestors() {
        Set<Long> pids = new HashSet<>();
        ProcessHandle p = ProcessHandle.current();
        while (p != null && pids.add(p.pid())) {
            p = p.parent().orElse(null);
        }
        return pids;
    }

    /**
     * Whether an environment block sets one of the session's markers. Compared entry-by-entry rather than as a
     * substring, so {@code DISPLAY=:1} does not claim {@code :11}; {@code DISPLAY=:1.0} (a screen-qualified form
     * of the same display) still counts.
     */
    private static boolean mentions(String environ, String displayEnv, String busEnv) {
        if (environ == null) {
            return false;
        }
        for (String entry : environ.split("\0")) {
            if (busEnv != null && entry.equals(busEnv)) {
                return true;
            }
            if (displayEnv != null && (entry.equals(displayEnv) || entry.startsWith(displayEnv + "."))) {
                return true;
            }
        }
        return false;
    }

    /** A process's raw environment block ({@code NUL}-separated), or {@code null} if it can't be read. */
    private static String environ(long pid) {
        return read(PROC.resolve(String.valueOf(pid)).resolve("environ"));
    }

    /** Whether {@code pid} lives in one of the session's own infrastructure units. */
    private static boolean isInfrastructure(long pid, Collection<String> infraUnits) {
        if (infraUnits == null || infraUnits.isEmpty()) {
            return false;
        }
        String cgroup = read(PROC.resolve(String.valueOf(pid)).resolve("cgroup"));
        return cgroup != null && infraUnits.stream().anyMatch(cgroup::contains);
    }

    /** Read a {@code /proc} file, or {@code null} — the process may have exited, or belong to another user. */
    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Log-friendly identification of a process that would not die, for the rare non-empty survivor list. */
    public static String describe(ProcessHandle p) {
        return p.pid() + " (" + p.info().command().map(c -> c.substring(c.lastIndexOf('/') + 1)).orElse("?") + ")";
    }
}
