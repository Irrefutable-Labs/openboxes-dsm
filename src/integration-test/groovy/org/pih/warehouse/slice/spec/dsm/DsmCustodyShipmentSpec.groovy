package org.pih.warehouse.slice.spec.dsm

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
import org.pih.warehouse.common.util.RandomUtil
import org.pih.warehouse.core.Constants
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User
import org.pih.warehouse.dsm.Base32Crockford
import org.pih.warehouse.dsm.DsmCustodyException
import org.pih.warehouse.dsm.DsmCustodyService
import org.pih.warehouse.dsm.DsmCustodyTransfer
import org.pih.warehouse.dsm.DsmProductToken
import org.pih.warehouse.dsm.DsmWarehouseAccount
import org.pih.warehouse.dsm.DsmWarehouseLink
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
 * The two-warehouse demo, end to end, against DSM's own code: a storage set of real storage nodes on Postgres,
 * three dsm-app-host processes (the organization's registry and the two warehouses), and this application's own
 * services. One product; units enter custody at A from the registry, ship A to B, are received at B, move A to B
 * by stock transfer, are consumed at B, enter custody at B from a supplier that is not on DSM; and B's custody is
 * proven to A over their Connect session. Nothing stands in for a DSM component
 * (docs/plans/2026-11-01-dsm-custody-integration.md).
 *
 * Environment: DSM_FLEET_BIN, DSM_APP_HOST_BIN, DSM_TEST_DATABASE_URL (see DsmTestProcess). The database is the
 * Testcontainers MySQL every OpenBoxes integration test uses; the DSM state is the fresh accounts' own.
 */
@Stepwise
class DsmCustodyShipmentSpec extends IntegrationSpec {

    static final long SUPPLY = 1_000L
    static final int STOCK_AT_A = 100
    static final int UNITS_SHIPPED = 10
    static final int UNITS_TRANSFERRED = 7
    static final int UNITS_CONSUMED = 4
    static final int UNITS_FROM_SUPPLIER = 3
    static final int UNITS_SHIPPED_AGAIN = 5

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

    @Shared File root
    @Shared DsmTestFleet fleet
    @Shared DsmTestHost hostRegistry
    @Shared DsmTestHost hostA
    @Shared DsmTestHost hostB

    @Shared String registryAccountId
    @Shared String warehouseAId
    @Shared String warehouseBId
    @Shared String clinicId
    @Shared String productId
    @Shared String ticker
    @Shared String shipmentId
    @Shared String supplierShipmentId
    @Shared String secondShipmentId

    RandomUtil randomUtil = new RandomUtil()

    void setupSpec() {
        root = new File("build/dsm-test/${System.currentTimeMillis()}")
        fleet = DsmTestFleet.start(root)
        hostRegistry = DsmTestHost.start("registry", root, fleet.envConfig)
        hostA = DsmTestHost.start("warehouse-a", root, fleet.envConfig)
        hostB = DsmTestHost.start("warehouse-b", root, fleet.envConfig)
        // ERA for the fee a token's creation burns, and for what the accounts' admissions cost.
        hostRegistry.claimFaucet()
        hostA.claimFaucet()
        hostB.claimFaucet()
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
        hostB?.close()
        hostA?.close()
        hostRegistry?.close()
        fleet?.close()
    }

    @Transactional
    void 'the registry and two warehouses get accounts through the API, the registry issues the token, the warehouses connect'() {
        given: 'two depots with inventories, a clinic that stays off DSM, and a product whose code is a ticker'
        actAt(Location.get("1"))
        Location warehouseA = depot("Warehouse A")
        Location warehouseB = depot("Warehouse B")
        Location clinic = depot("Clinic C")
        warehouseAId = warehouseA.id
        warehouseBId = warehouseB.id
        clinicId = clinic.id
        Category rootCategory = Category.getRootCategory() ?: new CategoryTestBuilder().rootCategory().build(true)
        // The product code is the token's ticker (2 to 8 of A-Z, 0-9), chosen before the product is saved: the
        // builder would otherwise generate a code no ticker can be made of.
        Product product = new ProductTestBuilder().randomizedName().category(rootCategory).build()
        product.productCode = "P" + String.format("%07d", new Random().nextInt(10_000_000))
        product.save(flush: true, failOnError: true)
        productId = product.id

        when: 'the registry and each warehouse are registered'
        Map registry = api("registerRegistry", [label: "Test organization registry", ingressOrigin: hostRegistry.ingressOrigin, dataDir: hostRegistry.dataDir.absolutePath])
        Map accountA = api("registerWarehouse", [locationId: warehouseA.id, ingressOrigin: hostA.ingressOrigin, dataDir: hostA.dataDir.absolutePath])
        Map accountB = api("registerWarehouse", [locationId: warehouseB.id, ingressOrigin: hostB.ingressOrigin, dataDir: hostB.dataDir.absolutePath])
        registryAccountId = registry.id

        then: 'each account is the host it names'
        registry.registry
        registry.deviceId == Base32Crockford.encode(hostRegistry.deviceId())
        accountA.deviceId == Base32Crockford.encode(hostA.deviceId())
        accountB.deviceId == Base32Crockford.encode(hostB.deviceId())
        !accountA.registry

        when: 'the registry issues the product token: the whole supply the organization will ever take in'
        Map token = api("issueToken", [productId: product.id, issuerId: registry.id, supply: SUPPLY])
        ticker = token.ticker

        then: 'the supply is the registry\'s, and no warehouse holds any'
        token.policyAnchor.length() == 52
        hostRegistry.balance(ticker) == SUPPLY
        hostA.balance(ticker) == null
        hostB.balance(ticker) == null

        when: 'A offers B a connection, and B approves it'
        Map link = api("pair", [offeringId: accountA.id, acceptingId: accountB.id])

        then:
        link.sessionId.length() == 52
        DsmWarehouseLink.get(link.id).offering.id == accountA.id
    }

    @Transactional
    void 'recording stock at A takes the units into custody from the registry before the adjustment is written'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Product product = Product.get(productId)

        when:
        recordStock(warehouseA, product, STOCK_AT_A)

        then: 'the registry moved the units to A, and A\'s account holds them'
        DsmCustodyTransfer credit = DsmCustodyTransfer.findByKindAndProduct(DsmCustodyTransfer.KIND_REGISTRY_CREDIT, product)
        credit.quantity == STOCK_AT_A
        credit.origin.id == registryAccountId
        credit.destination.location.id == warehouseAId
        credit.dateEstablished != null
        credit.memo.startsWith(DsmCustodyService.MEMO_TRANSACTION)
        hostRegistry.balance(ticker) == SUPPLY - STOCK_AT_A
        hostA.balance(ticker) == STOCK_AT_A
    }

    @Transactional
    void 'sending the shipment moves the units on DSM before OpenBoxes records the TRANSFER_OUT'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Location warehouseB = Location.get(warehouseBId)
        Product product = Product.get(productId)
        actAt(warehouseA)
        Shipment shipment = shipment(warehouseA, warehouseB, product, UNITS_SHIPPED)
        shipmentId = shipment.id

        when:
        shipmentService.sendShipment(shipment, "sent from A to B", admin(), warehouseA, new Date())

        then: 'the custody transfer is recorded from the origin account\'s own send'
        DsmCustodyTransfer transfer = DsmCustodyTransfer.findByShipmentAndProduct(shipment, product)
        transfer.kind == DsmCustodyTransfer.KIND_SHIPMENT
        transfer.quantity == UNITS_SHIPPED
        transfer.memo == DsmCustodyService.memoFor(shipment)
        transfer.originStateHash.length() == 52
        transfer.dateEstablished == null

        and: 'OpenBoxes recorded the outbound transaction after it'
        shipment.hasShipped()
        Set<Transaction> outgoing = Shipment.get(shipmentId).outgoingTransactions
        outgoing.size() == 1
        outgoing.first().transactionType.id == Constants.TRANSFER_OUT_TRANSACTION_TYPE_ID
        outgoing.first().transactionEntries*.quantity == [UNITS_SHIPPED]

        and: 'A\'s account spent the units, and B\'s account takes them in'
        hostA.balance(ticker) == STOCK_AT_A - UNITS_SHIPPED
        hostB.awaitBalance(ticker, UNITS_SHIPPED) == UNITS_SHIPPED
    }

    @Transactional
    void 'completing the receipt at B writes the TRANSFER_IN only once B\'s account holds the transfer as accepted'() {
        given: 'B starts a receipt of the shipment and records every line as received'
        Location warehouseB = Location.get(warehouseBId)
        Shipment shipment = Shipment.get(shipmentId)
        Product product = Product.get(productId)
        actAt(warehouseB)
        Receipt receipt = Receipt.get(receiptV2Service.startReceipt(shipment.id).id)
        receipt.receiptItems.each { ReceiptItem item -> item.quantityReceived = item.quantityShipped }
        receipt.save(flush: true, failOnError: true)

        when:
        receiptV2Service.completeReceipt(new ReceiptCompleteRequestCommand(receipt: receipt, itemsToComplete: []))

        then: 'the transfer is established from B\'s own history, and the inbound transaction follows it'
        DsmCustodyTransfer transfer = DsmCustodyTransfer.findByShipmentAndProduct(shipment, product)
        transfer.dateEstablished != null
        transfer.destinationTransferId
        Set<Transaction> incoming = Shipment.get(shipmentId).incomingTransactions
        incoming.size() == 1
        incoming.first().transactionType.id == Constants.TRANSFER_IN_TRANSACTION_TYPE_ID
        incoming.first().transactionEntries*.quantity == [UNITS_SHIPPED]
    }

    @Transactional
    void 'A establishes B\'s custody as a verified holdings proof over their Connect session, through the API'() {
        given:
        DsmCustodyTransfer transfer = DsmCustodyTransfer.findByShipmentAndProduct(Shipment.get(shipmentId), Product.get(productId))

        when:
        Map proven = api("proveHoldings", [id: transfer.id])

        then: 'the proof recomputed B\'s root: B holds exactly the shipped units'
        proven.provenAmount == UNITS_SHIPPED
        proven.provenPosition > 0
    }

    @Transactional
    void 'a stock transfer between the two warehouses moves the units account to account before either transaction is written'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Location warehouseB = Location.get(warehouseBId)
        Product product = Product.get(productId)
        actAt(warehouseA)
        TransferStockCommand command = new TransferStockCommand(
                location: warehouseA,
                otherLocation: warehouseB,
                inventoryItem: inventoryItem(product),
                quantity: UNITS_TRANSFERRED,
                transferOut: Boolean.TRUE,
        )

        when:
        Transaction transaction = inventoryService.transferStock(command)
        // The session flushes on commit here; the lookup below needs the mirrored transaction and local transfer written.
        LocalTransfer.withSession { session -> session.flush() }

        then: 'B\'s account held the units before the mirrored transactions were saved'
        !transaction.hasErrors()
        LocalTransfer.findBySourceTransaction(transaction) != null
        DsmCustodyTransfer moved = DsmCustodyTransfer.findByMemoAndProduct(
                DsmCustodyService.MEMO_TRANSACTION + transaction.transactionNumber, product)
        moved.kind == DsmCustodyTransfer.KIND_STOCK_TRANSFER
        moved.quantity == UNITS_TRANSFERRED
        moved.dateEstablished != null
        hostA.balance(ticker) == STOCK_AT_A - UNITS_SHIPPED - UNITS_TRANSFERRED
        hostB.balance(ticker) == UNITS_SHIPPED + UNITS_TRANSFERRED
    }

    @Transactional
    void 'consumption at B burns the units before the debit is written'() {
        given:
        Location warehouseB = Location.get(warehouseBId)
        Product product = Product.get(productId)
        actAt(warehouseB)
        Transaction consumption = new Transaction(
                transactionType: TransactionType.get(Constants.CONSUMPTION_TRANSACTION_TYPE_ID),
                inventory: warehouseB.inventory,
                transactionDate: new Date(),
                comment: "used on the ward",
        )
        consumption.addToTransactionEntries(new TransactionEntry(
                inventoryItem: inventoryItem(product), product: product, quantity: UNITS_CONSUMED))

        when: 'the debit is backed as the controller backs it, then saved'
        dsmCustodyService.backStockChange(consumption)
        consumption.save(flush: true, failOnError: true)

        then:
        DsmCustodyTransfer burned = DsmCustodyTransfer.findByMemoAndProduct(
                DsmCustodyService.MEMO_TRANSACTION + consumption.transactionNumber, product)
        burned.kind == DsmCustodyTransfer.KIND_BURN
        burned.quantity == UNITS_CONSUMED
        burned.origin.location.id == warehouseBId
        hostB.balance(ticker) == UNITS_SHIPPED + UNITS_TRANSFERRED - UNITS_CONSUMED
    }

    @Transactional
    void 'a shipment from a clinic that is not on DSM is sent as OpenBoxes always sent it'() {
        given:
        Location clinic = Location.get(clinicId)
        Location warehouseB = Location.get(warehouseBId)
        Product product = Product.get(productId)
        recordStock(clinic, product, UNITS_FROM_SUPPLIER)
        Shipment shipment = shipment(clinic, warehouseB, product, UNITS_FROM_SUPPLIER)
        supplierShipmentId = shipment.id

        when:
        shipmentService.sendShipment(shipment, "from the clinic", admin(), clinic, new Date())

        then: 'nothing moved on DSM: the clinic has no account, and the units are not in custody yet'
        shipment.hasShipped()
        DsmCustodyTransfer.findByShipmentAndProduct(shipment, product) == null
        DsmCustodyTransfer.countByKind(DsmCustodyTransfer.KIND_REGISTRY_CREDIT) == 1
    }

    @Transactional
    void 'receiving it at B takes the units into custody from the registry before the TRANSFER_IN is written'() {
        given:
        Location warehouseB = Location.get(warehouseBId)
        Shipment shipment = Shipment.get(supplierShipmentId)
        Product product = Product.get(productId)
        actAt(warehouseB)
        Receipt receipt = Receipt.get(receiptV2Service.startReceipt(shipment.id).id)
        receipt.receiptItems.each { ReceiptItem item -> item.quantityReceived = item.quantityShipped }
        receipt.save(flush: true, failOnError: true)

        when:
        receiptV2Service.completeReceipt(new ReceiptCompleteRequestCommand(receipt: receipt, itemsToComplete: []))

        then:
        DsmCustodyTransfer credit = DsmCustodyTransfer.findByMemoAndProduct(
                DsmCustodyService.MEMO_RECEIPT + receipt.receiptNumber, product)
        credit.kind == DsmCustodyTransfer.KIND_REGISTRY_CREDIT
        credit.quantity == UNITS_FROM_SUPPLIER
        credit.dateEstablished != null
        Shipment.get(supplierShipmentId).incomingTransactions.size() == 1
        hostB.balance(ticker) == UNITS_SHIPPED + UNITS_TRANSFERRED - UNITS_CONSUMED + UNITS_FROM_SUPPLIER
        hostRegistry.balance(ticker) == SUPPLY - STOCK_AT_A - UNITS_FROM_SUPPLIER
    }

    @Transactional
    void 'DSM refuses to move more units than A\'s account holds, and records nothing'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Location warehouseB = Location.get(warehouseBId)
        Product product = Product.get(productId)
        actAt(warehouseA)
        Shipment shipment = shipment(warehouseA, warehouseB, product, SUPPLY as int)
        long heldBefore = hostA.balance(ticker)

        when:
        dsmCustodyService.releaseOnSend(shipment)

        then: 'the send is refused with DSM\'s reason'
        DsmCustodyException refused = thrown()
        refused.message.contains("DSM refused")

        and: 'nothing moved and nothing was recorded'
        DsmCustodyTransfer.findByShipmentAndProduct(shipment, product) == null
        hostA.balance(ticker) == heldBefore
    }

    @Transactional
    void 'a shipment from a DSM warehouse to a location without a DSM account is refused'() {
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
        refused.message.contains("has no DSM account")
        !Shipment.get(shipment.id).hasShipped()
        !Shipment.get(shipment.id).outgoingTransactions
    }

    @Transactional
    void 'a second shipment of 5 units is sent and taken in at B'() {
        given:
        Location warehouseA = Location.get(warehouseAId)
        Location warehouseB = Location.get(warehouseBId)
        Product product = Product.get(productId)
        actAt(warehouseA)
        Shipment shipment = shipment(warehouseA, warehouseB, product, UNITS_SHIPPED_AGAIN)
        secondShipmentId = shipment.id
        long atB = hostB.balance(ticker)

        when:
        shipmentService.sendShipment(shipment, "second", admin(), warehouseA, new Date())

        then:
        shipment.hasShipped()
        hostB.awaitBalance(ticker, atB + UNITS_SHIPPED_AGAIN) == atB + UNITS_SHIPPED_AGAIN
    }

    @Transactional
    void 'a receipt that records more units than DSM moved is refused'() {
        given: 'B records one unit more than the 5 that DSM moved'
        Location warehouseB = Location.get(warehouseBId)
        Shipment shipment = Shipment.get(secondShipmentId)
        actAt(warehouseB)
        Receipt receipt = Receipt.get(receiptV2Service.startReceipt(shipment.id).id)
        receipt.receiptItems.each { ReceiptItem item -> item.quantityReceived = item.quantityShipped + 1 }
        receipt.save(flush: true, failOnError: true)

        when:
        receiptV2Service.completeReceipt(new ReceiptCompleteRequestCommand(receipt: receipt, itemsToComplete: []))

        then:
        DsmCustodyException refused = thrown()
        refused.message.contains("cannot receive units it has no custody of")
        !Shipment.get(shipment.id).incomingTransactions
    }

    @Transactional
    void 'the API lists what the accounts established'() {
        when:
        Map accounts = apiGet("accounts")
        Map transfers = apiGet("transfers")

        then:
        accounts.data.size() == 3
        accounts.data.count { it.registry } == 1
        transfers.data*.kind.toSet() == [DsmCustodyTransfer.KIND_REGISTRY_CREDIT, DsmCustodyTransfer.KIND_SHIPMENT,
                                         DsmCustodyTransfer.KIND_STOCK_TRANSFER, DsmCustodyTransfer.KIND_BURN].toSet()
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

    private Map apiGet(String action) {
        bindWebRequest()
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
