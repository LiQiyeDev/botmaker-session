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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>B8's gate, and the most important test in this module</b> — it is the reaper's own failure mode.
 *
 * <p>{@link SessionReaper} exists to guarantee that no process it launched outlives the session. The guarantee
 * has a hole between the guard and the bookkeeping:
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
 * <p>A launch that wins the guard and loses to the teardown starts a process into an already-drained list. The
 * result is exactly what the class was written to prevent: a Xephyr or a game still running with no session
 * left to reap it, holding a display number and GPU memory. Nothing detects it — {@code reap()} returned
 * successfully, {@code launch()} returned a healthy {@link Process}, and both are telling the truth.
 *
 * <p>The contract is therefore <b>refused or reaped, never neither</b>: if {@code launch} returns a process,
 * that process must be dead once {@code reap} has returned. That is what these tests assert, and what B8's fix
 * must make true — most simply by moving the guard and the {@code launched.add} under one lock with the flag.
 *
 * <h2>What measuring it showed: B8 is real in the code and unreachable under systemd</h2>
 *
 * <p>Racing this <b>240 times</b> on a systemd box produced <b>109 refusals, 131 reaps and zero leaks</b>. The
 * window does not open there, and the reason is structural rather than luck: under the systemd strategy
 * {@code reap()} spends seconds in {@code systemctl --user stop} before it ever reads {@code launched}, so a
 * launch that passes the guard has always finished its bookkeeping by the time the kill loop runs — and the
 * slice stop tears down the whole cgroup regardless of what the list contains.
 *
 * <p>The {@code launched} list is the entire teardown only under the <b>fallback</b> strategy, when there is no
 * user systemd. That is where B8 bites, and it is the configuration this box cannot produce: {@code
 * systemdAvailable} is a static cached probe with no injection point. So this test <b>cannot gate B8 here</b>,
 * and saying otherwise would be worse than not having it — a green test that claims to guard a bug is how the
 * bug comes back.
 *
 * <p>Two consequences for Phase 4. The fix still stands (the interleaving is plainly wrong and costs one lock),
 * but its severity is <b>conditional on the no-systemd fallback</b>, which the audit did not record. And
 * verifying it needs a seam that makes the strategy injectable — which is worth having anyway, since the
 * fallback path currently has no test coverage at all on a developer machine or in CI.
 */
@EnabledOnOs(OS.LINUX)
class SessionReaperRaceTest {

    /** Long enough that nothing exits on its own; the test always kills it. */
    private static final List<String> SLEEPER = List.of("/bin/sh", "-c", "exec sleep 120");

    /** The race is a few instructions wide, so it is sampled rather than hit once. */
    private static final int ATTEMPTS = 40;

    /**
     * Green today, and honest about why: see the class javadoc. Kept live rather than {@code @Disabled} because
     * it is a real regression sampler for the systemd path — it just is not B8's gate, and B8's gate needs the
     * injectable strategy Phase 4 has to add.
     */
    @Test
    void aProcessLaunchedConcurrentlyWithReapIsEitherRefusedOrReaped() throws Exception {
        List<Process> survivors = new ArrayList<>();
        try {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                SessionReaper reaper = new SessionReaper("racetest-" + attempt);
                CountDownLatch go = new CountDownLatch(1);
                AtomicReference<Process> launched = new AtomicReference<>();
                AtomicReference<Throwable> refused = new AtomicReference<>();

                Thread launcher = new Thread(() -> {
                    try {
                        go.await();
                        launched.set(reaper.launch("app", SLEEPER, Map.of(), Redirect.DISCARD));
                    } catch (Throwable t) {
                        refused.set(t); // an IllegalStateException here is the *correct* outcome
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

            if (!survivors.isEmpty()) {
                fail(survivors.size() + " of " + ATTEMPTS + " launches raced with reap() and left a live process "
                        + "behind. The reaper reported a clean teardown and the launcher reported a healthy "
                        + "process; both were true, and the session is leaked. This is B8.");
            }
        } finally {
            survivors.forEach(Process::destroyForcibly);
        }
    }

    /**
     * The sequential half of the same contract, which already holds: once {@code reap()} has returned, a
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
}
