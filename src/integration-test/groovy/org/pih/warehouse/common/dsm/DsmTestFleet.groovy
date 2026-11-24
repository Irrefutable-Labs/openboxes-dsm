package org.pih.warehouse.common.dsm

import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import groovy.transform.CompileStatic

/**
 * The DSM storage set the tests' warehouse accounts run against: `openboxes-dsm-fleet` (dsm/fleet in this
 * repository), which is the SDK's own node harness as a process. It starts the storage node's own code on
 * Postgres databases of its own, writes the env config a dsm-app-host reads, prints `READY <config path>` and
 * serves until it is stopped. Its stdin is this JVM's pipe, so the set goes down with the test run whatever
 * stops it.
 *
 * Environment: `DSM_FLEET_BIN` (the helper binary) and `DSM_TEST_DATABASE_URL` (the Postgres server; the
 * database it names must exist, and each node gets a database named after it).
 */
@CompileStatic
class DsmTestFleet extends DsmTestProcess {

    /** The env config the hosts read to reach the set. */
    final File envConfig

    private DsmTestFleet(File log, Process process, File envConfig) {
        super("the storage set", log, process)
        this.envConfig = envConfig
    }

    static DsmTestFleet start(File root) {
        String binary = required("DSM_FLEET_BIN", "the openboxes-dsm-fleet binary (dsm/fleet, built against the DSM checkout)")
        String database = required("DSM_TEST_DATABASE_URL", "the Postgres server the storage nodes run on")
        root.mkdirs()
        File log = new File(root, "fleet.log")
        ProcessBuilder builder = new ProcessBuilder(binary, "--out", new File(root, "fleet").absolutePath)
        builder.environment().put("DSM_TEST_DATABASE_URL", database)
        builder.environment().put("RUST_LOG", "info")
        builder.redirectError(ProcessBuilder.Redirect.to(log))
        Process process = builder.start()
        File envConfig = awaitReady(process, log)
        return new DsmTestFleet(log, process, envConfig)
    }

    /** The config path the helper prints once every node serves. */
    private static File awaitReady(Process process, File log) {
        AtomicReference<String> ready = new AtomicReference<>()
        CountDownLatch printed = new CountDownLatch(1)
        Thread reader = new Thread({
            BufferedReader lines = new BufferedReader(
                    new InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
            String line
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("READY ")) {
                    ready.set(line.substring("READY ".length()).trim())
                    printed.countDown()
                }
            }
        }, "dsm-fleet-stdout")
        reader.daemon = true
        reader.start()
        if (!printed.await(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            String tail = log.isFile() ? log.readLines(StandardCharsets.UTF_8.name()).takeRight(20).join("\n") : ""
            throw new IllegalStateException(
                    "the storage set did not become ready within ${STEP_TIMEOUT_MS / 1000} s; its log ends:\n${tail}")
        }
        File envConfig = new File(ready.get())
        if (!envConfig.isFile()) {
            throw new IllegalStateException("the storage set named an env config that is not there: ${envConfig}")
        }
        return envConfig
    }
}
