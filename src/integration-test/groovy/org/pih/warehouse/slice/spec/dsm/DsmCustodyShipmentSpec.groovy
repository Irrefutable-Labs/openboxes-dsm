package org.pih.warehouse.slice.spec.dsm

import com.google.protobuf.ByteString
import dsm.types.proto.ConnectAppOfferRequestV1
import dsm.types.proto.Envelope
import dsm.types.proto.ConnectAppOfferV1
import dsm.types.proto.ConnectCapV1
import dsm.types.proto.ConnectReplyV1
import dsm.types.proto.ConnectScopeKind
import dsm.types.proto.ConnectScopeV1
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import grails.util.GrailsWebMockUtil
import groovy.json.JsonSlurper
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.context.request.RequestContextHolder
import spock.lang.Shared
import spock.lang.Stepwise

import org.pih.warehouse.api.DsmCustodyApiController
import org.pih.warehouse.api.receiving.v2.ReceiptV2Service
import org.pih.warehouse.auth.AuthService
import org.pih.warehouse.common.base.IntegrationSpec
import org.pih.warehouse.common.domain.builder.core.LocationTestBuilder
import org.pih.warehouse.common.domain.builder.inventory.RecordInventoryCommandTestBuilder
import org.pih.warehouse.common.domain.builder.product.CategoryTestBuilder
import org.pih.warehouse.common.domain.builder.product.ProductTestBuilder
import org.pih.warehouse.common.domain.builder.shipping.ShipmentTestBuilder
import org.pih.warehouse.common.dsm.DsmTestFleet
import org.pih.warehouse.common.dsm.DsmTestHost
import org.pih.warehouse.common.dsm.DsmTestOpenBoxes
import org.pih.warehouse.common.util.RandomUtil
import org.pih.warehouse.core.Constants
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User
import org.pih.warehouse.dsm.Base32Crockford
import org.pih.warehouse.dsm.DsmAccountService
import org.pih.warehouse.dsm.DsmAdmission
import org.pih.warehouse.dsm.DsmCustodyException
import org.pih.warehouse.dsm.DsmCustodyService
import org.pih.warehouse.dsm.DsmCustodyTransfer
import org.pih.warehouse.dsm.DsmHostClient
import org.pih.warehouse.dsm.DsmHostException
import org.pih.warehouse.dsm.DsmHostClient
import org.pih.warehouse.dsm.DsmWarehouseAccount
import org.pih.warehouse.inventory.InventoryItem
import org.pih.warehouse.inventory.InventoryService
import org.pih.warehouse.inventory.LocalTransfer
import org.pih.warehouse.inventory.ProductAvailabilityService
import org.pih.warehouse.inventory.RecordInventoryCommand
import org.pih.warehouse.inventory.Transaction
import org.pih.warehouse.inventory.TransactionEntry
import org.pih.warehouse.inventory.TransactionType
import org.pih.warehouse.inventory.TransferStockCommand
import org.pih.warehouse.product.Category
import org.pih.warehouse.product.Product
import org.pih.warehouse.receiving.Receipt
import org.pih.warehouse.receiving.ReceiptCompleteRequestCommand
import org.pih.warehouse.receiving.ReceiptItem
import org.pih.warehouse.shipping.Shipment
import org.pih.warehouse.shipping.ShipmentIdentifierService
import org.pih.warehouse.shipping.ShipmentItem
import org.pih.warehouse.shipping.ShipmentService

/**
 * Independent organizations exchanging verifiable inventory custody, against DSM's own code: a storage set of real
 * storage nodes on Postgres, a dsm-app-host per account, and three OpenBoxes instances that share nothing:
 * - organization A is the instance this test runs in, with two warehouses of its own and a clinic off DSM;
 * - organization B and the product registry are this application started as processes of their own, each with its
 *   own database, DSM data root and users, reached only over HTTP.
 * The one thing that crosses between them is DSM: an offer code handed over out of band, then connections and
 * transfers. Nothing stands in for a DSM component (docs/plans/2026-11-01-dsm-custody-integration.md).
 *
 * Environment: DSM_FLEET_BIN, DSM_APP_HOST_BIN, DSM_TEST_DATABASE_URL (see DsmTestProcess).
 */
@Stepwise
class DsmCustodyShipmentSpec extends IntegrationSpec {

    static final int ADMITTED_AT_A = 100
    static final int DECLINED_AT_A = 5
    static final int UNITS_SHIPPED = 10
    static final int UNITS_SHIPPED_AGAIN = 5
    static final int UNITS_TRANSFERRED = 7
    static final int UNITS_INTERNAL_SHIPMENT = 6
    static final int UNITS_CONSUMED = 4
    static final int UNITS_FROM_SUPPLIER = 3
    /** The second registry operator: a seat holder, signed in with a wallet of their own; the username a seat makes. */
    static final String OTHER_OPERATOR = "second.operator"

    @Autowired
    DsmCustodyService dsmCustodyService

    @Autowired
    InventoryService inventoryService

    @Autowired
    ProductAvailabilityService productAvailabilityService

    @Autowired
    ShipmentService shipmentService

    @Autowired
    ShipmentIdentifierService shipmentIdentifierService

    @Autowired
    ReceiptV2Service receiptV2Service

    @Autowired
    AuthService authService

    @Autowired
    GrailsApplication grailsApplication

    /**
     * The web request the application's own code runs under: the shipment and receipt notifications render e-mail
     * templates that read the signed-in warehouse from the web session, and the API controller reads its params
     * from it. Bound for every feature, released after it.
     */
    GrailsWebRequest webRequest

    @Shared DsmTestFleet fleet
    @Shared DsmTestHost hostRegistry
    @Shared DsmTestHost hostA
    @Shared DsmTestHost hostA2
    @Shared DsmTestHost hostB
    /** Owners' and an operator's wallets: hosts standing in for phones. Nobody has a password. */
    @Shared DsmTestHost hostRegistryOwner
    @Shared DsmTestHost hostBOwner
    @Shared DsmTestHost hostOperator2
    @Shared DsmTestOpenBoxes registryInstance
    @Shared DsmTestOpenBoxes orgBInstance
    @Shared DsmTestOpenBoxes.Session registry
    @Shared DsmTestOpenBoxes.Session secondOperator
    @Shared DsmTestOpenBoxes.Session orgB

