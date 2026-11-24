package org.pih.warehouse.dsm

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import dsm.types.proto.AmountDirection
import dsm.types.proto.ConnectAppRequestIntentV1
import dsm.types.proto.ConnectAppStatusV1
import dsm.types.proto.ConnectDecidedV1
import dsm.types.proto.ConnectDecision
import dsm.types.proto.ConnectFact
import dsm.types.proto.ConnectOutcome
import dsm.types.proto.ConnectPayV1
import dsm.types.proto.ConnectPendingV1
import dsm.types.proto.ConnectReplyV1
import dsm.types.proto.ConnectRequestRefV1
import dsm.types.proto.ConnectRespondRequestV1
import dsm.types.proto.Envelope
import dsm.types.proto.TransactionInfo
import dsm.types.proto.TransactionType
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import org.pih.warehouse.core.User
import org.pih.warehouse.dsm.DsmHostClient.Route
import org.pih.warehouse.product.Product

/**
 * How physical units enter DSM custody, from both sides of the connection between a warehouse organization and the
 * registry (docs/plans/2026-11-01-dsm-custody-integration.md):
 * - the organization asks: a payment request over the connection, outside any grant, naming the document that says the
 *   units exist;
 * - the registry's operators decide on the registry's own instance: DSM holds the request until its account is told,
 *   and that account is told only after the configured number of different operators approved;
 * - the organization establishes the outcome from its own account's result and history, and only then records stock
 *   against the units.
 *
 * There is no other way units leave the registry: no route here sends from a registry account, the registry grants
 * no scope that spends, and its account is on no instance that custodies stock.
 */
@Transactional
class DsmAdmissionService {

    static final String MEMO_ADMISSION = "openboxes:admission:"

    /** What DSM keeps of a payment's memo (`payment_memo`): anything longer is cut, and would never match. */
    static final int MAX_PAYMENT_MEMO = 96

    static final int DEFAULT_APPROVALS_REQUIRED = 2

    static final long HISTORY_ROWS = 1_000L

    DsmAccountService dsmAccountService
    GrailsApplication grailsApplication

    // ------------------------------------------------------------------ the organization's side

    /**
     * Ask the registry to release `quantity` units into `account`'s custody. Answers once the request went out; the
     * units move only when the registry's operators approve it.
     */
    DsmAdmission requestAdmission(DsmWarehouseAccount account, Product product, int quantity, String reference,
                                  User requestedBy) {
        if (account.registry) {
            throw new DsmCustodyException("a registry takes no units into custody")
        }
        if (quantity < 1) {
            throw new DsmCustodyException("an admission is of at least one unit, not ${quantity}")
        }
        if (!reference?.trim()) {
            throw new DsmCustodyException(
                    "an admission names the document that says the physical units exist (a goods-received note, a count sheet)")
        }
        DsmProductToken token = dsmAccountService.tokenFor(product)
        DsmConnection connection = registryConnection(account)
        DsmAdmission admission = new DsmAdmission(
                account: account,
                product: product,
                quantity: quantity,
                reference: reference.trim(),
                requestedBy: requestedBy,
        )
        admission.save(failOnError: true, flush: true)
        // Shown on the registry's phone as the thing to approve: the reference people know, then a short handle of this
        // row, so no unbroken run of characters is wider than a phone's card. The memo is matched whole, never parsed.
        admission.memo = "${MEMO_ADMISSION}${admission.reference} ${admission.id[-8..-1]}".toString()
        if (admission.memo.length() > MAX_PAYMENT_MEMO) {
            throw new DsmCustodyException(
                    "the reference ${admission.reference} is too long: DSM carries ${MAX_PAYMENT_MEMO} characters of an " +
                            "admission's memo, and this one is ${admission.memo.length()}")
        }
        admission.sessionId = connection.sessionId
        ConnectAppRequestIntentV1 intent = ConnectAppRequestIntentV1.newBuilder()
                .setSessionId(ByteString.copyFrom(Base32Crockford.decodeId32(connection.sessionId, "the connection id")))
                .setPay(ConnectPayV1.newBuilder()
                        .setPolicyCommit(ByteString.copyFrom(Base32Crockford.decodeId32(token.policyAnchor, "the anchor")))
                        .setAmount(quantity)
                        .setMemo(admission.memo))
                .build()
        ConnectRequestRefV1 made = DsmSteps.reply(
                dsmAccountService.clientFor(account).connect("connect.app.request", intent, Route.INVOKE),
                ConnectReplyV1.ReplyCase.REQUEST, "connect.app.request").request
        admission.seq = made.seq
        admission.save(failOnError: true, flush: true)
        return admission
    }

