package org.pih.warehouse.dsm

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import dsm.types.proto.AmountDirection
import dsm.types.proto.ConnectAppOfferRequestV1
import dsm.types.proto.ConnectAppOfferV1
import dsm.types.proto.ConnectAppRequestIntentV1
import dsm.types.proto.ConnectAppStatusV1
import dsm.types.proto.ConnectApproveRequestV1
import dsm.types.proto.ConnectFact
import dsm.types.proto.ConnectHoldingsV1
import dsm.types.proto.ConnectOutcome
import dsm.types.proto.ConnectPreviewRequestV1
import dsm.types.proto.ConnectPreviewV1
import dsm.types.proto.ConnectReplyV1
import dsm.types.proto.ConnectRequestRefV1
import dsm.types.proto.ConnectScopeKind
import dsm.types.proto.ConnectScopeV1
import dsm.types.proto.ConnectSessionStatus
import dsm.types.proto.ConnectSessionV1
import dsm.types.proto.Envelope
import dsm.types.proto.OnlineTransferResponse
import dsm.types.proto.OnlineTransferSmartRequest
import dsm.types.proto.StorageSyncRequest
import dsm.types.proto.StorageSyncResponse
import dsm.types.proto.TokenBurnRequest
import dsm.types.proto.TokenBurnResponse
import dsm.types.proto.TokenCreateRequest
import dsm.types.proto.TokenCreateResponse
import dsm.types.proto.TransactionInfo
import dsm.types.proto.TransactionType
import dsm.types.proto.VerifiedHoldingV1
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import org.pih.warehouse.core.Constants
import org.pih.warehouse.core.Location
import org.pih.warehouse.dsm.DsmHostClient.Route
import org.pih.warehouse.inventory.Transaction
import org.pih.warehouse.inventory.TransactionCode
import org.pih.warehouse.inventory.TransactionEntry
import org.pih.warehouse.inventory.TransactionIdentifierService
import org.pih.warehouse.product.Product
import org.pih.warehouse.api.PartialReceipt
import org.pih.warehouse.api.PartialReceiptItem
import org.pih.warehouse.receiving.Receipt
import org.pih.warehouse.receiving.ReceiptItem
import org.pih.warehouse.shipping.Shipment
import org.pih.warehouse.shipping.ShipmentItem

/**
 * Custody of inventory units on DSM, for warehouses that have a DSM account of their own
 * (docs/plans/2026-11-01-dsm-custody-integration.md).
 *
 * A product's units are a DSM token of 0 decimals that a registry account (the organization's) committed; a
 * warehouse account's balance of it is the units in that warehouse's custody. Every OpenBoxes inventory
 * transaction at a DSM warehouse is backed by a DSM transition made before it is written:
 * - a shipment or stock transfer between two DSM warehouses is a transfer from the origin's account to the
 *   destination's, and the destination's inbound transaction waits for its own account to hold it accepted;
 * - units that enter custody from outside DSM (a receipt from a supplier, a count that found stock, a credit
 *   adjustment) are a transfer from the token's registry to the warehouse;
 * - units that leave custody (consumption, expiry, damage, a count that found less) are burned at the warehouse.
 * The proof shown to others is a holdings proof the asking account verifies against the holder's root over their
 * DSM Connect session. Business records stay here; what goes to DSM is the token, the quantity and a memo naming
 * the OpenBoxes document, sealed to the two accounts.
 *
 * Nothing a host answers is taken as evidence: a send is recorded from the account's own history, a receipt
 * from the destination's, and holdings from the fact the asking account established (DSM Amendment A11).
 */
@Transactional
class DsmCustodyService {

    static final String MEMO_SHIPMENT = "openboxes:shipment:"
    static final String MEMO_RECEIPT = "openboxes:receipt:"
    static final String MEMO_TRANSACTION = "openboxes:transaction:"

    /** How long one DSM step may take before it is reported as not having happened (the DSM suites' own bound). */
    static final long STEP_TIMEOUT_MS = 240_000L

    static final long POLL_MS = 500L

    /** How many history rows one read of an account's history examines, newest first. */
    static final long HISTORY_ROWS = 1_000L

    /** The longest display name an offer carries (DSM Connect, `MAX_DISPLAY_NAME`). */
    static final int MAX_DISPLAY_NAME = 64

    TransactionIdentifierService transactionIdentifierService
    GrailsApplication grailsApplication

    // ------------------------------------------------------------------ accounts