    @Shared String accountAId
    @Shared String accountA2Id
    @Shared String accountBId
    @Shared String registryAccountId
    @Shared String warehouseAId
    @Shared String warehouseA2Id
    @Shared String warehouseBAtAId
    @Shared String warehouseBAtBId
    @Shared String warehouseAAtBId
    @Shared String clinicId
    @Shared String productId
    @Shared String productAtBId
    @Shared String registryProductId
    @Shared String ticker
    @Shared String anchor
    @Shared long supply
    @Shared String shipmentId
    @Shared String secondShipmentAtBId
    @Shared String secondInternalShipmentId
    @Shared String supplierShipmentId

    RandomUtil randomUtil = new RandomUtil()

    void setupSpec() {
        long run = System.currentTimeMillis()
        // Each organization's hosts sit under its own data root, so no instance can register another's.
        File orgARoot = new File("build/dsm-test/org-a/${run}")
        File orgBRoot = new File("build/dsm-test/org-b-${run}")
        File registryRoot = new File("build/dsm-test/registry-${run}")
        [orgARoot, orgBRoot, registryRoot]*.mkdirs()
        fleet = DsmTestFleet.start(new File("build/dsm-test/fleet-${run}"))
        hostRegistry = DsmTestHost.start("registry", registryRoot, fleet.envConfig)
        hostA = DsmTestHost.start("warehouse-a", orgARoot, fleet.envConfig)
        hostA2 = DsmTestHost.start("warehouse-a2", orgARoot, fleet.envConfig)
        hostB = DsmTestHost.start("warehouse-b", orgBRoot, fleet.envConfig)
        hostRegistryOwner = DsmTestHost.start("registry-owner-wallet", registryRoot, fleet.envConfig)
        hostBOwner = DsmTestHost.start("org-b-owner-wallet", orgBRoot, fleet.envConfig)
        hostOperator2 = DsmTestHost.start("registry-operator-wallet", registryRoot, fleet.envConfig)
        // ERA for the fee that issuing an object burns, and for what the accounts' admissions cost; the registry
        // owner's wallet issues the seat object its operators hold.
        [hostRegistry, hostA, hostA2, hostB, hostRegistryOwner].each { DsmTestHost host -> host.claimFaucet() }
        // Each instance is started with its host. On first run nobody can sign in: the first wallet to connect through
        // the sign-in page's code and sign the owner statement owns it, and that session is the owner's.
        registryInstance = DsmTestOpenBoxes.start("registry-openboxes", registryRoot, hostRegistry, "Product registry")
        orgBInstance = DsmTestOpenBoxes.start("org-b-openboxes", orgBRoot, hostB)
        registryInstance.awaitUp()
        orgBInstance.awaitUp()
        registry = registryInstance.claim(hostRegistryOwner)
        orgB = orgBInstance.claim(hostBOwner)
    }

    void setup() {
        bindWebRequest()
        // The signed-in user and location are thread-locals: an instance loaded in an earlier feature's session
        // would otherwise meet a fresh copy of itself in this one. Each feature signs in again with actAt().
        authService.currentUser = null
        authService.currentLocation = null
    }

    void cleanup() {
        RequestContextHolder.resetRequestAttributes()
    }

    void cleanupSpec() {
        // Every process is stopped before the run ends: a connected account's listener keeps writing to its
        // store while it runs.
        orgBInstance?.close()
        registryInstance?.close()
        hostOperator2?.close()
        hostBOwner?.close()
        hostRegistryOwner?.close()
        hostB?.close()
        hostA2?.close()
        hostA?.close()
        hostRegistry?.close()
        fleet?.close()
    }

    void 'the registry, on its own instance, issues a very large fixed supply: capacity to admit, none of it stock'() {
        given: 'a second operator, seated by the owner, and a product on the registry\'s own instance'
        Map category = registry.postJson("/generic/category/", [name: "Registered products"]) as Map
        Map registered = createProduct(registry, "Oral rehydration salts", productCode(), category.id as String)
        registryProductId = registered.id
        secondOperator = seatedOperator()

        when: 'the registry account was made on first run, when its owner claimed the instance'
        Map account = (registry.get("/dsm/accounts") as List<Map>).first()
        registryAccountId = account.id
        Map token = registry.post("/dsm/tokens", [productId: registeredProductId()]) as Map
        ticker = token.ticker
        anchor = token.policyAnchor
        supply = token.supply as long

        then: 'the whole supply sits with the registry, and nothing has been released'
        account.registry
        account.deviceId == Base32Crockford.encode(hostRegistry.deviceId())
        supply == DsmAccountService.DEFAULT_SUPPLY
        token.reserve == supply
        token.released == 0
        hostRegistry.balance(ticker) == supply

        when: 'the registry\'s instance is asked to hold a warehouse\'s account'
        Map refused = registry.attemptPost("/dsm/accounts/warehouses", [locationId: "1",
                ingressOrigin: hostB.ingressOrigin, dataDir: hostB.dataDir.canonicalPath])

        then: 'release authority never shares a server with custody'
        refused.status == 400
        refused.json.errorMessage.contains("release authority")
    }

