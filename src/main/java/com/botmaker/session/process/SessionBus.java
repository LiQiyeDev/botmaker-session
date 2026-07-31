package com.botmaker.session.process;

import com.botmaker.shared.Diag;
import com.botmaker.shared.launch.LaunchIsolation;

import java.io.File;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A private D-Bus session bus the nested session owns — and, through it, its <b>own Flatpak portal</b>. This is
 * what makes isolation hold for a launcher we do not control.
 *
 * <p><b>Why a private display is not enough.</b> Handing a launcher {@code DISPLAY=:N} only confines processes
 * that are its descendants. A Flatpak launcher's game usually is not: Heroic runs it through umu, which
 * re-enters the host with {@code steam-runtime-launch-client --bus-name=org.freedesktop.portal.Flatpak}, and
 * {@code flatpak-portal} is a <em>D-Bus-activated service of the host session</em> whose own environment holds
 * {@code DISPLAY=:0}. It spawns the container from <em>its</em> environment, so the game lands on the real
 * desktop no matter what the launcher was given — {@code --pass-env-matching=*} does not save it. Measured on
 * a live run, the flip is visible process by process: {@code steam-runtime-launch-client} still on {@code :3},
 * then {@code pv-adverb}, {@code proton} and {@code wineserver} all on {@code :0}.
 *
 * <p><b>What this fixes, and how.</b> A bus started here inherits the session's environment, so the portal
 * <em>it</em> activates does too — and the container that portal spawns therefore lands back on {@code :N}. The
 * same live chain, re-run with this bus in place, showed {@code pv-adverb} and {@code wineserver} on the private
 * display. The mechanism is structural rather than cooperative: it does not ask the launcher to behave.
 *
 * <p><b>The crux is the generated service file.</b> The stock
 * {@code /usr/share/dbus-1/services/org.freedesktop.portal.Flatpak.service} carries a {@code SystemdService=}
 * line, which defers activation to the user-global {@code flatpak-portal.service} — i.e. to the very portal that
 * already holds {@code DISPLAY=:0}. We therefore point the bus at a service directory of our own containing the
 * same {@code Name=}/{@code Exec=} and <b>no</b> {@code SystemdService=}, so the bus spawns the portal itself.
 * Without that omission this whole class is a no-op.
 *
 * <p><b>A private bus is genuinely private.</b> Host session services (notifications, the secret store, a
 * system tray) are not reachable from it. That is mostly a feature — it is also what stops a launcher's
 * single-instance check from finding the copy already running on your desktop and forwarding our launch to it,
 * which was the original "close Heroic and try again" failure. A launcher that needs the host keyring to log in
 * would notice; one that keeps its own credentials (Heroic does) does not. Verified live: Heroic enumerated its
 * full library and launched normally on a bare private bus. There is deliberately no {@code xdg-dbus-proxy}
 * bridge — {@code dbus-daemon} cannot forward unknown names upstream, so a hybrid "our portal, the host's
 * everything else" bus is not expressible; the choice is one bus or the other.
 *
 * <p>Best-effort by contract: if {@code dbus-daemon} is missing or never prints an address, {@link #start}
 * returns {@code null} and the session runs display-isolated only, exactly as it did before this existed.
 */
public final class SessionBus implements AutoCloseable {

    /** How long to wait for {@code dbus-daemon} to print its address. */
    private static final long START_TIMEOUT_MS = 10_000;
    private static final long POLL_MS = 50;

    /** The stock portal service file, read for its {@code Exec=} so we don't hardcode a distro's path. */
    private static final Path STOCK_PORTAL_SERVICE =
        Path.of("/usr/share/dbus-1/services/org.freedesktop.portal.Flatpak.service");

    private static final String PORTAL_NAME = "org.freedesktop.portal.Flatpak";
    /** Used only when the stock file is unreadable and carries no {@code Exec=} to copy. */
    private static final String PORTAL_EXEC_FALLBACK = "/usr/libexec/flatpak-portal";

    private final String address;
    private final Process daemon;
    private final Path serviceDir;

    private SessionBus(String address, Process daemon, Path serviceDir) {
        this.address = address;
        this.daemon = daemon;
        this.serviceDir = serviceDir;
    }

    /** The bus address to hand children as {@code DBUS_SESSION_BUS_ADDRESS}. */
    public String address() {
        return address;
    }

    /** Whether the daemon is still running — a dead bus means children would silently fall back to the host's. */
    public boolean alive() {
        return daemon.isAlive();
    }

    /**
     * Start a private session bus through {@code reaper} (so it joins the session's slice and is torn down with
     * everything else), with {@code sessionEnv} — crucially the private {@code DISPLAY} — in its environment, so
     * the portal it activates inherits it.
     *
     * @return the bus, or {@code null} when one could not be started; never throws, because a session without a
     *         private bus is still a useful session.
     */
    public static SessionBus start(SessionReaper reaper, String id, Map<String, String> sessionEnv) {
        Path serviceDir = null;
        try {
            serviceDir = writeServiceDir(id);
            Path config = writeConfig(serviceDir);
            File out = SessionReaper.tempOutputFile("botmaker-dbus-");
            // --print-address writes the address to stdout; --nofork keeps the process in our reap group (a
            // forking daemon would escape the scope and outlive the session).
            Process daemon = reaper.launch("dbus",
                List.of(LaunchIsolation.PRIVATE_BUS_BINARY, "--config-file=" + config, "--print-address", "--nofork"),
                sessionEnv,
                Redirect.appendTo(out));
            String address = awaitAddress(out, daemon);
            if (address == null) {
                daemon.destroy();
                Diag.error("[Session] " + id + ": private bus did not report an address — continuing without one "
                    + "(a Flatpak launcher's game may escape to :0)");
                deleteQuietly(serviceDir);
                return null;
            }
            Diag.log("[Session] " + id + ": private session bus up (own " + PORTAL_NAME + ")");
            return new SessionBus(address, daemon, serviceDir);
        } catch (Exception e) {
            Diag.error("[Session] " + id + ": no private session bus (" + e.getMessage()
                + ") — continuing without one; a Flatpak launcher's game may escape to :0");
            deleteQuietly(serviceDir);
            return null;
        }
    }

    /**
     * Write the service directory holding our own {@link #PORTAL_NAME} activation — the same {@code Exec=} the
     * stock file names, with {@code SystemdService=} deliberately omitted so this bus spawns its own portal
     * instead of handing activation to the host's.
     */
    public static Path writeServiceDir(String id) throws Exception {
        Path dir = Files.createTempDirectory("botmaker-bus-" + id + "-");
        dir.toFile().deleteOnExit();
        Path service = dir.resolve(PORTAL_NAME + ".service");
        Files.writeString(service, "[D-BUS Service]\nName=" + PORTAL_NAME + "\nExec=" + portalExec() + "\n");
        service.toFile().deleteOnExit();
        return dir;
    }

    /** The portal binary the stock service file names, or a sensible default when it can't be read. */
    private static String portalExec() {
        try {
            for (String line : Files.readAllLines(STOCK_PORTAL_SERVICE)) {
                if (line.startsWith("Exec=")) {
                    return line.substring("Exec=".length()).trim();
                }
            }
        } catch (Exception ignored) {
            // No flatpak installed, or an unreadable file — the fallback is only ever used to fail politely.
        }
        return PORTAL_EXEC_FALLBACK;
    }

    /**
     * A minimal session-bus config listening on a private socket and reading activations <em>only</em> from
     * {@code serviceDir}. Nothing from the host's service directories is included: pulling those in would
     * re-introduce the stock portal file, {@code SystemdService=} line and all.
     */
    private static Path writeConfig(Path serviceDir) throws Exception {
        Path config = serviceDir.resolve("session.conf");
        Files.writeString(config, """
            <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN"
             "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
            <busconfig>
              <type>session</type>
              <listen>unix:tmpdir=/tmp</listen>
              <servicedir>%s</servicedir>
              <policy context="default">
                <allow send_destination="*" eavesdrop="true"/>
                <allow eavesdrop="true"/>
                <allow own="*"/>
              </policy>
            </busconfig>
            """.formatted(serviceDir));
        config.toFile().deleteOnExit();
        return config;
    }

    /** Poll the daemon's stdout until it holds a bus address, or time out / it dies. */
    private static String awaitAddress(File out, Process daemon) {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String address = readAddress(out);
            if (address != null) {
                return address;
            }
            if (!daemon.isAlive()) {
                return null;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * The address {@code dbus-daemon} printed, or {@code null} if it hasn't yet. Guards against a partial write
     * by requiring the terminating {@code guid=} field, which the daemon always writes last.
     */
    public static String readAddress(File out) {
        try {
            String s = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8).trim();
            String first = s.isEmpty() ? "" : s.split("\\R")[0].trim();
            return first.startsWith("unix:") && first.contains("guid=") ? first : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The daemon belongs to the reaper; this only drops the generated files. */
    @Override
    public void close() {
        deleteQuietly(serviceDir);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var entries = Files.list(dir)) {
            entries.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort; the files are in the temp dir and marked deleteOnExit
                }
            });
            Files.deleteIfExists(dir);
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
