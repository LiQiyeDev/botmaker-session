package com.botmaker.session.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The private bus is what stops a Flatpak launcher's game escaping to {@code :0}, and it does that through one
 * detail — the generated service file must <em>omit</em> {@code SystemdService=}, or D-Bus hands activation to
 * the user-global portal that already holds {@code DISPLAY=:0} and the whole mechanism silently does nothing.
 * That omission and the address parsing are the two things worth pinning without a live bus.
 */
class SessionBusTest {

    @Test
    void anAddressIsOnlyAcceptedOnceItIsCompletelyWritten(@TempDir Path dir) throws Exception {
        File out = dir.resolve("addr.out").toFile();

        // Nothing written yet, and a half-written line: both must read as "not ready" rather than as a truncated
        // address a child would then fail to connect to.
        Files.writeString(out.toPath(), "");
        assertNull(SessionBus.readAddress(out));
        Files.writeString(out.toPath(), "unix:path=/tmp/dbus-Ab");
        assertNull(SessionBus.readAddress(out));

        // dbus-daemon always writes guid= last, so its presence is the completeness marker.
        String full = "unix:path=/tmp/dbus-AbCdEf,guid=f6f5277987d837996f9dccfe6a6a2ed7";
        Files.writeString(out.toPath(), full + "\n");
        assertEquals(full, SessionBus.readAddress(out));
    }

    @Test
    void aMissingFileReadsAsNotReadyRatherThanThrowing(@TempDir Path dir) {
        assertNull(SessionBus.readAddress(dir.resolve("never-created.out").toFile()));
    }

    @Test
    void theGeneratedPortalServiceMustNotDeferActivationToSystemd() throws Exception {
        Path dir = SessionBus.writeServiceDir("test");
        try {
            Path service = dir.resolve("org.freedesktop.portal.Flatpak.service");
            String source = Files.readString(service);

            // The crux of the whole mechanism. The stock file carries SystemdService=flatpak-portal.service,
            // which hands activation to the user-global portal — the one already holding DISPLAY=:0. If that
            // line ever gets copied in here, the bus stops spawning its own portal and every game silently
            // escapes to the real desktop again, with nothing failing loudly to say so.
            assertFalse(source.contains("SystemdService"), source);
            assertTrue(source.contains("Name=org.freedesktop.portal.Flatpak"), source);
            assertTrue(source.contains("Exec="), source);
        } finally {
            try (var entries = Files.list(dir)) {
                entries.forEach(p -> p.toFile().delete());
            }
            Files.deleteIfExists(dir);
        }
    }
}
