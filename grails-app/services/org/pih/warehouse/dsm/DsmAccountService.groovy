package org.pih.warehouse.dsm

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import dsm.types.proto.BalanceGetResponse
import dsm.types.proto.BalancesListResponse
import dsm.types.proto.ConnectAppOfferRequestV1
import dsm.types.proto.ConnectAppOfferV1
import dsm.types.proto.ConnectApproveRequestV1
import dsm.types.proto.ConnectPreviewRequestV1
import dsm.types.proto.ConnectPreviewV1
import dsm.types.proto.ConnectReplyV1
import dsm.types.proto.ConnectScopeKind
import dsm.types.proto.ConnectScopeV1
import dsm.types.proto.ConnectSessionStatus
import dsm.types.proto.ConnectSessionV1
import dsm.types.proto.Envelope
import dsm.types.proto.FaucetClaimRequest
import dsm.types.proto.TokenCreateRequest
import dsm.types.proto.TokenCreateResponse
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import org.pih.warehouse.core.Location
import org.pih.warehouse.dsm.DsmHostClient.Route
import org.pih.warehouse.product.Product

/**
 * What an organization's administrator sets up on its own instance: its own DSM accounts, the tokens it issues
 * (a registry) or adopts (a warehouse organization), and its own side of each Connect session. Every call here acts
 * on one account of this instance. No call reaches another organization's host: what crosses between organizations
 * is an offer code, handed over out of band, and DSM's own Connect session.
 */
@Transactional
class DsmAccountService {

    /**
     * The supply a registry issues unless told otherwise: large enough that no product runs out of capacity to admit,
     * and well inside what a DSM balance (a u64) carries. It is a ceiling on what can ever be admitted, not stock.
     */
    static final long DEFAULT_SUPPLY = 1_000_000_000_000_000L

    /** The longest display name an offer carries (DSM Connect, `MAX_DISPLAY_NAME`). */
    static final int MAX_DISPLAY_NAME = 64

    GrailsApplication grailsApplication

    // ------------------------------------------------------------------ accounts

    DsmWarehouseAccount accountFor(Location location) {
        return location ? DsmWarehouseAccount.findByLocation(location) : null
    }

    DsmHostClient clientFor(DsmWarehouseAccount account) {
        // A row must not be able to send this server to another host.
        String origin = grailsApplication.config.getProperty("openboxes.dsm.host.origin", String)
        String dataDir = grailsApplication.config.getProperty("openboxes.dsm.host.dataDir", String)
        if (origin && (account.ingressOrigin != origin || account.dataDir != dataDir)) {
            throw new DsmCustodyException("this account's record names a host other than the one this server was started with")
        }
        return new DsmHostClient(account.ingressOrigin, new File(account.dataDir))
    }

    /** This instance's registry account, or null: an instance that custodies stock has none. */
    DsmWarehouseAccount registryAccount() {
        return DsmWarehouseAccount.findByLocationIsNull()
    }

    /**
     * Give a warehouse its DSM account: the dsm-app-host running from `dataDir` on this machine, reached at
     * `ingressOrigin`. The account's device id is read from the host's own record. It roots every token this
     * instance has adopted, so it can take in what it is sent.
     */
    DsmWarehouseAccount registerAccount(Location location, String ingressOrigin, String dataDir) {
        if (registryAccount()) {
            throw new DsmCustodyException(
                    "this instance holds the registry account: release authority never shares a server with custody")
        }
        if (!location?.inventory) {
            throw new DsmCustodyException(
                    "${location?.name} has no inventory: only a warehouse that holds stock has a DSM account")
        }
        DsmWarehouseAccount existing = accountFor(location)
        if (existing) {
            throw new DsmCustodyException("${location.name} already has a DSM account, device ${existing.deviceId}")
        }
        DsmWarehouseAccount account = saveAccount(new DsmWarehouseAccount(location: location), ingressOrigin, dataDir)
        DsmProductToken.list().each { DsmProductToken token -> rootAt(account, token) }
        return account
    }

