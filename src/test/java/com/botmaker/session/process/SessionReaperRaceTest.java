package com.botmaker.session.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.ProcessBuilder.Redirect;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>B8's gate, and the most important test in this module</b> — it is the reaper's own failure mode.
 *
 * <p>{@link SessionReaper} exists to guarantee that no process it launched outlives the session. Before the fix,
 * the guarantee had a hole between the guard and the bookkeeping:
 *
 * <pre>{@code
 * launch():  if (reaped) throw ...;   //  (1) passes
 *            Process p = pb.start();  //  (3) the process now exists
 *            launched.add(p);         //  (4) into a list nobody will read again
 *
 * reap():    reaped = true;           //  (2) between (1) and (3)
 *            for (Process p : launched) p.destroyForcibly();
 *            launched.clear();
 * }</pre>
 *
 * <p>A launch that wins the guard and loses to the teardown started a process into an already-drained list. The
 * result is exactly what the class was written to prevent: a Xephyr or a game still running with no session
 * left to reap it, holding a display number and GPU memory. Nothing detects it — {@code reap()} returned
 * successfully, {@code launch()} returned a healthy {@link Process}, and both are telling the truth.
 *
 * <p>The contract is therefore <b>refused or reaped, never neither</b>: if {@code launch} returns a process,
 * that process must be dead once {@code reap} has returned. That is what these tests assert. The fix moves the
 * guard, the {@code launched} list and the flag under one lock, and — the half that matters — re-tests the flag
 * after {@code start()} so a launch that loses the race <em>destroys</em> its child instead of merely declining
 * to track it.
 *
 * <h2>Why there are two race tests, and only one of them is the gate</h2>
 *
 * <p>Racing the systemd strategy <b>240 times</b> in Phase 3 produced <b>109 refusals, 131 reaps and zero
 * leaks</b> — on the unfixed code. The window does not open there, and the reason is structural rather than
 * luck: under systemd {@code reap()} spends seconds in {@code systemctl --user stop} before it ever reads
 * {@code launched}, so a launch that passed the guard had always finished its bookkeeping by the time the kill
 * loop ran — and the slice stop tears down the whole cgroup regardless of what the list contains.
 *
 * <p>The {@code launched} list is the entire teardown only under the <b>fallback</b> strategy, when there is no
 * user systemd. That is where B8 bites, and it used to be a configuration this box could not produce:
 * {@code systemdAvailable} is a static cached probe with no injection point. So the fix came with a seam —
 * {@link SessionReaper#SessionReaper(String, boolean)} — and <b>{@link #fallbackLaunchRacingReapIsRefusedOrReaped()}
 * is B8's gate</b>. {@link #systemdLaunchRacingReapIsRefusedOrReaped()} stays as a regression sampler for the
 * path production actually takes here; it was green before the fix and is green after, and saying otherwise
 * would be worse than not having it — a green test that claims to guard a bug is how the bug comes back.
 */
@EnabledOnOs(OS.LINUX)
class SessionReaperRaceTest {

    /** Long enough that nothing exits on its own; the test always kills it. */
    private static final List<String> SLEEPER = List.of("/bin/sh", "-c", "exec sleep 120");

    /** A shell that keeps a child of its own, so a teardown that ignores descendants is visible. */
    private static final List<String> SLEEPER_WITH_CHILD = List.of("/bin/sh", "-c", "sleep 120 & wait");

    /** The race is a few instructions wide, so it is sampled rather than hit once. */
    private static final int ATTEMPTS = 40;

    /**
     * <b>B8's gate.</b> Under the fallback strategy the {@code launched} list is the whole teardown, so a process
     * started after {@code reap()} drained it is leaked outright. Red on the previous commit; green with the
     * post-{@code start()} re-test.
     */
    @Test
    void fallbackLaunchRacingReapIsRefusedOrReaped() throws Exception {
        assertNoSurvivors(race(false), "fallback");
    }

    /**
     * The systemd path production takes on this box. Green before the fix too — see the class javadoc — and kept
     * as a regression sampler rather than as B8's gate.
     */
    @Test
    void systemdLaunchRacingReapIsRefusedOrReaped() throws Exception {
        assertNoSurvivors(race(true), "systemd");
    }

    /**
     * The fallback teardown itself, which had no coverage at all before B8's seam existed: {@code reap()} must
     * kill a tracked process <em>and its descendants</em>, since there is no cgroup behind it to catch what the
     * list misses.
     */
    @Test
    void fallbackReapKillsTheTrackedProcessAndItsDescendants() throws Exception {
        SessionReaper reaper = new SessionReaper("fallback-reap", false);
        Process p = reaper.launch("app", SLEEPER_WITH_CHILD, Map.of(), Redirect.DISCARD);
        List<ProcessHandle> children = waitForDescendants(p);
        assertFalse(children.isEmpty(), "the fixture must actually fork a child for this test to mean anything");

        reaper.reap();

        assertTrue(p.waitFor(5, TimeUnit.SECONDS), "reap() left the tracked process alive");
        for (ProcessHandle child : children) {
            assertTrue(waitForExit(child), "reap() left a descendant alive: pid " + child.pid());
        }
    }

    /**
     * The sequential half of the same contract, which already held: once {@code reap()} has returned, a
     * subsequent {@code launch} is refused outright. Kept live (never {@code @Disabled}) so the fix for the
     * concurrent case cannot be "drop the guard".
     */
    @Test
    void launchAfterReapIsRefused() {
        SessionReaper reaper = new SessionReaper("after-reap");
        reaper.reap();

        try {
            Process leaked = reaper.launch("app", SLEEPER, Map.of(), Redirect.DISCARD);
            leaked.destroyForcibly();
            fail("launch() after reap() started a process nothing will ever reap");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("already reaped"),
                    "the refusal must say why: " + expected.getMessage());
        } catch (Exception e) {
            fail("launch() after reap() must be refused with IllegalStateException, not " + e);
        }
    }

    /** {@code reap()} is idempotent by contract; a second call must not throw or re-kill. */
    @Test
    void reapIsIdempotent() {
        SessionReaper reaper = new SessionReaper("idempotent");
        reaper.reap();
        reaper.reap();
        reaper.reap();
    }

    /**
     * Race {@code launch} against {@code reap} {@link #ATTEMPTS} times on the given strategy, and return every
     * process that was neither refused nor reaped. An empty list is the contract holding.
     */
    private static List<Process> race(boolean useSystemd) throws InterruptedException {
        List<Process> survivors = new ArrayList<>();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            SessionReaper reaper = new SessionReaper("racetest-" + (useSystemd ? "sd-" : "fb-") + attempt, useSystemd);
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<Process> launched = new AtomicReference<>();

            Thread launcher = new Thread(() -> {
                try {
                    go.await();
                    launched.set(reaper.launch("app", SLEEPER, Map.of(), Redirect.DISCARD));
                } catch (Throwable t) {
                    // An IllegalStateException here is the *correct* outcome: refused.
                }
            }, "race-launcher");

            Thread reaperThread = new Thread(() -> {
                try {
                    go.await();
                    reaper.reap();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "race-reaper");

            launcher.start();
            reaperThread.start();
            go.countDown();
            launcher.join(30_000);
            reaperThread.join(30_000);

            Process p = launched.get();
            if (p == null) {
                continue; // refused — the contract's other acceptable answer
            }
            // Give the reap a moment to land, then check the only thing that matters.
            if (p.waitFor(2, TimeUnit.SECONDS)) {
                continue; // reaped
            }
            survivors.add(p);
        }
        return survivors;
    }

    private static void assertNoSurvivors(List<Process> survivors, String strategy) {
        try {
            if (!survivors.isEmpty()) {
                fail(survivors.size() + " of " + ATTEMPTS + " launches raced with reap() on the " + strategy
                        + " strategy and left a live process behind. The reaper reported a clean teardown and the "
                        + "launcher reported a healthy process; both were true, and the session is leaked. "
                        + "This is B8.");
            }
        } finally {
            survivors.forEach(Process::destroyForcibly);
        }
    }

    /** The shell forks its child asynchronously; poll briefly rather than sleeping a fixed amount. */
    private static List<ProcessHandle> waitForDescendants(Process p) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            List<ProcessHandle> children = p.descendants().toList();
            if (!children.isEmpty()) {
                return children;
            }
            Thread.sleep(100);
        }
        return List.of();
    }

    private static boolean waitForExit(ProcessHandle handle) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (!handle.isAlive()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
