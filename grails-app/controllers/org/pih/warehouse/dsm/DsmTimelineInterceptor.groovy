package org.pih.warehouse.dsm

/** Records OpenBoxes' own writes for the overlay's WEB2 side. It never changes a request. */
class DsmTimelineInterceptor {

    private static final Map<String, String> LABELS = [
            "stockMovementApi"   : "Stock movement updated in this organization's database",
            "partialReceivingApi": "Receipt recorded in this organization's database",
            "recordStockApi"     : "Stock recorded in this organization's database",
            "inventoryItem"      : "Inventory updated in this organization's database",
            "stockTransferApi"   : "Stock transfer updated in this organization's database",
    ]

    DsmTimelineInterceptor() {
        matchAll().except(uri: "/static/**").except(uri: "/health").except(uri: "/info")
    }

    boolean before() {
        return true
    }

    boolean after() {
        if (request.method == "POST" && LABELS.containsKey(controllerName) && response.status < 400) {
            DsmTimeline.record(LABELS[controllerName], session.user?.name?.toString() ?: "")
        }
        return true
    }
}
