package org.pih.warehouse.dsm

import com.google.protobuf.ByteString
import dsm.types.proto.AmountDirection
import dsm.types.proto.ConnectAppRequestIntentV1
import dsm.types.proto.ConnectAppStatusV1
import dsm.types.proto.ConnectFact
import dsm.types.proto.ConnectHoldingsV1
import dsm.types.proto.ConnectOutcome
import dsm.types.proto.ConnectReplyV1
import dsm.types.proto.ConnectRequestRefV1
import dsm.types.proto.Envelope
import dsm.types.proto.OnlineTransferResponse
import dsm.types.proto.OnlineTransferSmartRequest
import dsm.types.proto.StorageSyncRequest
import dsm.types.proto.StorageSyncResponse
import dsm.types.proto.TokenBurnRequest
import dsm.types.proto.TokenBurnResponse
import dsm.types.proto.TransactionInfo
import dsm.types.proto.TransactionType
import dsm.types.proto.VerifiedHoldingV1
import grails.gorm.transactions.Transactional
import org.pih.warehouse.api.PartialReceipt
import org.pih.warehouse.api.PartialReceiptItem
import org.pih.warehouse.auth.AuthService
import org.pih.warehouse.core.Constants
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User
import org.pih.warehouse.dsm.DsmHostClient.Route
import org.pih.warehouse.inventory.Transaction
import org.pih.warehouse.inventory.TransactionCode
import org.pih.warehouse.inventory.TransactionEntry
import org.pih.warehouse.inventory.TransactionIdentifierService
import org.pih.warehouse.product.Product
import org.pih.warehouse.receiving.Receipt
import org.pih.warehouse.receiving.ReceiptItem
import org.pih.warehouse.shipping.Shipment
import org.pih.warehouse.shipping.ShipmentIdentifierService
import org.pih.warehouse.shipping.ShipmentItem
import org.pih.warehouse.shipping.ShipmentType

/**
 * Custody of inventory units on DSM, for this organization's own warehouses
 * (docs/plans/2026-11-01-dsm-custody-integration.md).
 *
 * A product's units are a DSM token of 0 decimals a registry committed; a warehouse account's balance of it is the
 * units in that warehouse's custody. Every OpenBoxes inventory transaction at a DSM warehouse is backed by a DSM
 * transition made before it is written:
 * - a shipment is a transfer from the origin's account to the destination's device. The destination may be another
 *   organization: this instance then knows it only as a device id on a Connect session, and what happens at the
 *   other end is that organization's own business, on its own instance;
 * - units a peer sent are taken in here from this account's own history, as a shipment record of this instance's
 *   own, and received through the ordinary receiving workflow;
 * - a stock transfer moves units between two accounts of this organization;
 * - units that enter custody from outside DSM (a receipt from a supplier, a count that found stock, a credit
 *   adjustment) are drawn from an admission the registry's operators approved (DsmAdmissionService);
 * - units that leave custody (consumption, expiry, damage, a count that found less) are burned at the warehouse.
 * The proof shown to others is a holdings proof the asking account verifies against the holder's root over their
 * DSM Connect session. Business records stay on each organization's own instance; what goes to DSM is the token, the
 * quantity and a memo naming the document, sealed to the two accounts.
 *
 * Nothing a host answers is taken as evidence: a send is recorded from the sending account's own history, a receipt
 * from the receiving account's, and holdings from the fact the asking account established (DSM Amendment A11).
 */
@Transactional
class DsmCustodyService {

    static final String MEMO_SHIPMENT = "openboxes:shipment:"
    static final String MEMO_RECEIPT = "openboxes:receipt:"
    static final String MEMO_TRANSACTION = "openboxes:transaction:"

    /** How many history rows one read of an account's history examines, newest first. */
    static final long HISTORY_ROWS = 1_000L

    DsmAccountService dsmAccountService
    DsmAdmissionService dsmAdmissionService
    DsmCarriageService dsmCarriageService
    TransactionIdentifierService transactionIdentifierService
    ShipmentIdentifierService shipmentIdentifierService
    AuthService authService

    // Resolved at call time: these services call into this one.
    def shipmentService
    def inventoryService

    // ------------------------------------------------------------------ shipments going out