    /**
     * The registry's account: it commits product tokens and holds the supply no warehouse has custody of yet. An
     * instance has one, and holds no warehouse account beside it, so release authority never shares a server with
     * custody.
     */
    DsmWarehouseAccount registerRegistry(String label, String ingressOrigin, String dataDir) {
        if (!label?.trim()) {
            throw new DsmCustodyException("a registry has a name")
        }
        if (DsmWarehouseAccount.count() > 0) {
            throw new DsmCustodyException(
                    "this instance already holds a DSM account: a registry's instance holds that account alone, so " +
                            "release authority never shares a server with custody")
        }
        return saveAccount(new DsmWarehouseAccount(label: label.trim()), ingressOrigin, dataDir)
    }

    /**
     * ERA from the network faucet, for the fees this account's own transitions cost: a new host holds none, and cannot
     * issue a token until it does. A claim whose admission is still held is finished by the next claim.
     */
    DsmWarehouseAccount claimNetworkFees(DsmWarehouseAccount account) {
        FaucetClaimRequest request = FaucetClaimRequest.newBuilder()
                .setDeviceId(ByteString.copyFrom(Base32Crockford.decodeId32(account.deviceId, "the device id")))
                .build()
        DsmHostClient client = clientFor(account)
        DsmSteps.until("${account} claims its network fees") {
            Envelope envelope = client.invoke("faucet.claim", request)
            DsmHostClient.expect("faucet.claim", envelope, Envelope.PayloadCase.FAUCET_CLAIM_RESPONSE)
                    .faucetClaimResponse.success ? Boolean.TRUE : null
        }
        return account
    }

    /**
     * The directory a registered account's data directory must sit under (`openboxes.dsm.dataRoot`): the host's
     * ingress secret is read from the data directory, so a request may not name an arbitrary path, and an instance
     * can reach no host outside its own root.
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
     * The token that stands for `product`'s units, committed by this instance's registry: 0 decimals, the whole
     * `supply` released to the registry, transferable and burnable. Only a registry issues: a warehouse's balance
     * is its custody, so a warehouse never holds units that stand for nothing. The product's code (the ticker) is
     * all of the product that goes into the policy.
     */
    DsmProductToken issueProductToken(Product product, long supply = DEFAULT_SUPPLY) {
        DsmWarehouseAccount registry = registryAccount()
        if (!registry) {
            throw new DsmCustodyException(
                    "this instance holds no registry account: a warehouse organization adopts a token from its " +
                            "anchor, and only a registry issues one")
        }
        DsmProductToken already = DsmProductToken.findByProduct(product)
        if (already) {
            throw new DsmCustodyException(
                    "product ${product.productCode} already stands for units on DSM as ${already.ticker} " +
                            "(anchor ${already.policyAnchor})")
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
        Envelope envelope = clientFor(registry).invoke("token.create", request)
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
                issuerDeviceId: registry.deviceId,
                policyAnchor: Base32Crockford.encode(anchor),
                ticker: response.ticker ?: ticker,
                supply: supply,
        )
        token.save(failOnError: true, flush: true)
        return token
    }

    /**
     * Map a token to a product of this organization's own catalog and root it on each of its own accounts: the host
     * fetches the published policy itself and re-hashes it to the anchor, so nothing about the token is taken from
     * whoever handed the anchor over. A warehouse takes the token on before it can ever be sent it.
     */
    DsmProductToken adoptToken(Product product, String policyAnchor) {
        if (registryAccount()) {
            throw new DsmCustodyException("this instance is the registry: it issues tokens, it does not adopt them")
        }
        Base32Crockford.decodeId32(policyAnchor, "the policy anchor")
        DsmProductToken mapped = DsmProductToken.findByProduct(product) ?: DsmProductToken.findByPolicyAnchor(policyAnchor)
        if (mapped) {
            throw new DsmCustodyException(
                    "${mapped.ticker} (anchor ${mapped.policyAnchor}) already stands for product ${mapped.product.productCode}")
        }
        List<DsmWarehouseAccount> accounts = DsmWarehouseAccount.list()
        if (!accounts) {
            throw new DsmCustodyException("this instance holds no DSM account to adopt a token on")
        }
        DsmProductToken token = null
        accounts.each { DsmWarehouseAccount account ->
            String ticker = rootAnchorAt(account, policyAnchor)
            token = token ?: new DsmProductToken(product: product, policyAnchor: policyAnchor, ticker: ticker)
        }
        token.save(failOnError: true, flush: true)
        return token
    }