    DsmWarehouseAccount accountFor(Location location) {
        return location ? DsmWarehouseAccount.findByLocation(location) : null
    }

    DsmHostClient clientFor(DsmWarehouseAccount account) {
        return new DsmHostClient(account.ingressOrigin, new File(account.dataDir))
    }

    /**
     * Give a warehouse its DSM account: the dsm-app-host running from `dataDir` on this machine, reached at
     * `ingressOrigin`. The account's device id is read from the host's own record.
     */
    DsmWarehouseAccount registerAccount(Location location, String ingressOrigin, String dataDir) {
        if (!location?.inventory) {
            throw new DsmCustodyException(
                    "${location?.name} has no inventory: only a warehouse that holds stock has a DSM account")
        }
        DsmWarehouseAccount existing = accountFor(location)
        if (existing) {
            throw new DsmCustodyException("${location.name} already has a DSM account, device ${existing.deviceId}")
        }
        return saveAccount(new DsmWarehouseAccount(location: location), ingressOrigin, dataDir)
    }

    /**
     * A registry account: the organization's account that issues product tokens and holds the supply no warehouse
     * has custody of yet.
     */
    DsmWarehouseAccount registerRegistry(String label, String ingressOrigin, String dataDir) {
        if (!label?.trim()) {
            throw new DsmCustodyException("a registry has a name")
        }
        return saveAccount(new DsmWarehouseAccount(label: label.trim()), ingressOrigin, dataDir)
    }

    /**
     * The directory a registered account's data directory must sit under (`openboxes.dsm.dataRoot`): the host's
     * ingress secret is read from the data directory, so a request may not name an arbitrary path.
     */
    File dataRoot() {
        String configured = grailsApplication.config.getProperty("openboxes.dsm.dataRoot", String)
        if (!configured?.trim()) {
            throw new DsmCustodyException("openboxes.dsm.dataRoot is not configured: no DSM account can be registered")
        }
        return new File(configured.trim()).canonicalFile
    }

    private File confinedDataDir(String dataDir) {
        if (!dataDir?.trim()) {
            throw new DsmCustodyException("an account's data directory is required")
        }
        File root = dataRoot()
        File dir = new File(dataDir.trim()).canonicalFile
        String rootPath = root.path.endsWith(File.separator) ? root.path : root.path + File.separator
        if (dir != root && !dir.path.startsWith(rootPath)) {
            throw new DsmCustodyException(
                    "an account's data directory must be under ${root} (openboxes.dsm.dataRoot); ${dir} is not")
        }
        return dir
    }

    private DsmWarehouseAccount saveAccount(DsmWarehouseAccount account, String ingressOrigin, String dataDir) {
        File confined = confinedDataDir(dataDir)
        if (!ingressOrigin?.trim()) {
            throw new DsmCustodyException("an account's ingress origin is required")
        }
        DsmHostClient client
        try {
            client = new DsmHostClient(ingressOrigin.trim(), confined)
        } catch (IllegalArgumentException refused) {
            throw new DsmCustodyException(refused.message, refused)
        }
        String deviceId = Base32Crockford.encode(client.deviceId())
        DsmWarehouseAccount held = DsmWarehouseAccount.findByDeviceId(deviceId)
        if (held) {
            throw new DsmCustodyException("the account at ${ingressOrigin} is already registered as ${held}")
        }
        account.ingressOrigin = ingressOrigin.trim()
        account.dataDir = confined.path
        account.deviceId = deviceId
        account.save(failOnError: true, flush: true)
        return account
    }

    // ------------------------------------------------------------------ tokens

    /** The ticker a product's token is named by: its product code, as a DSM ticker (2 to 8 of A-Z, 0-9). */
    static String tickerFor(Product product) {
        String code = product.productCode?.trim()?.toUpperCase()
        if (!code) {
            throw new DsmCustodyException("product ${product.name} has no product code to name its DSM token by")
        }
        if (!(code ==~ /[A-Z0-9]{2,8}/)) {
            throw new DsmCustodyException(
                    "product code ${code} is not a DSM ticker: 2 to 8 characters, A-Z and 0-9 only")
        }
        return code
    }

