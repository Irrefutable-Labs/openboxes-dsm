package org.pih.warehouse.dsm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

import com.google.protobuf.ByteString
import com.google.protobuf.Message
import dsm.types.proto.AppHostActivityV1
import dsm.types.proto.ArgPack
import dsm.types.proto.Codec
import dsm.types.proto.ConnectReplyV1
import dsm.types.proto.Envelope
import dsm.types.proto.IngressRequest
import dsm.types.proto.IngressResponse
import dsm.types.proto.RouterInvokeOp
import dsm.types.proto.RouterQueryOp
import dsm.types.proto.TransactionInfo
import dsm.types.proto.WalletHistoryResponse
import groovy.transform.CompileStatic

/**
 * A warehouse's own DSM account, reached through its dsm-app-host on this machine.
 *
 * The host speaks the DSM SDK's protobuf ingress (the boundary a phone's wallet screen and the Wildstate game
 * server use): an IngressRequest in, an IngressResponse out, the answer a 0x03-framed Envelope. This client only
 * frames requests and reads answers; every DSM decision is the SDK's, inside the host. Nothing a call returns is
 * evidence by itself: what OpenBoxes acts on is read back from the account's own state (its balances, its
 * history, the facts its Connect routes established).
 *
 * The ingress listens on a loopback address alone and admits a call only with the secret the host's launch wrote
 * to `<data-dir>/ingress-token` (readable by the operator alone), named as `Authorization: Bearer <secret>`, with
 * a Host header naming the ingress itself. The secret is read on every call: it changes with each launch.
 */
@CompileStatic
class DsmHostClient {

    /** The longest one call may take: a lineage walk on a cold account can take a minute (Wildstate, 2026-10-04). */
    static final int CALL_TIMEOUT_MS = 120_000

    /** Where a launch writes its ingress secret (dsm-app-host, `INGRESS_TOKEN`). */
    static final String INGRESS_TOKEN_FILE = "ingress-token"

    private static final byte ENVELOPE_FRAME = 0x03 as byte

    final String origin
    final File dataDir

    /**
     * @param origin the host's game-facing ingress, `http://127.0.0.1:<port>`, `http://localhost:<port>` or
     *               `http://[::1]:<port>`: the host binds a loopback address and refuses any other
     * @param dataDir the host's data directory, where its ingress secret is read from
     */
    DsmHostClient(String origin, File dataDir) {
        URI parsed
        try {
            parsed = new URI(origin)
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("the ingress origin ${origin} is not a URI: ${e.message}", e)
        }
        if (parsed.scheme != "http" || parsed.rawPath || parsed.rawQuery != null || parsed.rawFragment != null
                || parsed.port < 1) {
            throw new IllegalArgumentException(
                    "the ingress origin ${origin} is not http://<loopback host>:<port> with nothing after it")
        }
        if (!(parsed.host in ["127.0.0.1", "localhost", "[::1]"])) {
            throw new IllegalArgumentException(
                    "the ingress origin ${origin} is not on this machine: the host serves its ingress on a loopback " +
                            "address alone, and OpenBoxes must run beside it")
        }
        this.origin = origin
        this.dataDir = dataDir
    }

    /** A route that changes the account's state, recorded by the host in its activity record. */
    Envelope invoke(String method, Message body) {
        return invoke(method, body.toByteArray())
    }

    Envelope invoke(String method, byte[] body) {
        return router(method, body, Route.INVOKE, "/ingress")
    }

    /** A route that changes the account's state, run without being recorded: what a poll calls. */
    Envelope invokeQuietly(String method, Message body) {
        return router(method, body.toByteArray(), Route.INVOKE, "/ingress/quiet")
    }

    /** A route that reads the account's state. */
    Envelope query(String method, Message body) {
        return query(method, body.toByteArray())
    }

    Envelope query(String method, byte[] body) {
        return router(method, body, Route.QUERY, "/ingress/quiet")
    }

    /** A DSM Connect route's reply (`connect.*`, `connect.app.*`). */
    ConnectReplyV1 connect(String method, Message body, Route route) {
        Envelope envelope = route == Route.INVOKE ? invoke(method, body) : query(method, body)
        return expect(method, envelope, Envelope.PayloadCase.CONNECT_REPLY).connectReply
    }