    /**
     * Before OpenBoxes records a shipment as sent: the custody transfer of each product's shipped units from the
     * origin's account to the destination's device. Nothing happens for a shipment from a warehouse that is not on
     * this instance's DSM accounts. A shipment from a DSM warehouse to a location this account has no connection to
     * is refused: custody cannot leave DSM through a warehouse that is not on it.
     *
     * @return the transfers recorded, one per product; empty when the origin is not on DSM
     * @throws DsmCustodyException when DSM refuses, or the shipment cannot be moved on DSM as it stands
     */
    List<DsmCustodyTransfer> releaseOnSend(Shipment shipment, Date shipDate) {
        DsmWarehouseAccount origin = dsmAccountService.accountFor(shipment.origin)
        if (!origin) {
            return []
        }
        DsmStockWrites.grant("send ${shipmentName(shipment)}")
        refuseBehindBaseline(shipment.origin, quantitiesByProduct(shipment).keySet(), shipDate)
        DsmConnection connection = dsmAccountService.connectionTo(origin, shipment.destination)
        if (!connection) {
            throw new DsmCustodyException(
                    "${shipment.origin?.name} is a DSM warehouse but ${shipment.destination?.name} has no DSM account " +
                            "it is connected to: custody cannot leave DSM to a warehouse that is not on it")
        }
        String memo = memoFor(shipment)
        List<DsmCustodyTransfer> transfers = []
        quantitiesByProduct(shipment).each { Product product, Integer quantity ->
            transfers << moveUnits(origin, connection, dsmAccountService.tokenFor(product), quantity, memo,
                    DsmCustodyTransfer.KIND_SHIPMENT, shipment)
        }
        return transfers
    }

    /**
     * OpenBoxes counts stock back from its latest baseline, so a transaction dated before one never counts: DSM would move
     * the units while the books went on showing them. This is refused before anything moves.
     */
    private void refuseBehindBaseline(Location location, Collection<Product> products, Date date) {
        if (!products || !dsmAccountService.accountFor(location)) {
            return
        }
        if (!date) {
            throw new DsmCustodyException("a stock record at ${location.name} needs its date before DSM moves anything")
        }
        products.each { Product product ->
            Date baseline = Transaction.executeQuery(
                    """select max(t.transactionDate) from TransactionEntry e join e.transaction t
                       where t.inventory = :inventory and e.inventoryItem.product = :product
                       and t.transactionType.transactionCode in (:codes)""",
                    [inventory: location.inventory, product: product,
                     codes: [TransactionCode.PRODUCT_INVENTORY, TransactionCode.INVENTORY]])[0] as Date
            if (baseline && date.before(baseline)) {
                throw new DsmCustodyException(
                        "${product.productCode} at ${location.name} was counted on ${baseline}, and this is dated ${date}: " +
                                "a record dated before the count would never reach the books while DSM moved the units. Date it now.")
            }
        }
    }

    /** Nothing DSM established is reversed: this refuses a rollback of a document DSM moved units for. */
    void refuseRollback(Shipment shipment) {
        if (shipment && DsmCustodyTransfer.findByShipment(shipment)) {
            throw new DsmCustodyException(
                    "shipment ${shipmentName(shipment)} moved custody on DSM, and that is never undone: " +
                            "correct it forward, with a return shipment that names this one")
        }
    }

    // ------------------------------------------------------------------ custody coming in from a peer

    /**
     * What peers have sent this organization's accounts that it has not yet made a shipment record of: each account's
     * inbox taken in, then its own history read. A row counts only if it comes from a connected peer's warehouse and
     * is of a token this organization adopted. Where the peer's shipments are carried (DsmCustodyToken), each document
     * also says how it stands at the door: `carriage.required`, and the custody units `handedOver` so far, `by` whom.
     */
    List<Map> inboundCustody() {
        List<Map> inbound = []
        List<String> ownDevices = DsmWarehouseAccount.list()*.deviceId
        DsmWarehouseAccount.findAllByLocationIsNotNull().each { DsmWarehouseAccount account ->
            DsmHostClient client = dsmAccountService.clientFor(account)
            syncInbox(client, account)
            Map<List, Map> byDocument = [:]
            Map<List, DsmConnection> peerOf = [:]
            List<TransactionInfo> rows = client.history(HISTORY_ROWS, 0)
            rows.each { TransactionInfo row ->
                if (row.txType != TransactionType.TX_TYPE_ONLINE || row.direction != AmountDirection.AMOUNT_DIRECTION_CREDIT
                        || !row.memo.startsWith(MEMO_SHIPMENT) || row.fromDeviceId.size() != 32) {
                    return
                }
                String from = Base32Crockford.encode(row.fromDeviceId.toByteArray())
                DsmConnection peer = DsmConnection.findByAccountAndPeerDeviceId(account, from)
                DsmProductToken token = DsmProductToken.findByTicker(row.tokenId)
                if (!peer?.peerLocation || !token || DsmWarehouseAccount.findByDeviceId(from)) {
                    return
                }
                if (DsmCustodyTransfer.findByKindAndMemoAndProductAndPeerDeviceId(
                        DsmCustodyTransfer.KIND_INBOUND, row.memo, token.product, from)) {
                    return
                }
                Map document = byDocument.computeIfAbsent([from, row.memo]) {
                    peerOf[[from, row.memo]] = peer
                    [memo: row.memo, peerDeviceId: from, originLocationId: peer.peerLocation.id,
                     originName: peer.peerLocation.name, accountId: account.id,
                     destinationLocationId: account.location.id, lines: []]
                }
                document.lines << [productId: token.product.id, productCode: token.product.productCode,
                                   ticker: token.ticker, quantity: row.amount, transferId: row.id]
            }
            byDocument.each { List key, Map document ->
                document.units = (document.lines.sum { Map line -> line.quantity as long } ?: 0L) as long
                Map handover = DsmCarriageService.handoverOf(peerOf[key], document.memo as String, rows, ownDevices)
                document.carriage = handover
                        ? [required: true, handedOver: handover.units, by: (handover.by as String)?.take(8),
                           arrived: (handover.units as long) >= (document.units as long)]
                        : [required: false]
            }
            inbound.addAll(byDocument.values())
        }
        return inbound
    }