    /**
     * The token that stands for `product`'s units, committed by `issuer`: 0 decimals, `supply` units released to
     * the issuer, transferable and burnable. The whole supply that will ever exist: a registry issues it large
     * enough for every unit the organization will ever take into custody. Nothing about the product but its code
     * (the ticker) goes into the policy.
     */
    DsmProductToken issueProductToken(DsmWarehouseAccount issuer, Product product, long supply) {
        DsmProductToken already = DsmProductToken.findByProduct(product)
        if (already) {
            throw new DsmCustodyException(
                    "product ${product.productCode} already stands for units on DSM as ${already.ticker} " +
                            "(anchor ${already.policyAnchor}), issued by ${already.issuer}")
        }
        if (supply < 1) {
            throw new DsmCustodyException("a token's supply is at least one unit, not ${supply}")
        }
        String ticker = tickerFor(product)
        TokenCreateRequest request = TokenCreateRequest.newBuilder()
                .setTicker(ticker)
                .setAlias(ticker)
                .setDecimals(0)
                .setGenesisSupplyEntered(Long.toString(supply))
                .setBurnEnabled(true)
                .setTransferable(true)
                .setThreshold(1)
                .build()
        Envelope envelope = clientFor(issuer).invoke("token.create", request)
        TokenCreateResponse response = DsmHostClient
                .expect("token.create", envelope, Envelope.PayloadCase.TOKEN_CREATE_RESPONSE)
                .tokenCreateResponse
        if (!response.success) {
            throw new DsmCustodyException("token.create ${ticker}: ${response.message}")
        }
        byte[] anchor = response.policyAnchor.toByteArray()
        if (anchor.length != 32) {
            throw new DsmHostException("token.create", "the policy anchor is ${anchor.length} bytes, not 32")
        }
        DsmProductToken token = new DsmProductToken(
                product: product,
                issuer: issuer,
                policyAnchor: Base32Crockford.encode(anchor),
                ticker: response.ticker ?: ticker,
                supply: supply,
        )
        token.save(failOnError: true, flush: true)
        return token
    }

    DsmProductToken issueProductToken(Location issuer, Product product, long supply) {
        return issueProductToken(requireAccount(issuer, "issue a token"), product, supply)
    }

    /**
     * Two accounts that were never connected are connected before the first move between them: an online send
     * reaches only a contact, and pairing (DSM Amendment A11) is how each side comes to hold the other as a
     * verified contact. Both accounts are this organization's, so the destination's approval is this
     * application's own step.
     */
    void ensureConnected(DsmWarehouseAccount origin, DsmWarehouseAccount destination) {
        if (DsmWarehouseLink.findByOfferingAndAccepting(origin, destination)
                || DsmWarehouseLink.findByOfferingAndAccepting(destination, origin)) {
            return
        }
        pair(origin, destination)
    }

    /**
     * Have `account` root `token`'s anchor: its host fetches the published policy, re-hashes it to the anchor and
     * registers the token (`tokens.addByAnchor`), so a transfer of it can be accepted. Rooting an anchor the
     * account already holds is the idempotent case on the host. Called before every move into an account.
     */
    void ensureRootedAt(DsmWarehouseAccount account, DsmProductToken token) {
        // The route takes the anchor's own text as its arguments, not an arg pack.
        Envelope envelope = clientFor(account).invokeRawArgsQuietly("tokens.addByAnchor", token.policyAnchor.getBytes("UTF-8"))
        if (envelope.payloadCase == Envelope.PayloadCase.ERROR) {
            throw new DsmCustodyException("${account} could not root ${token.ticker}: ${envelope.error.message}")
        }
    }

    // ------------------------------------------------------------------ pairing (DSM Connect)

    /**
     * Connect two accounts (DSM Amendment A11): `offering`'s account makes a signed offer naming every product
     * token's anchor and asking to prove holdings of them; `accepting`'s account reads the code, fetches the
     * offer over TLS pinned by the code, approves it, and posts its signed accept to the offering account's
     * relay. Each side then holds the other as a verified contact and the accepting side has rooted the anchors.
     * The session is recorded once the offering account's own state holds it.
     */
    DsmWarehouseLink pairWarehouses(Location offering, Location accepting) {
        return pair(requireAccount(offering, "offer a connection"), requireAccount(accepting, "accept a connection"))
    }