    private void rootAt(DsmWarehouseAccount account, DsmProductToken token) {
        rootAnchorAt(account, token.policyAnchor)
    }

    /** Have `account` root the anchor (`tokens.addByAnchor`, idempotent on the host); the policy's ticker. */
    private String rootAnchorAt(DsmWarehouseAccount account, String policyAnchor) {
        // The route takes the anchor's own text as its arguments, not an arg pack.
        Envelope envelope = clientFor(account).invokeRawArgsQuietly("tokens.addByAnchor", policyAnchor.getBytes("UTF-8"))
        if (envelope.payloadCase == Envelope.PayloadCase.ERROR) {
            throw new DsmCustodyException("${account} could not root ${policyAnchor}: ${envelope.error.message}")
        }
        TokenCreateResponse response = DsmHostClient
                .expect("tokens.addByAnchor", envelope, Envelope.PayloadCase.TOKEN_CREATE_RESPONSE)
                .tokenCreateResponse
        if (!response.success) {
            throw new DsmCustodyException("${account} could not root ${policyAnchor}: ${response.message}")
        }
        return response.ticker
    }

    DsmProductToken tokenFor(Product product) {
        DsmProductToken token = DsmProductToken.findByProduct(product)
        if (!token) {
            throw new DsmCustodyException(
                    "product ${product.productCode} (${product.name}) has no DSM token: its units are not on DSM")
        }
        return token
    }

    /** What `account` holds spendable of `token`, by the anchor its own balance list names; 0 for no row. */
    long balanceOf(DsmWarehouseAccount account, DsmProductToken token) {
        Envelope envelope = clientFor(account).query("balance.list", new byte[0])
        BalancesListResponse list = DsmHostClient
                .expect("balance.list", envelope, Envelope.PayloadCase.BALANCES_LIST_RESPONSE)
                .balancesListResponse
        BalanceGetResponse row = list.balancesList.find { BalanceGetResponse b -> b.policyAnchorB32 == token.policyAnchor }
        return row == null ? 0L : row.available
    }

    // ------------------------------------------------------------------ this side of a Connect session

    /**
     * This account makes a signed offer and returns its code, for whoever runs the other side. The offer asks for
     * nothing that spends: a warehouse's offer to another warehouse asks to prove holdings of the tokens this
     * instance knows; an offer to a registry asks for nothing at all, so everything it later asks of the registry
     * waits for the registry's operators.
     */
    ConnectAppOfferV1 offerConnection(DsmWarehouseAccount account, boolean toRegistry) {
        ConnectAppOfferRequestV1.Builder offer = ConnectAppOfferRequestV1.newBuilder()
                .setDisplayName(displayNameOf(account))
        if (!toRegistry) {
            List<ByteString> anchors = DsmProductToken.list().collect { DsmProductToken token ->
                ByteString.copyFrom(Base32Crockford.decodeId32(token.policyAnchor, "the anchor of ${token.ticker}"))
            }
            offer.addScopes(ConnectScopeV1.newBuilder()
                    .setKind(ConnectScopeKind.CONNECT_SCOPE_KIND_HOLDINGS)
                    .addAllPolicyCommits(anchors))
        }
        // The host names its own relay endpoint and certificate pin in the offer.
        return DsmSteps.reply(clientFor(account).connect("connect.app.offer", offer.build(), Route.INVOKE),
                ConnectReplyV1.ReplyCase.OFFER, "connect.app.offer").offer
    }

    private static String displayNameOf(DsmWarehouseAccount account) {
        return (account.location ? account.location.name : account.label).take(MAX_DISPLAY_NAME)
    }