    /**
     * The account's history, newest first, as `wallet.history` reports it: `limit` rows from `offset`. The route
     * takes its bounds as sixteen raw little-endian bytes in the arg pack's body, not as a message.
     */
    List<TransactionInfo> history(long limit, long offset) {
        ByteBuffer bounds = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        bounds.putLong(limit)
        bounds.putLong(offset)
        Envelope envelope = query("wallet.history", bounds.array())
        WalletHistoryResponse response =
                expect("wallet.history", envelope, Envelope.PayloadCase.WALLET_HISTORY_RESPONSE).walletHistoryResponse
        return response.transactionsList
    }

    /** What the host recorded after `after`, and which account this is. */
    AppHostActivityV1 activity(long after) {
        byte[] answer = send("GET", "/activity/${after}".toString(), null, "/activity")
        return AppHostActivityV1.parseFrom(answer)
    }

    /** This account's device id, from the host's own record. */
    byte[] deviceId() {
        byte[] id = activity(0).deviceId.toByteArray()
        if (id.length != 32) {
            throw new DsmHostException("/activity", "the host names a device id of ${id.length} bytes, not 32")
        }
        return id
    }

    /** `envelope`'s payload, refused unless it is `wanted`. */
    static Envelope expect(String method, Envelope envelope, Envelope.PayloadCase wanted) {
        if (envelope.payloadCase != wanted) {
            throw new DsmHostException(method, "the route answered ${envelope.payloadCase}, not ${wanted}")
        }
        return envelope
    }

    static enum Route {
        QUERY,
        INVOKE
    }

    private Envelope router(String method, byte[] body, Route route, String path) {
        ByteString args = ArgPack.newBuilder()
                .setCodec(Codec.CODEC_PROTO)
                .setBody(ByteString.copyFrom(body))
                .build()
                .toByteString()
        IngressRequest.Builder request = IngressRequest.newBuilder()
        if (route == Route.INVOKE) {
            request.setRouterInvoke(RouterInvokeOp.newBuilder().setMethod(method).setArgs(args))
        } else {
            request.setRouterQuery(RouterQueryOp.newBuilder().setMethod(method).setArgs(args))
        }
        byte[] answer = send("POST", path, request.build().toByteArray(), method)
        IngressResponse response
        try {
            response = IngressResponse.parseFrom(answer)
        } catch (IOException e) {
            throw new DsmHostException(method, "the answer is not an IngressResponse: ${e.message}", e)
        }
        switch (response.resultCase) {
            case IngressResponse.ResultCase.ERROR:
                throw new DsmHostException(method, response.error.message)
            case IngressResponse.ResultCase.OK_BYTES:
                break
            default:
                throw new DsmHostException(method, "the ingress answered nothing")
        }
        byte[] framed = response.okBytes.toByteArray()
        if (framed.length == 0 || framed[0] != ENVELOPE_FRAME) {
            throw new DsmHostException(method, "the answer is not a framed envelope")
        }
        Envelope envelope
        try {
            envelope = Envelope.parseFrom(Arrays.copyOfRange(framed, 1, framed.length))
        } catch (IOException e) {
            throw new DsmHostException(method, "the answer is not an Envelope: ${e.message}", e)
        }
        if (envelope.payloadCase == Envelope.PayloadCase.ERROR) {
            throw new DsmHostException(method, envelope.error.message)
        }
        return envelope
    }

    private String authorization() {
        File token = new File(dataDir, INGRESS_TOKEN_FILE)
        if (!token.isFile()) {
            throw new DsmHostException(origin,
                    "no ingress secret at ${token}: is the account's dsm-app-host running from ${dataDir}?")
        }
        return "Bearer " + token.getText(StandardCharsets.UTF_8.name()).trim()
    }

    private byte[] send(String verb, String path, byte[] body, String what) {
        HttpURLConnection connection
        try {
            connection = (HttpURLConnection) new URL(origin + path).openConnection()
        } catch (IOException e) {
            throw new DsmHostException(what, "the ingress at ${origin}: ${e.message}", e)
        }
        try {
            connection.requestMethod = verb
            connection.connectTimeout = CALL_TIMEOUT_MS
            connection.readTimeout = CALL_TIMEOUT_MS
            connection.setRequestProperty("Authorization", authorization())
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-protobuf")
                connection.setFixedLengthStreamingMode(body.length)
                connection.outputStream.withStream { OutputStream out -> out.write(body) }
            }
            int status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                InputStream refused = connection.errorStream ?: connection.inputStream
                String why = refused ? refused.getText(StandardCharsets.UTF_8.name()) : ""
                throw new DsmHostException(what, "the host answered ${status}: ${why}")
            }
            return connection.inputStream.bytes
        } catch (IOException e) {
            throw new DsmHostException(what, "the ingress at ${origin}: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }
}
