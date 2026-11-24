package org.pih.warehouse.dsm

/** What the generic API may not write: stock, custody and authority change only through their own flows. */
class DsmProtectedDomains {

    private static final Set<String> REFUSED = [
            "transaction", "transactionentry", "transactiontype", "transactionsource", "inventory", "inventoryitem",
            "shipment", "shipmentitem", "receipt", "receiptitem", "user", "person", "role", "locationrole",
    ] as Set

    private DsmProtectedDomains() {
    }

    static boolean refuses(String resource) {
        String name = resource?.toLowerCase()
        return name != null && (name in REFUSED || name.startsWith("dsm"))
    }
}