    /**
     * This account reads a code it was handed, checks what the offer asks for, and approves it. A signed offer that
     * asks for a spending scope is refused outright: a grant would let the other side spend without this
     * organization's operators, and at a registry it would be release authority given away.
     *
     * @param peerLocation this instance's Location for the offering warehouse; null at a registry
     */
    DsmConnection acceptConnection(DsmWarehouseAccount account, String code, Location peerLocation) {
        if (!code?.trim()) {
            throw new DsmCustodyException("an offer code is required")
        }
        if (!account.registry && !peerLocation) {
            throw new DsmCustodyException("a warehouse names the Location that stands for the warehouse it connects to")
        }
        DsmHostClient client = clientFor(account)
        ConnectPreviewV1 preview = DsmSteps.reply(client.connect("connect.preview",
                ConnectPreviewRequestV1.newBuilder().setCode(code.trim()).build(), Route.QUERY),
                ConnectReplyV1.ReplyCase.PREVIEW, "connect.preview").preview
        List<ConnectScopeKind> asked = preview.scopesList*.kind
        List<ConnectScopeKind> refused = asked.findAll { ConnectScopeKind kind ->
            kind != ConnectScopeKind.CONNECT_SCOPE_KIND_HOLDINGS
        }
        if (refused || (account.registry && asked)) {
            throw new DsmCustodyException(
                    "the offer from ${preview.displayName} asks for ${asked}: ${account} grants " +
                            "${account.registry ? "nothing, so every release waits for its operators" : "holdings proofs and nothing that spends"}")
        }
        String peerDeviceId = Base32Crockford.encode(preview.appDeviceId.toByteArray())
        if (peerDeviceId == account.deviceId) {
            throw new DsmCustodyException("${account} cannot connect to itself")
        }
        ConnectSessionV1 approved = DsmSteps.reply(client.connect("connect.approve",
                ConnectApproveRequestV1.newBuilder().setOfferDigest(preview.offerDigest).build(), Route.INVOKE),
                ConnectReplyV1.ReplyCase.SESSION, "connect.approve").session
        return recordConnection(account, peerDeviceId, peerLocation, false, preview.displayName,
                DsmConnection.SIDE_ACCEPTED, Base32Crockford.encode(approved.sessionId.toByteArray()),
                Base32Crockford.encode(preview.offerDigest.toByteArray()))
    }

    /**
     * The offering side records the session its own account now holds for the offer: the peer's device id is read
     * from that session. Waits up to the step bound for the accept to reach this account's relay.
     *
     * @param peerLocation this instance's Location for the peer's warehouse; null when the peer is the registry
     */
    DsmConnection confirmConnection(DsmWarehouseAccount account, String offerDigest, Location peerLocation,
                                    boolean peerRegistry) {
        if (!peerRegistry && !peerLocation) {
            throw new DsmCustodyException("name the Location that stands for the peer, or that the peer is the registry")
        }
        byte[] digest = Base32Crockford.decodeId32(offerDigest, "the offer digest")
        ConnectSessionV1 held = DsmSteps.until("${account} holds a session for offer ${offerDigest}") {
            sessionsHeldBy(account).find { ConnectSessionV1 session ->
                session.offerDigest.toByteArray() == digest &&
                        session.status == ConnectSessionStatus.CONNECT_SESSION_STATUS_CONNECTED
            }
        }
        return recordConnection(account, Base32Crockford.encode(held.peerDeviceId.toByteArray()), peerLocation,
                peerRegistry, peerRegistry ? "registry" : peerLocation.name, DsmConnection.SIDE_OFFERED,
                Base32Crockford.encode(held.sessionId.toByteArray()), offerDigest)
    }

    /** The row for this account and peer, written or moved to the newer session (a re-offer naming new tokens). */
    private DsmConnection recordConnection(DsmWarehouseAccount account, String peerDeviceId, Location peerLocation,
                                           boolean peerRegistry, String peerName, String side, String sessionId,
                                           String offerDigest) {
        DsmConnection connection = DsmConnection.findByAccountAndPeerDeviceId(account, peerDeviceId) ?:
                new DsmConnection(account: account, peerDeviceId: peerDeviceId)
        connection.peerLocation = peerLocation
        connection.peerRegistry = peerRegistry
        connection.peerName = peerName
        connection.side = side
        connection.sessionId = sessionId
        connection.offerDigest = offerDigest
        connection.save(failOnError: true, flush: true)
        return connection
    }

    /** `account`'s connection to the warehouse `location` stands for, or null. */
    DsmConnection connectionTo(DsmWarehouseAccount account, Location location) {
        return location ? DsmConnection.findByAccountAndPeerLocation(account, location) : null
    }

    List<ConnectSessionV1> sessionsHeldBy(DsmWarehouseAccount account) {
        return DsmSteps.reply(clientFor(account).connect("connect.app.sessions", Empty.getDefaultInstance(), Route.QUERY),
                ConnectReplyV1.ReplyCase.SESSIONS, "connect.app.sessions").sessions.sessionsList
    }
}