    /**
     * What this account's own result says became of the request. The registry's answer alone moves nothing here:
     * only DSM's fact that the payment was accepted, with this account's own history showing the credit, establishes
     * the units.
     */
    DsmAdmission refreshAdmission(DsmAdmission admission) {
        if (admission.status in [DsmAdmission.STATUS_ESTABLISHED, DsmAdmission.STATUS_DECLINED, DsmAdmission.STATUS_FAILED]) {
            return admission
        }
        if (admission.seq == null) {
            admission.status = DsmAdmission.STATUS_FAILED
            admission.reason = "the request never went out"
            admission.save(failOnError: true, flush: true)
            return admission
        }
        DsmHostClient client = dsmAccountService.clientFor(admission.account)
        ConnectRequestRefV1 ref = ConnectRequestRefV1.newBuilder()
                .setSessionId(ByteString.copyFrom(Base32Crockford.decodeId32(admission.sessionId, "the connection id")))
                .setSeq(admission.seq)
                .build()
        Envelope envelope = client.invokeQuietly("connect.app.status", ref)
        ConnectAppStatusV1 status = DsmHostClient
                .expect("connect.app.status", envelope, Envelope.PayloadCase.CONNECT_REPLY)
                .connectReply.status
        if (status.fact == ConnectFact.CONNECT_FACT_PAID) {
            TransactionInfo credit = creditOf(client, admission)
            if (credit) {
                admission.status = DsmAdmission.STATUS_ESTABLISHED
                admission.dateEstablished = new Date()
                admission.creditTransferId = credit.id
            }
        } else if (status.answered && status.outcome == ConnectOutcome.CONNECT_OUTCOME_AWAITING_APPROVAL) {
            admission.status = DsmAdmission.STATUS_AWAITING_APPROVAL
        } else if (status.answered && status.outcome in [ConnectOutcome.CONNECT_OUTCOME_DECLINED, ConnectOutcome.CONNECT_OUTCOME_FAILED]) {
            admission.status = status.outcome == ConnectOutcome.CONNECT_OUTCOME_DECLINED ?
                    DsmAdmission.STATUS_DECLINED : DsmAdmission.STATUS_FAILED
            admission.reason = status.reason
        }
        admission.save(failOnError: true, flush: true)
        return admission
    }

    /**
     * This account's own history row of the credit: from the registry, of exactly the units, carrying exactly the memo
     * DSM builds for this connection and request (`payment_memo`), which the signed transfer's terms commit to. Nothing
     * else counts, so an organization's choice of reference cannot make one request's credit stand for another's.
     */
    private TransactionInfo creditOf(DsmHostClient client, DsmAdmission admission) {
        DsmProductToken token = dsmAccountService.tokenFor(admission.product)
        String registryDevice = registryConnection(admission.account).peerDeviceId
        String paymentMemo = "dsm-connect:${admission.sessionId}:${admission.seq} ${admission.memo}".toString()
        return client.history(HISTORY_ROWS, 0).find { TransactionInfo row ->
            row.txType == TransactionType.TX_TYPE_ONLINE &&
                    row.direction == AmountDirection.AMOUNT_DIRECTION_CREDIT &&
                    row.fromDeviceId.size() == 32 &&
                    Base32Crockford.encode(row.fromDeviceId.toByteArray()) == registryDevice &&
                    row.tokenId == token.ticker &&
                    row.amount == admission.quantity &&
                    row.memo == paymentMemo
        }
    }