    DsmWarehouseLink pair(DsmWarehouseAccount offeringAccount, DsmWarehouseAccount acceptingAccount) {
        if (offeringAccount.id == acceptingAccount.id) {
            throw new DsmCustodyException("${offeringAccount} cannot connect to itself")
        }
        DsmWarehouseLink linked = DsmWarehouseLink.findByOfferingAndAccepting(offeringAccount, acceptingAccount)
        if (linked) {
            ConnectSessionV1 held = sessionHeldBy(offeringAccount, linked.sessionId)
            if (held?.status == ConnectSessionStatus.CONNECT_SESSION_STATUS_CONNECTED) {
                return linked
            }
            throw new DsmCustodyException(
                    "${offeringAccount} and ${acceptingAccount} were connected as session ${linked.sessionId}, which " +
                            "${offeringAccount} no longer holds as connected; remove the link to connect again")
        }
        List<DsmProductToken> tokens = DsmProductToken.list()
        if (!tokens) {
            throw new DsmCustodyException(
                    "no product token has been issued yet: connect after issuing, so ${acceptingAccount} roots the " +
                            "anchors when it approves")
        }
        List<ByteString> anchors = tokens.collect { DsmProductToken token ->
            ByteString.copyFrom(Base32Crockford.decodeId32(token.policyAnchor, "the anchor of ${token.ticker}"))
        }
        String displayName = offeringAccount.location ? offeringAccount.location.name : offeringAccount.label
        ConnectAppOfferRequestV1 offerRequest = ConnectAppOfferRequestV1.newBuilder()
                .setDisplayName(displayName.take(MAX_DISPLAY_NAME))
                .addScopes(ConnectScopeV1.newBuilder()
                        .setKind(ConnectScopeKind.CONNECT_SCOPE_KIND_HOLDINGS)
                        .addAllPolicyCommits(anchors))
                .addAllTokenAnchors(anchors)
                .build()
        DsmHostClient offeringClient = clientFor(offeringAccount)
        DsmHostClient acceptingClient = clientFor(acceptingAccount)
        // The host names its own relay endpoint and certificate pin in the offer.
        ConnectAppOfferV1 offer = reply(offeringClient.connect("connect.app.offer", offerRequest, Route.INVOKE),
                ConnectReplyV1.ReplyCase.OFFER, "connect.app.offer").offer
        ConnectPreviewV1 preview = reply(acceptingClient.connect("connect.preview",
                ConnectPreviewRequestV1.newBuilder().setCode(offer.code).build(), Route.QUERY),
                ConnectReplyV1.ReplyCase.PREVIEW, "connect.preview").preview
        String offeredBy = Base32Crockford.encode(preview.appDeviceId.toByteArray())
        if (offeredBy != offeringAccount.deviceId) {
            throw new DsmCustodyException(
                    "the offer at the code names account ${offeredBy}, not ${offeringAccount}'s ${offeringAccount.deviceId}")
        }
        ConnectSessionV1 approved = reply(acceptingClient.connect("connect.approve",
                ConnectApproveRequestV1.newBuilder().setOfferDigest(preview.offerDigest).build(), Route.INVOKE),
                ConnectReplyV1.ReplyCase.SESSION, "connect.approve").session
        String sessionId = Base32Crockford.encode(approved.sessionId.toByteArray())
        ConnectSessionV1 held = until("${offeringAccount} holds the session ${sessionId}") {
            sessionHeldBy(offeringAccount, sessionId)
        }
        String peer = Base32Crockford.encode(held.peerDeviceId.toByteArray())
        if (peer != acceptingAccount.deviceId) {
            throw new DsmCustodyException(
                    "session ${sessionId} at ${offeringAccount} names peer ${peer}, not ${acceptingAccount}'s ${acceptingAccount.deviceId}")
        }
        DsmWarehouseLink link = new DsmWarehouseLink(
                offering: offeringAccount,
                accepting: acceptingAccount,
                sessionId: sessionId,
                offerDigest: Base32Crockford.encode(preview.offerDigest.toByteArray()),
        )
        link.save(failOnError: true, flush: true)
        return link
    }

    // ------------------------------------------------------------------ shipments

    /**
     * Before OpenBoxes records a shipment as sent: the custody transfer of each product's shipped units from the
     * origin's account to the destination's. Nothing happens for a shipment between warehouses that are not on
     * DSM, and nothing yet for a shipment from a warehouse that is not on DSM (its units enter custody when the
     * destination receives them). A shipment from a DSM warehouse to a location without an account is refused:
     * custody cannot leave DSM through a warehouse that has no account.
     *
     * @return the transfers recorded, one per product; empty when the origin is not on DSM
     * @throws DsmCustodyException when DSM refuses, or the shipment cannot be moved on DSM as it stands
     */
    List<DsmCustodyTransfer> releaseOnSend(Shipment shipment) {
        DsmWarehouseAccount origin = accountFor(shipment.origin)
        if (!origin) {
            return []
        }
        DsmWarehouseAccount destination = accountFor(shipment.destination)
        if (!destination) {
            throw new DsmCustodyException(
                    "${shipment.origin?.name} is a DSM warehouse but ${shipment.destination?.name} has no DSM account: " +
                            "custody cannot leave DSM to a warehouse that is not on it")
        }
        String memo = MEMO_SHIPMENT + shipmentName(shipment)
        List<DsmCustodyTransfer> transfers = []
        quantitiesByProduct(shipment).each { Product product, Integer quantity ->
            transfers << moveUnits(origin, destination, tokenFor(product), quantity, memo,
                    DsmCustodyTransfer.KIND_SHIPMENT, shipment)
        }
        return transfers
    }

