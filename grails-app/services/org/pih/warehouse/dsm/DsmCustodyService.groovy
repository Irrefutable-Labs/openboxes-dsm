package org.pih.warehouse.dsm

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import dsm.types.proto.AmountDirection
import dsm.types.proto.ConnectAcceptIssuedV1
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
import dsm.types.proto.TokenCreateRequest
import dsm.types.proto.TokenCreateResponse
import dsm.types.proto.TransactionInfo
import dsm.types.proto.TransactionType
import dsm.types.proto.VerifiedHoldingV1
import grails.gorm.transactions.Transactional
import org.pih.warehouse.core.Location
import org.pih.warehouse.dsm.DsmHostClient.Route
import org.pih.warehouse.product.Product
import org.pih.warehouse.receiving.Receipt
import org.pih.warehouse.receiving.ReceiptItem
import org.pih.warehouse.shipping.Shipment
import org.pih.warehouse.shipping.ShipmentItem

/**
 * Custody of inventory units on DSM, for warehouses that have a DSM account of their own
 * (docs/plans/2026-11-01-dsm-custody-integration.md).
 *
 * A product's units are a DSM token of 0 decimals the issuing warehouse's account committed. A shipment from one
 * DSM warehouse to another is a transfer of the shipped units from the origin's account to the destination's; a
 * receipt is the destination's own acceptance of that transfer, read from the destination account's history; and
 * what a third party is shown is a holdings proof the origin's account verifies against the destination's root
 * over their DSM Connect session. OpenBoxes writes its TRANSFER_OUT only once the origin's account has sent, and
 * its TRANSFER_IN only once the destination's account holds the transfer as accepted. Business records (the
 * product, the shipment's contents, the receipt) stay here; what goes to DSM is the token, the quantity and a
 * memo naming the shipment, sealed to the two accounts.
 *
 * Nothing a host answers is taken as evidence: a send is recorded from the account's own history, a receipt from
 * the destination's, and holdings from the fact the origin's account established (DSM Amendment A11).
 */
@Transactional
class DsmCustodyService {

    /** The memo a custody transfer carries, sealed to the two accounts: names the shipment it moves. */
    static final String MEMO_PREFIX = "openboxes:shipment:"

    /** How long one DSM step may take before it is reported as not having happened (the DSM suites' own bound). */
    static final long STEP_TIMEOUT_MS = 240_000L

    static final long POLL_MS = 500L

    /** How many history rows one read of an account's history examines, newest first. */
    static final long HISTORY_ROWS = 1_000L

