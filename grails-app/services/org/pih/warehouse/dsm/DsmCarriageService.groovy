package org.pih.warehouse.dsm

import com.google.protobuf.ByteString
import dsm.types.proto.AmountDirection
import dsm.types.proto.ConnectAppRequestIntentV1
import dsm.types.proto.ConnectPayV1
import dsm.types.proto.ConnectReplyV1
import dsm.types.proto.Envelope
import dsm.types.proto.OnlineTransferResponse
import dsm.types.proto.OnlineTransferSmartRequest
import dsm.types.proto.TransactionInfo
import dsm.types.proto.TransactionType
import grails.gorm.transactions.Transactional
import org.pih.warehouse.dsm.DsmHostClient.Route

/**
 * Custody in a person's hands, between two organizations (docs/plans/2026-11-03-dsm-authority-architecture.md, the
 * offline section). The product units move account to account when a shipment is sent, as before; who physically holds
 * the goods on the way is a second token, the sender's custody token, whose units the sending account pays to the
 * carrier's wallet and the carrier's wallet pays on to the receiving account at the door. Both legs carry the
 * shipment's memo. The receiving organization takes the shipment in only once its own account holds the hand-over.
 *
 * A wallet is credited a token only once it has adopted it (DSM: adoption precedes receipt), so a carrier adds the
 * custody token to their wallet first; a payment made before that lands when they do.
 */
@Transactional
class DsmCarriageService {

    static final String MEMO_UNITS = "openboxes:custody units"

    DsmAccountService dsmAccountService
    DsmWalletService dsmWalletService

    // ------------------------------------------------------------------ the tokens

    /** This organization's own custody token, or null before one is added. */
    DsmCustodyToken ownToken() {
        return DsmCustodyToken.findByCreatorIsNotNull()
    }

    /**
     * This organization's custody token, from the anchor of a token its creator made in their own wallet. The account
     * roots it by fetching the policy itself and re-hashing it to the anchor; nothing is taken from the text pasted.
     */
    DsmCustodyToken addOwnToken(String policyAnchor, DsmWallet creator) {
        if (!creator) {
            throw new DsmCustodyException("a custody token is made in a wallet connected here: name that wallet")
        }
        if (ownToken()) {
            throw new DsmCustodyException("this organization already has a custody token, ${ownToken().ticker}")
        }
        return root(policyAnchor, creator, null)
    }

    /** A partner's custody token: their carriers hand custody over to this organization's account under it. */
    DsmCustodyToken addPartnerToken(String policyAnchor, DsmConnection peer) {
        if (!peer?.peerLocation) {
            throw new DsmCustodyException("a partner's custody token belongs to a connection to a partner's warehouse")
        }
        if (DsmCustodyToken.findByPeer(peer)) {
            throw new DsmCustodyException("${peer} already hands custody over under ${DsmCustodyToken.findByPeer(peer).ticker}")
        }
        return root(policyAnchor, null, peer)
    }

    private DsmCustodyToken root(String policyAnchor, DsmWallet creator, DsmConnection peer) {
        Base32Crockford.decodeId32(policyAnchor, "the token's anchor")
        if (DsmCustodyToken.findByPolicyAnchor(policyAnchor)) {
            throw new DsmCustodyException("${policyAnchor} is already a custody token here")
        }
        if (DsmProductToken.findByPolicyAnchor(policyAnchor) || DsmAccessLevel.findByPolicyAnchor(policyAnchor)) {
            throw new DsmCustodyException("${policyAnchor} stands for a product or a level of access, not for custody")
        }
        String ticker = dsmAccountService.rootAnchorAt(dsmWalletService.homeAccount(), policyAnchor)
        DsmCustodyToken token = new DsmCustodyToken(policyAnchor: policyAnchor, ticker: ticker, creator: creator, peer: peer)
        token.save(failOnError: true, flush: true)
        return token
    }

    /**
     * Ask the own token's creator to release `quantity` custody units to the organization's account: a payment request
     * no grant covers, held on the creator's phone (Apps, Waiting) until they approve it there.
     */
    long requestUnits(long quantity) {
        DsmCustodyToken token = ownToken()
        if (!token) {
            throw new DsmCustodyException("this organization has no custody token yet")
        }
        if (quantity < 1) {
            throw new DsmCustodyException("release at least one custody unit")
        }
        return askToPay(token.creator, token, quantity, MEMO_UNITS)
    }