    /**
     * Before OpenBoxes records a receipt's inbound transaction (the v2 receiving workflow): see
     * {@link #requireEstablishedOnReceive(Shipment, Map, String)}.
     */
    List<DsmCustodyTransfer> requireEstablishedOnReceive(Receipt receipt) {
        Map<Product, Integer> received = [:]
        receipt.receiptItems.each { ReceiptItem item ->
            if ((item.quantityReceived ?: 0) > 0) {
                received[item.product] = (received[item.product] ?: 0) + item.quantityReceived
            }
        }
        return requireEstablishedOnReceive(receipt.shipment, received, receipt.receiptNumber)
    }

    /** The same, for the old receiving workflow's partial receipt. */
    List<DsmCustodyTransfer> requireEstablishedOnReceive(PartialReceipt partialReceipt) {
        Map<Product, Integer> received = [:]
        partialReceipt.partialReceiptItems.each { PartialReceiptItem item ->
            if (item.shouldSave && (item.quantityReceiving ?: 0) > 0) {
                received[item.product] = (received[item.product] ?: 0) + item.quantityReceiving
            }
        }
        return requireEstablishedOnReceive(partialReceipt.shipment, received, partialReceipt.receipt?.receiptNumber)
    }

    /**
     * Before OpenBoxes records a receipt's inbound transaction at a DSM warehouse, per product received:
     * - from a DSM warehouse: the destination's own account must hold the shipment's transfer as accepted (its
     *   inbox taken in, the transfer verified against the origin's stored key and applied), read from the
     *   destination account's history after a sync; never from the origin's word or OpenBoxes' record of the send;
     * - from a location that is not on DSM: the units enter custody now, by a transfer of exactly the received
     *   quantity from the token's registry to the destination, which the destination's account must then hold.
     *
     * @return the transfers established, one per product received; empty when the destination is not on DSM
     * @throws DsmCustodyException when the destination's account does not hold a transfer, or more was received
     *         than DSM moved
     */
    List<DsmCustodyTransfer> requireEstablishedOnReceive(Shipment shipment, Map<Product, Integer> received,
                                                         String receiptNumber) {
        DsmWarehouseAccount destination = accountFor(shipment.destination)
        if (!destination) {
            return []
        }
        DsmWarehouseAccount origin = accountFor(shipment.origin)
        if (!origin) {
            // Custody enters DSM: the token's registry moves the received units to this warehouse.
            String memo = MEMO_RECEIPT + (receiptNumber ?: shipmentName(shipment))
            List<DsmCustodyTransfer> credited = []
            received.each { Product product, Integer quantityReceived ->
                credited << creditFromRegistry(destination, tokenFor(product), quantityReceived, memo)
            }
            return credited
        }
        List<DsmCustodyTransfer> established = []
        received.each { Product product, Integer quantityReceived ->
            DsmProductToken token = tokenFor(product)
            String memo = MEMO_SHIPMENT + shipmentName(shipment)
            DsmCustodyTransfer transfer = DsmCustodyTransfer.findByMemoAndProduct(memo, product)
            if (!transfer) {
                throw new DsmCustodyException(
                        "shipment ${shipmentName(shipment)} was never moved on DSM for ${token.ticker}: " +
                                "${destination} holds no custody of these units to receive")
            }
            requireAcceptedAt(transfer)
            if (quantityReceived > transfer.quantity) {
                throw new DsmCustodyException(
                        "receipt records ${quantityReceived} ${token.ticker} but DSM moved ${transfer.quantity}: " +
                                "${destination} cannot receive units it has no custody of")
            }
            established << transfer
        }
        return established
    }

    // ------------------------------------------------------------------ stock transfers and other transactions

