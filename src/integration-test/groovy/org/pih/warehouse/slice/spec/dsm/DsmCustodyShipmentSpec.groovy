package org.pih.warehouse.slice.spec.dsm

import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import org.grails.web.servlet.mvc.GrailsWebRequest
import grails.util.GrailsWebMockUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.context.request.RequestContextHolder
import spock.lang.Shared
import spock.lang.Stepwise

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
import org.pih.warehouse.dsm.DsmCustodyException
import org.pih.warehouse.dsm.DsmCustodyService
import org.pih.warehouse.dsm.DsmCustodyTransfer
import org.pih.warehouse.dsm.DsmProductToken
import org.pih.warehouse.dsm.DsmWarehouseAccount
import org.pih.warehouse.dsm.DsmWarehouseLink
import org.pih.warehouse.inventory.InventoryItem
import org.pih.warehouse.inventory.InventoryService
import org.pih.warehouse.inventory.ProductAvailabilityService
import org.pih.warehouse.inventory.RecordInventoryCommand
import org.pih.warehouse.inventory.Transaction
import org.pih.warehouse.product.Category
import org.pih.warehouse.product.Product
import org.pih.warehouse.receiving.Receipt
import org.pih.warehouse.receiving.ReceiptCompleteRequestCommand
import org.pih.warehouse.receiving.ReceiptDto
import org.pih.warehouse.receiving.ReceiptItem
import org.pih.warehouse.shipping.Shipment
import org.pih.warehouse.shipping.ShipmentIdentifierService
import org.pih.warehouse.shipping.ShipmentItem
import org.pih.warehouse.shipping.ShipmentService

/**
 * The two-warehouse demo, end to end, against DSM's own code: a storage set of real storage nodes on Postgres,
 * two dsm-app-host processes (one per warehouse), and this application's own services. One product, one
 * shipment from warehouse A to warehouse B, received at B; then B's custody proven to A over their Connect
 * session. Nothing stands in for a DSM component (docs/plans/2026-11-01-dsm-custody-integration.md).
 *
 * Environment: DSM_FLEET_BIN, DSM_APP_HOST_BIN, DSM_TEST_DATABASE_URL (see DsmTestProcess). The database is the
 * Testcontainers MySQL every OpenBoxes integration test uses; the DSM state is the two fresh accounts' own.
 */
@Stepwise
class DsmCustodyShipmentSpec extends IntegrationSpec {

    static final int UNITS_ISSUED = 100
    static final int UNITS_SHIPPED = 10
    static final int UNITS_RETURNED_LATER = 5

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
     * templates that read the signed-in warehouse from the web session, and a service-level test has no session
     * unless it binds one. Bound for every feature, released after it.
     */
    GrailsWebRequest webRequest

    @Shared
    File root

    @Shared
    DsmTestFleet fleet

    @Shared
    DsmTestHost hostA

    @Shared
    DsmTestHost hostB

    @Shared
    String warehouseAId

    @Shared
    String warehouseBId

    @Shared
    String clinicId

    @Shared
    String productId

    @Shared
    String shipmentId

    @Shared
    String secondShipmentId

    @Shared
    String ticker

    RandomUtil randomUtil = new RandomUtil()

    void setupSpec() {
        root = new File("build/dsm-test/${System.currentTimeMillis()}")
        fleet = DsmTestFleet.start(root)
        hostA = DsmTestHost.start("warehouse-a", root, fleet.envConfig)
        hostB = DsmTestHost.start("warehouse-b", root, fleet.envConfig)
        // ERA for the fee a token's creation burns, and for what the accounts' admissions cost.
        hostA.claimFaucet()
        hostB.claimFaucet()
    }

    void setup() {
        webRequest = GrailsWebMockUtil.bindMockWebRequest(grailsApplication.mainContext)
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
        fleet?.close()
    }

    @Transactional
    void 'two warehouses get DSM accounts, one issues the product token, and they connect'() {
        given: 'two depots with inventories, a clinic without a DSM account, and a product with a ticker-shaped code'
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

        when: 'each warehouse is given its account'
        DsmWarehouseAccount accountA = dsmCustodyService.registerAccount(warehouseA, hostA.ingressOrigin, hostA.dataDir.absolutePath)
        DsmWarehouseAccount accountB = dsmCustodyService.registerAccount(warehouseB, hostB.ingressOrigin, hostB.dataDir.absolutePath)

        then: 'each account is the host it names'
        accountA.deviceId == org.pih.warehouse.dsm.Base32Crockford.encode(hostA.deviceId())
        accountB.deviceId == org.pih.warehouse.dsm.Base32Crockford.encode(hostB.deviceId())
        accountA.deviceId != accountB.deviceId

        when: 'warehouse A issues the product token with the units it holds'
        DsmProductToken token = dsmCustodyService.issueProductToken(warehouseA, product, UNITS_ISSUED)
        ticker = token.ticker

        then: 'the whole supply is on A, and B holds none'
        token.policyAnchor.length() == 52
        hostA.balance(ticker) == UNITS_ISSUED
        hostB.balance(ticker) == null

        when: 'the two connect: A offers, B reads the code, approves, and roots the anchor'
        DsmWarehouseLink link = dsmCustodyService.pairWarehouses(warehouseA, warehouseB)

        then:
        link.sessionId.length() == 52
        link.offering.id == accountA.id
        link.accepting.id == accountB.id
    }