    @Transactional
    void 'each organization registers only its own accounts and adopts the object against its own catalog'() {
        given: 'A\'s two warehouses, its clinic off DSM, its Location for B\'s warehouse, and its own product'
        actAt(Location.get("1"))
        Location warehouseA = depot("Warehouse A")
        Location warehouseA2 = depot("Warehouse A2")
        Location clinic = depot("Clinic C")
        Location warehouseBAtA = depot("Organization B warehouse")
        warehouseAId = warehouseA.id
        warehouseA2Id = warehouseA2.id
        clinicId = clinic.id
        warehouseBAtAId = warehouseBAtA.id
        Category rootCategory = Category.getRootCategory() ?: new CategoryTestBuilder().rootCategory().build(true)
        Product product = new ProductTestBuilder().randomizedName().category(rootCategory).build()
        product.productCode = "A" + String.format("%07d", new Random().nextInt(10_000_000))
        product.save(flush: true, failOnError: true)
        productId = product.id

        and: 'B\'s warehouse, the Location it knows A by (a supplier in its own records), and its own product under its own code'
        warehouseBAtBId = "1"
        // The default data's supplier: a location B ships nothing from and manages no stock at.
        warehouseAAtBId = "2"
        Map categoryAtB = orgB.postJson("/generic/category/", [name: "Medicines"]) as Map
        Map productAtB = createProduct(orgB, "ORS sachets", "B" + String.format("%07d", new Random().nextInt(10_000_000)), categoryAtB.id as String)
        productAtBId = productAtB.id

        when:
        Map accountA = api("registerWarehouse", [locationId: warehouseA.id, ingressOrigin: hostA.ingressOrigin, dataDir: hostA.dataDir.canonicalPath])
        Map accountA2 = api("registerWarehouse", [locationId: warehouseA2.id, ingressOrigin: hostA2.ingressOrigin, dataDir: hostA2.dataDir.canonicalPath])
        // B's account was made on first run, for its default warehouse, when its owner claimed the instance.
        Map accountB = (orgB.get("/dsm/accounts") as List<Map>).first()
        accountAId = accountA.id
        accountA2Id = accountA2.id
        accountBId = accountB.id
        Map adoptedAtA = api("adoptToken", [productId: product.id, policyAnchor: anchor])
        Map adoptedAtB = orgB.post("/dsm/tokens/adoptions", [productId: productAtBId, policyAnchor: anchor]) as Map

        then: 'each account is the host it names, and the one anchor stands for each organization\'s own product'
        accountA.deviceId == Base32Crockford.encode(hostA.deviceId())
        accountB.deviceId == Base32Crockford.encode(hostB.deviceId())
        adoptedAtA.ticker == ticker
        adoptedAtB.ticker == ticker
        adoptedAtB.productCode != adoptedAtA.productCode

        and: 'neither instance knows the other\'s account as an account'
        DsmWarehouseAccount.findByDeviceId(accountB.deviceId) == null
        orgB.get("/dsm/accounts")*.deviceId == [accountB.deviceId]
    }

    // A refused call marks the feature's transaction for rollback, so each refusal here stands in a feature of its own.
    @Transactional
    void 'an organization cannot register another organization\'s host: it sits outside its own data root'() {
        when:
        String outside = apiRefused("registerWarehouse", [locationId: warehouseBAtAId, ingressOrigin: hostB.ingressOrigin, dataDir: hostB.dataDir.canonicalPath])

        then:
        outside.contains("must be under")
        DsmWarehouseAccount.findByDeviceId(Base32Crockford.encode(hostB.deviceId())) == null
    }

    @Transactional
    void 'a warehouse organization cannot issue an object: only a registry does'() {
        when:
        String refusal = apiRefused("issueToken", [productId: productId])

        then:
        refusal.contains("only a registry issues")
    }

    @Transactional
    void 'pairing is three calls, each by whoever runs that side, and a code is all that crosses'() {
        given:
        actAt(Location.get(warehouseAId))

        when: 'A and B each offer the registry a connection, and its operators accept'
        Map offerA = api("offerConnection", [accountId: accountAId, toRegistry: true])
        registry.post("/dsm/connections/accepts", [accountId: registryAccountId, code: offerA.code])
        Map connectionAToRegistry = api("confirmConnection", [accountId: accountAId, offerDigest: offerA.offerDigest, peerRegistry: true])
        Map offerB = orgB.post("/dsm/connections/offers", [accountId: accountBId, toRegistry: true]) as Map
        registry.post("/dsm/connections/accepts", [accountId: registryAccountId, code: offerB.code])
        Map connectionBToRegistry = orgB.post("/dsm/connections/confirmations", [accountId: accountBId, offerDigest: offerB.offerDigest, peerRegistry: true]) as Map

        then: 'each side read the peer from its own account'
        connectionAToRegistry.peerDeviceId == Base32Crockford.encode(hostRegistry.deviceId())
        connectionAToRegistry.side == "OFFERED"
        connectionBToRegistry.peerDeviceId == Base32Crockford.encode(hostRegistry.deviceId())
        registry.get("/dsm/connections").size() == 2

        when: 'A offers B a connection, B accepts on its own instance, A confirms on its own'
        Map offerToB = api("offerConnection", [accountId: accountAId])
        Map acceptedAtB = orgB.post("/dsm/connections/accepts", [accountId: accountBId, code: offerToB.code, peerLocationId: warehouseAAtBId]) as Map
        Map confirmedAtA = api("confirmConnection", [accountId: accountAId, offerDigest: offerToB.offerDigest, peerLocationId: warehouseBAtAId])

        then:
        acceptedAtB.peerDeviceId == Base32Crockford.encode(hostA.deviceId())
        acceptedAtB.side == "ACCEPTED"
        confirmedAtA.peerDeviceId == Base32Crockford.encode(hostB.deviceId())
        confirmedAtA.sessionId == acceptedAtB.sessionId

        when: 'A connects its own two warehouses the same way'
        Map offerToA2 = api("offerConnection", [accountId: accountAId])
        api("acceptConnection", [accountId: accountA2Id, code: offerToA2.code, peerLocationId: warehouseAId])
        api("confirmConnection", [accountId: accountAId, offerDigest: offerToA2.offerDigest, peerLocationId: warehouseA2Id])

        then:
        noExceptionThrown()
    }

    void 'an offer that asks to spend is refused by the registry and by a warehouse, so nothing can be released under a grant'() {
        given: 'A\'s host signs an offer capping what it may take from the other side, outside anything OpenBoxes would offer'
        ConnectAppOfferV1 spending = hostA.client.connect("connect.app.offer", ConnectAppOfferRequestV1.newBuilder()
                .setDisplayName("Warehouse A")
                .addScopes(ConnectScopeV1.newBuilder()
                        .setKind(ConnectScopeKind.CONNECT_SCOPE_KIND_PAY)
                        .addCaps(ConnectCapV1.newBuilder()
                                .setPolicyCommit(ByteString.copyFrom(Base32Crockford.decodeId32(anchor, "the anchor")))
                                .setPerRequest(1_000L)
                                .setTotal(1_000L)))
                .build(), DsmHostClient.Route.INVOKE).offer

        when:
        Map atRegistry = registry.attemptPost("/dsm/connections/accepts", [accountId: registryAccountId, code: spending.code])
        Map atB = orgB.attemptPost("/dsm/connections/accepts", [accountId: accountBId, code: spending.code, peerLocationId: warehouseAAtBId])

        then:
        atRegistry.status == 400
        atRegistry.json.errorMessage.contains("grants nothing")
        atB.status == 400
        atB.json.errorMessage.contains("nothing that spends")
        registry.get("/dsm/connections").size() == 2
    }

    @Transactional
    void 'no units can be recorded at a DSM warehouse before the registry has released them'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Product product = Product.get(productId)