    /** What the account holds of its own custody token: units it can dispatch with. */
    long spareUnits() {
        DsmCustodyToken token = ownToken()
        return token ? balance(dsmWalletService.homeAccount(), token) : 0L
    }

    private long balance(DsmWarehouseAccount account, DsmCustodyToken token) {
        return dsmAccountService.balanceOf(account, new DsmProductToken(policyAnchor: token.policyAnchor, ticker: token.ticker))
    }

    // ------------------------------------------------------------------ dispatch: the account pays a carrier

    /**
     * Hand a sent shipment to a carrier: the account pays the shipment's units of its own custody token to the
     * carrier's wallet, under the shipment's memo. Once per shipment; a second call with the same carrier returns the
     * record, with another is refused. The account's own history decides whether the payment was already made.
     */
    DsmCarriage dispatch(String memo, DsmWallet carrier) {
        if (!carrier) {
            throw new DsmCustodyException("name the carrier's wallet")
        }
        DsmCustodyToken token = ownToken()
        if (!token) {
            throw new DsmCustodyException("this organization has no custody token: add one before handing goods to a carrier")
        }
        List<DsmCustodyTransfer> sent = DsmCustodyTransfer.findAllByKindAndMemo(DsmCustodyTransfer.KIND_SHIPMENT, memo)
        if (!sent) {
            throw new DsmCustodyException("${memo} moved nothing on DSM: only a sent shipment is handed to a carrier")
        }
        int units = unitsOf(sent)
        DsmWarehouseAccount account = sent[0].account
        DsmCarriage recorded = DsmCarriage.findByKindAndMemo(DsmCarriage.KIND_DISPATCHED, memo)
        if (recorded) {
            if (recorded.walletDeviceId != carrier.deviceId) {
                throw new DsmCustodyException(
                        "${memo} is already with ${recorded.walletDeviceId.take(8)}: custody is handed on by them, not again from here")
            }
            return recorded
        }
        DsmHostClient client = dsmAccountService.clientFor(account)
        TransactionInfo paid = findPayment(client.history(DsmCustodyService.HISTORY_ROWS, 0), account.deviceId,
                carrier.deviceId, token.ticker, units, memo, AmountDirection.AMOUNT_DIRECTION_DEBIT)
        String stateHash
        if (paid) {
            stateHash = Base32Crockford.encode(paid.txHash.toByteArray())
        } else {
            OnlineTransferSmartRequest request = OnlineTransferSmartRequest.newBuilder()
                    .setRecipientDeviceId(ByteString.copyFrom(Base32Crockford.decodeId32(carrier.deviceId, "the carrier's device id")))
                    .setAmount(Integer.toString(units))
                    .setTokenId(token.ticker)
                    .setMemo(memo)
                    .build()
            Envelope envelope
            try {
                envelope = client.invoke("wallet.sendSmart", request)
            } catch (DsmHostException refused) {
                throw new DsmCustodyException(
                        "DSM refused to hand ${units} ${token.ticker} to ${carrier.label} for ${memo}: ${refused.message}", refused)
            }
            OnlineTransferResponse response = DsmHostClient
                    .expect("wallet.sendSmart", envelope, Envelope.PayloadCase.ONLINE_TRANSFER_RESPONSE)
                    .onlineTransferResponse
            if (!response.success) {
                throw new DsmCustodyException(
                        "DSM refused to hand ${units} ${token.ticker} to ${carrier.label} for ${memo}: ${response.message}")
            }
            byte[] hash = response.transactionHash.v.toByteArray()
            if (hash.length != 32) {
                throw new DsmHostException("wallet.sendSmart", "the state hash is ${hash.length} bytes, not 32")
            }
            stateHash = Base32Crockford.encode(hash)
        }
        DsmCarriage carriage = new DsmCarriage(kind: DsmCarriage.KIND_DISPATCHED, account: account, token: token, memo: memo,
                walletDeviceId: carrier.deviceId, units: units, stateHash: stateHash)
        carriage.save(failOnError: true, flush: true)
        return carriage
    }

    /** The units a shipment moved on DSM, over all its products. */
    static int unitsOf(Collection<DsmCustodyTransfer> transfers) {
        return (transfers.sum { DsmCustodyTransfer t -> t.quantity } ?: 0) as int
    }

    // ------------------------------------------------------------------ hand-over: a carrier pays the account