    /**
     * Make this organization's own shipment record of what a peer sent under `memo`: origin is this instance's
     * Location for the peer, destination is the account's warehouse, the lines are what the account's own history
     * holds. It is shipped from here on, and received through the ordinary receiving workflow.
     */
    Shipment establishInbound(String peerDeviceId, String memo) {
        Map document = inboundCustody().find { Map it -> it.memo == memo && it.peerDeviceId == peerDeviceId }
        if (!document) {
            throw new DsmCustodyException(
                    "no account here holds units from ${peerDeviceId} under ${memo} that have not been taken in")
        }
        DsmWarehouseAccount account = DsmWarehouseAccount.get(document.accountId)
        Location origin = Location.get(document.originLocationId)
        Map carriage = document.carriage as Map
        if (carriage.required && !carriage.arrived) {
            throw new DsmCustodyException(
                    "${memo.substring(MEMO_SHIPMENT.length())} is on its way from ${origin.name}: ${account} has been handed " +
                            "${carriage.handedOver} of its ${document.units} units. The carrier hands them over on their phone " +
                            "when they arrive; until then nothing is taken in.")
        }
        User user = authService.currentUser
        Shipment shipment = new Shipment(
                name: "${origin.name}: ${memo.substring(MEMO_SHIPMENT.length())}".toString(),
                origin: origin,
                destination: account.location,
                shipmentType: ShipmentType.get(Constants.DEFAULT_SHIPMENT_TYPE_ID),
                expectedShippingDate: new Date(),
                createdBy: user,
                updatedBy: user,
        )
        shipment.shipmentNumber = shipmentIdentifierService.generate(shipment)
        document.lines.each { Map line ->
            Product product = Product.get(line.productId)
            // The default lot: a line is received as whatever lots the receiving screen records it under.
            shipment.addToShipmentItems(new ShipmentItem(product: product, quantity: line.quantity as Integer,
                    inventoryItem: inventoryService.findOrCreateDefaultInventoryItem(product)))
        }
        shipment.save(failOnError: true, flush: true)
        document.lines.each { Map line ->
            new DsmCustodyTransfer(
                    kind: DsmCustodyTransfer.KIND_INBOUND,
                    shipment: shipment,
                    product: Product.get(line.productId),
                    account: account,
                    peerDeviceId: peerDeviceId,
                    quantity: line.quantity as Integer,
                    memo: memo,
                    dateEstablished: new Date(),
                    destinationTransferId: line.transferId,
            ).save(failOnError: true, flush: true)
        }
        if (carriage.required) {
            dsmCarriageService.recordHandover(account, DsmConnection.findByAccountAndPeerDeviceId(account, peerDeviceId), memo,
                    document.units as int, DsmCarriageService.handoverOf(DsmConnection.findByAccountAndPeerDeviceId(account, peerDeviceId),
                    memo, dsmAccountService.clientFor(account).history(HISTORY_ROWS, 0), DsmWarehouseAccount.list()*.deviceId))
        }
        // The peer's own books debited it; here it is only marked on its way, so it can be received.
        shipmentService.sendShipment(shipment, null, user, account.location, new Date(), false)
        return shipment
    }

    // ------------------------------------------------------------------ receipts

