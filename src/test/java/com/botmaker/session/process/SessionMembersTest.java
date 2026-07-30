package com.botmaker.session.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Membership is decided from a real {@code /proc}, so it is asserted against real processes: the class exists
 * because the cgroup lies about who belongs to a session, and a mock of {@code /proc} would only re-assert the
 * mock. Both exclusions get their own case — they are the ones that turn a teardown into a suicide or into a
 * crash, which is exactly what this phase was fixing.
 */
@EnabledOnOs(OS.LINUX)
class SessionMembersTest {

    /**
     * A process that stays alive <em>and keeps the environment we gave it</em>. A shell that execs would keep
     * the environment too, but not its argv — the sibling {@link com.botmaker.shared.launch.GameLauncherKillTest}
     * needs the latter, so both use the same non-exec'ing loop for one obvious idiom.
     */
    private static Process spinner(Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "while :; do sleep 1; done");
        pb.environment().putAll(env);
        return pb.start();
    }

    /** A display number no real session uses, so a stray X client can't be mistaken for our fixture. */
    private static String uniqueDisplay() {
        return ":" + (900 + Math.abs(UUID.randomUUID().hashCode() % 90));
    }

    @Test
    void aProcessCarryingTheSessionDisplayIsAMember() throws Exception {
        String display = uniqueDisplay();
        Process member = spinner(Map.of("DISPLAY", display));
        try {
            List<ProcessHandle> found = waitForMember(display, member.pid());
            assertTrue(found.stream().anyMatch(p -> p.pid() == member.pid()),
                    "a process with DISPLAY=" + display + " belongs to that session");
        } finally {
            member.destroyForcibly();
        }
    }

    @Test
    void anUnrelatedProcessIsNotAMember() throws Exception {
        String display = uniqueDisplay();
        Process outsider = spinner(Map.of("DISPLAY", ":0"));
        try {
            assertTrue(SessionMembers.of(display, null, List.of()).stream()
                            .noneMatch(p -> p.pid() == outsider.pid()),
                    "a process on another display must never be signalled by a session teardown");
        } finally {
            outsider.destroyForcibly();
        }
    }

    /**
     * The display number is compared entry-by-entry, not as a substring: {@code :1} must not claim {@code :11}.
     * Getting this wrong would have a session tear down its neighbour's game.
     */
    @Test
    void aLongerDisplayNumberIsNotAPrefixMatch() throws Exception {
        Process other = spinner(Map.of("DISPLAY", ":11"));
        try {
            assertTrue(SessionMembers.of(":1", null, List.of()).stream()
                            .noneMatch(p -> p.pid() == other.pid()),
                    ":11 is a different display from :1");
        } finally {
            other.destroyForcibly();
        }
    }

    /**
     * The bot JVM very plausibly carries the session's own environment — it created it. Signalling it is how a
     * teardown kills the process performing the teardown (the {@code pkill -f} bug, one layer down).
     */
    @Test
    void thisJvmIsNeverAMemberOfItsOwnSession() {
        String display = System.getenv("DISPLAY");
        if (display == null || display.isBlank()) {
            return; // headless CI: nothing to be wrongly matched by
        }
        assertTrue(SessionMembers.of(display, null, List.of()).stream()
                        .noneMatch(p -> p.pid() == ProcessHandle.current().pid()),
                "the running JVM must never appear in its own session's member list");
    }

    /**
     * The session's {@code dbus-daemon} and window manager are launched <em>with</em> the private {@code DISPLAY},
     * so the environment test matches them too — and killing them alongside the game is precisely the ordering
     * this class exists to prevent. They are told apart by cgroup, which is what this asserts: a marker naming
     * the child's own cgroup takes it out of the member list even though its environment still matches.
     */
    @Test
    void aProcessInAnInfrastructureCgroupIsExcluded() throws Exception {
        String display = uniqueDisplay();
        Process infra = spinner(Map.of("DISPLAY", display));
        try {
            List<ProcessHandle> asMember = waitForMember(display, infra.pid());
            assertTrue(asMember.stream().anyMatch(p -> p.pid() == infra.pid()), "precondition: it matches");

            // A test process inherits this JVM's cgroup, so the JVM's own cgroup line stands in for a session
            // infrastructure unit name — the substring is all the exclusion ever looks at.
            String ownCgroup = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/self/cgroup")).trim();
            String marker = ownCgroup.substring(ownCgroup.lastIndexOf('/') + 1);

            assertTrue(SessionMembers.of(display, null, List.of(marker)).stream()
                            .noneMatch(p -> p.pid() == infra.pid()),
                    "a process in an infrastructure unit must be left for the slice reap, not signalled early");
        } finally {
            infra.destroyForcibly();
        }
    }

    /**
     * The launcher must be asked to exit before anything it spawned — a process that watches its children die
     * first is exactly what Chromium aborts on, and that abort was the coredump this class was written to
     * remove. The ordering is by start time rather than by parentage on purpose: under Flatpak, {@code zypak}
     * reparents Chromium's helpers onto the portal, so the process tree ranks them <em>ahead</em> of the
     * launcher that spawned them. A start time cannot be reassigned that way.
     */
    @Test
    void theOldestProcessIsSignalledFirst() throws Exception {
        String display = uniqueDisplay();
        // An outer shell that forks an inner one and then loops: two live members, one spawned by the other.
        ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "sh -c 'while :; do sleep 1; done' & while :; do sleep 1; done");
        pb.environment().put("DISPLAY", display);
        Process outer = pb.start();
        try {
            // The shells also have live `sleep` children of their own, so the set is larger than the two
            // processes this asserts about — which is the realistic shape anyway.
            List<ProcessHandle> members = waitForMembers(display, 2);
            assertTrue(members.size() >= 2, "precondition: the shell and what it spawned are both members");

            List<ProcessHandle> ordered = SessionMembers.inStartOrder(members);

            assertEquals(outer.pid(), ordered.get(0).pid(),
                    "the process that started everything else must be the one asked to exit first");
        } finally {
            outer.descendants().forEach(ProcessHandle::destroyForcibly);
            outer.destroyForcibly();
        }
    }

    private static List<ProcessHandle> waitForMembers(String display, int count) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            List<ProcessHandle> found = SessionMembers.of(display, null, List.of());
            if (found.size() >= count) {
                return found;
            }
            Thread.sleep(100);
        }
        return SessionMembers.of(display, null, List.of());
    }

    /**
     * The remnants are signalled <b>one at a time</b>, not in a sweep. A sweep is the same mistake as the
     * parents-first walk in miniature — a live supervisor gets to watch its own helpers die, which is what
     * Chromium aborts on — and it is what ran immediately before the coredump on the last run that produced one.
     *
     * <p>Asserted from inside the processes: each traps {@code SIGTERM}, records when it arrived and keeps
     * running, so the receipt times are real evidence of the spacing. A sweep would land them all in the same
     * millisecond.
     */
    @Test
    void remnantsAreSignalledOneAtATimeRatherThanSwept() throws Exception {
        String display = uniqueDisplay();
        Path log = Files.createTempFile("botmaker-term-", ".log");
        List<Process> spinners = new ArrayList<>();
        try {
            // trap-and-continue: the TERM is observed and timestamped, but the process stays alive, so the walk
            // has to escalate to SIGKILL before moving to the next one — the shape a stubborn helper has.
            for (int i = 0; i < 3; i++) {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                        "trap 'date +%s%3N >> " + log.toAbsolutePath() + "' TERM; while :; do sleep 0.1; done");
                pb.environment().put("DISPLAY", display);
                spinners.add(pb.start());
            }
            List<ProcessHandle> members = waitForMembers(display, 3);
            assertTrue(members.size() >= 3, "precondition: all three spinners are members");

            SessionMembers.terminateOneByOne(SessionMembers.inStartOrder(members),
                    System.currentTimeMillis() + 30_000);

            List<Long> times = Files.readAllLines(log).stream().map(String::trim)
                    .filter(s -> !s.isEmpty()).map(Long::parseLong).sorted().toList();
            assertEquals(3, times.size(), "each spinner should have been asked exactly once: " + times);
            for (int i = 1; i < times.size(); i++) {
                long gap = times.get(i) - times.get(i - 1);
                assertTrue(gap >= 250, "signals " + (i - 1) + "→" + i + " were " + gap
                        + "ms apart — that is a sweep, not a staggered walk");
            }
            for (Process p : spinners) {
                assertFalse(p.isAlive(), "a member that ignores SIGTERM must still be killed");
            }
        } finally {
            spinners.forEach(Process::destroyForcibly);
            Files.deleteIfExists(log);
        }
    }

    @Test
    void shutdownTerminatesMembersAndReportsNoSurvivors() throws Exception {
        String display = uniqueDisplay();
        Process member = spinner(Map.of("DISPLAY", display));
        try {
            List<ProcessHandle> members = waitForMember(display, member.pid());

            List<ProcessHandle> survivors = SessionMembers.shutdown(members, 3_000);

            assertEquals(List.of(), survivors, "a SIGTERM-able process must not survive shutdown");
            assertTrue(member.waitFor(3, TimeUnit.SECONDS), "the member should have exited");
            assertFalse(member.isAlive());
        } finally {
            member.destroyForcibly();
        }
    }

    /** Process creation is asynchronous; poll until the new process is visible in {@code /proc}. */
    private static List<ProcessHandle> waitForMember(String display, long pid) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            List<ProcessHandle> found = SessionMembers.of(display, null, List.of());
            if (found.stream().anyMatch(p -> p.pid() == pid)) {
                return found;
            }
            Thread.sleep(100);
        }
        return SessionMembers.of(display, null, List.of());
    }
}
