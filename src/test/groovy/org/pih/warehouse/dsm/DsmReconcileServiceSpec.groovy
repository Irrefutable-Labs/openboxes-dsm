package org.pih.warehouse.dsm

import spock.lang.Specification
import spock.lang.Unroll

class DsmReconcileServiceSpec extends Specification {

    @Unroll
    void "books #books, DSM #dsm, undrawn #undrawn, in transit #inTransit: the gap is #gap"() {
        expect:
        DsmReconcileService.gapOf(books, dsm, undrawn, inTransit) == gap

        where:
        books | dsm | undrawn | inTransit || gap
        // Northgate, 2026-11-03: a backdated shipment left 40 phantom units on the books.
        81    | 440 | 399     | 0         || 40
        // Admitted units not yet recorded are DSM's and not the books'.
        45    | 195 | 150     | 0         || 0
        // The hospital's account holds what has arrived before its books do.
        40    | 60  | 0       | 20        || 0
        // DSM holds units the books do not show.
        0     | 40  | 0       | 0         || -40
        0     | 0   | 0       | 0         || 0
    }
}