    /**
     * Before OpenBoxes records a receipt's inbound transaction (the v2 receiving workflow): see
     * {@link #requireEstablishedOnReceive(Shipment, Map, String)}.
     */
    List<DsmCustodyTransfer> requireEstablishedOnReceive(Receipt receipt) {
        DsmStockWrites.grant("receive ${receipt.receiptNumber}")
        Map<Product, Integer> received = [:]
        receipt.receiptItems.each { ReceiptItem item ->
            if ((item.quantityReceived ?: 0) > 0) {
                received[item.product] = (received[item.product] ?: 0) + item.quantityReceived
            }
        }
        refuseBehindBaseline(receipt.shipment?.destination, received.keySet(), receipt.actualDeliveryDate)
        return requireEstablishedOnReceive(receipt.shipment, received, receipt.receiptNumber)
    }

    /** The same, for the old receiving workflow's partial receipt. */
    List<DsmCustodyTransfer> requireEstablishedOnReceive(PartialReceipt partialReceipt) {
        DsmStockWrites.grant("receive a partial receipt")
        Map<Product, Integer> received = [:]
        partialReceipt.partialReceiptItems.each { PartialReceiptItem item ->
            if (item.shouldSave && (item.quantityReceiving ?: 0) > 0) {
                received[item.product] = (received[item.product] ?: 0) + item.quantityReceiving
            }
        }
        refuseBehindBaseline(partialReceipt.shipment?.destination, received.keySet(), partialReceipt.dateDelivered)
        return requireEstablishedOnReceive(partialReceipt.shipment, received, partialReceipt.receipt?.receiptNumber)
    }

    /**
     * Before OpenBoxes records a receipt's inbound transaction at a DSM warehouse, per product received:
     * - from a warehouse of this organization: the destination's own account must hold the shipment's transfer as
     *   accepted, read from its own history after a sync;
     * - from another organization's warehouse: the shipment must be this instance's record of custody the account
     *   already holds (see {@link #establishInbound});
     * - from a location that is not on DSM: the units must already have been admitted to this account, and are drawn
     *   from the admission now.
     * Receipts of one shipment add up: together they may not exceed what DSM moved.
     *
     * @return the transitions established, one per product received; empty when the destination is not on DSM
     * @throws DsmCustodyException when the account does not hold the units, or more was received than DSM moved
     */
    List<DsmCustodyTransfer> requireEstablishedOnReceive(Shipment shipment, Map<Product, Integer> received,
                                                         String receiptNumber) {
        DsmWarehouseAccount destination = dsmAccountService.accountFor(shipment.destination)
        if (!destination) {
            return []
        }
        DsmWarehouseAccount originAccount = dsmAccountService.accountFor(shipment.origin)
        DsmConnection peer = dsmAccountService.connectionTo(destination, shipment.origin)
        if (!originAccount && !peer) {
            String memo = MEMO_RECEIPT + (receiptNumber ?: shipmentName(shipment))
            return received.collect { Product product, Integer quantityReceived ->
                dsmAdmissionService.drawAdmitted(destination, product, quantityReceived, memo)
            }
        }
        return received.collect { Product product, Integer quantityReceived ->
            DsmCustodyTransfer inbound = DsmCustodyTransfer.findByKindAndShipmentAndProduct(
                    DsmCustodyTransfer.KIND_INBOUND, shipment, product)
            if (!inbound && originAccount) {
                inbound = acceptedFromOwnWarehouse(shipment, product, originAccount, destination)
            }
            if (!inbound) {
                throw new DsmCustodyException(
                        "shipment ${shipmentName(shipment)} is not custody ${destination} holds for ${product.productCode}: " +
                                "${destination} cannot receive units it has no custody of")
            }
            int total = inbound.quantityReceived + quantityReceived
            if (total > inbound.quantity) {
                throw new DsmCustodyException(
                        "receipts record ${total} ${product.productCode} but DSM moved ${inbound.quantity}: " +
                                "${destination} cannot receive units it has no custody of")
            }
            inbound.quantityReceived = total
            inbound.save(failOnError: true, flush: true)
            return inbound
        }
    }

