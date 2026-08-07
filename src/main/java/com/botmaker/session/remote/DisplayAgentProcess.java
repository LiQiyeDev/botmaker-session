package com.botmaker.session.remote;

import com.botmaker.session.impl.NestedSession;

import com.botmaker.shared.Diag;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Spawns a {@link DisplayAgent} and keeps it fed — the process half of {@link RemoteDisplay}.
 *
 * <p><b>Two spawn forms, tried in order.</b> The direct one is {@code $JAVA_HOME/bin/java -cp <our classpath>
 * DisplayAgent}, which works for every normal launch (a dev {@code javafx:run}, a {@code java -jar} bot, a
 * jpackage app-image, whose bundled runtime does ship {@code bin/java}). When that isn't available — a
 * classpath-less launch, a stripped runtime — it falls back to re-executing <em>this very program</em>
 * ({@link ProcessHandle#info()}'s command and arguments) with {@link DisplayAgent#ARG_MARKER} prepended, which
 * is why host applications dispatch on {@link DisplayAgent#isAgentInvocation} at the top of their {@code main}.
 *
 * <p>The child's stdin is the request stream and its stdout the response stream; <b>stderr is redirected to a
 * file</b> rather than a pipe, deliberately. A pipe nobody drains fills its buffer and blocks the writer, and
 * the writer here is the process holding the display — a diagnostic channel must not be able to wedge the
 * thing it is diagnosing. The file's path is logged once, so a failure has somewhere to be read.
 *
 * <p>Nothing supervises the child beyond this: it exits on EOF (the parent has gone) and it is destroyed on
 * {@link #close()}. That is the whole lifecycle, and it is enough — an agent whose parent was {@code kill -9}'d
 * reads EOF on its next request and leaves.
 */
final class DisplayAgentProcess implements AutoCloseable {

    private final Process process;
    private final File log;

    private DisplayAgentProcess(Process process, File log) {
        this.process = process;
        this.log = log;
    }

    /** The spawned agent, or {@code null} when neither spawn form produced a live process. */
    static DisplayAgentProcess start(String displayName, NestedSession.Backend backend) {
        File log = agentLog(displayName);
        List<List<String>> forms = new ArrayList<>();
        List<String> direct = directCommand(displayName, backend);
        if (direct != null) {
            forms.add(direct);
        }
        List<String> reexec = reexecCommand(displayName, backend);
        if (reexec != null) {
            forms.add(reexec);
        }
        for (List<String> command : forms) {
            Process process = spawn(command, log);
            if (process != null) {
                Diag.log("[Session] display agent for " + displayName + " is up (pid " + process.pid() + ")"
                    + (log == null ? "" : "; its output: " + log.getAbsolutePath()));
                return new DisplayAgentProcess(process, log);
            }
        }
        return null;
    }

    private static Process spawn(List<String> command, File log) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            builder.redirectError(log == null
                ? ProcessBuilder.Redirect.DISCARD
                : ProcessBuilder.Redirect.appendTo(log));
            // The agent must not inherit DISPLAY from us: it is told which display to open, and an inherited
            // one is exactly the host :0 this whole design exists to keep out of the picture.
            builder.environment().remove("DISPLAY");
            Process process = builder.start();
            // A command that isn't runnable fails here; one that is runnable but wrong (no main class) exits
            // within milliseconds, so give it a moment before calling it a success.
            if (process.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Diag.error("[Session] display agent `" + command.get(0) + "` exited immediately with "
                    + process.exitValue() + " — trying the next spawn form");
                return null;
            }
            return process;
        } catch (IOException e) {
            Diag.error("[Session] display agent `" + command.get(0) + "` could not start: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** {@code java -cp … DisplayAgent :N backend}, or {@code null} when this JVM has no usable classpath. */
    private static List<String> directCommand(String displayName, NestedSession.Backend backend) {
        String classpath = System.getProperty("java.class.path", "");
        Path java = Path.of(System.getProperty("java.home", ""), "bin", "java");
        if (classpath.isBlank() || !Files.isExecutable(java)) {
            return null;
        }
        return List.of(java.toString(), "-cp", classpath, DisplayAgent.class.getName(),
            DisplayAgent.ARG_MARKER, displayName, backend == null ? "" : backend.id());
    }

    /**
     * This program, started again with the agent marker in front of its own arguments — the form that works
     * when there is no classpath to hand (a modular or launcher-wrapped image).
     */
    private static List<String> reexecCommand(String displayName, NestedSession.Backend backend) {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String command = info.command().orElse(null);
        if (command == null) {
            return null;
        }
        List<String> argv = new ArrayList<>();
        argv.add(command);
        argv.add(DisplayAgent.ARG_MARKER);
        argv.add(displayName);
        argv.add(backend == null ? "" : backend.id());
        return argv;
    }

    /** Where this agent's stderr goes. {@code null} (and so discarded) when no temp file can be made. */
    private static File agentLog(String displayName) {
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir", "."), "botmaker");
            Files.createDirectories(dir);
            return dir.resolve("display-agent" + displayName.replace(':', '-') + "-"
                + ProcessHandle.current().pid() + ".log").toFile();
        } catch (Exception e) {
            return null;
        }
    }

    Process process() {
        return process;
    }

    boolean alive() {
        return process.isAlive();
    }

    /** Where the agent's own output landed, or {@code null} when it was discarded. */
    File log() {
        return log;
    }

    @Override
    public void close() {
        // Closing stdin is the polite form — the agent's read loop sees EOF and exits after finishing whatever
        // it was doing. The destroy is the guarantee, for an agent already blocked in Xlib.
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try {
            if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