    /**
     * Before OpenBoxes records a stock transfer between two warehouses that are both on DSM: the units move from
     * the origin's account to the destination's, and the destination's account must hold them before the two
     * mirrored transactions are written. Nothing happens for a transfer within one warehouse or between
     * warehouses that are not on DSM; one side on DSM and the other not is refused.
     */
    DsmCustodyTransfer transferBetweenWarehouses(Location from, Location to, Product product, int quantity,
                                                 String transactionNumber) {
        DsmWarehouseAccount origin = accountFor(from)
        DsmWarehouseAccount destination = accountFor(to)
        if (!origin && !destination) {
            return null
        }
        if (!origin || !destination) {
            throw new DsmCustodyException(
                    "a stock transfer between ${from?.name} and ${to?.name} crosses DSM's edge: " +
                            "${origin ? to?.name : from?.name} has no DSM account")
        }
        if (origin.id == destination.id) {
            return null
        }
        if (!transactionNumber?.trim()) {
            throw new DsmCustodyException("a stock transfer needs its transaction number before it moves on DSM")
        }
        DsmCustodyTransfer transfer = moveUnits(origin, destination, tokenFor(product), quantity,
                MEMO_TRANSACTION + transactionNumber.trim(), DsmCustodyTransfer.KIND_STOCK_TRANSFER, null)
        requireAcceptedAt(transfer)
        return transfer
    }

    /**
     * Before OpenBoxes saves an inventory transaction at a DSM warehouse that is not a shipment's or a stock
     * transfer's: units that leave stock (a DEBIT type: consumption, expiry, damage, a debit adjustment; or a
     * negative adjustment) are burned at the warehouse, and units that enter stock (a CREDIT type with a positive
     * quantity: a credit adjustment, a count that found more) are moved from the token's registry to the
     * warehouse. Transactions of a count itself (INVENTORY, PRODUCT_INVENTORY) carry no change; the adjustment
     * written with them does. Nothing happens at a warehouse that is not on DSM.
     *
     * @return what was burned or credited, one record per product with a change
     */
    List<DsmCustodyTransfer> backStockChange(Transaction transaction) {
        DsmWarehouseAccount account = accountFor(transaction.inventory?.warehouse)
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
        List<DsmCustodyTransfer> backed = []
        changes.each { Product product, Integer change ->
            if (change == 0) {
                return
            }
            DsmProductToken token = tokenFor(product)
            if (change < 0) {
                backed << burn(account, token, -change, memo)
            } else {
                backed << creditFromRegistry(account, token, change, memo)
            }
        }
        return backed
    }

    // ------------------------------------------------------------------ the holdings proof

