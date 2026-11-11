package org.pih.warehouse.dsm

import grails.converters.JSON
import grails.validation.ValidationException
import org.grails.web.json.JSONObject
import org.pih.warehouse.api.PartialReceipt
import org.pih.warehouse.api.PartialReceiptItem
import org.pih.warehouse.api.StockMovement
import org.pih.warehouse.api.StockMovementItem
import org.pih.warehouse.core.Constants
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User
import org.pih.warehouse.product.ProductAvailability
import org.pih.warehouse.inventory.StockMovementStatusCode
import org.pih.warehouse.product.Product
import org.pih.warehouse.requisition.Requisition
import org.pih.warehouse.requisition.RequisitionType
import org.pih.warehouse.shipping.Shipment
import org.pih.warehouse.shipping.ShipmentType
import org.springframework.http.HttpStatus

/**
 * The phone screen: what a person signed in with their wallet does with the goods in front of them. At the sending
 * warehouse they send from the stock on hand and hand the shipment to a carrier. At the receiving warehouse the carrier hands custody over from
 * their own phone, and the goods are taken in and received, line by line as counted. Every step is the same DSM
 * movement the desktop screen makes; the organization's setup stays on the desktop DSM screen.
 */
class DsmPhoneController {

    DsmCustodyService dsmCustodyService
    DsmCarriageService dsmCarriageService
    DsmWalletService dsmWalletService
    def receiptService
    def stockMovementService

    def index() {
        [location: Location.get(session.warehouse?.id)]
    }

    /** Everything the screen shows, read fresh: the goods leaving, arriving, waiting to be received, and carried. */
    def state() {
        attempt {
            DsmWallet me = mine()
            [
                    location : Location.get(session.warehouse?.id)?.name,
                    user     : session.userName,
                    me       : me ? [id: me.id, label: me.label, deviceId: me.deviceId.take(8)] : null,
                    wallets  : DsmWallet.list(sort: "dateCreated").collect { DsmWallet w -> [id: w.id, label: w.label] },
                    arriving : arriving(),
                    receiving: receiving(),
                    leaving  : leaving(),
                    carrying : me ? carrying(me) : [],
                    stock    : stock(),
                    partners : partners(),
            ]
        }
    }

    /**
     * Send from the stock on hand: a stock movement to a connected DSM warehouse, picked from what is available, and
     * shipped now. Shipping is what moves the custody on DSM; if DSM refuses, nothing leaves.
     */
    def send() {
        attempt {
            Location origin = Location.get(session.warehouse?.id)
            Location destination = Location.get(params.destinationId)
            if (!destination || destination.id == origin?.id) {
                throw new DsmCustodyException("choose the warehouse this goes to")
            }
            if (!partners().any { Map p -> p.id == destination.id }) {
                throw new DsmCustodyException("${destination.name} is not a DSM warehouse this one is connected to")
            }
            User user = User.get(session.user.id)
            Date now = new Date()
            List<StockMovementItem> lines = []
            int order = 100
            stock().each { Map row ->
                String counted = params["quantity.${row.id}"]
                int quantity = counted ? counted as Integer : 0
                if (quantity > 0) {
                    if (quantity > (row.available as Long)) {
                        throw new DsmCustodyException("only ${row.available} ${row.code} are available to send")
                    }
                    lines << new StockMovementItem(product: Product.get(row.id), quantityRequested: quantity, sortOrder: order)
                    order += 100
                }
            }
            if (!lines) {
                throw new DsmCustodyException("count what goes: every line is at 0")
            }
            StockMovement movement = new StockMovement(origin: origin, destination: destination, requestedBy: user,
                    dateRequested: now, requestType: RequisitionType.STOCK, description: params.note?.trim() ?: null,
                    lineItems: lines)
            StockMovement created = stockMovementService.createStockMovement(movement)
            [StockMovementStatusCode.PICKING, StockMovementStatusCode.PICKED, StockMovementStatusCode.CHECKING].each {
                StockMovementStatusCode step ->
                Map change = [status: step.name()]
                if (step == StockMovementStatusCode.PICKING) {
                    change.createPicklist = true
                }
                stockMovementService.transitionStockMovement(stockMovementService.getStockMovement(created.id), new JSONObject(change))
            }
            StockMovement ready = stockMovementService.getStockMovement(created.id)
            ready.shipmentType = ShipmentType.get(Constants.DEFAULT_SHIPMENT_TYPE_ID)
            ready.dateShipped = now
            ready.expectedDeliveryDate = now
            ready.driverName = user.name
            stockMovementService.updateShipment(ready)
            // the shipment was made in this same request: the requisition is re-read so it finds it
            Requisition.get(created.id).refresh()
            stockMovementService.transitionStockMovement(stockMovementService.getStockMovement(created.id),
                    new JSONObject([status: StockMovementStatusCode.DISPATCHED.name()]))
            Shipment shipment = stockMovementService.getStockMovement(created.id).shipment
            [number: shipment.shipmentNumber, units: lines.sum { StockMovementItem l -> l.quantityRequested }, to: destination.name]
        }
    }

