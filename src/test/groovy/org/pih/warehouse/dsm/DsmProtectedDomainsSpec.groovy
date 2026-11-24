package org.pih.warehouse.dsm

import spock.lang.Specification
import spock.lang.Unroll

class DsmProtectedDomainsSpec extends Specification {

    @Unroll
    void "the generic API cannot write #resource"() {
        expect:
        DsmProtectedDomains.refuses(resource)

        where:
        resource << ["transaction", "Transaction", "transactionEntry", "transactionType", "user", "person", "role", "locationRole",
                     "shipment", "shipmentItem", "receipt", "receiptItem", "inventoryItem", "inventory",
                     "dsmSeat", "dsmWallet", "DsmCustodyTransfer"]
    }

    @Unroll
    void "the generic API can still write #resource"() {
        expect:
        !DsmProtectedDomains.refuses(resource)

        where:
        resource << ["product", "category", "location", "productType", null]
    }
}
