package com.botmaker.session.impl;

import com.botmaker.session.process.SessionMembers;
import com.botmaker.session.process.SessionReaper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>SS3 items 3 and 5 — who a session is allowed to kill.</b>
 *
 * <p>Both of this module's teardown bugs have the same shape and the same symptom: a session tears down
 * something that was never its to tear down, and the person watching sees <em>their own</em> desktop or
 * <em>their own</em> bot die. Neither had a test.
 *
 * <h2>Item 3 — {@link AdoptedSession} owns nothing</h2>
 *
 * <p>Its defining property, in its own javadoc: "the display server, the window manager, the private bus and
 * the game all belong to whoever started them; {@code close()} drops this session's own two X connections and
 * stops there." That is the entire reason it is a separate class from {@link NestedSession} rather than a
 * boolean on one. A refactor that unified {@code close()} across the two — an obvious, tempting simplification,
 * since they differ by so little else — would break it silently, and the symptom would be a user's live
 * session being torn down under them mid-run while a bot they started merely finished.
 *
 * <p>This is asserted structurally rather than behaviourally, on purpose. Behaviourally it needs a live private
 * display with a real game in it, which is the {@code -Dbotmaker.live=true} suite; structurally it is
 * unconditional and runs everywhere, and it catches the exact refactor that would cause the damage — because
 * you cannot reap a tree without holding one of the two things that can.
 *
 * <h2>Item 5 — {@code shutdownMembers} spares this JVM and its ancestors</h2>
 *
 * <p>{@code SessionMembersTest.thisJvmIsNeverAMemberOfItsOwnSession} covers {@code of()}. Nothing covered the
 * same guarantee through the path that actually runs it, and the failure mode there is a teardown that kills
 * the bot doing the tearing down.
 */
class SessionOwnershipTest {

    // ---- Item 3: AdoptedSession owns nothing ----

    /**
     * The two types that can end a process tree in this module. Holding either is what "owning" means; an
     * adopted session must hold neither.
     */
    private static final Set<Class<?>> DESTRUCTIVE = Set.of(SessionReaper.class);

    @Test
    void adoptedSessionHoldsNoReaper() {
        List<Field> reapers = fieldsOfType(AdoptedSession.class, DESTRUCTIVE);
        if (!reapers.isEmpty()) {
            fail("AdoptedSession holds " + describe(reapers) + ". It adopts a display someone else owns, so a "
                    + "reaper here can only ever tear down a session its owner is still using. If a shared "
                    + "teardown was factored out of NestedSession, this is the class that must not receive it.");
        }
    }

    /**
     * {@link NestedSession} is the control: it <em>does</em> hold a reaper, because it owns everything it
     * started. Without this half, the assertion above would keep passing after someone renamed the reaper.
     */
    @Test
    void nestedSessionDoesHoldAReaper() {
        assertFalse(fieldsOfType(NestedSession.class, DESTRUCTIVE).isEmpty(),
                "NestedSession owns its process tree and must hold the thing that ends it — if this fails, the "
                        + "reaper moved and the AdoptedSession assertion above is no longer checking anything");
    }

    /**
     * The other half of ownership: an adopted session must not enumerate the display's members either. Even
     * without a reaper, {@code SessionMembers.shutdown} on someone else's display is the same damage by a
     * different route.
     */
    @Test
    void adoptedSessionDoesNotEnumerateOrSignalTheDisplaysMembers() {
        assertTrue(fieldsOfType(AdoptedSession.class, Set.of(SessionMembers.class)).isEmpty(),
                "AdoptedSession must not hold session members: signalling them is reaping by another name");
    }

    // ---- Item 5: the teardown never signals the JVM running it ----

    /**
     * Through {@code of()}, which is what {@code NestedSession.shutdownMembers} calls. This JVM is on a display
     * (the developer's, or none), and under systemd every process descends from pid 1 — so a members query that
     * gets its exclusions wrong sweeps in the caller. Signalling that list is a teardown that kills the bot.
     */
    @Test
    void theCallersOwnJvmIsNeverAMemberOfASessionItTearsDown() {
        long self = ProcessHandle.current().pid();
        Set<Long> selfAndAncestors = ancestry();

        for (String display : List.of(":0", ":1", ":99", System.getenv("DISPLAY") == null ? ":0" : System.getenv("DISPLAY"))) {
            List<ProcessHandle> members = SessionMembers.of(display, null, List.of());
            Set<Long> pids = members.stream().map(ProcessHandle::pid).collect(Collectors.toSet());

            assertFalse(pids.contains(self),
                    "SessionMembers.of(" + display + ") returned this JVM (pid " + self + "). Signalling that "
                            + "list is a session teardown that kills the bot performing it.");
            for (long ancestor : selfAndAncestors) {
                assertFalse(pids.contains(ancestor),
                        "SessionMembers.of(" + display + ") returned ancestor pid " + ancestor + ". A bot's "
                                + "parent very likely carries the session's DISPLAY in its environment, which "
                                + "is exactly how a `pkill -f` kills the launching JVM.");
            }
        }
    }

    /** Signalling an empty member list must be a no-op, not an error — a session can legitimately have none. */
    @Test
    void shuttingDownNoMembersIsANoOp() {
        assertTrue(SessionMembers.shutdown(List.of(), 100).isEmpty(),
                "an empty session tears down cleanly and reports no survivors");
    }

    // ---- helpers ----

    private static Set<Long> ancestry() {
        Set<Long> pids = new java.util.HashSet<>();
        ProcessHandle h = ProcessHandle.current();
        pids.add(h.pid());
        java.util.Optional<ProcessHandle> parent = h.parent();
        while (parent.isPresent() && pids.size() < 32) {
            if (!pids.add(parent.get().pid())) break;
            parent = parent.get().parent();
        }
        return pids;
    }

    private static List<Field> fieldsOfType(Class<?> owner, Set<Class<?>> types) {
        return java.util.Arrays.stream(owner.getDeclaredFields())
                .filter(f -> types.stream().anyMatch(t -> t.isAssignableFrom(f.getType())))
                .toList();
    }

    private static String describe(List<Field> fields) {
        return fields.stream().map(f -> f.getType().getSimpleName() + " " + f.getName())
                .collect(Collectors.joining(", "));
    }
}