    /** The longest display name an offer carries (DSM Connect, `MAX_DISPLAY_NAME`). */
    static final int MAX_DISPLAY_NAME = 64

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
        DsmHostClient client = new DsmHostClient(ingressOrigin, new File(dataDir))
        String deviceId = Base32Crockford.encode(client.deviceId())
        DsmWarehouseAccount account = new DsmWarehouseAccount(
                location: location,
                ingressOrigin: ingressOrigin,
                dataDir: dataDir,
                deviceId: deviceId,
        )
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
     * The token that stands for `product`'s units, committed by `issuer`'s account: 0 decimals, `supply` units
     * released to the issuer, transferable and burnable. Nothing about the product but its code (the ticker)
     * goes into the policy.
     */
    DsmProductToken issueProductToken(Location issuer, Product product, long supply) {
        DsmWarehouseAccount account = requireAccount(issuer, "issue a token")
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
        Envelope envelope = clientFor(account).invoke("token.create", request)
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
                issuer: account,
                policyAnchor: Base32Crockford.encode(anchor),
                ticker: response.ticker ?: ticker,
                supply: supply,
        )
        token.save(failOnError: true, flush: true)
        return token
    }

    // ------------------------------------------------------------------ pairing (DSM Connect)

    /**
     * Connect two warehouses' accounts (DSM Amendment A11): `offering`'s account makes a signed offer naming the
     * anchors of the tokens it issued and asking to prove holdings of them and to have later anchors rooted;
     * `accepting`'s account reads the code, fetches the offer over TLS pinned by the code, approves it, and posts
     * its signed accept to the offering account's relay. Each side then holds the other as a verified contact and
     * the accepting side has rooted the anchors, so a transfer of those tokens can be accepted. The session is
     * recorded once the offering account's own state holds it.
     */
    DsmWarehouseLink pairWarehouses(Location offering, Location accepting) {
        DsmWarehouseAccount offeringAccount = requireAccount(offering, "offer a connection")
        DsmWarehouseAccount acceptingAccount = requireAccount(accepting, "accept a connection")
        if (offeringAccount.id == acceptingAccount.id) {
            throw new DsmCustodyException("${offering.name} cannot connect to itself")
        }
        DsmWarehouseLink linked = DsmWarehouseLink.findByOfferingAndAccepting(offeringAccount, acceptingAccount)
        if (linked) {
            ConnectSessionV1 held = sessionHeldBy(offeringAccount, linked.sessionId)
            if (held?.status == ConnectSessionStatus.CONNECT_SESSION_STATUS_CONNECTED) {
                return linked
            }
            throw new DsmCustodyException(
                    "${offering.name} and ${accepting.name} were connected as session ${linked.sessionId}, which " +
                            "${offeringAccount}'s account no longer holds as connected; remove the link to connect again")
        }
        List<DsmProductToken> issued = DsmProductToken.findAllByIssuer(offeringAccount)
        if (!issued) {
            throw new DsmCustodyException(
                    "${offering.name} has issued no token yet: connect after issuing, so ${accepting.name} roots the " +
                            "anchors when it approves")
        }
        List<ByteString> anchors = issued.collect { DsmProductToken token ->
            ByteString.copyFrom(Base32Crockford.decodeId32(token.policyAnchor, "the anchor of ${token.ticker}"))
        }
        ConnectAppOfferRequestV1 offerRequest = ConnectAppOfferRequestV1.newBuilder()
                .setDisplayName(offering.name.take(MAX_DISPLAY_NAME))
                .addScopes(ConnectScopeV1.newBuilder()
                        .setKind(ConnectScopeKind.CONNECT_SCOPE_KIND_ACCEPT_ISSUED))
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
                    "the offer at the code names account ${offeredBy}, not ${offering.name}'s ${offeringAccount.deviceId}")
        }
        ConnectSessionV1 approved = reply(acceptingClient.connect("connect.approve",
                ConnectApproveRequestV1.newBuilder().setOfferDigest(preview.offerDigest).build(), Route.INVOKE),
                ConnectReplyV1.ReplyCase.SESSION, "connect.approve").session
        String sessionId = Base32Crockford.encode(approved.sessionId.toByteArray())
        ConnectSessionV1 held = until("${offering.name}'s account holds the session ${sessionId}") {
            sessionHeldBy(offeringAccount, sessionId)
        }
        String peer = Base32Crockford.encode(held.peerDeviceId.toByteArray())
        if (peer != acceptingAccount.deviceId) {
            throw new DsmCustodyException(
                    "session ${sessionId} at ${offering.name} names peer ${peer}, not ${accepting.name}'s ${acceptingAccount.deviceId}")
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

    /**
     * Have `accepting` root the anchor of a token `offering` issued after they were connected (the
     * ACCEPT_ISSUED scope): the accepting account fetches the policy, re-hashes it to the anchor and checks the
     * creator is the offering account before it roots anything. Waits until the accepting side has carried the
     * request out, or reports why it did not.
     */
    ConnectAppStatusV1 rootTokenAtPeer(DsmProductToken token, DsmWarehouseLink link) {
        if (token.issuer.id != link.offering.id) {
            throw new DsmCustodyException(
                    "${token.ticker} was issued by ${token.issuer}, not by the session's offering account ${link.offering}")
        }
        ConnectAppRequestIntentV1 intent = ConnectAppRequestIntentV1.newBuilder()
                .setSessionId(ByteString.copyFrom(Base32Crockford.decodeId32(link.sessionId, "the session id")))
                .setAcceptIssued(ConnectAcceptIssuedV1.newBuilder()
                        .setAnchor(ByteString.copyFrom(Base32Crockford.decodeId32(token.policyAnchor, "the anchor"))))
                .build()
        return askAndAwait(link, intent, "${link.accepting} roots ${token.ticker}") { ConnectAppStatusV1 status ->
            status.outcome == ConnectOutcome.CONNECT_OUTCOME_CARRIED_OUT
        }
    }

    // ------------------------------------------------------------------ the shipment lifecycle

    /**
     * Before OpenBoxes records a shipment as sent: the custody transfer of each product's shipped units from the
     * origin's account to the destination's. Nothing happens for a shipment between warehouses that are not on
     * DSM; a shipment with one side on DSM and the other not is refused, since custody can neither enter nor
     * leave DSM through a warehouse that has no account. The origin account's own history decides whether the
     * transfer was already sent (a retry after a failed OpenBoxes save), so a shipment is never sent twice.
     *
     * @return the transfers recorded, one per product; empty when neither warehouse is on DSM
     * @throws DsmCustodyException when DSM refuses, or the shipment cannot be moved on DSM as it stands
     */
    List<DsmCustodyTransfer> releaseOnSend(Shipment shipment) {
        Accounts accounts = accountsOf(shipment)
        if (!accounts) {
            return []
        }
        DsmHostClient originClient = clientFor(accounts.origin)
        List<DsmCustodyTransfer> transfers = []
        quantitiesByProduct(shipment).each { Product product, Integer quantity ->
            DsmProductToken token = tokenFor(product)
            String memo = memoFor(shipment)
            DsmCustodyTransfer recorded = DsmCustodyTransfer.findByShipmentAndProduct(shipment, product)
            if (recorded) {
                if (recorded.quantity != quantity) {
                    throw new DsmCustodyException(
                            "shipment ${shipmentName(shipment)} already moved ${recorded.quantity} ${token.ticker} on DSM " +
                                    "(origin state ${recorded.originStateHash}); it now ships ${quantity}, which DSM has not " +
                                    "established")
                }
                transfers << recorded
                return
            }
            // Sent already, but not recorded (the OpenBoxes save after the send failed): the account's own
            // history is the record of what it sent.
            TransactionInfo sent = findTransfer(originClient.history(HISTORY_ROWS, 0),
                    accounts.origin, accounts.destination, token, quantity, memo,
                    AmountDirection.AMOUNT_DIRECTION_DEBIT)
            String originStateHash
            if (sent) {
                originStateHash = Base32Crockford.encode(sent.txHash.toByteArray())
            } else {
                OnlineTransferSmartRequest request = OnlineTransferSmartRequest.newBuilder()
                        .setRecipientDeviceId(ByteString.copyFrom(
                                Base32Crockford.decodeId32(accounts.destination.deviceId, "the destination's device id")))
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
                            "DSM refused to move ${quantity} ${token.ticker} from ${accounts.origin} to " +
                                    "${accounts.destination} for shipment ${shipmentName(shipment)}: ${refused.message}",
                            refused)
                }
                OnlineTransferResponse response = DsmHostClient
                        .expect("wallet.sendSmart", envelope, Envelope.PayloadCase.ONLINE_TRANSFER_RESPONSE)
                        .onlineTransferResponse
                if (!response.success) {
                    throw new DsmCustodyException(
                            "DSM refused to move ${quantity} ${token.ticker} from ${accounts.origin} to " +
                                    "${accounts.destination} for shipment ${shipmentName(shipment)}: ${response.message}")
                }
                byte[] stateHash = response.transactionHash.v.toByteArray()
                if (stateHash.length != 32) {
                    throw new DsmHostException("wallet.sendSmart", "the state hash is ${stateHash.length} bytes, not 32")
                }
                originStateHash = Base32Crockford.encode(stateHash)
            }
            DsmCustodyTransfer transfer = new DsmCustodyTransfer(
                    shipment: shipment,
                    product: product,
                    origin: accounts.origin,
                    destination: accounts.destination,
                    quantity: quantity,
                    memo: memo,
                    originStateHash: originStateHash,
            )
            transfer.save(failOnError: true, flush: true)
            transfers << transfer
        }
        return transfers
    }

    /**
     * Before OpenBoxes records a receipt's inbound transaction: the destination's own account must hold each
     * received product's transfer from the origin as accepted (its inbox taken in, the transfer verified against
     * the origin's stored key and applied). Read from the destination account's history after a sync; never from
     * the origin's word or OpenBoxes' own record of the send.
     *
     * @return the transfers established, one per product received; empty when neither warehouse is on DSM
     * @throws DsmCustodyException when the destination's account does not hold a transfer yet
     */
    List<DsmCustodyTransfer> requireEstablishedOnReceive(Receipt receipt) {
        Shipment shipment = receipt.shipment
        Accounts accounts = accountsOf(shipment)
        if (!accounts) {
            return []
        }
        Map<Product, Integer> received = [:]
        receipt.receiptItems.each { ReceiptItem item ->
            if ((item.quantityReceived ?: 0) > 0) {
                received[item.product] = (received[item.product] ?: 0) + item.quantityReceived
            }
        }
        List<DsmCustodyTransfer> established = []
        DsmHostClient destinationClient = clientFor(accounts.destination)
        boolean synced = false
        received.each { Product product, Integer quantityReceived ->
            DsmProductToken token = tokenFor(product)
            DsmCustodyTransfer transfer = DsmCustodyTransfer.findByShipmentAndProduct(shipment, product)
            if (!transfer) {
                throw new DsmCustodyException(
                        "shipment ${shipmentName(shipment)} was never moved on DSM for ${token.ticker}: " +
                                "${accounts.destination} holds no custody of these units to receive")
            }
            if (!transfer.dateEstablished) {
                if (!synced) {
                    syncInbox(destinationClient, accounts.destination)
                    synced = true
                }
                TransactionInfo accepted = findTransfer(destinationClient.history(HISTORY_ROWS, 0),
                        accounts.origin, accounts.destination, token, transfer.quantity, transfer.memo,
                        AmountDirection.AMOUNT_DIRECTION_CREDIT)
                if (!accepted) {
                    throw new DsmCustodyException(
                            "DSM has not established custody of shipment ${shipmentName(shipment)} at " +
                                    "${accounts.destination} yet: its account holds no accepted transfer of " +
                                    "${transfer.quantity} ${token.ticker} from ${accounts.origin} with memo " +
                                    "\"${transfer.memo}\"; complete the receipt once the account has taken it in")
                }
                transfer.dateEstablished = new Date()
                transfer.destinationTransferId = accepted.id
                transfer.save(failOnError: true, flush: true)
            }
            if (quantityReceived > transfer.quantity) {
                throw new DsmCustodyException(
                        "receipt records ${quantityReceived} ${token.ticker} but DSM moved ${transfer.quantity}: " +
                                "${accounts.destination} cannot receive units it has no custody of")
            }
            established << transfer
        }
        return established
    }

    /**
     * The proof a third party is shown: the destination's holdings of the product's token, proven by the
     * destination's account and verified by the origin's against the destination's validated economic root over
     * their Connect session (DSM Amendment A11). Recorded on the transfer once the origin's account holds the
     * fact; an answer alone establishes nothing.
     */
    DsmCustodyTransfer establishHoldingsProof(DsmCustodyTransfer transfer) {
        DsmWarehouseLink link = DsmWarehouseLink.findByOfferingAndAccepting(transfer.origin, transfer.destination)
        if (!link) {
            throw new DsmCustodyException(
                    "${transfer.origin} has no DSM Connect session to ${transfer.destination}: connect the two " +
                            "warehouses before asking for a holdings proof")
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

    // ------------------------------------------------------------------ helpers

    /** The two accounts a shipment moves custody between, or null when neither warehouse is on DSM. */
    private static class Accounts {
        DsmWarehouseAccount origin
        DsmWarehouseAccount destination
    }

    private Accounts accountsOf(Shipment shipment) {
        DsmWarehouseAccount origin = accountFor(shipment.origin)
        DsmWarehouseAccount destination = accountFor(shipment.destination)
        if (!origin && !destination) {
            return null
        }
        if (!origin) {
            throw new DsmCustodyException(
                    "${shipment.destination?.name} is a DSM warehouse but ${shipment.origin?.name} has no DSM account: " +
                            "custody cannot enter DSM from a warehouse that is not on it (issuance from outside DSM " +
                            "is not built)")
        }
        if (!destination) {
            throw new DsmCustodyException(
                    "${shipment.origin?.name} is a DSM warehouse but ${shipment.destination?.name} has no DSM account: " +
                            "custody cannot leave DSM to a warehouse that is not on it")
        }
        return new Accounts(origin: origin, destination: destination)
    }

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
        return MEMO_PREFIX + shipmentName(shipment)
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
     * `memo`, in `direction` as the reading account sees it, or null. The memo is read from the sealed terms the
     * signed operation commits to, so a row that carries it was sent by the origin for this shipment.
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
     * status of the request satisfies `done`. A declined or failed request, or one that waits for approval on the
     * accepting side, is reported with the reason the status carries.
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