        when:
        recordStock(warehouseA, product, ADMITTED_AT_A)

        then: 'the recording service reports the refusal as the command\'s own error'
        IllegalStateException refused = thrown()
        refused.message.contains("request an admission of ${ADMITTED_AT_A}")
        !hostA.balance(ticker)
        hostRegistry.balance(ticker) == supply
    }

    @Transactional
    void 'an admission needs two different registry operators, and nothing moves before the second approves'() {
        given:
        actAt(Location.get(warehouseAId))

        when: 'A asks, naming the document for the units'
        Map admission = api("requestAdmission", [accountId: accountAId, productId: productId, quantity: ADMITTED_AT_A, reference: "GRN-0001"])

        then: 'the request waits on the registry, whose list shows what is asked and on what document'
        admission.seq != null
        Map waiting = awaitRelease("GRN-0001")
        waiting.summary.contains(ADMITTED_AT_A.toString())
        waiting.requesterDeviceId == Base32Crockford.encode(hostA.deviceId())
        waiting.approvals == []
        waiting.approvalsRequired == 2

        when: 'the first operator approves'
        Map first = registry.post("/dsm/releases/approvals", [sessionId: waiting.sessionId, seq: waiting.seq]) as Map

        then: 'the registry\'s account has not been told, and nothing moved'
        !first.decided
        first.approvals == 1
        hostRegistry.balance(ticker) == supply
        !hostA.balance(ticker)

        when: 'the same operator approves again'
        Map again = registry.attemptPost("/dsm/releases/approvals", [sessionId: waiting.sessionId, seq: waiting.seq])

        then:
        again.status == 400
        again.json.errorMessage.contains("another operator")
        hostRegistry.balance(ticker) == supply

        when: 'a second operator approves'
        Map second = secondOperator.post("/dsm/releases/approvals", [sessionId: waiting.sessionId, seq: waiting.seq]) as Map

        then: 'the registry\'s account carries it out, and A\'s own account establishes it'
        second.decided
        awaitAdmissionStatus(admission.id as String, DsmAdmission.STATUS_ESTABLISHED).creditTransferId
        hostA.awaitBalance(ticker, ADMITTED_AT_A) == ADMITTED_AT_A
        hostRegistry.awaitBalance(ticker, supply - ADMITTED_AT_A) == supply - ADMITTED_AT_A

        and: 'the registry\'s audit trail names who decided, and what it now reports released'
        Map decision = (registry.get("/dsm/releases/decisions") as List<Map>).first()
        decision.decision == "APPROVED"
        decision.decidedBy == OTHER_OPERATOR
        Map token = (registry.get("/dsm/tokens") as List<Map>).first()
        token.released == ADMITTED_AT_A
        token.reserve == supply - ADMITTED_AT_A
    }

    @Transactional
    void 'one operator can decline, and then nothing is released'() {
        given:
        actAt(Location.get(warehouseAId))

        when:
        Map admission = api("requestAdmission", [accountId: accountAId, productId: productId, quantity: DECLINED_AT_A, reference: "GRN-DECLINED"])
        Map waiting = awaitRelease("GRN-DECLINED")
        registry.post("/dsm/releases/declines", [sessionId: waiting.sessionId, seq: waiting.seq])

        then:
        awaitAdmissionStatus(admission.id as String, DsmAdmission.STATUS_DECLINED).reason
        hostA.balance(ticker) == ADMITTED_AT_A
        hostRegistry.balance(ticker) == supply - ADMITTED_AT_A
    }

    @Transactional
    void 'an instance that holds no registry account decides no release'() {
        given:
        actAt(Location.get(warehouseAId))

        when:
        String refusal = apiRefused("releases", [:])

        then:
        refusal.contains("no registry account")
    }

    @Transactional
    void 'recording stock draws on the admission, and units cannot be recorded twice'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Product product = Product.get(productId)

        when:
        recordStock(warehouseA, product, ADMITTED_AT_A)

        then: 'the record names the registry, and DSM was not asked to move anything again'
        DsmCustodyTransfer draw = DsmCustodyTransfer.findByKindAndProduct(DsmCustodyTransfer.KIND_ADMITTED, product)
        draw.quantity == ADMITTED_AT_A
        draw.peerDeviceId == Base32Crockford.encode(hostRegistry.deviceId())
        draw.memo.startsWith(DsmCustodyService.MEMO_TRANSACTION)
        hostA.balance(ticker) == ADMITTED_AT_A
        DsmAdmission.findByReference("GRN-0001").quantityDrawn == ADMITTED_AT_A
    }

    @Transactional
    void 'more than was ever admitted cannot be recorded'() {
        given: 'a recording writes transactions up to two seconds on from its date, and two may not share a second'
        Thread.sleep(3_100L)

        when: 'another recording, with every admitted unit already drawn'
        recordStock(Location.get(warehouseAId), Product.get(productId), ADMITTED_AT_A + 1)

        then:
        IllegalStateException refused = thrown()
        refused.message.contains("has 0 admitted")
        hostA.balance(ticker) == ADMITTED_AT_A
    }

    @Transactional
    void 'sending a shipment to the other organization moves the units on DSM before OpenBoxes records the TRANSFER_OUT'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Location warehouseB = Location.get(warehouseBAtAId)
        Product product = Product.get(productId)
        actAt(warehouseA)
        Shipment shipment = shipment(warehouseA, warehouseB, product, UNITS_SHIPPED)
        shipmentId = shipment.id

        when:
        shipmentService.sendShipment(shipment, "sent from A to B", admin(), warehouseA, new Date())

        then: 'the custody transfer is recorded from the origin account\'s own send, naming B by its device id alone'
        DsmCustodyTransfer transfer = DsmCustodyTransfer.findByShipmentAndProduct(shipment, product)
        transfer.kind == DsmCustodyTransfer.KIND_SHIPMENT
        transfer.quantity == UNITS_SHIPPED
        transfer.memo == DsmCustodyService.memoFor(shipment)
        transfer.peerDeviceId == Base32Crockford.encode(hostB.deviceId())
        transfer.originStateHash.length() == 52

        and: 'OpenBoxes recorded the outbound transaction after it'
        shipment.hasShipped()
        Set<Transaction> outgoing = Shipment.get(shipmentId).outgoingTransactions
        outgoing.size() == 1
        outgoing.first().transactionType.id == Constants.TRANSFER_OUT_TRANSACTION_TYPE_ID
        outgoing.first().transactionEntries*.quantity == [UNITS_SHIPPED]

        and: 'A\'s account spent the units, and B\'s account takes them in'
        hostA.balance(ticker) == ADMITTED_AT_A - UNITS_SHIPPED
        hostB.awaitBalance(ticker, UNITS_SHIPPED) == UNITS_SHIPPED
    }

    void 'B takes the custody in on its own instance, from its own account, and receives it through its own workflow'() {
        when: 'B reads what its own account holds and has not taken in'
        Map inbound = awaitInbound(orgB)

        then: 'the lines are B\'s own product, and the sender is its Location for A'
        inbound.originLocationId == warehouseAAtBId
        inbound.destinationLocationId == warehouseBAtBId
        inbound.lines*.productId == [productAtBId]
        inbound.lines*.quantity == [UNITS_SHIPPED]

        when: 'B makes its own shipment record of it, and receives it through the partial receiving API'
        Map established = orgB.post("/dsm/inbound", [peerDeviceId: inbound.peerDeviceId, memo: inbound.memo]) as Map
        receiveAtB(established.shipmentId as String, UNITS_SHIPPED)

        then: 'the units are in B\'s stock, in B\'s database'
        quantityOnHandAtB() == UNITS_SHIPPED

        and: 'B\'s record says what its own account accepted'
        Map transfer = (orgB.get("/dsm/transfers") as List<Map>).find { it.shipmentId == established.shipmentId }
        transfer.kind == DsmCustodyTransfer.KIND_INBOUND
        transfer.quantity == UNITS_SHIPPED
        transfer.quantityReceived == UNITS_SHIPPED
        transfer.destinationTransferId

        and: 'it is not offered for taking in again'
        orgB.get("/dsm/inbound") == []
    }

    @Transactional
    void 'A verifies B\'s custody itself: an owns proof over their connection, with no access to anything of B\'s'() {
        given:
        DsmCustodyTransfer transfer = DsmCustodyTransfer.findByShipmentAndProduct(Shipment.get(shipmentId), Product.get(productId))

        when: 'B\'s account checks its connections, as a partner\'s wallet does'
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false)
        Thread answering = hostB.answeringRequests(done)
        Map proven
        try {
            proven = api("proveHoldings", [id: transfer.id])
        } finally {
            done.set(true)
            answering.join()
        }

        then: 'the proof recomputed B\'s root: B owns exactly the shipped units'
        proven.provenAmount == UNITS_SHIPPED
        proven.provenPosition > 0
    }

    @Transactional
    void 'a second shipment is taken in, and a receipt for more units than DSM moved is refused'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Location warehouseB = Location.get(warehouseBAtAId)
        Product product = Product.get(productId)
        actAt(warehouseA)
        Shipment shipment = shipment(warehouseA, warehouseB, product, UNITS_SHIPPED_AGAIN)

        when:
        shipmentService.sendShipment(shipment, "second", admin(), warehouseA, new Date())
        hostB.awaitBalance(ticker, UNITS_SHIPPED + UNITS_SHIPPED_AGAIN)
        Map inbound = awaitInbound(orgB)
        Map established = orgB.post("/dsm/inbound", [peerDeviceId: inbound.peerDeviceId, memo: inbound.memo]) as Map
        secondShipmentAtBId = established.shipmentId
        Map refused = attemptReceiveAtB(secondShipmentAtBId, UNITS_SHIPPED_AGAIN + 1)

        then: 'B records nothing for it'
        refused.status != 200
        refused.text.contains("cannot receive units it has no custody of")
        quantityOnHandAtB() == UNITS_SHIPPED

        when: 'B receives what DSM moved'
        receiveAtB(secondShipmentAtBId, UNITS_SHIPPED_AGAIN)

        then:
        quantityOnHandAtB() == UNITS_SHIPPED + UNITS_SHIPPED_AGAIN
    }

    @Transactional
    void 'a stock transfer between A\'s own two warehouses moves the units account to account before either transaction is written'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Location warehouseA2 = Location.get(warehouseA2Id)
        Product product = Product.get(productId)
        actAt(warehouseA)
        TransferStockCommand command = new TransferStockCommand(
                location: warehouseA,
                otherLocation: warehouseA2,
                inventoryItem: inventoryItem(product),
                quantity: UNITS_TRANSFERRED,
                transferOut: Boolean.TRUE,
        )

        when:
        Transaction transaction = inventoryService.transferStock(command)
        // The session flushes on commit here; the lookup below needs the mirrored transaction and local transfer written.
        LocalTransfer.withSession { session -> session.flush() }

        then: 'A2\'s account held the units before the mirrored transactions were saved'
        !transaction.hasErrors()
        LocalTransfer.findBySourceTransaction(transaction) != null
        DsmCustodyTransfer moved = DsmCustodyTransfer.findByKindAndMemo(DsmCustodyTransfer.KIND_STOCK_TRANSFER,
                DsmCustodyService.MEMO_TRANSACTION + transaction.transactionNumber)
        moved.quantity == UNITS_TRANSFERRED
        moved.dateEstablished != null
        hostA.balance(ticker) == ADMITTED_AT_A - UNITS_SHIPPED - UNITS_SHIPPED_AGAIN - UNITS_TRANSFERRED
        hostA2.balance(ticker) == UNITS_TRANSFERRED
    }

    @Transactional
    void 'a stock transfer to the other organization is refused: it would write B\'s inventory here'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Product product = Product.get(productId)
        actAt(warehouseA)

        when:
        dsmCustodyService.transferBetweenWarehouses(warehouseA, Location.get(warehouseBAtAId), product, 1, "T-1")

        then:
        DsmCustodyException refused = thrown()
        refused.message.contains("send a shipment")
    }

    @Transactional
    void 'a shipment between A\'s own warehouses is sent'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Location warehouseA2 = Location.get(warehouseA2Id)
        Product product = Product.get(productId)
        actAt(warehouseA)
        Shipment shipment = shipment(warehouseA, warehouseA2, product, UNITS_INTERNAL_SHIPMENT)

        when:
        shipmentService.sendShipment(shipment, "between our own", admin(), warehouseA, new Date())
        secondInternalShipmentId = shipment.id

        then:
        shipment.hasShipped()
    }

    @Transactional
    void 'receiving it writes the TRANSFER_IN only once the destination\'s own account holds it'() {
        given:
        Location warehouseA2 = Location.get(warehouseA2Id)
        Shipment shipment = Shipment.get(secondInternalShipmentId)
        Product product = Product.get(productId)
        actAt(warehouseA2)
        Receipt receipt = Receipt.get(receiptV2Service.startReceipt(shipment.id).id)
        receipt.receiptItems.each { ReceiptItem item -> item.quantityReceived = item.quantityShipped }
        receipt.save(flush: true, failOnError: true)

        when:
        receiptV2Service.completeReceipt(new ReceiptCompleteRequestCommand(receipt: receipt, itemsToComplete: []))

        then:
        DsmCustodyTransfer inbound = DsmCustodyTransfer.findByKindAndShipmentAndProduct(DsmCustodyTransfer.KIND_INBOUND, shipment, product)
        inbound.quantityReceived == UNITS_INTERNAL_SHIPMENT
        inbound.destinationTransferId
        Set<Transaction> incoming = Shipment.get(shipment.id).incomingTransactions
        incoming.size() == 1
        incoming.first().transactionType.id == Constants.TRANSFER_IN_TRANSACTION_TYPE_ID
        hostA2.balance(ticker) == UNITS_TRANSFERRED + UNITS_INTERNAL_SHIPMENT
    }

    @Transactional
    void 'consumption burns the units before the debit is written'() {
        given:
        Location warehouseA2 = Location.get(warehouseA2Id)
        Product product = Product.get(productId)
        actAt(warehouseA2)
        Transaction consumption = new Transaction(
                transactionType: TransactionType.get(Constants.CONSUMPTION_TRANSACTION_TYPE_ID),
                inventory: warehouseA2.inventory,
                transactionDate: new Date(),
                comment: "used on the ward",
        )
        consumption.addToTransactionEntries(new TransactionEntry(
                inventoryItem: inventoryItem(product), product: product, quantity: UNITS_CONSUMED))

        when: 'the debit is backed as the controller backs it, then saved'
        dsmCustodyService.backStockChange(consumption)
        consumption.save(flush: true, failOnError: true)

        then:
        DsmCustodyTransfer burned = DsmCustodyTransfer.findByKindAndMemo(DsmCustodyTransfer.KIND_BURN,
                DsmCustodyService.MEMO_TRANSACTION + consumption.transactionNumber)
        burned.quantity == UNITS_CONSUMED
        burned.account.location.id == warehouseA2Id
        hostA2.balance(ticker) == UNITS_TRANSFERRED + UNITS_INTERNAL_SHIPMENT - UNITS_CONSUMED
    }

    @Transactional
    void 'a shipment from a clinic off DSM is sent as OpenBoxes always sent it'() {
        given:
        Location clinic = Location.get(clinicId)
        Location warehouseA = Location.get(warehouseAId)
        Product product = Product.get(productId)
        recordStock(clinic, product, UNITS_FROM_SUPPLIER)
        Shipment shipment = shipment(clinic, warehouseA, product, UNITS_FROM_SUPPLIER)

        when:
        shipmentService.sendShipment(shipment, "from the clinic", admin(), clinic, new Date())
        supplierShipmentId = shipment.id

        then: 'nothing moved on DSM: the clinic has no account, and the units are not in custody yet'
        shipment.hasShipped()
        DsmCustodyTransfer.findByShipmentAndProduct(shipment, product) == null
    }

    @Transactional
    void 'receiving it needs an admission first, and then draws on it before the TRANSFER_IN is written'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Shipment shipment = Shipment.get(supplierShipmentId)
        Product product = Product.get(productId)
        actAt(warehouseA)
        long heldBefore = hostA.balance(ticker)

        when: 'the units are admitted against the delivery note'
        Map admission = api("requestAdmission", [accountId: accountAId, productId: productId, quantity: UNITS_FROM_SUPPLIER, reference: "DN-CLINIC"])
        Map waiting = awaitRelease("DN-CLINIC")
        registry.post("/dsm/releases/approvals", [sessionId: waiting.sessionId, seq: waiting.seq])
        secondOperator.post("/dsm/releases/approvals", [sessionId: waiting.sessionId, seq: waiting.seq])
        awaitAdmissionStatus(admission.id as String, DsmAdmission.STATUS_ESTABLISHED)

        and: 'A receives the delivery'
        Receipt receipt = Receipt.get(receiptV2Service.startReceipt(shipment.id).id)
        receipt.receiptItems.each { ReceiptItem item -> item.quantityReceived = item.quantityShipped }
        receipt.save(flush: true, failOnError: true)
        receiptV2Service.completeReceipt(new ReceiptCompleteRequestCommand(receipt: receipt, itemsToComplete: []))

        then:
        DsmCustodyTransfer draw = DsmCustodyTransfer.findByKindAndMemo(DsmCustodyTransfer.KIND_ADMITTED,
                DsmCustodyService.MEMO_RECEIPT + receipt.receiptNumber)
        draw.quantity == UNITS_FROM_SUPPLIER
        Shipment.get(supplierShipmentId).incomingTransactions.size() == 1
        hostA.balance(ticker) == heldBefore + UNITS_FROM_SUPPLIER
    }

    @Transactional
    void 'DSM refuses to move more units than A\'s account holds, and records nothing'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Location warehouseB = Location.get(warehouseBAtAId)
        Product product = Product.get(productId)
        actAt(warehouseA)
        Shipment shipment = shipment(warehouseA, warehouseB, product, 1_000_000)
        long heldBefore = hostA.balance(ticker)

        when:
        dsmCustodyService.releaseOnSend(shipment, new Date())

        then: 'the send is refused with DSM\'s reason'
        DsmCustodyException refused = thrown()
        refused.message.contains("DSM refused")

        and: 'nothing moved and nothing was recorded'
        DsmCustodyTransfer.findByShipmentAndProduct(shipment, product) == null
        hostA.balance(ticker) == heldBefore
    }

    @Transactional
    void 'a shipment from a DSM warehouse to a location it has no connection to is refused'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Location clinic = Location.get(clinicId)
        Product product = Product.get(productId)
        actAt(warehouseA)
        Shipment shipment = shipment(warehouseA, clinic, product, 1)

        when:
        shipmentService.sendShipment(shipment, "to the clinic", admin(), warehouseA, new Date())

        then:
        DsmCustodyException refused = thrown()
        refused.message.contains("custody cannot leave DSM")
        !Shipment.get(shipment.id).hasShipped()
        !Shipment.get(shipment.id).outgoingTransactions
    }

    @Transactional
    void 'custody adds up across the three instances: what was released is held or burned, and the reserve is the rest'() {
        when:
        Map token = (registry.get("/dsm/tokens") as List<Map>).first()
        long released = token.released as long
        long held = hostA.balance(ticker) + hostA2.balance(ticker) + hostB.balance(ticker)

        then:
        released == ADMITTED_AT_A + UNITS_FROM_SUPPLIER
        held + UNITS_CONSUMED == released
        token.reserve + token.released == supply

        and: 'each instance lists only its own side of it'
        apiGet("transfers").data*.kind.toSet() == [DsmCustodyTransfer.KIND_ADMITTED, DsmCustodyTransfer.KIND_SHIPMENT,
                                                   DsmCustodyTransfer.KIND_STOCK_TRANSFER, DsmCustodyTransfer.KIND_INBOUND,
                                                   DsmCustodyTransfer.KIND_BURN].toSet()
        (orgB.get("/dsm/transfers") as List<Map>)*.kind.toSet() == [DsmCustodyTransfer.KIND_INBOUND].toSet()
        (registry.get("/dsm/transfers") as List<Map>) == []
        apiGet("accounts").data.size() == 2
        orgB.get("/dsm/accounts").size() == 1
        registry.get("/dsm/accounts").size() == 1
    }

    // ------------------------------------------------------------------ the other instances

    private String registeredProductId() {
        return registryProductId
    }

    private static String productCode() {
        return "R" + String.format("%07d", new Random().nextInt(10_000_000))
    }

    /**
     * A second registry operator with no password: the owner's wallet issues a seat object and the instance roots it as
     * a level, the owner's wallet releases seats to the account, the operator's wallet connects as a person, the owner
     * assigns them a seat, and the operator signs in by approving on their wallet. Every step is what a person does.
     */
    private DsmTestOpenBoxes.Session seatedOperator() {
        DsmTestOpenBoxes.Session site = new DsmTestOpenBoxes.Session(registryInstance.siteUrl)
        site.cookie = registry.cookie
        Closure<Map> state = { (site.exchange("GET", "/dsmWallet/state", null, null).json as Map).data as Map }
        String me = (state().me as Map).id

        dsm.types.proto.TokenCreateResponse minted = DsmHostClient.expect("token.create",
                hostRegistryOwner.client.invoke("token.create", dsm.types.proto.TokenCreateRequest.newBuilder()
                        .setTicker("RGSEAT").setAlias("RGSEAT").setDecimals(0).setGenesisSupplyEntered("5")
                        .setBurnEnabled(true).setTransferable(true).setThreshold(1).build()),
                Envelope.PayloadCase.TOKEN_CREATE_RESPONSE).tokenCreateResponse
        assert minted.success: "the seat object was not issued: ${minted.message}"
        String anchor = Base32Crockford.encode(minted.policyAnchor.toByteArray())
        Map level = (site.post("/dsmWallet/addLevel", [name: "Operator", roleType: "ROLE_ADMIN", policyAnchor: anchor, creatorId: me]) as Map)
        site.post("/dsmWallet/requestSeats", [levelId: level.id, quantity: "2"])
        answeredBy(hostRegistryOwner, "two seats reach the registry's account") {
            ((state().levels as List<Map>).first().spare as long) >= 1 ? true : null
        }

        Map offer = site.post("/dsmWallet/offer", [:]) as Map
        hostOperator2.connectByCode(offer.code as String)
        poll("the operator's wallet connects") {
            (site.post("/dsmWallet/answered", [offerDigest: offer.offerDigest, purpose: "person"]) as Map).answered ? true : null
        }
        String operatorDevice = Base32Crockford.encode(hostOperator2.deviceId()).take(8)
        Map wallet = (state().wallets as List<Map>).find { Map w -> w.deviceId == operatorDevice }
        site.post("/dsmWallet/assignSeat", [levelId: level.id, walletId: wallet.id, locationId: "1", firstName: "Second", lastName: "Operator"])
        return registryInstance.signIn(OTHER_OPERATOR, hostOperator2)
    }

    /** `probe` while `wallet` answers what waits on its phone, until `probe` gives something. */
    private static void answeredBy(DsmTestHost wallet, String what, Closure probe) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false)
        Thread answers = wallet.answeringRequests(done)
        try {
            poll(what, probe)
        } finally {
            done.set(true)
            answers.join()
        }
    }

    private static void poll(String what, Closure probe) {
        long deadline = System.currentTimeMillis() + 240_000L
        while (System.currentTimeMillis() < deadline) {
            try {
                if (probe() != null) {
                    return
                }
            } catch (DsmHostException | org.pih.warehouse.dsm.DsmCustodyException | IllegalStateException notYet) {
                // settling: the next round looks again
            }
            Thread.sleep(3_000L)
        }
        throw new IllegalStateException("not within 240 s: ${what}")
    }

    private static Map createProduct(DsmTestOpenBoxes.Session session, String name, String code, String categoryId) {
        Map type = (session.get("/generic/productType/") as List<Map>).first()
        return session.postJson("/generic/product/", [name: name, productCode: code, category: [id: categoryId],
                                                      productType: [id: type.id]]) as Map
    }

    /** The registry's list until it shows the request naming `reference`. */
    private Map awaitRelease(String reference) {
        long deadline = System.currentTimeMillis() + DsmTestHost.STEP_TIMEOUT_MS
        while (true) {
            Map found = (registry.get("/dsm/releases") as List<Map>).find { Map it -> it.summary.contains(reference) }
            if (found) {
                return found
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("the registry never showed the request for ${reference}")
            }
            Thread.sleep(DsmTestHost.POLL_MS)
        }
    }

    /** A's own view of the admission, read from its own account, until it reaches `status`. */
    private DsmAdmission awaitAdmissionStatus(String admissionId, String status) {
        long deadline = System.currentTimeMillis() + DsmTestHost.STEP_TIMEOUT_MS
        while (true) {
            Map seen = (apiGet("admissions").data as List<Map>).find { Map it -> it.id == admissionId }
            if (seen.status == status) {
                DsmAdmission admission = DsmAdmission.get(admissionId)
                admission.refresh()
                return admission
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("admission ${admissionId} is ${seen.status}, not ${status}: ${seen.reason}")
            }
            Thread.sleep(DsmTestHost.POLL_MS)
        }
    }

    private Map awaitInbound(DsmTestOpenBoxes.Session session) {
        long deadline = System.currentTimeMillis() + DsmTestHost.STEP_TIMEOUT_MS
        while (true) {
            List<Map> inbound = session.get("/dsm/inbound") as List<Map>
            if (inbound) {
                return inbound.first()
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("nothing arrived to take in")
            }
            Thread.sleep(DsmTestHost.POLL_MS)
        }
    }

    /** A receipt of `quantity` units of the shipment's one line, in the receiving screen's two steps: save, then complete. */
    private Map attemptReceiveAtB(String shipmentAtBId, int quantity) {
        Map pending = orgB.attemptPostJson("/partialReceiving/${shipmentAtBId}?stepNumber=1",
                receiptBody(orgB.get("/partialReceiving/${shipmentAtBId}?stepNumber=1") as Map, "PENDING", quantity))
        if (pending.status != 200) {
            return pending
        }
        // The delivery date is stored rounded to the second, and a transaction may not be dated in the future.
        Thread.sleep(1_100L)
        return orgB.attemptPostJson("/partialReceiving/${shipmentAtBId}?stepNumber=2",
                receiptBody(orgB.get("/partialReceiving/${shipmentAtBId}?stepNumber=2") as Map, "COMPLETED", quantity))
    }

    private static Map receiptBody(Map receipt, String status, int quantity) {
        return [
                receiptStatus: status,
                containers   : receipt.containers.collect { Map container ->
                    [
                            container    : container.container ? [id: container.container.id] : null,
                            shipmentItems: container.shipmentItems.collect { Map item ->
                                [shipmentItemId   : item.shipmentItemId,
                                 receiptItemId    : item.receiptItemId,
                                 quantityReceiving: quantity]
                            },
                    ]
                },
        ]
    }

    private void receiveAtB(String shipmentAtBId, int quantity) {
        Map answer = attemptReceiveAtB(shipmentAtBId, quantity)
        if (answer.status != 200) {
            throw new IllegalStateException("the receipt was refused: ${answer.status} ${answer.text}")
        }
    }

    private int quantityOnHandAtB() {
        List<Map> availability = orgB.get("/products/${productAtBId}/productAvailability") as List<Map>
        return (availability.sum { Map it -> it.quantityOnHand ?: 0 } ?: 0) as int
    }

    // ------------------------------------------------------------------ fixtures

    private void bindWebRequest() {
        RequestContextHolder.resetRequestAttributes()
        webRequest = GrailsWebMockUtil.bindMockWebRequest(grailsApplication.mainContext)
    }

    /** One call of the DSM custody API controller's `action` with `params`, as a request would make it. */
    private Map api(String action, Map params) {
        Location signedIn = authService.currentLocation
        bindWebRequest()
        if (signedIn) {
            webRequest.session.setAttribute("warehouse", signedIn)
            webRequest.session.setAttribute("user", authService.currentUser)
        }
        params.each { key, value -> webRequest.params.put(key as String, value?.toString()) }
        DsmCustodyApiController controller = grailsApplication.mainContext.getBean(DsmCustodyApiController)
        controller."$action"()
        int status = webRequest.currentResponse.status
        String body = webRequest.currentResponse.contentAsString
        if (status != 200) {
            throw new IllegalStateException("${action} answered ${status}: ${body}")
        }
        return new JsonSlurper().parseText(body).data as Map
    }

    /** The refusal the action answers, naming it if it did not refuse. */
    private String apiRefused(String action, Map params) {
        bindWebRequest()
        params.each { key, value -> webRequest.params.put(key as String, value?.toString()) }
        DsmCustodyApiController controller = grailsApplication.mainContext.getBean(DsmCustodyApiController)
        controller."$action"()
        int status = webRequest.currentResponse.status
        String body = webRequest.currentResponse.contentAsString
        if (status != 400) {
            throw new IllegalStateException("${action} answered ${status}, not a refusal: ${body}")
        }
        return new JsonSlurper().parseText(body).errorMessage
    }

    private Map apiGet(String action) {
        Location signedIn = authService.currentLocation
        bindWebRequest()
        if (signedIn) {
            webRequest.session.setAttribute("warehouse", signedIn)
            webRequest.session.setAttribute("user", authService.currentUser)
        }
        DsmCustodyApiController controller = grailsApplication.mainContext.getBean(DsmCustodyApiController)
        controller."$action"()
        return new JsonSlurper().parseText(webRequest.currentResponse.contentAsString) as Map
    }

    private Location depot(String name) {
        Location location = new LocationTestBuilder()
                .name(randomUtil.randomStringFieldValue(name))
                .asFacility()
                .build(true)
        if (!location.inventory) {
            inventoryService.addInventory(location)
        }
        return location
    }

    private static User admin() {
        User user = User.findByUsername("admin")
        if (!user) {
            throw new IllegalStateException("the test database holds no admin user")
        }
        return user
    }

    /** Work as the admin user signed in at `location`, as the application's own code expects it. */
    private void actAt(Location location) {
        authService.currentLocation = location
        authService.currentUser = admin()
        webRequest.session.setAttribute("warehouse", location)
    }

    /** Stock at `location` in OpenBoxes' own books, with product availability refreshed so a send validates. */
    private void recordStock(Location location, Product product, int quantity) {
        actAt(location)
        RecordInventoryCommand command = new RecordInventoryCommandTestBuilder()
                .product(product)
                .inventory(location.inventory)
                .transactionDateNow()
                .row(null, null, null, quantity)
                .build()
        RecordInventoryCommand saved = inventoryService.saveRecordInventoryCommand(command, [:])
        if (saved.hasErrors()) {
            throw new IllegalStateException("recording stock: ${saved.errors}")
        }
        productAvailabilityService.refreshProductAvailability(location, product, Boolean.TRUE)
    }

    private InventoryItem inventoryItem(Product product) {
        return inventoryService.findInventoryItemByProductAndLotNumber(product, null)
                ?: new InventoryItem(product: product).save(flush: true, failOnError: true)
    }

    private Shipment shipment(Location origin, Location destination, Product product, int quantity) {
        Shipment shipment = new ShipmentTestBuilder().origin(origin).destination(destination).build(true)
        shipment.shipmentNumber = shipmentIdentifierService.generate(shipment)
        shipment.addToShipmentItems(new ShipmentItem(product: product, quantity: quantity, inventoryItem: inventoryItem(product)))
        shipment.save(flush: true, failOnError: true)
        return shipment
    }
}