    /**
     * Ask a carrier's wallet to hand a partner's shipment over: a payment request for the shipment's units of the
     * partner's custody token, under the shipment's memo, held on the carrier's phone until they approve it. The
     * carrier's wallet has to be connected to this organization's account.
     */
    long askHandover(DsmConnection peer, String memo, int units, DsmWallet carrier) {
        DsmCustodyToken token = DsmCustodyToken.findByPeer(peer)
        if (!token) {
            throw new DsmCustodyException("${peer} hands no custody over here: add their custody token first")
        }
        if (!carrier) {
            throw new DsmCustodyException("name the carrier's wallet: it has to be connected here to be asked")
        }
        if (units < 1) {
            throw new DsmCustodyException("${memo} has no units to hand over")
        }
        return askToPay(carrier, token, units, memo)
    }

    /**
     * How a partner's shipment stands at this account's door, from the account's own history: the custody units
     * wallets have paid it under the memo, of the partner's custody token. Null when the partner hands no custody over.
     *
     * @return `required`, `units` handed over so far, `by` the device that paid them, `transferId` of the latest credit
     */
    static Map handoverOf(DsmConnection peer, String memo, List<TransactionInfo> rows, Collection<String> ownDevices) {
        DsmCustodyToken token = DsmCustodyToken.findByPeer(peer)
        if (!token) {
            return null
        }
        return handoverIn(rows, token.ticker, memo, ownDevices)
    }

    /** The custody units `rows` credit under `memo` of `ticker` from wallets (devices that are none of `ownDevices`). */
    static Map handoverIn(List<TransactionInfo> rows, String ticker, String memo, Collection<String> ownDevices) {
        long units = 0L
        String by = null
        String transferId = null
        rows.each { TransactionInfo row ->
            if (row.txType != TransactionType.TX_TYPE_ONLINE || row.direction != AmountDirection.AMOUNT_DIRECTION_CREDIT
                    || row.tokenId != ticker || row.memo != memo || row.fromDeviceId.size() != 32) {
                return
            }
            String from = Base32Crockford.encode(row.fromDeviceId.toByteArray())
            if (from in ownDevices) {
                return
            }
            units += row.amount
            by = by ?: from
            transferId = transferId ?: row.id
        }
        return [required: true, units: units, by: by, transferId: transferId]
    }

    /** Record that `account` holds a shipment's hand-over, once; the record is bookkeeping, the credit is the fact. */
    DsmCarriage recordHandover(DsmWarehouseAccount account, DsmConnection peer, String memo, int units, Map handover) {
        DsmCarriage recorded = DsmCarriage.findByKindAndMemo(DsmCarriage.KIND_HANDED_OVER, memo)
        if (recorded) {
            return recorded
        }
        DsmCarriage carriage = new DsmCarriage(kind: DsmCarriage.KIND_HANDED_OVER, account: account,
                token: DsmCustodyToken.findByPeer(peer), memo: memo, walletDeviceId: handover.by as String, units: units,
                transferId: handover.transferId as String)
        carriage.save(failOnError: true, flush: true)
        return carriage
    }

    // ------------------------------------------------------------------ helpers

    private long askToPay(DsmWallet wallet, DsmCustodyToken token, long amount, String memo) {
        ConnectAppRequestIntentV1 intent = ConnectAppRequestIntentV1.newBuilder()
                .setSessionId(ByteString.copyFrom(Base32Crockford.decodeId32(wallet.sessionId, "the session id")))
                .setPay(ConnectPayV1.newBuilder()
                        .setPolicyCommit(ByteString.copyFrom(Base32Crockford.decodeId32(token.policyAnchor, "the anchor")))
                        .setAmount(amount)
                        .setMemo(memo.take(256)))
                .build()
        return DsmSteps.reply(
                dsmAccountService.clientFor(dsmWalletService.homeAccount()).connect("connect.app.request", intent, Route.INVOKE),
                ConnectReplyV1.ReplyCase.REQUEST, "connect.app.request").request.seq
    }

    private static TransactionInfo findPayment(List<TransactionInfo> rows, String fromDevice, String toDevice, String ticker,
                                               int amount, String memo, AmountDirection direction) {
        return rows.find { TransactionInfo row ->
            row.txType == TransactionType.TX_TYPE_ONLINE && row.direction == direction && row.fromDeviceId.size() == 32 &&
                    Base32Crockford.encode(row.fromDeviceId.toByteArray()) == fromDevice &&
                    Base32Crockford.encode(row.toDeviceId.toByteArray()) == toDevice &&
                    row.tokenId == ticker && row.amount == amount && row.memo == memo
        }
    }
}