    /** Hand a sent shipment to a carrier: the account sends its custody units to that wallet. */
    def dispatch() {
        attempt {
            DsmCarriage carriage = dsmCarriageService.dispatch(params.memo, wallet(params.walletId))
            [units: carriage.units, ticker: carriage.token.ticker, wallet: carriage.walletDeviceId.take(8)]
        }
    }

    /** Ask a carrier's wallet to hand a partner's shipment over to this organization: this person's own by default. */
    def handover() {
        attempt {
            DsmWarehouseAccount home = dsmWalletService.homeAccount()
            DsmConnection peer = DsmConnection.findByAccountAndPeerDeviceId(home, params.peerDeviceId)
            if (!peer) {
                throw new DsmCustodyException("no partner ${params.peerDeviceId} is connected to ${home}")
            }
            DsmWallet carrier = params.walletId ? wallet(params.walletId) : mine()
            if (!carrier) {
                throw new DsmCustodyException("sign in with your wallet to hand over from this phone")
            }
            [seq: dsmCarriageService.askHandover(peer, params.memo, (params.units ?: "0") as int, carrier)]
        }
    }

    /** Take a partner's shipment in at the door: this organization's own record of the custody its account holds. */
    def takeIn() {
        attempt {
            Shipment shipment = dsmCustodyService.establishInbound(params.peerDeviceId, params.memo)
            [shipmentId: shipment.id, number: shipment.shipmentNumber]
        }
    }

    /**
     * Receive a shipment taken in at the door, each line as counted (`quantity.<shipment item id>`, the rest of the
     * line when absent), through the ordinary receipt: DSM decides whether the warehouse holds the units first.
     */
    def receive() {
        attempt {
            // As the desktop does it in two steps: the count becomes a pending receipt, and that receipt is completed.
            PartialReceipt count = receiptService.getPartialReceipt(params.shipmentId, "1")
            count.recipient = User.get(session.user.id)
            count.dateDelivered = new Date()
            count.partialReceiptItems.each { PartialReceiptItem item ->
                String counted = params["quantity.${item.shipmentItem.id}"]
                item.quantityReceiving = counted ? counted as Integer : left(item)
                item.shouldSave = item.quantityReceiving > 0
            }
            receiptService.savePartialReceipt(count, false)
            PartialReceipt receipt = receiptService.getPartialReceipt(params.shipmentId, "2")
            receipt.recipient = count.recipient
            receipt.dateDelivered = count.dateDelivered
            // The desktop marks every counted line for this step; DSM checks and records only the lines so marked.
            receipt.partialReceiptItems.each { PartialReceiptItem item -> item.shouldSave = (item.quantityReceiving ?: 0) > 0 }
            receiptService.saveAndCompletePartialReceipt(receipt)
            [number: receipt.shipment.shipmentNumber]
        }
    }

    // ------------------------------------------------------------------ what the screen shows

    /** Partners' shipments whose units this organization's account holds and has not taken in, and how each stands at the door. */
    private List<Map> arriving() {
        dsmCustodyService.inboundCustody().collect { Map document ->
            Map carriage = document.carriage as Map
            [memo        : document.memo, number: number(document.memo as String), from: document.originName,
             peerDeviceId: document.peerDeviceId, units: document.units,
             lines       : document.lines.collect { Map line ->
                 [code: line.productCode, name: Product.get(line.productId as String)?.name, quantity: line.quantity]
             },
             carrier     : carriage.required ? [handedOver: carriage.handedOver, by: carriage.by, arrived: carriage.arrived] : null]
        }
    }

    /** Shipments taken in at the door with units still to receive, with what is left of each line. */
    private List<Map> receiving() {
        List<DsmCustodyTransfer> inbound = DsmCustodyTransfer.findAllByKind(DsmCustodyTransfer.KIND_INBOUND,
                [sort: "dateCreated", order: "desc", max: 60])
        inbound.findAll { DsmCustodyTransfer t -> t.shipment && t.quantityReceived < t.quantity }
                .groupBy { DsmCustodyTransfer t -> t.shipment }
                .collect { Shipment shipment, List<DsmCustodyTransfer> lines ->
                    PartialReceipt receipt = receiptService.getPartialReceipt(shipment.id, "1")
                    List<Map> items = receipt.partialReceiptItems.findAll { PartialReceiptItem i -> left(i) > 0 }
                            .collect { PartialReceiptItem i ->
                                [id: i.shipmentItem.id, code: i.product.productCode, name: i.product.name,
                                 remaining: left(i), lot: i.lotNumber]
                            }
                    items ? [shipmentId: shipment.id, number: shipment.shipmentNumber, from: shipment.origin.name,
                             document: number(lines[0].memo), items: items] : null
                }.findAll()
    }