    /**
     * Stock is being recorded at `account`: `quantity` units must already be in its custody from an established
     * admission no earlier recording used, and they are drawn from it now. Otherwise the recording is refused,
     * naming what to ask the registry for: units enter custody only through its operators' approval.
     */
    DsmCustodyTransfer drawAdmitted(DsmWarehouseAccount account, Product product, int quantity, String memo) {
        DsmCustodyTransfer recorded = DsmCustodyTransfer.findByKindAndMemoAndProduct(DsmCustodyTransfer.KIND_ADMITTED, memo, product)
        if (recorded) {
            if (recorded.quantity != quantity || recorded.account.id != account.id) {
                throw new DsmCustodyException(
                        "${memo} already recorded ${recorded.quantity} admitted units at ${recorded.account}; it now " +
                                "asks for ${quantity} at ${account}")
            }
            return recorded
        }
        DsmProductToken token = dsmAccountService.tokenFor(product)
        DsmConnection registry = registryConnection(account)
        DsmAdmission.findAllByAccountAndProductAndStatusInList(account, product,
                [DsmAdmission.STATUS_REQUESTED, DsmAdmission.STATUS_AWAITING_APPROVAL]).each { DsmAdmission open ->
            refreshAdmission(open)
        }
        List<DsmAdmission> established = DsmAdmission.findAllByAccountAndProductAndStatus(
                account, product, DsmAdmission.STATUS_ESTABLISHED, [sort: "dateEstablished", order: "asc"])
        int available = (established.sum { DsmAdmission admission -> admission.quantityUndrawn } ?: 0) as int
        if (available < quantity) {
            throw new DsmCustodyException(
                    "${account} has ${available} admitted ${token.ticker} and this records ${quantity}: request an " +
                            "admission of ${quantity - available} from the registry, naming the document for these units")
        }
        int remaining = quantity
        established.each { DsmAdmission admission ->
            int drawn = Math.min(remaining, admission.quantityUndrawn)
            if (drawn > 0) {
                admission.quantityDrawn += drawn
                admission.save(failOnError: true, flush: true)
                remaining -= drawn
            }
        }
        DsmCustodyTransfer draw = new DsmCustodyTransfer(
                kind: DsmCustodyTransfer.KIND_ADMITTED,
                product: product,
                account: account,
                peerDeviceId: registry.peerDeviceId,
                quantity: quantity,
                memo: memo,
                dateEstablished: new Date(),
        )
        draw.save(failOnError: true, flush: true)
        return draw
    }

    private DsmConnection registryConnection(DsmWarehouseAccount account) {
        DsmConnection connection = DsmConnection.findByAccountAndPeerRegistry(account, true)
        if (!connection) {
            throw new DsmCustodyException(
                    "${account} has no connection to a registry: offer it one, and confirm the connection once it approves")
        }
        return connection
    }

    // ------------------------------------------------------------------ the registry's side

    int approvalsRequired() {
        Integer configured = grailsApplication.config.getProperty("openboxes.dsm.registry.approvalsRequired", Integer)
        return configured ?: DEFAULT_APPROVALS_REQUIRED
    }

    /**
     * The release requests waiting on this registry, each with who has approved it so far. The host's own Connect
     * listener takes requests in and carries out an approved one once its relationship has settled, so this only reads.
     */
    List<Map> pendingReleases() {
        DsmWarehouseAccount registry = requireRegistry()
        List<ConnectPendingV1> pending = DsmSteps.reply(
                dsmAccountService.clientFor(registry).connect("connect.pending", Empty.getDefaultInstance(), Route.QUERY),
                ConnectReplyV1.ReplyCase.PENDING, "connect.pending").pending.pendingList
        return pending.collect { ConnectPendingV1 request ->
            String sessionId = Base32Crockford.encode(request.sessionId.toByteArray())
            DsmConnection requester = DsmConnection.findBySessionId(sessionId)
            [
                    sessionId        : sessionId,
                    seq              : request.seq,
                    requester        : request.displayName,
                    requesterDeviceId: requester?.peerDeviceId,
                    summary          : request.summary,
                    approvedOnAccount: request.approved,
                    approvals        : DsmReleaseApproval.findAllBySessionIdAndSeq(sessionId, request.seq)*.approver*.username,
                    approvalsRequired: approvalsRequired(),
            ]
        }
    }