    /** A shipment between two of this organization's warehouses: the destination's own history must hold what was sent. */
    private DsmCustodyTransfer acceptedFromOwnWarehouse(Shipment shipment, Product product,
                                                        DsmWarehouseAccount origin, DsmWarehouseAccount destination) {
        String memo = memoFor(shipment)
        DsmCustodyTransfer sent = DsmCustodyTransfer.findByKindAndMemoAndProductAndPeerDeviceId(
                DsmCustodyTransfer.KIND_SHIPMENT, memo, product, destination.deviceId)
        if (!sent) {
            return null
        }
        TransactionInfo accepted = awaitAccepted(destination, origin.deviceId, dsmAccountService.tokenFor(product),
                sent.quantity, memo)
        DsmCustodyTransfer inbound = new DsmCustodyTransfer(
                kind: DsmCustodyTransfer.KIND_INBOUND,
                shipment: shipment,
                product: product,
                account: destination,
                peerDeviceId: origin.deviceId,
                quantity: sent.quantity,
                memo: memo,
                dateEstablished: new Date(),
                destinationTransferId: accepted.id,
        )
        inbound.save(failOnError: true, flush: true)
        return inbound
    }

    // ------------------------------------------------------------------ stock transfers and other transactions

    /**
     * Before OpenBoxes records a stock transfer between two warehouses that are both on DSM: the units move from
     * the origin's account to the destination's, and the destination's account must hold them before the two
     * mirrored transactions are written. A stock transfer writes both warehouses' inventory in this database, so both
     * must be this organization's; to another organization's warehouse it is a shipment. Nothing happens for a
     * transfer within one warehouse or between warehouses that are not on DSM; one side on DSM and the other not is
     * refused.
     */
    DsmCustodyTransfer transferBetweenWarehouses(Location from, Location to, Product product, int quantity,
                                                 String transactionNumber) {
        DsmStockWrites.grant("a stock transfer")
        DsmWarehouseAccount origin = dsmAccountService.accountFor(from)
        DsmWarehouseAccount destination = dsmAccountService.accountFor(to)
        if (!origin && !destination) {
            return null
        }
        if (!origin || !destination) {
            throw new DsmCustodyException(
                    "a stock transfer between ${from?.name} and ${to?.name} crosses DSM's edge: " +
                            "${origin ? to?.name : from?.name} has no DSM account on this instance; to another " +
                            "organization's warehouse, send a shipment")
        }
        if (origin.id == destination.id) {
            return null
        }
        if (!transactionNumber?.trim()) {
            throw new DsmCustodyException("a stock transfer needs its transaction number before it moves on DSM")
        }
        DsmConnection connection = DsmConnection.findByAccountAndPeerDeviceId(origin, destination.deviceId)
        if (!connection) {
            throw new DsmCustodyException(
                    "${origin} has no connection to ${destination}: connect the two before moving units between them")
        }
        DsmProductToken token = dsmAccountService.tokenFor(product)
        DsmCustodyTransfer transfer = moveUnits(origin, connection, token, quantity,
                MEMO_TRANSACTION + transactionNumber.trim(), DsmCustodyTransfer.KIND_STOCK_TRANSFER, null)
        if (!transfer.dateEstablished) {
            TransactionInfo accepted = awaitAccepted(destination, origin.deviceId, token, quantity, transfer.memo)
            transfer.dateEstablished = new Date()
            transfer.destinationTransferId = accepted.id
            transfer.save(failOnError: true, flush: true)
        }
        return transfer
    }

    /**
     * Before OpenBoxes saves an inventory transaction at a DSM warehouse that is not a shipment's or a stock
     * transfer's: units that leave stock (a DEBIT type: consumption, expiry, damage, a debit adjustment; or a
     * negative adjustment) are burned at the warehouse, and units that enter stock (a CREDIT type with a positive
     * quantity: a credit adjustment, a count that found more) must already have been admitted to it. Transactions of
     * a count itself (INVENTORY, PRODUCT_INVENTORY) carry no change; the adjustment written with them does. Nothing
     * happens at a warehouse that is not on DSM.
     *
     * @return what was burned or drawn, one record per product with a change
     */
    List<DsmCustodyTransfer> backStockChange(Transaction transaction) {
        DsmWarehouseAccount account = dsmAccountService.accountFor(transaction.inventory?.warehouse)
        if (!account) {
            return []
        }
        String typeId = transaction.transactionType?.id
        if (typeId in [Constants.TRANSFER_IN_TRANSACTION_TYPE_ID, Constants.TRANSFER_OUT_TRANSACTION_TYPE_ID]) {
            // Backed by the shipment, receipt or stock transfer it belongs to.
            return []
        }
        TransactionCode code = transaction.transactionType?.transactionCode
        if (code != TransactionCode.DEBIT && code != TransactionCode.CREDIT) {
            return []
        }
        if (!transaction.transactionNumber?.trim()) {
            transaction.transactionNumber = transactionIdentifierService.generate(transaction)
        }
        String memo = MEMO_TRANSACTION + transaction.transactionNumber.trim()
        Map<Product, Integer> changes = [:]
        transaction.transactionEntries.each { TransactionEntry entry ->
            Product product = entry.inventoryItem?.product ?: entry.product
            int signed = code == TransactionCode.DEBIT ? -(entry.quantity ?: 0) : (entry.quantity ?: 0)
            changes[product] = (changes[product] ?: 0) + signed
        }
        refuseBehindBaseline(transaction.inventory?.warehouse, changes.keySet(), transaction.transactionDate)
        DsmStockWrites.grant("a stock change")
        List<DsmCustodyTransfer> backed = []
        changes.each { Product product, Integer change ->
            if (change < 0) {
                backed << burn(account, dsmAccountService.tokenFor(product), -change, memo)
            } else if (change > 0) {
                backed << dsmAdmissionService.drawAdmitted(account, product, change, memo)
            }
        }
        return backed
    }

