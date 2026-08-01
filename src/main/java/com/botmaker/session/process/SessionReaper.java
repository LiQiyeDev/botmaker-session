package com.botmaker.session.process;

import com.botmaker.session.display.GamescopeDisplay;

import com.botmaker.shared.Diag;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Launches — and, above all, <em>reliably reaps</em> — the process tree of one nested session (the X server, a
 * window manager, the game). A nested session spawns several long-lived processes that must all die together;
 * the failure mode this class exists to prevent is orphans — a Xephyr/game left running after the bot JVM went
 * away, holding a display number and GPU memory.
 *
 * <p>Two strategies, chosen once at construction:
 * <ul>
 *   <li><b>systemd</b> (preferred, when {@code systemd-run --user --scope} works): every process is launched as
 *       a transient {@code --scope} in a shared per-session {@code .slice}, so a single
 *       {@code systemctl --user stop <slice>} tears the whole cgroup down <em>even if this JVM was
 *       {@code SIGKILL}ed</em> — the guarantee a by-name {@code pkill} can't make. {@code --scope} keeps the
 *       payload's stdio inherited from the launching {@link ProcessBuilder}, so a caller can still read a
 *       child's {@code -displayfd} output.</li>
 *   <li><b>fallback</b> (no user systemd): plain {@link ProcessBuilder} children, reaped by walking
 *       {@link ProcessHandle#descendants()} and force-destroying the tree. Correct while the JVM is alive;
 *       a hard {@code SIGKILL} of the JVM can still orphan (accepted where systemd isn't available).</li>
 * </ul>
 *
 * <p>Best-effort throughout: a launch failure is thrown to the caller (it can't proceed without the process),
 * but every teardown step is swallowed-and-logged so one failure never blocks the rest of the reap.
 */
public final class SessionReaper {

    /** Cached once per JVM: is a usable per-user systemd around to host transient scopes? */
    private static volatile Boolean systemdAvailable;

    private final String id;
    private final String slice;
    private final boolean useSystemd;
    /**
     * Guards {@link #reaped} together with {@link #launched}. The pair must move as one: a launch that passes the
     * guard, starts its process and only <em>then</em> records it can have the teardown run in between, leaving a
     * live process no slice will stop and no handle will kill — B8, and the exact failure this class exists to
     * prevent. Held only across the flag/list transitions, never across {@link ProcessBuilder#start()} or a kill.
     */
    private final Object lock = new Object();
    /** Guarded by {@link #lock}. */
    private final List<Process> launched = new ArrayList<>();
    /** The roles actually launched, so {@link #unitNamesExcept} names units that exist rather than a fixed list. */
    private final Set<String> roles = ConcurrentHashMap.newKeySet();
    /** Guarded by {@link #lock} for writes; read unguarded only for diagnostics. */
    private boolean reaped;

    public SessionReaper(String sessionId) {
        this(sessionId, systemdAvailable());
    }

    /**
     * The strategy seam, for tests. {@link #systemdAvailable()} is a cached static probe with no injection point,
     * so on a systemd machine the <b>fallback</b> path — the one where the {@link #launched} list is the entire
     * teardown, and therefore the one B8 actually bites in — is otherwise unreachable and untested. Production
     * code uses {@link #SessionReaper(String)} and lets the probe decide.
     */
    SessionReaper(String sessionId, boolean useSystemd) {
        this.id = sanitize(sessionId);
        this.slice = "botmaker-sess-" + id + ".slice";
        this.useSystemd = useSystemd;
        Diag.log("[Session] reaper for " + id + ": " + (useSystemd ? "systemd scope/slice " + slice
            : "process-tree (no user systemd)"));
    }

    /** Whether this reaper groups processes in a systemd slice (vs. plain child processes). */
    public boolean usesSystemd() {
        return useSystemd;
    }

    /**
     * The transient unit names of every role launched so far except {@code role} — the cgroup fingerprint of this
     * session's <em>infrastructure</em>, which {@link SessionMembers} needs in order to leave the display server,
     * the private bus and the window manager alone while it terminates the payload. Empty under the no-systemd
     * fallback, where there are no units (and the payload is simply our own descendants).
     */
    public Collection<String> unitNamesExcept(String role) {
        if (!useSystemd) {
            return List.of();
        }
        return roles.stream().filter(r -> !r.equals(role)).map(r -> "botmaker-sess-" + id + "-" + r).toList();
    }

    /**
     * Launch {@code command} (with {@code env} overlaid on the inherited environment) as a member of this
     * session's reap group, sending its stdout to {@code stdout}. {@code role} names the transient unit for
     * diagnosability (e.g. {@code "xephyr"}, {@code "wm"}, {@code "app"}).
     *
     * <p>Refused with {@link IllegalStateException} once {@link #reap()} has run — <em>including</em> when the reap
     * lands while this call is inside {@link ProcessBuilder#start()}: the just-started process is destroyed before
     * the exception is thrown, because losing that race must kill the child, not merely decline to track it.
     *
     * @return the launched {@link Process} — the {@code systemd-run --scope} wrapper under the systemd
     *         strategy (whose lifetime tracks the payload's), or the payload itself under the fallback.
     */
    public Process launch(String role, List<String> command, Map<String, String> env, Redirect stdout) throws IOException {
        return launch(role, command, env, stdout, null);
    }

    /**
     * As {@link #launch(String, List, Map, Redirect)}, but also redirecting the child's <em>stderr</em> to
     * {@code stderr} (defaulting to {@link Redirect#DISCARD} when {@code null}). Needed for a server that
     * reports its display number on stderr rather than a {@code -displayfd} stdout — gamescope does exactly
     * that (it logs {@code Starting Xwayland on :N}), so {@link GamescopeDisplay} captures stderr to parse it.
     */
    public Process launch(String role, List<String> command, Map<String, String> env, Redirect stdout, Redirect stderr)
            throws IOException {
        synchronized (lock) {
            if (reaped) {
                throw new IllegalStateException("SessionReaper " + id + " already reaped");
            }
        }
        roles.add(role);
        List<String> full = new ArrayList<>();
        ProcessBuilder pb;
        if (useSystemd) {
            full.add("systemd-run");
            full.add("--user");
            full.add("--scope");
            full.add("--quiet");
            full.add("--collect");                       // garbage-collect the unit once it exits
            full.add("--unit=botmaker-sess-" + id + "-" + role);
            full.add("--slice=" + slice);
            if (env != null) {
                env.forEach((k, v) -> full.add("--setenv=" + k + "=" + v));
            }
            full.addAll(command);
            pb = new ProcessBuilder(full);
        } else {
            pb = new ProcessBuilder(command);
            if (env != null) {
                pb.environment().putAll(env);
            }
        }
        pb.redirectOutput(stdout != null ? stdout : Redirect.DISCARD);
        pb.redirectError(stderr != null ? stderr : Redirect.DISCARD);
        Diag.log("[Session] " + id + "/" + role + ": " + String.join(" ", useSystemd ? full : command));
        // Deliberately not under the lock: a spawn can block, and reap() must never wait behind one.
        Process p = pb.start();
        synchronized (lock) {
            if (!reaped) {
                launched.add(p);
                return p;
            }
        }
        // The reap landed while we were starting. The process exists and nothing tracks it, so this call — not
        // the teardown that already returned — is the only thing that can still kill it.
        Diag.error("[Session] " + id + "/" + role + ": reaped mid-launch; destroying pid " + p.pid());
        destroyTree(p);
        if (useSystemd) {
            // The scope joined the slice after the stop, so the slice teardown missed it.
            stopUnit("botmaker-sess-" + id + "-" + role + ".scope");
        }
        throw new IllegalStateException("SessionReaper " + id + " already reaped");
    }

    /**
     * Tear the whole tree down. Idempotent. Under systemd this is one {@code systemctl --user stop <slice>}
     * (which reaps the cgroup regardless of what this JVM still tracks); under the fallback it force-destroys
     * every launched process together with its descendants.
     */
    public void reap() {
        List<Process> toKill;
        synchronized (lock) {
            if (reaped) {
                return;
            }
            reaped = true;
            toKill = List.copyOf(launched);
            launched.clear();
        }
        // Outside the lock: the flag is already set, so a launch racing us will refuse (or destroy its own child)
        // rather than be tracked — and holding the lock across a multi-second `systemctl stop` would stall it for
        // no gain.
        if (useSystemd) {
            stopUnit(slice);
            Diag.log("[Session] " + id + ": stopped slice " + slice);
            verifyStopped();
        }
        // Belt and suspenders (and the whole story under the fallback): force-kill each tracked process and its
        // descendants. Under systemd the scope wrappers and payloads are already gone; this is a cheap no-op then.
        toKill.forEach(this::destroyTree);
    }

    /** Force-destroy {@code p} together with its descendants; best-effort, like every other teardown step. */
    private void destroyTree(Process p) {
        try {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        } catch (Exception e) {
            Diag.error("[Session] " + id + ": killing a process failed: " + e.getMessage());
        }
    }

    /**
     * Check the reap actually emptied the cgroup, and finish the job when it didn't.
     *
     * <p>Stopping the slice is <em>supposed</em> to take everything in it, and the log said so unconditionally —
     * which is how a private {@code dbus-daemon} came to be found still {@code active running} in
     * {@code botmaker-sess-s167520-1-dbus.scope} with its display server long gone. A leftover is not cosmetic:
     * while it lives, {@link com.botmaker.shared.launch.RunningProbe} and
     * {@link com.botmaker.shared.launch.HostLauncherProbe} read it as a launcher/game that is up, and the next
     * launch is refused or skipped on its account.
     */
    private void verifyStopped() {
        List<String> leftovers = listUnits("botmaker-sess-" + id + "*");
        if (leftovers.isEmpty()) {
            return;
        }
        Diag.error("[Session] " + id + ": stopping " + slice + " left " + leftovers.size()
            + " unit(s) loaded: " + String.join(", ", leftovers) + " — stopping each");
        leftovers.forEach(SessionReaper::stopUnit);
    }

    /** {@code systemctl --user stop <unit>}, best-effort and bounded. */
    private static void stopUnit(String unit) {
        try {
            new ProcessBuilder("systemctl", "--user", "stop", unit)
                .redirectOutput(Redirect.DISCARD).redirectError(Redirect.DISCARD)
                .start().waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Diag.error("[Session] stopping " + unit + " failed: " + e.getMessage());
        }
    }

    /** Sanitise an id to what a systemd unit name accepts (alnum, dash, underscore); never empty. */
    private static String sanitize(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_-]", "");
        return s.isEmpty() ? "x" : s;
    }

    /**
     * Probe once whether {@code systemd-run --user --scope} actually works here (a user systemd, a session bus,
     * {@code XDG_RUNTIME_DIR}). Cached because the probe spawns a process and the answer can't change within a
     * JVM's lifetime.
     *
     * <p>{@code synchronized} so "once" is literal rather than typical. The double-check on a plain volatile let
     * two callers arriving together each spawn the probe and pay its {@code waitFor}, and since the orphan sweep
     * now runs concurrently with a session start, two callers arriving together is the normal case rather than a
     * rare interleaving. The lock is only ever contended on the first call.
     */
    private static synchronized boolean systemdAvailable() {
        Boolean cached = systemdAvailable;
        if (cached != null) {
            return cached;
        }
        boolean ok = false;
        try {
            if (System.getenv("XDG_RUNTIME_DIR") != null) {
                Process p = new ProcessBuilder(
                    "systemd-run", "--user", "--scope", "--quiet",
                    "--unit=botmaker-sess-probe-" + ProcessHandle.current().pid(), "true")
                    .redirectOutput(Redirect.DISCARD).redirectError(Redirect.DISCARD).start();
                ok = p.waitFor(4, TimeUnit.SECONDS) && p.exitValue() == 0;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            ok = false;
        }
        systemdAvailable = ok;
        return ok;
    }

    /**
     * Matches a per-session slice, capturing the owner pid. The {@code -<seq>} is optional on purpose: systemd
     * derives a parent slice from every dash in a unit name, so a session's {@code botmaker-sess-s<pid>-1.slice}
     * silently creates {@code botmaker-sess-s<pid>.slice} above it. Matching only the leaf left those parents
     * loaded-and-empty forever (six of them had accumulated by the end of one afternoon's live runs).
     */
    private static final Pattern SESSION_SLICE = Pattern.compile("botmaker-sess-s(\\d+)(?:-\\d+)?\\.slice");

    /**
     * Reap the leftovers of dead sessions: any {@code botmaker-sess-s<pid>-*.slice} whose owning JVM {@code pid}
     * is no longer alive is stopped. This is what makes "{@code kill -9} the JVM ⇒ zero orphans" true — a
     * {@code --scope} tree outlives the JVM that spawned it (that is the whole point: it stays reliably reapable
     * by slice name rather than by a fragile process-name {@code pkill}), and this sweep, run when the next
     * session starts (or on demand), collects those trees. A slice whose owner pid <em>is</em> alive — this JVM's
     * own live sessions, or a sibling JVM's — is left strictly alone. No-op when there is no user systemd.
     */
    public static void reapOrphans(Collection<String> liveSessionIds) {
        if (!systemdAvailable()) {
            return;
        }
        long self = ProcessHandle.current().pid();
        for (String slice : listSessionSlices()) {
            Matcher m = SESSION_SLICE.matcher(slice);
            if (!m.matches()) {
                continue;
            }
            long ownerPid = Long.parseLong(m.group(1));
            String why;
            if (ownerPid == self) {
                // Ours — but only the sessions we still hold are live. An id we no longer have an object for is
                // abandoned: measured, a dbus.scope of a long-dead display was still running under this very JVM
                // and the launch probes counted it as a launcher that was up.
                if (isLive(sessionIdOf(slice), liveSessionIds)) {
                    continue;
                }
                why = "we no longer hold it";
            } else if (ProcessHandle.of(ownerPid).map(ProcessHandle::isAlive).orElse(false)) {
                continue; // another live JVM still manages this session
            } else {
                why = "owner pid " + ownerPid + " is gone";
            }
            stopUnit(slice);
            Diag.log("[Session] reaped orphan slice " + slice + " (" + why + ")");
        }
    }

    /**
     * Whether {@code id} names a session this JVM still holds — <b>or is the parent of one</b>. That second half
     * matters: systemd derives a parent slice from every dash, so a live {@code s123-1} sits inside a
     * {@code botmaker-sess-s123.slice} that no session object is ever keyed by. Stopping <em>that</em> would take
     * the live session down with it, which is why the sweep can't simply ask for an exact match.
     */
    public static boolean isLive(String id, Collection<String> liveSessionIds) {
        return liveSessionIds.contains(id) || liveSessionIds.stream().anyMatch(live -> live.startsWith(id + "-"));
    }

    /** The session id inside a slice name — {@code botmaker-sess-s123-4.slice} → {@code s123-4}. */
    public static String sessionIdOf(String slice) {
        String name = slice.endsWith(".slice") ? slice.substring(0, slice.length() - ".slice".length()) : slice;
        return name.startsWith("botmaker-sess-") ? name.substring("botmaker-sess-".length()) : name;
    }

    /** The names of every {@code botmaker-sess-*} slice systemd currently knows, or empty on any failure. */
    private static List<String> listSessionSlices() {
        return listUnits("botmaker-sess-*.slice").stream().filter(u -> u.endsWith(".slice")).toList();
    }

    /**
     * The unit names matching {@code pattern} that systemd currently knows (any type — a session's members are
     * scopes, its groups are slices), or empty on any failure. {@code --all} on purpose: a unit that is loaded but
     * inactive is still a leftover worth collecting.
     */
    private static List<String> listUnits(String pattern) {
        List<String> units = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("systemctl", "--user", "list-units", "--all", "--plain",
                "--no-legend", pattern)
                .redirectError(Redirect.DISCARD).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(5, TimeUnit.SECONDS);
            for (String line : out.split("\\R")) {
                String name = line.trim().split("\\s+")[0];
                if (name.startsWith("botmaker-sess-")) {
                    units.add(name);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Diag.error("[Session] listing units " + pattern + " failed: " + e.getMessage());
        }
        return units;
    }

    /** A throwaway temp file for a child's {@code -displayfd} output, deleted on JVM exit. */
    public static File tempOutputFile(String prefix) throws IOException {
        File f = File.createTempFile(prefix, ".out");
        f.deleteOnExit();
        return f;
    }
}