    /**
     * The proof a third party is shown: the destination's holdings of the product's token, proven by the
     * destination's account and verified by the origin's against the destination's validated economic root over
     * their Connect session (DSM Amendment A11). Recorded on the transfer once the origin's account holds the
     * fact; an answer alone establishes nothing.
     */
    DsmCustodyTransfer establishHoldingsProof(DsmCustodyTransfer transfer) {
        if (!transfer.destination) {
            throw new DsmCustodyException("a ${transfer.kind} has no destination whose holdings could be proven")
        }
        DsmWarehouseLink link = DsmWarehouseLink.findByOfferingAndAccepting(transfer.origin, transfer.destination)
        if (!link) {
            throw new DsmCustodyException(
                    "${transfer.origin} has no DSM Connect session to ${transfer.destination}: connect the two " +
                            "before asking for a holdings proof")
        }
        DsmProductToken token = tokenFor(transfer.product)
        ByteString anchor = ByteString.copyFrom(Base32Crockford.decodeId32(token.policyAnchor, "the anchor"))
        ConnectAppRequestIntentV1 intent = ConnectAppRequestIntentV1.newBuilder()
                .setSessionId(ByteString.copyFrom(Base32Crockford.decodeId32(link.sessionId, "the session id")))
                .setHoldings(ConnectHoldingsV1.newBuilder().addPolicyCommits(anchor))
                .build()
        ConnectAppStatusV1 proven = askAndAwait(link, intent,
                "${transfer.destination} proves its ${token.ticker} to ${transfer.origin}") { ConnectAppStatusV1 status ->
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
     * Move `quantity` of `token` from `origin` to `destination` with `memo`, once: the origin account's own
     * history decides whether it already sent this move (a retry after a failed OpenBoxes save), the destination
     * roots the anchor first so it can accept, and the record is written from the account's answer.
     */
    private DsmCustodyTransfer moveUnits(DsmWarehouseAccount origin, DsmWarehouseAccount destination,
                                         DsmProductToken token, int quantity, String memo, String kind,
                                         Shipment shipment) {
        if (quantity < 1) {
            throw new DsmCustodyException("${memo}: nothing to move (${quantity} ${token.ticker})")
        }
        DsmCustodyTransfer recorded = DsmCustodyTransfer.findByMemoAndProduct(memo, token.product)
        if (recorded) {
            if (recorded.quantity != quantity || recorded.origin.id != origin.id || recorded.destination?.id != destination.id) {
                throw new DsmCustodyException(
                        "${memo} already moved ${recorded.quantity} ${token.ticker} from ${recorded.origin} to " +
                                "${recorded.destination} on DSM; it now asks for ${quantity} from ${origin} to " +
                                "${destination}, which DSM has not established")
            }
            return recorded
        }
        DsmHostClient originClient = clientFor(origin)
        // Sent already, but not recorded: the account's own history is the record of what it sent.
        TransactionInfo sent = findTransfer(originClient.history(HISTORY_ROWS, 0), origin, destination, token,
                quantity, memo, AmountDirection.AMOUNT_DIRECTION_DEBIT)
        String originStateHash
        if (sent) {
            originStateHash = Base32Crockford.encode(sent.txHash.toByteArray())
        } else {
            ensureConnected(origin, destination)
            ensureRootedAt(destination, token)
            OnlineTransferSmartRequest request = OnlineTransferSmartRequest.newBuilder()
                    .setRecipientDeviceId(ByteString.copyFrom(
                            Base32Crockford.decodeId32(destination.deviceId, "the destination's device id")))
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
                        "DSM refused to move ${quantity} ${token.ticker} from ${origin} to ${destination} for ${memo}: " +
                                refused.message, refused)
            }
            OnlineTransferResponse response = DsmHostClient
                    .expect("wallet.sendSmart", envelope, Envelope.PayloadCase.ONLINE_TRANSFER_RESPONSE)
                    .onlineTransferResponse
            if (!response.success) {
                throw new DsmCustodyException(
                        "DSM refused to move ${quantity} ${token.ticker} from ${origin} to ${destination} for ${memo}: " +
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
                origin: origin,
                destination: destination,
                quantity: quantity,
                memo: memo,
                originStateHash: originStateHash,
        )
        transfer.save(failOnError: true, flush: true)
        return transfer
    }

    /** Units entering custody: moved from the token's registry to `warehouse`, and held there before this returns. */
    private DsmCustodyTransfer creditFromRegistry(DsmWarehouseAccount warehouse, DsmProductToken token,
                                                  int quantity, String memo) {
        DsmWarehouseAccount registry = token.issuer
        if (!registry.registry) {
            throw new DsmCustodyException(
                    "${token.ticker} was issued by the warehouse ${registry}, not by a registry: units cannot enter " +
                            "custody from outside DSM, because a warehouse's balance is its custody and a policy's " +
                            "supply is fixed at creation; issue the product's token from a registry account")
        }
        if (registry.id == warehouse.id) {
            throw new DsmCustodyException("the registry ${registry} holds no stock to take in")
        }
        DsmCustodyTransfer credit = moveUnits(registry, warehouse, token, quantity, memo,
                DsmCustodyTransfer.KIND_REGISTRY_CREDIT, null)
        requireAcceptedAt(credit)
        return credit
    }

    /** Units leaving custody: burned at `warehouse` under the token's policy (`token.burn`). */
    private DsmCustodyTransfer burn(DsmWarehouseAccount warehouse, DsmProductToken token, int quantity, String memo) {
        DsmCustodyTransfer recorded = DsmCustodyTransfer.findByMemoAndProduct(memo, token.product)
        if (recorded) {
            if (recorded.kind != DsmCustodyTransfer.KIND_BURN || recorded.quantity != quantity
                    || recorded.origin.id != warehouse.id) {
                throw new DsmCustodyException(
                        "${memo} already backed ${recorded.quantity} ${token.ticker} as a ${recorded.kind} at " +
                                "${recorded.origin}; it now asks to burn ${quantity} at ${warehouse}")
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
            envelope = clientFor(warehouse).invoke("token.burn", request)
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
                origin: warehouse,
                quantity: quantity,
                memo: memo,
                dateEstablished: new Date(),
        )
        burned.save(failOnError: true, flush: true)
        return burned
    }

    /**
     * The destination's own account must hold `transfer` as accepted: its inbox taken in now, then its history
     * read for a credit of exactly the units from the origin's device, of the product's token, carrying the memo.
     * The memo travels in the sealed terms the signed operation commits to, so a row that carries it was sent by
     * the origin for this move. Waits up to the step bound: the destination's poller and the storage set are
     * doing the work.
     */
    private void requireAcceptedAt(DsmCustodyTransfer transfer) {
        if (transfer.dateEstablished) {
            return
        }
        DsmProductToken token = tokenFor(transfer.product)
        DsmHostClient destinationClient = clientFor(transfer.destination)
        TransactionInfo accepted = until(
                "${transfer.destination} holds ${transfer.quantity} ${token.ticker} from ${transfer.origin} (${transfer.memo})") {
            syncInbox(destinationClient, transfer.destination)
            findTransfer(destinationClient.history(HISTORY_ROWS, 0), transfer.origin, transfer.destination, token,
                    transfer.quantity, transfer.memo, AmountDirection.AMOUNT_DIRECTION_CREDIT)
        }
        transfer.dateEstablished = new Date()
        transfer.destinationTransferId = accepted.id
        transfer.save(failOnError: true, flush: true)
    }

    // ------------------------------------------------------------------ helpers

    private DsmWarehouseAccount requireAccount(Location location, String what) {
        DsmWarehouseAccount account = accountFor(location)
        if (!account) {
            throw new DsmCustodyException("${location?.name} has no DSM account and cannot ${what}")
        }
        return account
    }

    private static DsmProductToken tokenFor(Product product) {
        DsmProductToken token = DsmProductToken.findByProduct(product)
        if (!token) {
            throw new DsmCustodyException(
                    "product ${product.productCode} (${product.name}) has no DSM token: its units are not on DSM")
        }
        return token
    }

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
     * The online transfer among `rows` that moved `quantity` of `token` from `origin` to `destination` with
     * `memo`, in `direction` as the reading account sees it, or null.
     */
    private static TransactionInfo findTransfer(List<TransactionInfo> rows, DsmWarehouseAccount origin,
                                                DsmWarehouseAccount destination, DsmProductToken token,
                                                int quantity, String memo, AmountDirection direction) {
        return rows.find { TransactionInfo row ->
            row.txType == TransactionType.TX_TYPE_ONLINE &&
                    row.direction == direction &&
                    row.fromDeviceId.size() == 32 &&
                    Base32Crockford.encode(row.fromDeviceId.toByteArray()) == origin.deviceId &&
                    Base32Crockford.encode(row.toDeviceId.toByteArray()) == destination.deviceId &&
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

    private ConnectSessionV1 sessionHeldBy(DsmWarehouseAccount account, String sessionId) {
        List<ConnectSessionV1> sessions = reply(clientFor(account).connect("connect.app.sessions",
                Empty.getDefaultInstance(), Route.QUERY), ConnectReplyV1.ReplyCase.SESSIONS,
                "connect.app.sessions").sessions.sessionsList
        return sessions.find { ConnectSessionV1 s -> Base32Crockford.encode(s.sessionId.toByteArray()) == sessionId }
    }

    /**
     * Ask the accepting side of `link` for `intent` over the session and wait until the offering account's own
     * status of the request satisfies `done`. A declined or failed request is reported with the reason the status
     * carries.
     */
    private ConnectAppStatusV1 askAndAwait(DsmWarehouseLink link, ConnectAppRequestIntentV1 intent, String what,
                                           Closure<Boolean> done) {
        DsmHostClient client = clientFor(link.offering)
        ConnectRequestRefV1 made = reply(client.connect("connect.app.request", intent, Route.INVOKE),
                ConnectReplyV1.ReplyCase.REQUEST, "connect.app.request").request
        ConnectRequestRefV1 ref = ConnectRequestRefV1.newBuilder()
                .setSessionId(made.sessionId)
                .setSeq(made.seq)
                .build()
        return until(what) {
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

    private static ConnectReplyV1 reply(ConnectReplyV1 reply, ConnectReplyV1.ReplyCase wanted, String method) {
        if (reply.replyCase != wanted) {
            throw new DsmHostException(method, "the route answered ${reply.replyCase}, not ${wanted}")
        }
        return reply
    }

    /** `probe`'s first non-null answer within the step bound, or a refusal naming `what` did not happen. */
    private static <T> T until(String what, Closure<T> probe) {
        long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS
        while (true) {
            T found = probe()
            if (found != null) {
                return found
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new DsmCustodyException("${what}: not within ${STEP_TIMEOUT_MS / 1000} s")
            }
            Thread.sleep(POLL_MS)
        }
    }
}