    // ------------------------------------------------------------------ the holdings proof

    /**
     * The proof a third party is shown: the peer's holdings of the product's token, proven by the peer's account and
     * verified by this one against the peer's validated economic root over their Connect session (DSM Amendment
     * A11). Recorded on the transfer once this account holds the fact; an answer alone establishes nothing. This
     * account must be the one that made the offer: the session's application side is the side that may ask.
     */
    DsmCustodyTransfer establishHoldingsProof(DsmCustodyTransfer transfer) {
        if (!transfer.peerDeviceId) {
            throw new DsmCustodyException("a ${transfer.kind} has no peer whose holdings could be proven")
        }
        DsmConnection connection = DsmConnection.findByAccountAndPeerDeviceId(transfer.account, transfer.peerDeviceId)
        if (connection?.side != DsmConnection.SIDE_OFFERED) {
            throw new DsmCustodyException(
                    "${transfer.account} made no offer to ${transfer.peerDeviceId}: only the side that offered a " +
                            "session may ask over it")
        }
        DsmProductToken token = dsmAccountService.tokenFor(transfer.product)
        ByteString anchor = ByteString.copyFrom(Base32Crockford.decodeId32(token.policyAnchor, "the anchor"))
        ConnectAppRequestIntentV1 intent = ConnectAppRequestIntentV1.newBuilder()
                .setSessionId(ByteString.copyFrom(Base32Crockford.decodeId32(connection.sessionId, "the session id")))
                .setHoldings(ConnectHoldingsV1.newBuilder().addPolicyCommits(anchor))
                .build()
        ConnectAppStatusV1 proven = askAndAwait(transfer.account, intent,
                "${connection} proves its ${token.ticker} to ${transfer.account}") { ConnectAppStatusV1 status ->
            status.fact == ConnectFact.CONNECT_FACT_HOLDINGS
        }
        VerifiedHoldingV1 holding = proven.holdingsList.find { VerifiedHoldingV1 h -> h.policyCommit == anchor }
        if (!holding) {
            throw new DsmHostException("connect.app.status",
                    "the holdings fact names no balance of ${token.ticker} (${proven.holdingsList.size()} holdings)")
        }
        transfer.provenAmount = holding.amount
        transfer.provenPosition = proven.holdingsPosition
        transfer.save(failOnError: true, flush: true)
        return transfer
    }

    // ------------------------------------------------------------------ the moves

