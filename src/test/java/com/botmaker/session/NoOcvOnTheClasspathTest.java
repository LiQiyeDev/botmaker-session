package com.botmaker.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>SS4.</b> This module's pom excludes shared's OpenCV and Tess4J, so a consumer that wants a private
 * display does not download ~100 MB of OCR natives to open a nested X server. That is rule 2 of the two rules
 * in {@code CLAUDE.md} — and until now it was checked by a {@code mvn dependency:tree | grep} a human was
 * expected to remember during a pom review.
 *
 * <p>The failure mode of forgetting is specific and bad: the exclusion is not what breaks. Adding an
 * {@code org.opencv} reference to shared's {@code capture/} or {@code launch/} keeps compiling here, keeps
 * passing here, and fails at a <em>standalone consumer's runtime</em> with a {@link NoClassDefFoundError} on a
 * code path nobody exercised — most likely mid-session, on the machine of whoever depends on this module
 * without shared's full tree.
 *
 * <p>Two checks, deliberately from different angles, because either alone can be satisfied while the contract
 * is broken. The first asserts the <b>exclusion still holds</b>: the classes are not resolvable at test
 * runtime, which is the strongest statement available from inside a JVM. The second asserts the module
 * <b>does not need them</b>: every class in this module loads without one appearing. shared's own
 * {@code SharedNoOcvLeakTest} covers the third angle from the other side, scanning the packages this module
 * reaches for references it must not contain.
 *
 * <p><b>dadb is deliberately not excluded</b> and is asserted present, because that exclusion looks equally
 * tempting on a pom review and is the one that would break a real feature: {@code HostSession.launch} reaches
 * {@code EmulatorAppLauncher} → {@code AdbDevice} for {@code emu-app:} targets.
 */
class NoOcvOnTheClasspathTest {

    private static final List<String> BANNED = List.of(
            "org.opencv.core.Mat",
            "org.opencv.core.Core",
            "org.opencv.imgproc.Imgproc",
            "nu.pattern.OpenCV",
            "net.sourceforge.tess4j.Tesseract",
            "net.sourceforge.tess4j.ITesseract");

    /** Reachable through shared and required by {@code emu-app:} launches — the exclusion that must *not* happen. */
    private static final String DADB = "dadb.Dadb";

    @Test
    void neitherOpencvNorTess4jIsResolvableAtRuntime() {
        List<String> present = BANNED.stream().filter(NoOcvOnTheClasspathTest::isOnClasspath).toList();
        if (!present.isEmpty()) {
            fail("this module's pom excludes OpenCV and Tess4J from botmaker-shared, but these resolved: "
                    + String.join(", ", present)
                    + "\nEither the exclusions were dropped from pom.xml, or a new dependency pulls them in "
                    + "transitively. Both cost every consumer ~100 MB of natives it has no use for.");
        }
    }

    @Test
    void dadbStaysOnTheClasspath() {
        assertTrue(isOnClasspath(DADB),
                "dadb is excluded — but HostSession.launch reaches EmulatorAppLauncher -> AdbDevice for "
                        + "'emu-app:' targets, so excluding it leaves that launch kind throwing "
                        + "NoClassDefFoundError. It is the exclusion that looks as harmless as the other two "
                        + "and is not.");
    }

    /**
     * Loading every class in this module proves the exclusion is not merely tolerated but unnecessary: if any
     * type here had an OpenCV field, parameter or supertype, resolving it would fail here rather than at a
     * consumer's runtime.
     */
    @Test
    void everyClassInThisModuleLinksWithoutTheExcludedEngines() {
        List<String> roots = List.of(
                "com.botmaker.session.DesktopSession",
                "com.botmaker.session.Capability",
                "com.botmaker.session.ActiveSession",
                "com.botmaker.session.PointerPolicy",
                "com.botmaker.session.SessionHealth",
                "com.botmaker.session.display.SessionBackends",
                "com.botmaker.session.display.NestedDisplay",
                "com.botmaker.session.display.GamescopeDisplay",
                "com.botmaker.session.display.DisplayReadiness",
                "com.botmaker.session.impl.NestedSession",
                "com.botmaker.session.impl.AdoptedSession",
                "com.botmaker.session.impl.HostSession",
                "com.botmaker.session.impl.SessionAttachment",
                "com.botmaker.session.impl.SessionHostWindow",
                "com.botmaker.session.process.SessionReaper",
                "com.botmaker.session.process.SessionMembers",
                "com.botmaker.session.process.SessionBus",
                "com.botmaker.session.process.AppOutputLog",
                "com.botmaker.session.input.ControllerPointer",
                "com.botmaker.session.input.ControllerKeyboard");

        for (String name : roots) {
            try {
                Class.forName(name, true, NoOcvOnTheClasspathTest.class.getClassLoader());
            } catch (NoClassDefFoundError missing) {
                fail(name + " could not be initialised without the excluded engines: " + missing.getMessage()
                        + "\nThis is the standalone-consumer failure, caught here instead of there.");
            } catch (ClassNotFoundException gone) {
                fail(name + " no longer exists — this list has drifted from the module and is checking less "
                        + "than it claims to. Update it with the move that renamed the class.");
            }
        }
    }

    private static boolean isOnClasspath(String className) {
        try {
            Class.forName(className, false, NoOcvOnTheClasspathTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError absent) {
            return false;
        }
    }
}