    @Transactional
    void 'sending the shipment moves the units on DSM before OpenBoxes records the TRANSFER_OUT'() {
        given: 'A holds the stock in OpenBoxes too, and ships 10 units to B'
        Location warehouseA = Location.get(warehouseAId)
        Location warehouseB = Location.get(warehouseBId)
        Product product = Product.get(productId)
        recordStock(warehouseA, product, UNITS_ISSUED)
        Shipment shipment = shipment(warehouseA, warehouseB, product, UNITS_SHIPPED)
        shipmentId = shipment.id

        when:
        shipmentService.sendShipment(shipment, "sent from A to B", admin(), warehouseA, new Date())

        then: 'the custody transfer is recorded from the origin account\'s own send'
        DsmCustodyTransfer transfer = DsmCustodyTransfer.findByShipmentAndProduct(shipment, product)
        transfer != null
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
        hostA.balance(ticker) == UNITS_ISSUED - UNITS_SHIPPED
        hostB.awaitBalance(ticker, UNITS_SHIPPED) == UNITS_SHIPPED
    }

    @Transactional
    void 'completing the receipt at B writes the TRANSFER_IN only once B\'s account holds the transfer as accepted'() {
        given: 'B starts a receipt of the shipment and records every line as received'
        Location warehouseB = Location.get(warehouseBId)
        Shipment shipment = Shipment.get(shipmentId)
        Product product = Product.get(productId)
        actAt(warehouseB)
        ReceiptDto started = receiptV2Service.startReceipt(shipment.id)
        Receipt receipt = Receipt.get(started.id)
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
    void 'A establishes B\'s custody as a verified holdings proof over their Connect session'() {
        given:
        DsmCustodyTransfer transfer = DsmCustodyTransfer.findByShipmentAndProduct(Shipment.get(shipmentId), Product.get(productId))

        when:
        DsmCustodyTransfer proven = dsmCustodyService.establishHoldingsProof(transfer)

        then: 'the proof recomputed B\'s root: B holds exactly the shipped units'
        proven.provenAmount == UNITS_SHIPPED
        proven.provenPosition > 0
    }

    @Transactional
    void 'DSM refuses a shipment of more units than A\'s account holds, and OpenBoxes records nothing'() {
        given: 'OpenBoxes stock at A exceeds what A holds on DSM'
        Location warehouseA = Location.get(warehouseAId)
        Location warehouseB = Location.get(warehouseBId)
        Product product = Product.get(productId)
        recordStock(warehouseA, product, UNITS_ISSUED * 10)
        Shipment shipment = shipment(warehouseA, warehouseB, product, UNITS_ISSUED * 2)
        long heldBefore = hostA.balance(ticker)

        when:
        shipmentService.sendShipment(shipment, "too many", admin(), warehouseA, new Date())

        then: 'the send is refused with DSM\'s reason'
        DsmCustodyException refused = thrown()
        refused.message.contains("DSM refused")

        and: 'nothing moved and nothing was recorded'
        DsmCustodyTransfer.findByShipmentAndProduct(shipment, product) == null
        !Shipment.get(shipment.id).hasShipped()
        !Shipment.get(shipment.id).outgoingTransactions
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
        Shipment shipment = shipment(warehouseA, warehouseB, product, UNITS_RETURNED_LATER)
        secondShipmentId = shipment.id

        when:
        shipmentService.sendShipment(shipment, "second", admin(), warehouseA, new Date())

        then:
        shipment.hasShipped()
        hostB.awaitBalance(ticker, UNITS_SHIPPED + UNITS_RETURNED_LATER) == UNITS_SHIPPED + UNITS_RETURNED_LATER
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

    // ------------------------------------------------------------------ fixtures

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

    private Shipment shipment(Location origin, Location destination, Product product, int quantity) {
        Shipment shipment = new ShipmentTestBuilder().origin(origin).destination(destination).build(true)
        shipment.shipmentNumber = shipmentIdentifierService.generate(shipment)
        InventoryItem inventoryItem = inventoryService.findInventoryItemByProductAndLotNumber(product, null)
                ?: new InventoryItem(product: product).save(flush: true, failOnError: true)
        shipment.addToShipmentItems(new ShipmentItem(product: product, quantity: quantity, inventoryItem: inventoryItem))
        shipment.save(flush: true, failOnError: true)
        return shipment
    }
}
