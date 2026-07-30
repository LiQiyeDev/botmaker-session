package com.botmaker.session.process;

import com.botmaker.session.impl.NestedSession;

import com.botmaker.shared.Diag;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * What the launched application actually said — the reading every "why didn't it start?" question needs, and
 * the one this stack used to throw away.
 *
 * <p><b>Why this exists.</b> {@link NestedSession} spawned the app with {@code Redirect.DISCARD} on
 * <em>both</em> streams, so a launch that failed inside a session left no trace but its exit. The user-visible
 * cost was a Steam title that wouldn't start and a bug report built entirely out of an unrelated coredump: the
 * only artefact left behind was pressure-vessel's {@code bwrap --unshare-all … --seccomp <fd> /usr/bin/true}
 * capability probe dying on {@code SIGSYS} — which is that probe working as designed, fires on every Steam
 * launch, and was measured on a launch that succeeded. Two hours of reading a red herring, because the real
 * message was discarded microseconds after it was written.
 *
 * <p><b>A file first, the log second.</b> Everything goes to a file that outlives the session (Proton and the
 * store launchers are voluminous, and the interesting line is rarely the last one), while only a bounded
 * prefix is echoed into {@link Diag}. Unbounded echoing would be its own defect: a Proton cold start buries
 * every {@code [Session]} line under winetricks output, and a diagnostic nobody can find is not far from one
 * that was never printed. After {@link #MAX_ECHOED_LINES} the echo stops with a pointer to the file.
 *
 * <p>stdout and stderr deliberately land in the <em>same</em> file: their interleaving is itself evidence
 * (which message preceded which), and separating them would make a reader reconstruct the order by hand.
 */
public final class AppOutputLog implements AutoCloseable {

	/** How many lines reach {@link Diag} before the echo gives up and points at the file instead. */
	public static final int MAX_ECHOED_LINES = 200;
	/** Longest echoed line; a Proton stack trace on one line would otherwise be the whole screen. */
	public static final int MAX_LINE_CHARS = 300;
	private static final long POLL_MS = 150;

	private final File file;
	private final Consumer<String> sink;
	private final int maxLines;
	private final int maxChars;

	private volatile boolean stopped;
	private Thread tailer;

	private AppOutputLog(File file, Consumer<String> sink, int maxLines, int maxChars) {
		this.file = file;
		this.sink = sink;
		this.maxLines = maxLines;
		this.maxChars = maxChars;
	}

	/**
	 * Open the log for session {@code id} and start echoing it, or return {@code null} when the file couldn't be
	 * created. Null rather than an exception because this is a diagnostic aid: failing to open it must never be
	 * the reason a launch doesn't happen — the caller falls back to discarding, exactly as before.
	 */
	public static AppOutputLog open(String id) {
		try {
			// Deliberately NOT SessionReaper.tempOutputFile: that marks its file deleteOnExit, which is right for
			// a display number parsed during start-up and wrong here — a bot process exits the moment its run
			// ends, so the log of the launch that just failed would be deleted exactly when it is wanted. The
			// file stays in the system temp dir for the OS to sweep on its own schedule.
			AppOutputLog log = new AppOutputLog(File.createTempFile("botmaker-app-" + id + "-", ".log"),
				Diag::log, MAX_ECHOED_LINES, MAX_LINE_CHARS);
			log.startTailer(id);
			return log;
		} catch (Exception e) {
			Diag.error("[Session] " + id + ": could not open an app output log (" + e.getMessage()
				+ ") — the launch continues, but its output will be discarded");
			return null;
		}
	}

	/** The testable seam: an explicit file and sink, and caps small enough to reach in a test. */
	public static AppOutputLog forTesting(File file, Consumer<String> sink, int maxLines, int maxChars) {
		AppOutputLog log = new AppOutputLog(file, sink, maxLines, maxChars);
		log.startTailer("test");
		return log;
	}

	/** Where the full output is kept — named in the failure messages, since that is when anyone wants it. */
	public File file() {
		return file;
	}

	/**
	 * The redirect to hand {@code SessionReaper.launch} for <em>both</em> streams. Two independent appends to one
	 * file are safe: the OS opens it {@code O_APPEND}, so each write lands at the end rather than at a stale
	 * offset one stream is holding.
	 */
	public Redirect redirect() {
		return Redirect.appendTo(file);
	}

	/** Stop echoing. The file is left in place — it is most wanted precisely when the session had to be torn down. */
	@Override
	public void close() {
		stopped = true;
		Thread t = tailer;
		if (t != null) {
			t.interrupt();
		}
	}

	private void startTailer(String id) {
		tailer = new Thread(() -> tail(id), "session-app-output-" + id);
		tailer.setDaemon(true); // a bot that is otherwise finished must never be held open by a log reader
		tailer.start();
	}

	/**
	 * Follow the file as it grows, emitting whole lines only. Reads by byte offset with a carry-over buffer
	 * rather than through a {@code BufferedReader}, because the writer is another process appending as we read:
	 * a reader would happily hand back the half of a line that had arrived so far, and every such line would be
	 * torn across two log entries.
	 */
	private void tail(String id) {
		long position = 0;
		int emitted = 0;
		StringBuilder partial = new StringBuilder();
		while (!stopped) {
			try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
				long length = raf.length();
				if (length > position) {
					raf.seek(position);
					byte[] chunk = new byte[(int) Math.min(length - position, 1 << 16)];
					int read = raf.read(chunk);
					if (read > 0) {
						position += read;
						partial.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
						emitted = drainLines(partial, emitted, id);
					}
				}
			} catch (Exception e) {
				return; // the file went away (JVM exit sweep, a wiped temp dir) — nothing left to follow
			}
			if (emitted >= maxLines) {
				return;
			}
			sleep();
		}
	}

	/** Emit every complete line held in {@code buffer}, leaving any trailing partial line for the next read. */
	private int drainLines(StringBuilder buffer, int emitted, String id) {
		int newline;
		while ((newline = buffer.indexOf("\n")) >= 0) {
			String line = buffer.substring(0, newline);
			buffer.delete(0, newline + 1);
			if (line.isBlank()) {
				continue; // launcher output is padded with blank lines; they carry nothing and cost a screen
			}
			if (emitted >= maxLines) {
				sink.accept("[App] " + id + ": further output in " + file.getAbsolutePath());
				return emitted + 1;
			}
			sink.accept("[App] " + id + ": " + truncate(line.stripTrailing()));
			emitted++;
		}
		return emitted;
	}

	private String truncate(String line) {
		return line.length() <= maxChars ? line : line.substring(0, maxChars) + "…";
	}

	private void sleep() {
		try {
			Thread.sleep(POLL_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			stopped = true;
		}
	}
}
