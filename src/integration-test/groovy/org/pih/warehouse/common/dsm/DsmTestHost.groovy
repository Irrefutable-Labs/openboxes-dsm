package org.pih.warehouse.common.dsm

import com.google.protobuf.ByteString
import dsm.types.proto.BalanceGetResponse
import dsm.types.proto.BalancesListResponse
import dsm.types.proto.Envelope
import dsm.types.proto.FaucetClaimRequest
import dsm.types.proto.FaucetClaimResponse
import groovy.transform.CompileStatic

import org.pih.warehouse.dsm.DsmHostClient
import org.pih.warehouse.dsm.DsmSteps
import org.pih.warehouse.dsm.DsmHostException

/**
 * One warehouse's DSM account for a test: a `dsm-app-host` process (the binary the DSM repository builds, named
 * by `DSM_APP_HOST_BIN`) on the test's storage set, with its ingress and relay on free loopback ports. The first
 * start creates the account; its recovery mnemonic is written to the data directory, readable by this user alone.
 * The test speaks to it only through its ingress, as OpenBoxes does.
 */
@CompileStatic
class DsmTestHost extends DsmTestProcess {

    final File dataDir
    final int gamePort
    final int relayPort
    final DsmHostClient client

    private DsmTestHost(String name, File log, Process process, File dataDir, int gamePort, int relayPort) {
        super(name, log, process)
        this.dataDir = dataDir
        this.gamePort = gamePort
        this.relayPort = relayPort
        this.client = new DsmHostClient(ingressOrigin, dataDir)
    }

    String getIngressOrigin() {
        return "http://127.0.0.1:${gamePort}".toString()
    }

    static DsmTestHost start(String name, File root, File envConfig) {
        String binary = required("DSM_APP_HOST_BIN", "the dsm-app-host binary built from the DSM checkout")
        // What the client reads: the host keeps its own state apart from the secret it writes for its operator.
        File dataDir = new File(root, "${name}-ingress")
        File log = new File(root, "${name}.log")
        int gamePort = freePort()
        int relayPort = freePort()
        ProcessBuilder builder = new ProcessBuilder(binary,
                "--data-dir", new File(root, name).absolutePath,
                "--ingress-secret-dir", dataDir.absolutePath,
                "--env-config", envConfig.absolutePath,
                "--game-bind", "127.0.0.1:${gamePort}".toString(),
                "--relay-bind", "127.0.0.1:${relayPort}".toString(),
                "--relay-endpoint", "https://127.0.0.1:${relayPort}".toString(),
                "--relay-name", "127.0.0.1")
        builder.environment().put("RUST_LOG", "info")
        builder.redirectOutput(ProcessBuilder.Redirect.to(log))
        builder.redirectErrorStream(true)
        Process process = builder.start()
        DsmTestHost host = new DsmTestHost(name, log, process, dataDir, gamePort, relayPort)
        // The secret is this launch's once the ingress answers: the process writes it before it listens.
        host.until("${name}'s ingress answers") {
            new File(dataDir, DsmHostClient.INGRESS_TOKEN_FILE).isFile() ? host.client.activity(0) : null
        }
        return host
    }

    byte[] deviceId() {
        return client.deviceId()
    }

    /** Connect to an OpenBoxes instance through the code its page shows, as a person's wallet does by scanning it. */
    void connectByCode(String code) {
        dsm.types.proto.ConnectPreviewV1 preview = DsmSteps.reply(client.connect("connect.preview",
                dsm.types.proto.ConnectPreviewRequestV1.newBuilder().setCode(code.trim()).build(), DsmHostClient.Route.QUERY),
                dsm.types.proto.ConnectReplyV1.ReplyCase.PREVIEW, "connect.preview").preview
        DsmSteps.reply(client.connect("connect.approve",
                dsm.types.proto.ConnectApproveRequestV1.newBuilder().setOfferDigest(preview.offerDigest).build(), DsmHostClient.Route.INVOKE),
                dsm.types.proto.ConnectReplyV1.ReplyCase.SESSION, "connect.approve")
    }

    /**
     * Check this account's connected sessions and approve what waits, as a partner watching its wallet does, until `done`
     * says to stop. A request reaches a wallet only when it checks.
     */
    Thread answeringRequests(java.util.concurrent.atomic.AtomicBoolean done) {
        return Thread.start("${name} answers") {
            while (!done.get()) {
                try {
                    DsmSteps.reply(client.connect("connect.sync", com.google.protobuf.Empty.getDefaultInstance(), DsmHostClient.Route.INVOKE),
                            dsm.types.proto.ConnectReplyV1.ReplyCase.SESSIONS, "connect.sync")
                    DsmSteps.reply(client.connect("connect.pending", com.google.protobuf.Empty.getDefaultInstance(), DsmHostClient.Route.QUERY),
                            dsm.types.proto.ConnectReplyV1.ReplyCase.PENDING, "connect.pending").pending.pendingList.each { waiting ->
                        client.connect("connect.respond", dsm.types.proto.ConnectRespondRequestV1.newBuilder()
                                .setSessionId(waiting.sessionId).setSeq(waiting.seq)
                                .setDecision(dsm.types.proto.ConnectDecision.CONNECT_DECISION_APPROVE).build(), DsmHostClient.Route.INVOKE)
                    }
                } catch (DsmHostException | org.pih.warehouse.dsm.DsmCustodyException ignored) {
                    // Nothing to answer yet, or the session is settling: the next round looks again.
                }
                Thread.sleep(3000L)
            }
        }
    }

    /**
     * ERA from the network faucet, for the fees a token's creation burns. A claim whose admission is still held is
     * finished by the next claim, as a player claiming again finishes it.
     */
    void claimFaucet() {
        FaucetClaimRequest request = FaucetClaimRequest.newBuilder()
                .setDeviceId(ByteString.copyFrom(deviceId()))
                .build()
        until("${name} claims ERA") {
            Envelope envelope = client.invoke("faucet.claim", request)
            FaucetClaimResponse claimed = DsmHostClient
                    .expect("faucet.claim", envelope, Envelope.PayloadCase.FAUCET_CLAIM_RESPONSE)
                    .faucetClaimResponse
            if (claimed.success) {
                return claimed
            }
            throw new DsmHostException("faucet.claim", claimed.message)
        }
    }

    /** What the account's `balance.list` reports it can spend of `ticker`, in base units; null for a token it lists no row for. */
    Long balance(String ticker) {
        Envelope envelope = client.query("balance.list", new byte[0])
        BalancesListResponse list = DsmHostClient
                .expect("balance.list", envelope, Envelope.PayloadCase.BALANCES_LIST_RESPONSE)
                .balancesListResponse
        BalanceGetResponse row = list.balancesList.find { BalanceGetResponse b -> b.tokenId == ticker }
        return row == null ? null : row.available
    }

    /** The balance once it reaches `expected`, or a failure naming the last balance seen. */
    long awaitBalance(String ticker, long expected) {
        return until("${name} holds ${expected} ${ticker}") {
            Long held = balance(ticker)
            if (held == expected) {
                return held
            }
            throw new IllegalStateException("${name} holds ${held} ${ticker}")
        }
    }
}