    /**
     * Move `quantity` of `token` from `origin` to the peer of `connection` with `memo`, once: the origin account's own
     * history decides whether it already sent this move (a retry after a failed OpenBoxes save), and the record is
     * written from the account's answer.
     */
    private DsmCustodyTransfer moveUnits(DsmWarehouseAccount origin, DsmConnection connection,
                                         DsmProductToken token, int quantity, String memo, String kind,
                                         Shipment shipment) {
        if (quantity < 1) {
            throw new DsmCustodyException("${memo}: nothing to move (${quantity} ${token.ticker})")
        }
        String peer = connection.peerDeviceId
        DsmCustodyTransfer recorded = DsmCustodyTransfer.findByKindAndMemoAndProductAndPeerDeviceId(kind, memo, token.product, peer)
        if (recorded) {
            if (recorded.quantity != quantity || recorded.account.id != origin.id) {
                throw new DsmCustodyException(
                        "${memo} already moved ${recorded.quantity} ${token.ticker} from ${recorded.account} to " +
                                "${connection} on DSM; it now asks for ${quantity} from ${origin}, which DSM has not established")
            }
            return recorded
        }
        DsmHostClient originClient = dsmAccountService.clientFor(origin)
        // Sent already, but not recorded: the account's own history is the record of what it sent.
        TransactionInfo sent = findTransfer(originClient.history(HISTORY_ROWS, 0), origin.deviceId, peer, token,
                quantity, memo, AmountDirection.AMOUNT_DIRECTION_DEBIT)
        String originStateHash
        if (sent) {
            originStateHash = Base32Crockford.encode(sent.txHash.toByteArray())
        } else {
            OnlineTransferSmartRequest request = OnlineTransferSmartRequest.newBuilder()
                    .setRecipientDeviceId(ByteString.copyFrom(Base32Crockford.decodeId32(peer, "the peer's device id")))
                    .setAmount(Integer.toString(quantity))
                    .setTokenId(token.ticker)
                    .setMemo(memo)
                    .build()
            Envelope envelope
            try {
                envelope = originClient.invoke("wallet.sendSmart", request)
            } catch (DsmHostException refused) {
                // The account refused the send (an overspend, a recipient it cannot reach): DSM's own reason.
                throw new DsmCustodyException(
                        "DSM refused to move ${quantity} ${token.ticker} from ${origin} to ${connection} for ${memo}: " +
                                refused.message, refused)
            }
            OnlineTransferResponse response = DsmHostClient
                    .expect("wallet.sendSmart", envelope, Envelope.PayloadCase.ONLINE_TRANSFER_RESPONSE)
                    .onlineTransferResponse
            if (!response.success) {
                throw new DsmCustodyException(
                        "DSM refused to move ${quantity} ${token.ticker} from ${origin} to ${connection} for ${memo}: " +
                                response.message)
            }
            byte[] stateHash = response.transactionHash.v.toByteArray()
            if (stateHash.length != 32) {
                throw new DsmHostException("wallet.sendSmart", "the state hash is ${stateHash.length} bytes, not 32")
            }
            originStateHash = Base32Crockford.encode(stateHash)
        }
        DsmCustodyTransfer transfer = new DsmCustodyTransfer(
                kind: kind,
                shipment: shipment,
                product: token.product,
                account: origin,
                peerDeviceId: peer,
                quantity: quantity,
                memo: memo,
                originStateHash: originStateHash,
        )
        transfer.save(failOnError: true, flush: true)
        return transfer
    }

    /** Units leaving custody: burned at `warehouse` under the token's policy (`token.burn`). */
    private DsmCustodyTransfer burn(DsmWarehouseAccount warehouse, DsmProductToken token, int quantity, String memo) {
        DsmCustodyTransfer recorded = DsmCustodyTransfer.findByKindAndMemoAndProduct(DsmCustodyTransfer.KIND_BURN, memo, token.product)
        if (recorded) {
            if (recorded.quantity != quantity || recorded.account.id != warehouse.id) {
                throw new DsmCustodyException(
                        "${memo} already burned ${recorded.quantity} ${token.ticker} at ${recorded.account}; it now " +
                                "asks to burn ${quantity} at ${warehouse}")
            }
            return recorded
        }
        TokenBurnRequest request = TokenBurnRequest.newBuilder()
                .setTokenId(token.ticker)
                .setAmountEntered(Integer.toString(quantity))
                .setMessage(memo)
                .build()
        Envelope envelope
        try {
            envelope = dsmAccountService.clientFor(warehouse).invoke("token.burn", request)
        } catch (DsmHostException refused) {
            throw new DsmCustodyException(
                    "DSM refused to burn ${quantity} ${token.ticker} at ${warehouse} for ${memo}: ${refused.message}",
                    refused)
        }
        TokenBurnResponse response = DsmHostClient
                .expect("token.burn", envelope, Envelope.PayloadCase.TOKEN_BURN_RESPONSE)
                .tokenBurnResponse
        if (!response.success) {
            throw new DsmCustodyException(
                    "DSM refused to burn ${quantity} ${token.ticker} at ${warehouse} for ${memo}: ${response.message}")
        }
        DsmCustodyTransfer burned = new DsmCustodyTransfer(
                kind: DsmCustodyTransfer.KIND_BURN,
                product: token.product,
                account: warehouse,
                quantity: quantity,
                memo: memo,
                dateEstablished: new Date(),
        )
        burned.save(failOnError: true, flush: true)
        return burned
    }

