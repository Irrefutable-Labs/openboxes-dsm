package org.pih.warehouse.common.dsm

import java.nio.charset.StandardCharsets

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Another organization's OpenBoxes, for a test: this application started as a process of its own, on a port of its
 * own, with a database of its own (the test environment's Testcontainers URL gives each JVM its own MySQL) and a DSM
 * data root of its own. It is reached the way anyone reaches it, over HTTP with a signed-in session; the test holds
 * nothing of it in memory.
 */
class DsmTestOpenBoxes extends DsmTestProcess {

    /** Applying every migration on a fresh database, while other instances do the same, takes minutes. */
    static final long STARTUP_TIMEOUT_MS = 900_000L

    final int port
    final File dataRoot

    private DsmTestOpenBoxes(String name, File log, Process process, int port, File dataRoot) {
        super(name, log, process)
        this.port = port
        this.dataRoot = dataRoot
    }

    /**
     * @param host the DSM host this instance is started with: its account on first run, a warehouse's unless
     *             `registryLabel` names a product registry
     */
    static DsmTestOpenBoxes start(String name, File dataRoot, DsmTestHost host, String registryLabel = null) {
        String classpath = System.getProperty("openboxes.test.classpath")
        if (!classpath) {
            throw new IllegalStateException("openboxes.test.classpath is not set: build.gradle sets it for test tasks")
        }
        dataRoot.mkdirs()
        File log = new File(dataRoot, "openboxes.log")
        int port = freePort()
        List<String> command = [
                "${System.getProperty("java.home")}/bin/java".toString(),
                "-Xmx1500m",
                "-Dgrails.env=test",
                "-Dspring.output.ansi.enabled=never",
                "-Dapi.version=${System.getProperty("api.version")}".toString(),
                "-cp", classpath,
                "org.pih.warehouse.Application",
                "--server.port=${port}".toString(),
                "--openboxes.dsm.dataRoot=${dataRoot.canonicalPath}".toString(),
                "--openboxes.dsm.host.origin=${host.ingressOrigin}".toString(),
                "--openboxes.dsm.host.dataDir=${host.dataDir.canonicalPath}".toString(),
        ]
        if (registryLabel) {
            command << "--openboxes.dsm.host.registry=${registryLabel}".toString()
        }
        ProcessBuilder builder = new ProcessBuilder(command)
        builder.redirectOutput(ProcessBuilder.Redirect.to(log))
        builder.redirectErrorStream(true)
        return new DsmTestOpenBoxes(name, log, builder.start(), port, dataRoot)
    }

    /** Returns once the instance serves: every migration has run and the application answers. */
    void awaitUp() {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        String last = ""
        while (System.currentTimeMillis() < deadline) {
            requireAlive()
            try {
                Map answer = new Session(siteUrl).exchange("GET", "/health", null, null)
                if (answer.status == 200) {
                    return
                }
                last = "health answered ${answer.status}"
            } catch (IOException | IllegalStateException notYet) {
                last = notYet.message
            }
            Thread.sleep(2_000L)
        }
        throw new IllegalStateException("${name} did not come up: ${last}; its log ends:\n${logTail()}")
    }

    String getBaseUrl() {
        return "http://127.0.0.1:${port}/openboxes/api".toString()
    }

    String getSiteUrl() {
        return "http://127.0.0.1:${port}/openboxes".toString()
    }

    /**
     * The first run: `wallet` connects through the sign-in page's code and signs the owner statement, and becomes the
     * owner. There is no password at any point. The session returned is the owner's.
     */
    Session claim(DsmTestHost wallet) {
        Session page = new Session(siteUrl)
        // The page wants the one-time setup code the server wrote to its data root, which the installer alone can read.
        page.exchange("GET", "/dsmWalletSignIn/index", null, null)
        File setupCode = new File(dataRoot, "setup-code")
        long deadline = System.currentTimeMillis() + 20000L
        while (!setupCode.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(500L)
        }
        Map offered = page.exchange("GET", "/dsmWalletSignIn/claim?setup=${URLEncoder.encode(setupCode.text.trim(), "UTF-8")}", null, null)
        if (offered.status != 200 || !offered.json?.code) {
            throw new IllegalStateException("${name} offers no code to claim it: ${offered.status} ${offered.text}")
        }
        wallet.connectByCode(offered.json.code as String)
        answeredBy(wallet, "${name} claimed by ${wallet.name}") {
            Map step = page.exchange("POST", "/dsmWalletSignIn/claim", "", "application/x-www-form-urlencoded")
            if (step.json?.state == "FAILED") {
                throw new IllegalStateException("${name} could not be claimed: ${step.json.reason}")
            }
            step.json?.state == "SIGNED_IN" ? step : null
        }
        return apiSession(page)
    }

    /** A person signs in: their wallet is asked over its session and `wallet` approves, as a phone does. */
    Session signIn(String username, DsmTestHost wallet) {
        Session page = new Session(siteUrl)
        Map asked = page.exchange("POST", "/dsmWalletSignIn/ask", "username=${URLEncoder.encode(username, "UTF-8")}", "application/x-www-form-urlencoded")
        if (asked.json?.state != "ASKED") {
            throw new IllegalStateException("${name} did not ask ${username}'s wallet: ${asked.status} ${asked.text}")
        }
        answeredBy(wallet, "${username} signs in to ${name}") {
            Map step = page.exchange("POST", "/dsmWalletSignIn/answer", "", "application/x-www-form-urlencoded")
            if (step.json?.state in ["DECLINED", "FAILED", "NO_SEAT"]) {
                throw new IllegalStateException("${username} could not sign in to ${name}: ${step.json}")
            }
            step.json?.state == "SIGNED_IN" ? step : null
        }
        return apiSession(page)
    }

    /** The API session that carries the page's signed-in cookie. */
    private Session apiSession(Session page) {
        Session api = new Session(baseUrl)
        api.cookie = page.cookie
        return api
    }

    /** `step` is polled while `wallet` answers what waits on its phone, until `step` gives something. */
    private static void answeredBy(DsmTestHost wallet, String what, Closure step) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false)
        Thread answers = wallet.answeringRequests(done)
        try {
            wallet.until(what) { step() }
        } finally {
            done.set(true)
            answers.join()
        }
    }

    /** One HTTP answer: `status`, `text`, and `json` when the body parsed. */
    static class Session {

        private final String baseUrl
        String cookie

        Session(String baseUrl) {
            this.baseUrl = baseUrl
        }

        /** The `data` of a successful call; anything else fails the test, naming the answer. */
        def get(String path) {
            return data("GET", path, null, null)
        }

        def post(String path, Map form = [:]) {
            String body = form.collect { key, value ->
                "${URLEncoder.encode(key as String, "UTF-8")}=${URLEncoder.encode(value as String, "UTF-8")}"
            }.join("&")
            return data("POST", path, body, "application/x-www-form-urlencoded")
        }

        def postJson(String path, Object body) {
            return data("POST", path, JsonOutput.toJson(body), "application/json")
        }

        /** The whole answer of a form POST, for a call the test expects to be refused. */
        Map attemptPost(String path, Map form = [:]) {
            String body = form.collect { key, value ->
                "${URLEncoder.encode(key as String, "UTF-8")}=${URLEncoder.encode(value as String, "UTF-8")}"
            }.join("&")
            return exchange("POST", path, body, "application/x-www-form-urlencoded")
        }

        Map attemptPostJson(String path, Object body) {
            return exchange("POST", path, JsonOutput.toJson(body), "application/json")
        }

        private def data(String method, String path, String body, String contentType) {
            Map answer = exchange(method, path, body, contentType)
            if (answer.status < 200 || answer.status >= 300) {
                throw new IllegalStateException("${method} ${path} answered ${answer.status}: ${answer.text}")
            }
            return answer.json?.data
        }

        Map exchange(String method, String path, String body, String contentType) {
            HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection()
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 300_000
            connection.setRequestProperty("Accept", "application/json")
            if (cookie) {
                connection.setRequestProperty("Cookie", cookie)
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.withCloseable { it.write(body.getBytes(StandardCharsets.UTF_8)) }
            }
            int status = connection.responseCode
            connection.headerFields.get("Set-Cookie")?.each { String header ->
                if (header.startsWith("JSESSIONID=")) {
                    cookie = header.split(";")[0]
                }
            }
            InputStream stream = status < 400 ? connection.inputStream : connection.errorStream
            String text = stream ? stream.getText(StandardCharsets.UTF_8.name()) : ""
            Object json = null
            if (text.startsWith("{") || text.startsWith("[")) {
                json = new JsonSlurper().parseText(text)
            }
            return [status: status, text: text ?: connection.getHeaderField("Location"), json: json]
        }
    }
}
