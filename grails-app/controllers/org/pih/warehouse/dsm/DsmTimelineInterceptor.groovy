package org.pih.warehouse.dsm

/** Records OpenBoxes' own writes for the overlay's WEB2 side. It never changes a request. */
class DsmTimelineInterceptor {

    /** By "controller.action", or by controller alone for every POST to it. */
    private static final Map<String, String> LABELS = [
            "stockMovementApi"           : "Stock movement updated in this organization's database",
            "partialReceivingApi"        : "Receipt recorded in this organization's database",
            "recordStockApi"             : "Stock recorded in this organization's database",
            "inventoryItem"              : "Inventory updated in this organization's database",
            "stockTransferApi"           : "Stock transfer updated in this organization's database",
            // the door screen on a phone
            "dsmPhone.send"              : "Shipment created, picked and sent in this organization's database",
            "dsmPhone.dispatch"          : "Hand-over to the carrier recorded in this organization's database",
            "dsmPhone.handover"          : "Hand-over request recorded in this organization's database",
            "dsmPhone.takeIn"            : "Inbound shipment created in this organization's database",
            "dsmPhone.receive"           : "Receipt recorded in this organization's database",
            // the DSM page
            "dsmWallet.assignSeat"       : "User created and seat recorded in this organization's database",
            "dsmWallet.dispatch"         : "Hand-over to the carrier recorded in this organization's database",
            "dsmWallet.correctBooks"     : "Adjustment transaction recorded in this organization's database",
            "dsmCustodyApi.requestAdmission": "Goods-received note recorded, awaiting the registry",
            "dsmCustodyApi.proveHoldings": "Partner proof requested and recorded",
            "dsmCustodyApi.inbound"      : "Inbound shipment created in this organization's database",
    ]

    DsmTimelineInterceptor() {
        matchAll().except(uri: "/static/**").except(uri: "/health").except(uri: "/info")
    }

    boolean before() {
        return true
    }

    boolean after() {
        if (request.method == "POST" && response.status < 400) {
            String label = LABELS["${controllerName}.${actionName}".toString()] ?: LABELS[controllerName]
            if (label) {
                DsmTimeline.record(label, session.user?.name?.toString() ?: "")
            }
        }
        return true
    }
}