    /** Shipments this organization sent to partners, newest first, and who carries each. */
    private List<Map> leaving() {
        List<DsmCustodyTransfer> sent = DsmCustodyTransfer.findAllByKind(DsmCustodyTransfer.KIND_SHIPMENT,
                [sort: "dateCreated", order: "desc", max: 60])
        sent.groupBy { DsmCustodyTransfer t -> t.memo }.collect { String memo, List<DsmCustodyTransfer> lines ->
            DsmCarriage carriage = DsmCarriage.findByKindAndMemo(DsmCarriage.KIND_DISPATCHED, memo)
            [memo   : memo, number: number(memo), to: lines[0].shipment?.destination?.name ?: lines[0].peerDeviceId.take(8),
             units  : lines.sum { DsmCustodyTransfer t -> t.quantity }, at: lines[0].dateCreated,
             lines  : lines.collect { DsmCustodyTransfer t -> [code: t.product.productCode, name: t.product.name, quantity: t.quantity] },
             carrier: carriage ? [label: labelOf(carriage.walletDeviceId), units: carriage.units, ticker: carriage.token.ticker,
                                  at: carriage.dateCreated] : null]
        }.take(12)
    }

    /** What is on hand here and available to send, by product. */
    private List<Map> stock() {
        Location here = Location.get(session.warehouse?.id)
        if (!here) {
            return []
        }
        List rows = ProductAvailability.executeQuery("""
                select pa.product, sum(case when pa.quantityAvailableToPromise > 0 then pa.quantityAvailableToPromise else 0 end)
                from ProductAvailability pa where pa.location = :here group by pa.product""", [here: here])
        rows.findAll { Object[] row -> (row[1] as Long) > 0 }
                .collect { Object[] row -> Product product = row[0] as Product
                    [id: product.id, code: product.productCode, name: product.name, available: row[1] as Long] }
                .sort { Map a, Map b -> (a.name as String).compareToIgnoreCase(b.name as String) }
                .take(80)
    }

    /** The DSM warehouses this one is connected to: the only places custody can be sent. */
    private List<Map> partners() {
        DsmWarehouseAccount home = dsmWalletService.homeAccount()
        if (!home) {
            return []
        }
        DsmConnection.findAllByAccount(home).findAll { DsmConnection c -> c.peerLocation }
                .collect { DsmConnection c -> [id: c.peerLocation.id, name: c.peerLocation.name] }
                .unique { Map p -> p.id }
                .sort { Map p -> p.name }
    }

    /** What this organization handed to the signed-in wallet to carry. */
    private List<Map> carrying(DsmWallet me) {
        DsmCarriage.findAllByKindAndWalletDeviceId(DsmCarriage.KIND_DISPATCHED, me.deviceId,
                [sort: "dateCreated", order: "desc", max: 20]).collect { DsmCarriage c ->
            Shipment shipment = DsmCustodyTransfer.findAllByKindAndMemo(DsmCustodyTransfer.KIND_SHIPMENT, c.memo)[0]?.shipment
            [number: number(c.memo), to: shipment?.destination?.name, units: c.units, ticker: c.token.ticker, at: c.dateCreated]
        }
    }

    // ------------------------------------------------------------------ helpers

    /** What a line still has to receive, counting receipts that went through, not a count left pending. */
    private static int left(PartialReceiptItem item) {
        return (item.quantityShipped ?: 0) - item.quantityReceived - item.quantityCanceled
    }

    private static String number(String memo) {
        return memo.startsWith(DsmCustodyService.MEMO_SHIPMENT) ? memo.substring(DsmCustodyService.MEMO_SHIPMENT.length()) : memo
    }

    private static String labelOf(String deviceId) {
        return DsmWallet.findByDeviceId(deviceId)?.label ?: deviceId.take(8)
    }

    private static DsmWallet wallet(String id) {
        DsmWallet wallet = id ? DsmWallet.get(id) : null
        if (!wallet) {
            throw new DsmCustodyException("no wallet ${id} is connected here")
        }
        return wallet
    }

    private DsmWallet mine() {
        return session.dsmDevice ? DsmWallet.findByDeviceId(session.dsmDevice as String) : null
    }

    private void attempt(Closure action) {
        try {
            render([data: action()] as JSON)
        } catch (DsmCustodyException | DsmHostException | ValidationException | IllegalArgumentException | IllegalStateException refused) {
            response.status = HttpStatus.BAD_REQUEST.value()
            render([errorMessage: refused.message] as JSON)
        }
    }
}
