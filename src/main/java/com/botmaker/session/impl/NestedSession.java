package com.botmaker.session.impl;

import com.botmaker.session.Capability;
import com.botmaker.session.DesktopSession;
import com.botmaker.session.PreviewFrame;
import com.botmaker.session.SessionHealth;
import com.botmaker.session.SessionKeyboard;
import com.botmaker.session.SessionPointer;
import com.botmaker.session.SessionStartException;
import com.botmaker.session.display.GamescopeDisplay;
import com.botmaker.session.display.NestedDisplay;
import com.botmaker.session.display.SessionBackends;
import com.botmaker.session.display.SessionDisplay;
import com.botmaker.session.input.ControllerKeyboard;
import com.botmaker.session.input.ControllerPointer;
import com.botmaker.session.process.AppOutputLog;
import com.botmaker.session.process.SessionBus;
import com.botmaker.session.process.SessionMembers;
import com.botmaker.session.process.SessionReaper;
import com.botmaker.session.process.SessionUnit;
import com.botmaker.session.remote.DisplayLink;
import com.botmaker.session.remote.WindowIds;
import com.botmaker.session.video.FfmpegVideoStream;
import com.botmaker.session.video.VideoPacket;
import com.botmaker.session.video.VideoStream;

import com.botmaker.shared.Diag;
import com.botmaker.shared.Executables;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.launch.GameLauncher;
import com.botmaker.shared.launch.HostLauncherProbe;
import com.botmaker.shared.launch.LaunchCommands;
import com.botmaker.shared.launch.LaunchIsolation;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.launch.Launcher;
import com.botmaker.shared.platform.SessionEnv;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.ProcessBuilder.Redirect;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A {@link DesktopSession} over a private nested display the bot owns — the piece that makes background input
 * <em>flawless</em>. Because the game runs in its own Xephyr {@code :N}, that display's global pointer and
 * keyboard focus are the bot's alone: the same device-level XTest injection that would hijack the real cursor
 * on {@code :0} is, on {@code :N}, both accepted by the game <em>and</em> invisible to the user driving their
 * real desktop. That is why — unlike {@link HostSession} — a nested session honestly advertises
 * {@link Capability#BACKGROUND_CLICK}, {@link Capability#ISOLATED_FOCUS} and {@link Capability#MULTI_SESSION}.
 *
 * <p>A nested session <b>launches</b> its target (it cannot attach across servers — X11 has no window
 * migration), stopping any instance already running on {@code :0} first. Bring one up with {@link #start},
 * then {@link #launch(LaunchSpec)} the game into it. Everything it spawns — the X server, an optional window
 * manager, the game — lives in one {@link SessionReaper} group, so {@link #close()} reaps the whole tree.
 *
 * <p>Two display backends sit behind one {@link SessionDisplay} seam, chosen by {@link Options}: {@link
 * NestedDisplay} (Xephyr, 2D — no {@link Capability#HARDWARE_GL}/{@link Capability#VULKAN}) and {@link
 * GamescopeDisplay} (gamescope, hardware 3D — adds both). The supervisor here is identical for both. Launch
 * covers every kind with a <em>child-launchable</em> command (via {@link
 * com.botmaker.shared.launch.LaunchCommands}): {@code exe:}/{@code cli:} directly, and the store launchers
 * that expose a CLI form ({@code heroic:}/{@code steam:}/{@code faugus:}) run as our own child so they inherit
 * {@code DISPLAY=:N} instead of the daemon-routed protocol URL, which a launcher already on {@code :0} would
 * swallow. Kinds with no CLI form — {@code epic:} (URL-only) and {@code emu-app:} (ADB) — cannot map onto a
 * private display and are refused (a loud failure, never a silent {@code :0} fallback).
 */
public final class NestedSession implements DesktopSession {

    /** Monotonic per-JVM counter so concurrent sessions get distinct reap-group ids (display numbers come from Xephyr). */
    private static final AtomicInteger SEQ = new AtomicInteger();

    /**
     * The ids of the sessions this JVM currently holds. It exists so the orphan sweep can tell an <em>abandoned</em>
     * cgroup of ours from a live one: "owner pid is alive" used to be enough to spare a slice, which spared the
     * shells of sessions this JVM had already let go of — a private {@code dbus-daemon} was found still running in
     * one whose display server had been gone for hours, and the launch probes counted it as a launcher that was up.
     *
     * <p>An id is claimed at the <em>top</em> of {@link #start}, before its cgroup exists, and dropped again if the
     * start fails — the sweep runs concurrently with a bring-up, so "held" has to include "being built".
     */
    private static final Set<String> LIVE = ConcurrentHashMap.newKeySet();

    /** How long to wait for a launched game's window to appear on the nested display before giving up the attach. */
    static final long WINDOW_TIMEOUT_MS = 20_000;
    /**
     * The same budget for a <em>store launcher</em> kind, where the window we're waiting for is the game's and
     * not the process we spawned. Heroic/Steam boot their own runtime, then a Proton prefix, then (first run)
     * download winetricks/umu before the game ever maps — minutes, not seconds. Timing those out at the
     * {@link #WINDOW_TIMEOUT_MS} budget meant reaping a launcher that was still working, which is how a
     * perfectly healthy Heroic ended up producing a SIGTRAP coredump.
     */
    static final long LAUNCHER_WINDOW_TIMEOUT_MS = 120_000;
    /** How long to wait for an optional window manager to claim the display; a WM-less session proceeds anyway. */
    private static final long WM_TIMEOUT_MS = 5_000;
    private static final long POLL_MS = 150;
    /**
     * How long the payload gets to exit on {@code SIGTERM} at teardown before it is killed. Generous enough for
     * a launcher to close its windows, a Wine prefix to flush and each generation of a deep process tree to take
     * its own children down in turn; closing a session with nothing running still costs nothing, because the
     * budget is a deadline and not a wait.
     */
    private static final long MEMBER_SHUTDOWN_MS = 20_000;
    /**
     * Set to {@code false} to leave the display server's host window visible for the whole bring-up (the old
     * behaviour). The escape hatch exists because minimizing it is a host-WM-mediated operation on a window we
     * don't own: if a compositor ever throttles an iconified server's frames, capture would stall, and that is
     * far worse than the black flash this hides.
     */
    public static final String HIDE_UNTIL_READY_PROPERTY = "botmaker.session.hideuntilready";
    /**
     * How long to keep looking for the server's window on the host desktop. Generous because the search runs off
     * the start path and the thing it is hiding lasts up to {@link #LAUNCHER_WINDOW_TIMEOUT_MS}: gamescope's output
     * window showed up more than 3s after its Xwayland was connectable, which a tighter budget simply missed.
     */
    private static final long HOST_WINDOW_FIND_MS = 15_000;

    private final String id;
    private final SessionReaper reaper;
    private final SessionDisplay display;
    /**
     * Everything this session does to {@code :N}, held <b>in another process</b> — see {@link DisplayLink}. It
     * replaced a {@code LinuxController} plus a second EWMH connection, both open in this JVM, which is what an
     * X I/O error used to call {@code exit(1)} on.
     */
    private final DisplayLink link;
    private final ControllerPointer pointer;
    private final ControllerKeyboard keyboard;
    private final Options options;
    /** This session's own D-Bus bus (and Flatpak portal), or {@code null} when one couldn't be started. */
    private final SessionBus bus;

    private final SessionAttachment attachment;
    /**
     * Guards the host window and its {@link #hostWindowState} together. The two are decided on different threads
     * — the hider finds the window, an attach reveals it — and the interesting case is precisely when they land
     * at once, so "publish the window" and "decide what to do with it" have to be one step, not two.
     */
    private final Object hostWindowLock = new Object();
    /** The server's window on the host desktop while it is being kept out of sight, or {@code null}. */
    private volatile SessionHostWindow hostWindow;
    /**
     * What has been decided about the host window <em>including before it was found</em>. It carries the reveal
     * request the old {@code volatile boolean revealRequested} carried, but as the same closed set
     * {@link SessionHostWindow.Visibility} uses, so there is one vocabulary across the two objects instead of a
     * flag here and a flag there that had to be read in the right order to mean anything.
     */
    private volatile SessionHostWindow.Visibility hostWindowState = SessionHostWindow.Visibility.PENDING;
    private volatile Process gameProc;
    /** The launched app's captured stdout+stderr, or {@code null} until something has been launched. */
    private volatile AppOutputLog appLog;
    private volatile boolean closed;

    /** How long an {@link #x11Capturable()} answer is reused; a frame loop asks it 24 times a second. */
    private static final long CAPTURABLE_TTL_MS = 1000;

    private volatile boolean capturable = true;
    private volatile long capturableAt;

    private NestedSession(String id, SessionReaper reaper, SessionDisplay display,
                          DisplayLink link, Options options, SessionBus bus) {
        this.id = id;
        this.reaper = reaper;
        this.display = display;
        this.link = link;
        this.options = options;
        this.bus = bus;
        this.attachment = new SessionAttachment(link, id + " on " + display.displayName());
        this.pointer = new ControllerPointer(link);
        this.keyboard = new ControllerKeyboard(link, this::attached);
        // The input backend asks for the driven window on every use rather than holding a handle, because
        // attached() re-resolves: the launcher chain routinely swaps the window out from under us.
        link.setDrivenWindow(this::attachedWindowId);
    }

    /**
     * Bring up a nested display (and its optional window manager), ready for a game to be {@link #launch}ed into
     * it. On any failure the partially-started tree is reaped before the exception propagates, so a caller can
     * cleanly fall back to a {@link HostSession}.
     */
    public static NestedSession start(Options options) throws SessionStartException {
        // Id shape s<pid>-<seq> is a contract: the orphan sweep parses the owner pid back out of the slice name.
        String id = "s" + ProcessHandle.current().pid() + "-" + SEQ.incrementAndGet();
        // Claimed here rather than after the tree is up, because the sweep below runs *concurrently* with the
        // bring-up: from its point of view an unclaimed slice owned by this pid is an abandoned one, and it would
        // stop the session being built right now. The claim is dropped again on a failed start.
        LIVE.add(id);
        SessionReaper reaper = new SessionReaper(id);
        // Sweep the trees a previously-SIGKILLed JVM left behind (systemd strategy only) — off the critical path.
        // It used to run first and synchronously, which put a `systemctl list-units` plus a 5s-budgeted
        // `systemctl stop` per orphan in front of every launch, for work Studio already does at startup. It
        // touches only slices this start doesn't own, so there is nothing for it to serialise against.
        sweepOrphansConcurrently();
        SessionDisplay display = null;
        DisplayLink link = null;
        SessionBus bus = null;
        try {
            display = startDisplay(reaper, options);
            // The bus comes up *after* the display and is given it, because the whole point is that the Flatpak
            // portal this bus activates inherits the private DISPLAY — see SessionBus.
            bus = SessionBackends.usesPrivateBus(options)
                ? SessionBus.start(reaper, id, Map.of(SessionEnv.DISPLAY, display.displayName()))
                : null;
            // The connection to :N is opened in a child process (DisplayLink) rather than here: when the
            // display server dies, Xlib's default I/O handler calls exit(1) in whichever process holds the
            // connection, and that used to be Studio. The backend travels with it because it fixes the input
            // policy — XTest pinned, and gamescope's Xwayland reading an absolute warp as window-relative.
            link = DisplayLink.open(display.displayName(), options.backend());
            if (link == null) {
                throw new SessionStartException("could not open " + display.displayName());
            }
            NestedSession session = new NestedSession(id, reaper, display, link, options, bus);
            session.startWindowManager();
            session.hideUntilItHasSomethingToShow();
            return session;
        } catch (SessionStartException e) {
            cleanupFailedStart(id, reaper, link, bus);
            throw e;
        } catch (Exception e) {
            cleanupFailedStart(id, reaper, link, bus);
            throw new SessionStartException("nested session start failed: " + e.getMessage(), e);
        }
    }

    /**
     * Run {@link #reapOrphanSessions()} on a daemon thread. The sweep is only ever best-effort housekeeping —
     * nothing about this session's bring-up depends on its result — so nobody waits for it, and a JVM that exits
     * mid-sweep is not held open by it.
     */
    private static void sweepOrphansConcurrently() {
        Thread sweep = new Thread(NestedSession::reapOrphanSessions, "session-orphan-sweep");
        sweep.setDaemon(true);
        sweep.start();
    }

    /** Bring up the display server the options ask for: Xephyr (2D) or gamescope (hardware 3D). */
    private static SessionDisplay startDisplay(SessionReaper reaper, Options options) throws SessionStartException {
        return switch (options.backend()) {
            case XEPHYR -> NestedDisplay.startXephyr(reaper, options.width(), options.height());
            case GAMESCOPE -> GamescopeDisplay.start(reaper, options.displayServerCommand(),
                options.width(), options.height());
        };
    }

    /**
     * Reap a half-built session's resources in the reverse order they were acquired, and drop the {@link #LIVE}
     * claim {@link #start} took before it — a session that never came up must not go on sheltering its own slice
     * from the sweep.
     */
    private static void cleanupFailedStart(String id, SessionReaper reaper, DisplayLink link, SessionBus bus) {
        LIVE.remove(id);
        if (bus != null) {
            try { bus.close(); } catch (Throwable ignored) { }
        }
        if (link != null) {
            try { link.close(); } catch (Throwable ignored) { }
        }
        reaper.reap();
    }

    /**
     * Minimize the display server's own window on the host desktop until this session has a window of its own to
     * put in it. The server maps that window the moment it starts and nothing draws into it until the game (or the
     * launcher) appears on {@code :N} — which for a store launcher is up to
     * {@link #LAUNCHER_WINDOW_TIMEOUT_MS two minutes} of black rectangle on the user's real desktop.
     *
     * <p>Best-effort and silent when the window can't be identified (see {@link SessionHostWindow#find}): that
     * case is exactly today's behaviour. Skipped entirely when {@link #HIDE_UNTIL_READY_PROPERTY} is {@code false}.
     */
    private void hideUntilItHasSomethingToShow() {
        if (!Boolean.parseBoolean(System.getProperty(HIDE_UNTIL_READY_PROPERTY, "true"))) {
            return;
        }
        // Off the start path: the window can take seconds to appear (gamescope publishes its output window well
        // after its Xwayland accepts connections — measured at more than 3s on the dev box), and a cosmetic
        // nicety must never delay the launch it is hiding.
        Thread hider = new Thread(this::findAndHideHostWindow, "session-host-window-hider-" + id);
        hider.setDaemon(true);
        hider.start();
    }

    private void findAndHideHostWindow() {
        SessionHostWindow window = SessionHostWindow.find(display.serverPid(), options.backend().binaryName(),
            display.displayName(), HOST_WINDOW_FIND_MS);
        if (window == null) {
            Diag.log("[Session] " + id + ": no host window could be proved ours for "
                + options.backend().binaryName() + " on " + display.displayName()
                + " — leaving bring-up visible rather than minimizing a window that might be the user's");
            return;
        }
        synchronized (hostWindowLock) {
            hostWindow = window;
            // A reveal that arrived while the search was still running is the whole reason this is locked: the
            // window has to come up shown, not be hidden by a decision taken before the reveal existed.
            if (hostWindowState == SessionHostWindow.Visibility.REVEALED) {
                Diag.log("[Session] " + id + ": found the host window after the session already had content"
                    + " — showing it");
                window.reveal();
                return;
            }
            // The remaining guard: don't hide a window that has something real in it — an empty session is the
            // only thing worth hiding. It is what makes this safe on gamescope, whose host window isn't even
            // mapped until a client maps something on its Xwayland (measured: no WM_STATE, absent from
            // _NET_CLIENT_LIST while empty), so by the time we can find it there is already content and hiding
            // it would hide the launcher.
            int mapped = link.mappedCount();
            if (mapped != 0) {
                Diag.log("[Session] " + id + ": leaving the host window visible — "
                    + (mapped < 0 ? "could not read " + display.displayName() : mapped + " client(s) already on "
                    + display.displayName()));
                return;
            }
            window.hide(mapped);
            hostWindowState = window.state();
        }
    }

    /**
     * Stop hiding the host window — and, if the search hasn't finished yet, don't start. Idempotent:
     * {@link SessionHostWindow#reveal} only fires once, so the per-attach call site stays unconditional.
     *
     * <p>Public for the same reason as {@link #hostWindowId}: a host-side tool that wants to look at the session
     * (Studio's overlay editor) has to be able to un-minimize it first, and calling it a second time after the
     * session's own attach already did costs nothing.
     */
    public void revealHostWindow() {
        synchronized (hostWindowLock) {
            hostWindowState = SessionHostWindow.Visibility.REVEALED;
            SessionHostWindow window = hostWindow;
            if (window != null) {
                window.reveal();
            }
        }
    }

    /** Launch the resolved window manager (if any) into the nested display and wait, best-effort, for it. */
    private void startWindowManager() {
        List<String> wm = windowManagerCommandFor(options);
        if (wm.isEmpty()) {
            Diag.log("[Session] " + id + ": no window manager " + (options.backend() == Backend.GAMESCOPE
                ? "(gamescope manages its own Xwayland)" : "— running WM-less"));
            return;
        }
        try {
            reaper.launch(SessionUnit.WM, wm, sessionEnv(), ProcessBuilder.Redirect.DISCARD);
        } catch (Exception e) {
            Diag.error("[Session] " + id + ": window manager launch failed: " + e.getMessage());
            return;
        }
        long deadline = System.currentTimeMillis() + WM_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (link.hasWindowManager()) {
                Diag.log("[Session] " + id + ": window manager is up");
                return;
            }
            sleep();
        }
        // A WM that never claims the display is a soft failure: input still reaches a mapped window without one.
        Diag.error("[Session] " + id + ": window manager did not claim " + display.displayName()
            + " within " + WM_TIMEOUT_MS + "ms — continuing WM-less");
    }

    @Override
    public Set<Capability> capabilities() {
        // The whole point of a bot-owned display: BACKGROUND_CLICK/ISOLATED_FOCUS/MULTI_SESSION, which a shared
        // :0 desktop cannot offer. HARDWARE_GL/VULKAN come only from the gamescope backend (Xephyr is 2D here).
        EnumSet<Capability> caps = EnumSet.of(
            Capability.ABSOLUTE_POINTER,
            Capability.RELATIVE_POINTER,
            Capability.BACKGROUND_CLICK,
            Capability.ISOLATED_FOCUS,
            Capability.MULTI_SESSION,
            Capability.SCREEN_CAPTURE,
            Capability.WINDOW_LAUNCH,
            Capability.WINDOW_ATTACH);
        if (display.hardwareAccelerated()) {
            caps.add(Capability.HARDWARE_GL);
            caps.add(Capability.VULKAN);
        }
        if (display.waylandDisplay() != null) {
            // Only a compositor backend answers this — gamescope with --expose-wayland. It is what lets a
            // Wayland-only client (Waydroid's show-full-ui) run in a session at all.
            caps.add(Capability.WAYLAND_CLIENTS);
        }
        return caps;
    }

    @Override
    public Rectangle screen() {
        return new Rectangle(0, 0, display.width(), display.height());
    }

    /**
     * {@code false} once this display has <b>no mapped X11 client at all</b> — which is what a Wayland-only
     * payload looks like from here, and the only thing about it that is observable.
     *
     * <p>This was originally "does the display serve a Wayland socket", reading {@link Capability#WAYLAND_CLIENTS}
     * as its consequence. That proxy is <em>constant</em>: {@code GamescopeDisplay} passes
     * {@code --expose-wayland} unconditionally, so it answered {@code false} for every gamescope session,
     * including a plain X11 game whose window captures perfectly well. Both call sites — the pilot's route
     * resolver and {@link #openVideoStream} — then took the fallback for a session that never needed one, which
     * is how the H.264 path came to be dead code on the only backend it was built for. Counting mapped clients
     * answers the actual question: Waydroid maps none, Firestone maps one.
     *
     * <p>The asymmetry the old javadoc argued still holds and still shapes the edges. Answering {@code false}
     * for a session that could have been captured costs a consumer one look at its next-best source; answering
     * {@code true} for one hosting Waydroid costs a black stream with no error anywhere to explain it — the
     * reported "the pilot shows nothing". So an <em>unaskable</em> display ({@code mappedCount() == -1}) counts
     * as capturable rather than not: that is a broken link, not an empty display, and consumers keep the session
     * as their floor (see {@code PilotRoutes}) so the answer degrades to a better source or to this same
     * session, never past it to the user's real desktop.
     *
     * <p><b>Memoised</b> for {@value #CAPTURABLE_TTL_MS}&nbsp;ms: the pilot asks this once per frame and each
     * miss is an X round trip, while the thing it measures changes at the speed of a window mapping.
     */
    @Override
    public boolean x11Capturable() {
        long now = System.currentTimeMillis();
        if (now - capturableAt > CAPTURABLE_TTL_MS) {
            capturable = link.mappedCount() != 0;
            capturableAt = now;
        }
        return capturable;
    }

    @Override
    public SessionPointer pointer() {
        return pointer;
    }

    @Override
    public SessionKeyboard keyboard() {
        return keyboard;
    }

    @Override
    public void attach(GenericWindow window) {
        // An explicit attach names the window it wants, so stop following the launcher chain: the caller has
        // already answered the question the promotion exists to guess at.
        attachment.followLauncherChain(false);
        attachment.attach(window);
        if (window != null) {
            // There is something in the session now, so the host window is worth looking at.
            revealHostWindow();
        }
    }

    /**
     * The window this session drives — re-resolved when the one we attached to has gone. The rule (and the bug
     * behind it) lives in {@link SessionAttachment}; a closed session answers with the last resolved window rather
     * than round-tripping to a display that is being torn down.
     */
    @Override
    public GenericWindow attached() {
        return closed ? attachment.current() : attachment.resolve();
    }

    /**
     * Launch {@code spec} into this nested display and attach to the window it produces. Any instance already
     * running on {@code :0} is force-stopped first (a game can't run in two places, and we want <em>ours</em>).
     * Whether the target can be confined at all is asked once, up front, by {@link LaunchIsolation} — no
     * child-launchable command, a host launcher already open, or a Flatpak-only target with no private bus to
     * own its portal all refuse here with that check's wording. Otherwise each form of the ladder is tried until one
     * maps a window on {@code :N}, within {@link #windowTimeoutFor the kind's window budget}. When none does,
     * {@link #attached()} stays null — the caller must treat that as a loud failure, not fall back to {@code :0}.
     */
    @Override
    public void launch(LaunchSpec spec) {
        if (closed || spec == null) {
            return;
        }
        try {
            launchAndAttach(spec);
        } finally {
            // However that went, stop hiding the host window. A launch that produced nothing is much easier to
            // understand as an empty display than as a window the user can't find, and a minimized window is
            // exactly what nobody thinks to look for.
            revealHostWindow();
        }
    }

    private void launchAndAttach(LaunchSpec spec) {
        // One up-front question — "can this be confined at all?" — instead of three separate guards that each
        // answered part of it. A refusal here costs nothing; discovering the same thing after the launch costs
        // the whole window budget and reaps a half-booted launcher (the Electron SIGTRAP).
        LaunchIsolation.Verdict verdict = LaunchIsolation.check(spec);
        if (!verdict.isolatable()) {
            Diag.error("[Session] " + id + ": " + verdict.reason());
            return;
        }
        // Only the forms that exist here: the ladder's missing rungs would each be spawned, exit at once, and be
        // reported as a window timeout they never waited for.
        List<List<String>> candidates = LaunchIsolation.runnableLadder(spec);
        stopHostInstance(spec);

        long windowTimeoutMs = windowTimeoutFor(spec, options);
        // One log for the whole ladder, opened before the first rung: when an early form fails and a later one
        // works, the reason the first didn't is the thing worth reading.
        AppOutputLog output = appLog == null ? (appLog = AppOutputLog.open(id)) : appLog;
        ProcessBuilder.Redirect sink =
            output == null ? ProcessBuilder.Redirect.DISCARD : output.redirect();
        for (List<String> command : candidates) {
            Set<Long> before = windowIdsOnDisplay();
            Process proc;
            try {
                // Both streams, same file: which message preceded which is itself evidence.
                proc = reaper.launch(SessionUnit.APP, command, sessionEnv(), sink, sink);
            } catch (Exception e) {
                Diag.error("[Session] " + id + ": launching `" + String.join(" ", command) + "` failed: "
                    + e.getMessage() + " — trying the next launch form");
                continue;
            }
            gameProc = proc;
            GenericWindow target = awaitWindow(proc, before, windowTimeoutMs);
            if (target != null) {
                attach(target);
                // A store launcher's first window is its own UI, not the game's, and it stays alive behind the
                // game — so for those kinds the attach is provisional and the newest window keeps winning.
                boolean viaLauncher = HostLauncherProbe.routesThroughDaemon(spec.kind());
                attachment.followLauncherChain(viaLauncher);
                Diag.log("[Session] " + id + ": attached to '" + target.getTitle() + "' on " + display.displayName()
                    + (viaLauncher ? " — provisionally, it launches through " + spec.kind()
                    + " and the game's own window comes later" : ""));
                return;
            }
            Diag.error("[Session] " + id + ": `" + String.join(" ", command) + "` mapped no window on "
                + display.displayName() + " within " + windowTimeoutMs + "ms — trying the next launch form"
                + outputHint(output));
        }
        // Every form ran but nothing appeared on :N. The up-front probe already ruled out what it can see, so
        // rather than guess, ask what the process table says actually happened — and say where the app's own
        // account of it is, which is the only source that can explain a failure neither probe anticipated.
        Diag.error("[Session] " + id + ": " + spec.spec() + " launched but no window appeared on "
            + display.displayName() + ". " + LaunchIsolation.noWindowDiagnosis(spec) + outputHint(output));
    }

    /** {@code " Its output: <path>"}, or nothing when the log couldn't be opened. */
    private static String outputHint(AppOutputLog output) {
        return output == null ? "" : " Its output: " + output.file().getAbsolutePath();
    }

    @Override
    public BufferedImage capture() {
        // Through attached(), not the field: a destroyed window otherwise captures null forever while the game
        // is running and capturable one window over.
        GenericWindow target = attached();
        return target == null ? null : link.captureWindow(target);
    }

    /**
     * The whole nested screen, which — unlike {@link #capture()} — does not depend on the attachment. A store
     * launcher swapping its own window for the game's is invisible here, so a viewer streaming this keeps
     * showing the session right through the swap that used to blank it.
     */
    @Override
    public BufferedImage captureScreen() {
        return link.captureScreen();
    }

    /** Straight through to the link, which is where the saving is — see {@link DisplayLink#previewFrame}. */
    @Override
    public PreviewFrame previewFrame(int maxEdge, float quality) {
        return link.previewFrame(maxEdge, quality);
    }

    /**
     * An {@code ffmpeg} grabbing {@code :N} directly, launched into this session's reap group so it dies with
     * the display it is reading — the one process here that is neither the payload nor something the payload
     * needs, and the one that would otherwise outlive a {@code kill -9}'d Studio still holding an X connection.
     *
     * <p>Declined, with {@code null}, in the two cases where it could only produce black: no {@code ffmpeg} on
     * PATH, and a display whose client is Wayland-only ({@link #x11Capturable()}) — the same condition that
     * already sends the pilot's route resolver elsewhere. Declining is not a failure; the caller keeps its JPEG
     * path for exactly this.
     */
    @Override
    public VideoStream openVideoStream(int maxEdge, int fps, Consumer<VideoPacket> sink) {
        if (closed || !x11Capturable() || !Executables.onPath("ffmpeg")) {
            return null;
        }
        return FfmpegVideoStream.open(display.displayName(), display.width(), display.height(), maxEdge, fps,
                sink, command -> reaper.launch(SessionUnit.VIDEO, command, sessionEnv(), Redirect.PIPE));
    }

    @Override
    public SessionHealth health() {
        if (closed || !display.alive()) {
            return SessionHealth.DEAD;
        }
        Process g = gameProc;
        if (g != null && !g.isAlive()) {
            // Display and (any) WM are up but the game died — recoverable by relaunching into the same display.
            return SessionHealth.DEGRADED;
        }
        return SessionHealth.HEALTHY;
    }

    @Override
    public NativeController controller() {
        return link;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        LIVE.remove(id);
        Diag.log("[Session] " + id + ": closing — payload first, then our X connections, then the slice");
        // Before anything the game depends on goes away. See shutdownMembers.
        shutdownMembers();
        // And show the host window on the way out: a session being torn down while minimized is a window the
        // user never gets back, and the repaint below has to know where it was.
        revealHostWindow();
        // Stop following the app's output, but leave the file: a session torn down after a failed launch is
        // exactly when someone wants to read it.
        AppOutputLog output = appLog;
        if (output != null) {
            output.close();
            Diag.log("[Session] " + id + ": app output kept at " + output.file().getAbsolutePath());
        }
        try { link.close(); } catch (Throwable t) { Diag.error("[Session] " + id + ": display link close: " + t.getMessage()); }
        // The bus daemon itself belongs to the reaper (it is in the slice); this only drops its generated files.
        if (bus != null) {
            try { bus.close(); } catch (Throwable t) { Diag.error("[Session] " + id + ": bus close: " + t.getMessage()); }
        }
        reaper.reap();
        // The display server's window has just gone with it. Ask the host to repaint where it was: a compositor
        // that was not tracking that window leaves its last frame on screen as a gray rectangle.
        SessionHostWindow window = hostWindow;
        if (window != null) {
            window.repaintHostBehind();
        }
        Diag.log("[Session] " + id + ": closed");
    }

    /**
     * Shut the payload down <em>before</em> the display server does — the step that makes teardown a shutdown
     * rather than a crash.
     *
     * <p>{@link SessionReaper#reap()} alone is not enough for a Flatpak target, because {@code flatpak run} moves
     * the app out of our slice into its own transient scope (see {@link SessionMembers}): stopping the slice
     * killed gamescope and left the launcher to abort on the X connection that vanished under it — the
     * {@code SIGTRAP} coredump that appeared on every live run. Asking those processes to exit first removes the
     * crash, and — the part that actually matters — reaps processes the slice never reached at all.
     */
    private void shutdownMembers() {
        List<ProcessHandle> members = SessionMembers.of(display.displayName(),
            bus == null ? null : bus.address(), reaper.unitNamesExcept(SessionUnit.APP));
        if (members.isEmpty()) {
            return;
        }
        Diag.log("[Session] " + id + ": asking " + members.size() + " session process(es) to exit before "
            + display.displayName() + " goes away");
        long started = System.currentTimeMillis();
        List<ProcessHandle> survivors = SessionMembers.shutdown(members, MEMBER_SHUTDOWN_MS);
        if (survivors.isEmpty()) {
            Diag.log("[Session] " + id + ": session processes exited in " + (System.currentTimeMillis() - started) + "ms");
            // Not the same question as "the ones we signalled are gone": a launcher shutting down routinely
            // spawns one last helper, and the display server must not be reaped out from under it. Re-ask the
            // environment rather than trusting the list we already had.
            awaitNoMembers(started + MEMBER_SHUTDOWN_MS);
            return;
        }
        // Not fatal — the slice reap follows — but worth saying plainly: these are exactly the processes it
        // cannot reach, so a survivor here is a real orphan.
        Diag.error("[Session] " + id + ": " + survivors.size() + " session process(es) survived SIGKILL: "
            + survivors.stream().map(SessionMembers::describe).reduce((a, b) -> a + ", " + b).orElse(""));
    }

    /**
     * Poll until nothing carries this session's environment any more, or {@code deadline} passes — the
     * "is the payload really gone?" question, asked of the environment the same way membership itself is.
     *
     * <p>It exists because the step after it kills the display server, and every process still holding a
     * connection to {@code :N} when that happens takes an X IO error: the {@code SIGTRAP} coredump this whole
     * ordering was built to remove. A late-spawned helper is exactly the case the signalled-list wait cannot
     * see, and it is cheap to check — the common outcome is one scan that finds nothing.
     */
    private void awaitNoMembers(long deadline) {
        while (System.currentTimeMillis() < deadline) {
            List<ProcessHandle> stragglers = SessionMembers.of(display.displayName(),
                bus == null ? null : bus.address(), reaper.unitNamesExcept(SessionUnit.APP));
            if (stragglers.isEmpty()) {
                return;
            }
            Diag.log("[Session] " + id + ": still waiting on " + stragglers.size()
                + " late session process(es) before " + display.displayName() + " goes away: "
                + stragglers.stream().map(SessionMembers::describe).reduce((a, b) -> a + ", " + b).orElse(""));
            SessionMembers.shutdown(stragglers, Math.max(0, deadline - System.currentTimeMillis()));
            sleep();   // so an unkillable straggler costs a poll per pass, not a spin on the process table
        }
    }

    /** The pid rooting this session's display-server tree — see {@link SessionDisplay#serverPid()}. */
    long serverPid() {
        return display.serverPid();
    }

    /** The nested display this session drives, e.g. {@code ":9"} — for diagnostics and tests. */
    public String displayName() {
        return display.displayName();
    }

    /** The backend hosting this session — a consumer offering it to another process has to pass it on. */
    public NestedSession.Backend backend() {
        return options.backend();
    }

    /**
     * The X id of the {@link #attached() attached} window, or {@code 0} when nothing is attached. Here rather than
     * at the call site so a consumer never has to unwrap a JNA {@code Pointer} out of a window handle —
     * {@link AdoptedSession#handoffArguments} is the one caller, and Studio has no other reason to know JNA exists.
     */
    public long attachedWindowId() {
        return WindowIds.of(attached());
    }

    /**
     * The X id of the display server's <em>own</em> window on the host desktop, or {@code 0} while it isn't known.
     *
     * <p>Plumbing, not contract — but the one thing a host-side tool needs to point at a session. Studio's overlay
     * editor captures this window to draw over a running gamescope session: gamescope passes {@code -W/-H} and
     * {@code -w/-h} as the same size, so this window's pixels are 1:1 with what the bot sees on {@code :N} and its
     * coordinates need no mapping. Matching it by <em>title</em> from the host side does not work — gamescope
     * renames its output window after whatever app is running in it — which is why the id is published at all.
     *
     * <p>{@code 0} is a normal answer for the first seconds of a session, not an error: the window is found on the
     * hider thread ({@link #hideUntilItHasSomethingToShow}), and gamescope does not map it until a client maps
     * something on its Xwayland. A caller polls or falls back; it must not treat {@code 0} as failure.
     */
    public long hostWindowId() {
        SessionHostWindow window = hostWindow;
        return window == null ? 0 : window.windowId();
    }

    /** This session's reap-group id — for diagnostics and tests. */
    public String sessionId() {
        return id;
    }

    /**
     * Close this session if its display is gone, and say whether it did.
     *
     * <p>A dead display is not a state a session can come back from ({@link SessionHealth#DEAD}), but nothing used
     * to act on it: the object stayed "open", holding a slice with a private {@code dbus-daemon} in it, and every
     * launch probe read that leftover as a launcher still up. Whoever holds the session polls this — Studio's
     * background launcher does, and drops the session it can no longer use.
     *
     * @return {@code true} if this call closed it (so a caller notifies once, not on every poll)
     */
    public boolean closeIfDead() {
        if (closed || health() != SessionHealth.DEAD) {
            return false;
        }
        Diag.error("[Session] " + id + ": " + display.displayName() + " is gone — closing the session");
        close();
        return true;
    }

    /**
     * Reap the process trees of nested sessions this JVM no longer holds, and of sessions whose owning JVM has
     * died — the reliable answer to "a bot crashed and left a Xephyr running". Call it at startup (a
     * supervisor/Studio boot) and before deciding whether a launch can be isolated: a leftover is read as a
     * running launcher by the launch probes, so sweeping late means refusing a launch on a dead session's
     * account. {@link #start} runs it alongside each new session too — concurrently, since it is housekeeping and
     * not a precondition. No-op where there is no user systemd.
     */
    public static void reapOrphanSessions() {
        SessionReaper.reapOrphans(LIVE);
    }

    // --- internals ---

    /** The child environment every process launched into this session gets: its private DISPLAY, plus extras. */
    private Map<String, String> sessionEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SessionEnv.DISPLAY, display.displayName());
        if (bus != null) {
            // The session's own bus — and with it its own Flatpak portal, so a launcher that re-spawns its game
            // through the portal lands back on :N instead of the host's :0. See SessionBus for the measurements.
            env.put(SessionEnv.DBUS_SESSION_BUS_ADDRESS, bus.address());
        }
        // A Wayland-capable client offered both will usually prefer Wayland — and the *host* compositor is
        // exactly what this session exists to stay out of. Blanking it forces the private X display.
        //
        // Unless the display hosts a compositor of its own (gamescope --expose-wayland), in which case handing
        // over *that* socket keeps the client inside the session just as effectively, and is the only way a
        // Wayland-only client can run here at all — blanking it leaves Waydroid's show-full-ui with nothing to
        // connect to. See Capability.WAYLAND_CLIENTS.
        String wayland = display.waylandDisplay();
        env.put(SessionEnv.WAYLAND_DISPLAY, wayland == null ? "" : wayland);
        env.putAll(options.extraEnv());
        return env;
    }

    /**
     * The window manager to actually run for {@code options}: what the caller asked for when it said anything at
     * all (including {@link Options#withoutWindowManager() "none"}), else the backend's policy from
     * {@link SessionBackends#windowManagerFor}. A window manager on a gamescope session is refused whoever asked
     * for it — gamescope already manages its Xwayland, and a second manager would fight it for the selection.
     */
    static List<String> windowManagerCommandFor(Options options) {
        if (options.backend() == Backend.GAMESCOPE) {
            if (options.hasExplicitWindowManager() && !options.windowManagerCommand().isEmpty()) {
                Diag.error("[Session] ignoring window manager `" + String.join(" ", options.windowManagerCommand())
                    + "` — gamescope is the window manager for its own Xwayland");
            }
            return List.of();
        }
        return options.hasExplicitWindowManager()
            ? options.windowManagerCommand()
            : SessionBackends.windowManagerFor(options.backend());
    }

    /**
     * How long to wait for {@code spec}'s window: an explicit {@link Options#windowTimeoutMs()} when one is set,
     * else {@link #LAUNCHER_WINDOW_TIMEOUT_MS} for a kind whose launch is routed through a store launcher (we're
     * waiting on the game it starts, not on the process we spawned) and {@link #WINDOW_TIMEOUT_MS} otherwise —
     * an {@code exe:}/{@code cli:} target <em>is</em> the process we spawned, so a window that hasn't appeared in
     * twenty seconds isn't coming.
     */
    static long windowTimeoutFor(LaunchSpec spec, Options options) {
        long explicit = options == null ? 0L : options.windowTimeoutMs();
        if (explicit > 0) {
            return explicit;
        }
        return spec != null && HostLauncherProbe.routesThroughDaemon(spec.kind())
            ? LAUNCHER_WINDOW_TIMEOUT_MS
            : WINDOW_TIMEOUT_MS;
    }

    /**
     * Force-stop any incarnation of {@code spec} already running on the host, so ours is the only one. This
     * stops the <em>game</em> by name; it deliberately does not kill the user's launcher <em>daemon</em>
     * (Heroic/Steam), which would disrupt their whole session. That a running daemon would swallow our launch
     * entirely is handled one step earlier, by {@link HostLauncherProbe} refusing the launch outright.
     */
    private void stopHostInstance(LaunchSpec spec) {
        if (!Launcher.isRunning(spec)) {
            return;
        }
        String name = spec.fileName();
        if (name != null && !name.isBlank()) {
            Diag.log("[Session] " + id + ": stopping host instance of " + spec.spec() + " (" + name + ")");
            GameLauncher.kill(name);
        } else {
            Diag.error("[Session] " + id + ": " + spec.spec() + " is running on the host but can't be stopped by name");
        }
    }

    /** All window ids currently on the nested display — the "before" snapshot the new-window attach diffs against. */
    private Set<Long> windowIdsOnDisplay() {
        Set<Long> ids = new HashSet<>();
        for (GenericWindow w : link.getAllWindows()) {
            ids.add(WindowIds.of(w));
        }
        return ids;
    }

    /**
     * Wait for the game's window and return it. Preference order: a window whose {@code _NET_WM_PID} is in the
     * launched process subtree (the robust match — Wine/Proton set it); else a window that appeared since
     * {@code before} (covers apps/WMs that don't set {@code _NET_WM_PID}, and WM-less displays with no client
     * list); else {@code null} on timeout.
     */
    private GenericWindow awaitWindow(Process proc, Set<Long> before, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Set<Long> pids = subtreePids(proc);
            GenericWindow newest = null;
            for (GenericWindow w : link.getAllWindows()) {
                long pid = link.windowPid(WindowIds.of(w));
                if (pid > 0 && pids.contains(pid)) {
                    return w; // strongest evidence — this window's own client is our process
                }
                if (!before.contains(WindowIds.of(w))) {
                    newest = w; // last new window wins (the most recently mapped top-level)
                }
            }
            if (newest != null && !proc.isAlive() && subtreePids(proc).isEmpty()) {
                // Process already exited and left a new window (e.g. a launcher shim) — take it rather than spin.
                return newest;
            }
            if (newest != null) {
                return newest;
            }
            if (!proc.isAlive() && subtreePids(proc).isEmpty()) {
                Diag.error("[Session] " + id + ": launched process exited before a window appeared");
                return null;
            }
            sleep();
        }
        return null;
    }

    /** The pid of {@code proc} plus all its live descendants — under systemd the payload is a descendant of the scope. */
    private static Set<Long> subtreePids(Process proc) {
        Set<Long> pids = new HashSet<>();
        if (proc.isAlive()) {
            pids.add(proc.pid());
        }
        try {
            proc.descendants().forEach(h -> pids.add(h.pid()));
        } catch (Exception ignored) {
            // descendants() can race with exit; the pids we already have are enough.
        }
        return pids;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Which display server hosts the nested session — the 2D vs. hardware-3D choice. */
    public enum Backend {
        /** Xephyr: cheap 2D host, software-rendered here. */
        XEPHYR(Executables.XEPHYR),
        /** gamescope: embedded Xwayland on the real GPU — for Proton/DXVK/Vulkan 3D targets. */
        GAMESCOPE(Executables.GAMESCOPE);

        private final String binaryName;

        Backend(String binaryName) {
            this.binaryName = binaryName;
        }

        /**
         * The executable this backend spawns to host the nested display ({@code Xephyr} / {@code gamescope}).
         * Single-sourced here so a consumer probing {@code PATH} for availability can't drift from what
         * {@link NestedDisplay} / {@link GamescopeDisplay} actually run.
         */
        public String binaryName() {
            return binaryName;
        }

        /**
         * The stable lowercase wire id ({@code "xephyr"} / {@code "gamescope"}) — what the project file's
         * {@code session.backend} key holds and what a generated bot passes to {@code Session.useBackend}. Kept
         * distinct from {@link #binaryName()} on purpose: that one is capitalised {@code Xephyr} because it is
         * the executable's actual name, and persisting a value that has to match an executable's spelling is how
         * a rename breaks stored configs.
         */
        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /**
         * Parses a backend {@link #id()} — total, and empty for anything that isn't one, which includes
         * {@code null}, blank and the explicit {@code "auto"}. Empty therefore means <em>"no override, use the
         * default"</em> ({@link SessionBackends#preferredBackend}, which is gamescope), never a silent fallback
         * to a particular backend: mapping an unrecognised value onto Xephyr is exactly the software-GL crash
         * that default exists to avoid. {@link #XEPHYR} is reachable only by naming it explicitly.
         */
        public static java.util.Optional<Backend> fromId(String id) {
            if (id == null || id.isBlank()) {
                return java.util.Optional.empty();
            }
            String normalized = id.trim().toLowerCase(java.util.Locale.ROOT);
            for (Backend backend : values()) {
                if (backend.id().equals(normalized)) {
                    return java.util.Optional.of(backend);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /**
     * How a nested session is shaped: which {@link Backend} hosts it, the display size, an optional window
     * manager to run in it, and any extra per-session environment (a private {@code HOME}/{@code XDG_RUNTIME_DIR}/
     * {@code WINEPREFIX} to stop a single-instance game escaping back to {@code :0}). The {@code DISPLAY} is
     * always set for you. For {@link Backend#GAMESCOPE} the exact gamescope argv is overridable
     * ({@link #withGamescopeCommand}) so a real box can tune it — or switch to the child-launch form — without a
     * code change.
     */
    public static final class Options {
        private final Backend backend;
        private final int width;
        private final int height;
        private final List<String> windowManagerCommand;
        private final Map<String, String> extraEnv;
        private final List<String> gamescopeCommand;
        private final long windowTimeoutMs;
        private final boolean privateBus;

        private Options(Backend backend, int width, int height, List<String> wm,
                        Map<String, String> extraEnv, List<String> gamescopeCommand, long windowTimeoutMs,
                        boolean privateBus) {
            this.backend = backend;
            this.width = width;
            this.height = height;
            // null = "not stated, use the backend's default policy"; empty = "explicitly none".
            this.windowManagerCommand = wm == null ? null : List.copyOf(wm);
            this.extraEnv = Map.copyOf(extraEnv);
            this.gamescopeCommand = gamescopeCommand == null ? List.of() : List.copyOf(gamescopeCommand);
            this.windowTimeoutMs = Math.max(0, windowTimeoutMs);
            this.privateBus = privateBus;
        }

        /**
         * A 2D Xephyr session at {@code width}x{@code height}, no extra env, running the backend's default
         * window manager ({@link SessionBackends#windowManagerFor} — openbox when it's installed, since a bare
         * Xephyr has no EWMH and therefore no input focus to inject keys into).
         */
        public static Options xephyr(int width, int height) {
            return new Options(Backend.XEPHYR, width, height, null, Map.of(), List.of(), 0, true);
        }

        /**
         * A hardware-3D gamescope session at {@code width}x{@code height}, no extra env. Always WM-less:
         * gamescope is itself the window manager for its embedded Xwayland.
         */
        public static Options gamescope(int width, int height) {
            return new Options(Backend.GAMESCOPE, width, height, null, Map.of(), List.of(), 0, true);
        }

        /**
         * This session, but running {@code command} as its window manager (e.g. {@code "openbox"}) instead of the
         * backend default. Passing no arguments means <em>explicitly none</em>, which is how a caller opts out of
         * the Xephyr default.
         */
        public Options withWindowManager(String... command) {
            return new Options(backend, width, height, List.of(command), extraEnv, gamescopeCommand, windowTimeoutMs, privateBus);
        }

        /** This session, but with no window manager at all — the explicit opt-out of the backend default. */
        public Options withoutWindowManager() {
            return withWindowManager();
        }

        /** This session, but with {@code env} overlaid on every child's environment (in addition to DISPLAY). */
        public Options withExtraEnv(Map<String, String> env) {
            return new Options(backend, width, height, windowManagerCommand, env, gamescopeCommand, windowTimeoutMs, privateBus);
        }

        /**
         * This session, but waiting {@code millis} for the launched target's window instead of the per-kind
         * default ({@link #windowTimeoutFor}). Zero or negative restores the default. The knob exists because
         * "how long can this game take to draw the first time" is a property of the user's machine — a cold
         * Proton prefix on a slow disk — not something this class can know.
         */
        public Options withWindowTimeout(long millis) {
            return new Options(backend, width, height, windowManagerCommand, extraEnv, gamescopeCommand, millis, privateBus);
        }

        /**
         * This session, but launching gamescope with {@code command} instead of the default argv. Only meaningful
         * for {@link Backend#GAMESCOPE}; lets a real box adjust flags (backend, HDR, {@code --} child form) without
         * touching {@link GamescopeDisplay}.
         */
        public Options withGamescopeCommand(String... command) {
            return new Options(backend, width, height, windowManagerCommand, extraEnv, List.of(command), windowTimeoutMs, privateBus);
        }

        public Backend backend() { return backend; }
        public int width() { return width; }
        public int height() { return height; }
        /** The <em>explicit</em> window-manager argv, or empty when none was stated (or none was wanted). */
        public List<String> windowManagerCommand() {
            return windowManagerCommand == null ? List.of() : windowManagerCommand;
        }

        /** Whether a caller stated a window manager (including {@link #withoutWindowManager()}'s "none"). */
        boolean hasExplicitWindowManager() {
            return windowManagerCommand != null;
        }
        public Map<String, String> extraEnv() { return extraEnv; }

        /** The explicit window-wait budget in ms, or {@code 0} to use the per-kind default. */
        public long windowTimeoutMs() { return windowTimeoutMs; }

        /** Whether this session brings up its own D-Bus bus and Flatpak portal — see {@link SessionBus}. */
        public boolean privateBus() { return privateBus; }

        /**
         * This session, but sharing the host's D-Bus session bus instead of owning one. The opt-out exists to be
         * bisected with, not used: without a private bus a Flatpak launcher's game is spawned by the <em>host's</em>
         * Flatpak portal and lands on {@code :0}, and a launcher already running on the desktop will swallow the
         * launch. Display isolation still holds for anything that stays in our process tree.
         */
        public Options withoutPrivateBus() {
            return new Options(backend, width, height, windowManagerCommand, extraEnv, gamescopeCommand,
                windowTimeoutMs, false);
        }

        /** The gamescope argv to launch: an explicit override if set, else {@link GamescopeDisplay#defaultCommand}. */
        public List<String> displayServerCommand() {
            return gamescopeCommand.isEmpty() ? GamescopeDisplay.defaultCommand(width, height) : gamescopeCommand;
        }
    }
}