    /**
     * `account`'s own history must hold the credit: its inbox taken in now, then a row of exactly the units from
     * `fromDevice`, of the product's token, carrying the memo. The memo travels in the sealed terms the signed
     * operation commits to, so a row that carries it was sent for this move. Waits up to the step bound.
     */
    private TransactionInfo awaitAccepted(DsmWarehouseAccount account, String fromDevice, DsmProductToken token,
                                          int quantity, String memo) {
        DsmHostClient client = dsmAccountService.clientFor(account)
        return DsmSteps.until("${account} holds ${quantity} ${token.ticker} from ${fromDevice} (${memo})") {
            syncInbox(client, account)
            findTransfer(client.history(HISTORY_ROWS, 0), fromDevice, account.deviceId, token, quantity, memo,
                    AmountDirection.AMOUNT_DIRECTION_CREDIT)
        }
    }

    // ------------------------------------------------------------------ helpers

    static String memoFor(Shipment shipment) {
        return MEMO_SHIPMENT + shipmentName(shipment)
    }

    private static String shipmentName(Shipment shipment) {
        return shipment.shipmentNumber ?: shipment.id
    }

    private static Map<Product, Integer> quantitiesByProduct(Shipment shipment) {
        Map<Product, Integer> quantities = [:]
        shipment.shipmentItems.each { ShipmentItem item ->
            if (item.quantity > 0) {
                quantities[item.product] = (quantities[item.product] ?: 0) + item.quantity
            }
        }
        if (!quantities) {
            throw new DsmCustodyException("shipment ${shipmentName(shipment)} ships nothing")
        }
        return quantities
    }

    /**
     * The online transfer among `rows` that moved `quantity` of `token` between the two devices with `memo`, in
     * `direction` as the reading account sees it, or null.
     */
    private static TransactionInfo findTransfer(List<TransactionInfo> rows, String fromDevice, String toDevice,
                                                DsmProductToken token, int quantity, String memo,
                                                AmountDirection direction) {
        return rows.find { TransactionInfo row ->
            row.txType == TransactionType.TX_TYPE_ONLINE &&
                    row.direction == direction &&
                    row.fromDeviceId.size() == 32 &&
                    Base32Crockford.encode(row.fromDeviceId.toByteArray()) == fromDevice &&
                    Base32Crockford.encode(row.toDeviceId.toByteArray()) == toDevice &&
                    row.tokenId == token.ticker &&
                    row.amount == quantity &&
                    row.memo == memo
        }
    }

    /** Take in what the storage nodes hold for the account: the inbox poller's own sync, run now. */
    private static void syncInbox(DsmHostClient client, DsmWarehouseAccount account) {
        StorageSyncRequest request = StorageSyncRequest.newBuilder()
                .setPullInbox(true)
                .setPushPending(true)
                .setLimit(50)
                .build()
        // An invoke on this DSM revision (it changes the account's state); run quietly, as a poll.
        Envelope envelope = client.invokeQuietly("storage.sync", request)
        StorageSyncResponse response = DsmHostClient
                .expect("storage.sync", envelope, Envelope.PayloadCase.STORAGE_SYNC_RESPONSE)
                .storageSyncResponse
        if (!response.success) {
            throw new DsmCustodyException(
                    "${account}'s account could not take its inbox in: ${response.errorsList.join('; ')}")
        }
    }

    /**
     * Ask the peer of `asking`'s session for `intent` and wait until this account's own status of the request
     * satisfies `done`. A declined or failed request is reported with the reason the status carries.
     */
    private ConnectAppStatusV1 askAndAwait(DsmWarehouseAccount asking, ConnectAppRequestIntentV1 intent, String what,
                                           Closure<Boolean> done) {
        DsmHostClient client = dsmAccountService.clientFor(asking)
        ConnectRequestRefV1 made = DsmSteps.reply(client.connect("connect.app.request", intent, Route.INVOKE),
                ConnectReplyV1.ReplyCase.REQUEST, "connect.app.request").request
        ConnectRequestRefV1 ref = ConnectRequestRefV1.newBuilder()
                .setSessionId(made.sessionId)
                .setSeq(made.seq)
                .build()
        return DsmSteps.until(what) {
            Envelope envelope = client.invokeQuietly("connect.app.status", ref)
            ConnectAppStatusV1 status = DsmHostClient
                    .expect("connect.app.status", envelope, Envelope.PayloadCase.CONNECT_REPLY)
                    .connectReply.status
            if (done(status)) {
                return status
            }
            if (status.answered && status.outcome in [ConnectOutcome.CONNECT_OUTCOME_DECLINED,
                                                       ConnectOutcome.CONNECT_OUTCOME_FAILED]) {
                throw new DsmCustodyException("${what}: request ${made.seq} ${status.outcome}: ${status.reason}")
            }
            return null
        }
    }
}
