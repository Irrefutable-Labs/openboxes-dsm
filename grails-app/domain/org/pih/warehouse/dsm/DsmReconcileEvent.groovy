package org.pih.warehouse.dsm

import org.pih.warehouse.core.User
import org.pih.warehouse.product.Product

/**
 * One thing the reconciler found, or one correction: append-only. A later finding is a new row, and nothing here is
 * changed or removed, so a discrepancy stays on the record after it is corrected. Each row is also published as an object
 * signed by this organization's DSM account (`signedAddress`), which proves who said it, though not that nothing was
 * withheld.
 */
class DsmReconcileEvent {

    /** The books and DSM disagree. */
    static final String KIND_GAP = "GAP"
    /** They agree again, with no correction recorded here. */
    static final String KIND_CLEARED = "CLEARED"
    /** The books were moved to match DSM by a new adjustment. */
    static final String KIND_CORRECTED = "CORRECTED"
    /** The availability the screens read differs from what the stock transactions add up to. */
    static final String KIND_CACHE = "CACHE"
    static final String KIND_CACHE_CLEARED = "CACHE_CLEARED"

    String id

    String kind
    DsmWarehouseAccount account
    Product product

    /** What was measured: DSM's holding, its admitted units not yet recorded, units here not yet received, the books. */
    Long dsmHolding
    Long undrawn
    Long inTransit
    Long books
    Long cached

    /** Books minus what DSM says the books should be. */
    Long gap

    String note
    User recordedBy
    String transactionNumber

    /** The published object's address, Base32 Crockford. */
    String signedAddress

    Date dateCreated

    static mapping = {
        table 'dsm_reconcile_event'
        id generator: 'uuid'
        version false
    }

    static constraints = {
        kind(inList: [KIND_GAP, KIND_CLEARED, KIND_CORRECTED, KIND_CACHE, KIND_CACHE_CLEARED])
        cached(nullable: true)
        note(nullable: true, maxSize: 512)
        recordedBy(nullable: true)
        transactionNumber(nullable: true)
    }

    def beforeUpdate() {
        throw new DsmCustodyException("a reconciliation record is never changed: a correction is a new record")
    }

    def beforeDelete() {
        throw new DsmCustodyException("a reconciliation record is never removed")
    }
}