    /**
     * One operator approves a release request. The registry's account is told once the configured number of different
     * operators has, and not before; until then nothing moves.
     *
     * @return the request's state: `decided` says whether the account was told, `line` is what DSM said became of it
     */
    Map approveRelease(String sessionId, long seq, User operator) {
        DsmWarehouseAccount registry = requireRegistry()
        ConnectPendingV1 request = requirePending(registry, sessionId, seq)
        DsmConnection requester = requireKnownRequester(sessionId)
        if (DsmReleaseApproval.findBySessionIdAndSeqAndApprover(sessionId, seq, operator)) {
            throw new DsmCustodyException(
                    "${operator.username} has already approved this release: another operator must approve it")
        }
        new DsmReleaseApproval(sessionId: sessionId, seq: seq, summary: request.summary, approver: operator)
                .save(failOnError: true, flush: true)
        int approvals = DsmReleaseApproval.countBySessionIdAndSeq(sessionId, seq)
        int required = approvalsRequired()
        if (approvals < required) {
            return [sessionId: sessionId, seq: seq, summary: request.summary, approvals: approvals,
                    approvalsRequired: required, decided: false]
        }
        ConnectDecidedV1 decided = respond(registry, sessionId, seq, ConnectDecision.CONNECT_DECISION_APPROVE)
        new DsmReleaseDecision(
                sessionId: sessionId,
                seq: seq,
                requesterDeviceId: requester.peerDeviceId,
                summary: request.summary,
                decision: DsmReleaseDecision.DECISION_APPROVED,
                line: decided.line,
                decidedBy: operator,
        ).save(failOnError: true, flush: true)
        return [sessionId: sessionId, seq: seq, summary: request.summary, approvals: approvals,
                approvalsRequired: required, decided: true, line: decided.line]
    }

    /** One operator's refusal is enough: nothing is released, and the organization's result says so. */
    Map declineRelease(String sessionId, long seq, User operator) {
        DsmWarehouseAccount registry = requireRegistry()
        ConnectPendingV1 request = requirePending(registry, sessionId, seq)
        DsmConnection requester = requireKnownRequester(sessionId)
        ConnectDecidedV1 decided = respond(registry, sessionId, seq, ConnectDecision.CONNECT_DECISION_DECLINE)
        new DsmReleaseDecision(
                sessionId: sessionId,
                seq: seq,
                requesterDeviceId: requester.peerDeviceId,
                summary: request.summary,
                decision: DsmReleaseDecision.DECISION_DECLINED,
                line: decided.line,
                decidedBy: operator,
        ).save(failOnError: true, flush: true)
        return [sessionId: sessionId, seq: seq, summary: request.summary, decided: true, line: decided.line]
    }

    private DsmWarehouseAccount requireRegistry() {
        DsmWarehouseAccount registry = dsmAccountService.registryAccount()
        if (!registry) {
            throw new DsmCustodyException(
                    "this instance holds no registry account: release requests are decided on the registry's own instance")
        }
        return registry
    }

    private ConnectPendingV1 requirePending(DsmWarehouseAccount registry, String sessionId, long seq) {
        byte[] session = Base32Crockford.decodeId32(sessionId, "the connection id")
        List<ConnectPendingV1> pending = DsmSteps.reply(
                dsmAccountService.clientFor(registry).connect("connect.pending", Empty.getDefaultInstance(), Route.QUERY),
                ConnectReplyV1.ReplyCase.PENDING, "connect.pending").pending.pendingList
        ConnectPendingV1 request = pending.find { ConnectPendingV1 p ->
            p.seq == seq && p.sessionId.toByteArray() == session
        }
        if (!request) {
            throw new DsmCustodyException("request ${seq} on connection ${sessionId} is not waiting on this registry")
        }
        return request
    }

    /** A request is decided only on a connection this registry's own operators accepted and recorded. */
    private DsmConnection requireKnownRequester(String sessionId) {
        DsmConnection requester = DsmConnection.findBySessionId(sessionId)
        if (!requester) {
            throw new DsmCustodyException("connection ${sessionId} is not one this registry's operators accepted")
        }
        return requester
    }

    private ConnectDecidedV1 respond(DsmWarehouseAccount registry, String sessionId, long seq, ConnectDecision decision) {
        ConnectRespondRequestV1 respond = ConnectRespondRequestV1.newBuilder()
                .setSessionId(ByteString.copyFrom(Base32Crockford.decodeId32(sessionId, "the connection id")))
                .setSeq(seq)
                .setDecision(decision)
                .build()
        return DsmSteps.reply(dsmAccountService.clientFor(registry).connect("connect.respond", respond, Route.INVOKE),
                ConnectReplyV1.ReplyCase.DECIDED, "connect.respond").decided
    }
}
