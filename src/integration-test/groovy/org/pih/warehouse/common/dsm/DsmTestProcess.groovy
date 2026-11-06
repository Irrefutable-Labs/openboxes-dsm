package org.pih.warehouse.common.dsm

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import groovy.transform.CompileStatic

/**
 * A DSM process the integration tests start and stop: the storage set helper or a dsm-app-host. Both are DSM's
 * own code, built from the DSM checkout; the tests run nothing in their place. Where the binaries are, and which
 * Postgres server the storage set runs on, come from the environment, and a test with them unset fails rather
 * than skips: a suite that silently ran without DSM would report a property nobody has.
 */
@CompileStatic
abstract class DsmTestProcess implements Closeable {

    /** How long one step may take before the test names the step that did not happen (the DSM suites' bound). */
    static final long STEP_TIMEOUT_MS = 240_000L

    static final long POLL_MS = 500L

    final String name
    final File log
    final Process process

    protected DsmTestProcess(String name, File log, Process process) {
        this.name = name
        this.log = log
        this.process = process
    }

    static String required(String variable, String what) {
        String value = System.getenv(variable)
        if (!value?.trim()) {
            throw new IllegalStateException(
                    "${variable} must name ${what}: the DSM custody tests run against DSM's own code and do not run " +
                            "without it")
        }
        return value.trim()
    }

    /** The log's last lines, for a failure message. */
    String logTail(int lines = 20) {
        if (!log.isFile()) {
            return "(no log at ${log})"
        }
        List<String> all = log.readLines(StandardCharsets.UTF_8.name())
        return all.takeRight(lines).join("\n")
    }

    void requireAlive() {
        if (!process.isAlive()) {
            throw new IllegalStateException("${name} exited (${process.exitValue()}); its log ends:\n${logTail()}")
        }
    }

    /** `probe`'s first non-null answer within the step bound, or a failure naming what did not happen. */
    protected <T> T until(String what, Closure<T> probe) {
        long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS
        String lastReason = ""
        while (true) {
            requireAlive()
            try {
                T found = probe()
                if (found != null) {
                    return found
                }
            } catch (RuntimeException e) {
                lastReason = e.message
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException(
                        "${what}: not within ${STEP_TIMEOUT_MS / 1000} s (last: ${lastReason}); ${name}'s log ends:\n${logTail()}")
            }
            Thread.sleep(POLL_MS)
        }
    }

    /** Stop the process, as a phone kills an app: nothing is shut down for it. */
    @Override
    void close() {
        if (process.isAlive()) {
            process.destroy()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
    }

    static int freePort() {
        new ServerSocket(0).withCloseable { ServerSocket probe -> probe.localPort }
    }
}
