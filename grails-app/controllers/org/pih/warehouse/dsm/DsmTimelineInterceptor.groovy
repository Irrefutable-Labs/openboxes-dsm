package org.pih.warehouse.dsm

/**
 * Records what OpenBoxes itself handled, for the overlay's WEB2 side: every page a person opened and every write they
 * made, in plain words, so what DSM does can be seen against everything else. Only the overlay's own polling, the
 * screens' refreshes and static files are left out. It never changes a request.
 */
class DsmTimelineInterceptor {

    /** Plain words for the requests the story makes; anything else is named from its controller and action. */
    private static final Map<String, String> LABELS = [
            "auth.login"                    : "Password login page: refused, there are no passwords",
            "auth.logout"                   : "Signed out",
            "dsmWalletSignIn.index"         : "Sign-in page opened",
            "dsmWalletSignIn.ask"           : "Username entered; the phone is asked",
            "dashboard.index"               : "Dashboard opened",
            "inventory.browse"              : "Browse inventory",
            "inventoryItem.showStockCard"   : "Stock card opened",
            "inventoryItem.showRecordInventory": "Record stock page opened",
            "inventoryItem.saveRecordInventory": "Stock count saved: a transaction in this organization's database",
            "dsmWallet.index"               : "DSM page opened",
            "dsmWallet.assignSeat"          : "User created and seat recorded in this organization's database",
            "dsmWallet.dispatch"            : "Hand-over to the carrier recorded in this organization's database",
            "dsmWallet.checkNow"            : "Books compared with DSM",
            "dsmWallet.correctBooks"        : "Adjustment transaction recorded in this organization's database",
            "dsmCustodyApi.requestAdmission": "Goods-received note recorded, awaiting the registry",
            "dsmCustodyApi.proveHoldings"   : "Partner proof requested and recorded",
            "dsmCustodyApi.establishInbound": "Inbound shipment created in this organization's database",
            "dsmPhone.index"                : "Door screen opened on a phone",
            "dsmPhone.send"                 : "Shipment created, picked and sent in this organization's database",
            "dsmPhone.dispatch"             : "Hand-over to the carrier recorded in this organization's database",
            "dsmPhone.handover"             : "Hand-over request recorded in this organization's database",
            "dsmPhone.takeIn"               : "Inbound shipment created in this organization's database",
            "dsmPhone.receive"              : "Receipt recorded in this organization's database",
            "stockMovementApi"              : "Stock movement updated in this organization's database",
            "partialReceivingApi"           : "Receipt recorded in this organization's database",
            "recordStockApi"                : "Stock recorded in this organization's database",
            "stockTransferApi"              : "Stock transfer updated in this organization's database",
    ]

    /** Polling and refreshes: the overlay, the sign-in page waiting, the door screen and the DSM page re-reading. */
    private static final Set<String> QUIET = [
            "dsmWallet.state", "dsmWallet.timeline", "dsmWallet.reconcile", "dsmWallet.answered", "dsmWallet.offer",
            "dsmWalletSignIn.status", "dsmWalletSignIn.answer", "dsmWalletSignIn.qr", "dsmPhone.state",
            "errors.handleUnauthorized", "errors.handleException",
    ] as Set

    DsmTimelineInterceptor() {
        matchAll().except(uri: "/static/**").except(uri: "/assets/**").except(uri: "/health").except(uri: "/info")
    }

    boolean before() {
        return true
    }

    boolean after() {
        String key = "${controllerName}.${actionName}".toString()
        if (!controllerName || QUIET.contains(key) || isScreenRefresh()) {
            return true
        }
        String label = LABELS[key] ?: LABELS[controllerName] ?: words(controllerName, actionName, request.method)
        if (response.status >= 400) {
            label += " (refused: ${response.status})"
        }
        DsmTimeline.record(label, session.user?.name?.toString() ?: "", "${request.method} ${request.forwardURI - request.contextPath}".toString())
        return true
    }

    /** The DSM page re-reads its lists on a timer; those reads are not clicks. */
    private boolean isScreenRefresh() {
        return request.method == "GET" && (controllerName == "dsmCustodyApi" || controllerName == "dsmTimeline"
                || (controllerName == "genericApi" && request.getHeader("Referer")?.contains("/dsmWallet")))
    }

    /** "inventoryItem" + "showStockCard" + GET: "Inventory item: show stock card". */
    private static String words(String controller, String action, String method) {
        String name = controller.replaceAll(/([a-z])([A-Z])/, '$1 $2').toLowerCase().capitalize()
        String verb = (action ?: "index").replaceAll(/([a-z])([A-Z])/, '$1 $2').toLowerCase()
        return "${name}: ${verb}${method == 'POST' ? ' (saved)' : ''}".toString()
    }
}
