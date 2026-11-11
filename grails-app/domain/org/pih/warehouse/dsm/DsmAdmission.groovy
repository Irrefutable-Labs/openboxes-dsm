package org.pih.warehouse.dsm

import org.pih.warehouse.core.User
import org.pih.warehouse.product.Product

/**
 * A warehouse organization's request to admit physical units into DSM custody, and what became of it. The request
 * is a payment request over the connection with the registry, outside any grant, so nothing moves until the
 * registry's own operators approve it on the registry's own account. Once this account's result for the request holds
 * the payment as a fact and its own history shows the credit, the units are in this warehouse's custody and stock can be
 * recorded against them: each recording draws on `quantityDrawn`, so custody can never be recorded twice.
 */
class DsmAdmission {

    static final String STATUS_REQUESTED = "REQUESTED"
    static final String STATUS_AWAITING_APPROVAL = "AWAITING_APPROVAL"
    static final String STATUS_ESTABLISHED = "ESTABLISHED"
    static final String STATUS_DECLINED = "DECLINED"
    static final String STATUS_FAILED = "FAILED"

    String id

    DsmWarehouseAccount account
    Product product

    /** Units asked for. */
    Integer quantity

    /** Units already recorded as stock against this admission. */
    Integer quantityDrawn = 0

    /** The document that says the physical units exist (a goods-received note, a count sheet), shown to the registry. */
    String reference

    /** The memo the request carried, sealed to the two accounts. */
    String memo

    /** The connection and request sequence the request went out on; null until it did. */
    String sessionId
    Long seq

    String status = STATUS_REQUESTED

    /** Why the registry's answer established nothing. */
    String reason

    /** This account's history row of the accepted credit. */
    String creditTransferId

    User requestedBy

    Date dateCreated
    Date dateEstablished

    static mapping = {
        table 'dsm_admission'
        id generator: 'uuid'
    }

    static constraints = {
        quantity(min: 1)
        quantityDrawn(min: 0)
        reference(blank: false, maxSize: 128)
        memo(nullable: true, maxSize: 256)
        sessionId(nullable: true, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        seq(nullable: true)
        status(inList: [STATUS_REQUESTED, STATUS_AWAITING_APPROVAL, STATUS_ESTABLISHED, STATUS_DECLINED, STATUS_FAILED])
        reason(nullable: true, maxSize: 1024)
        creditTransferId(nullable: true, maxSize: 255, unique: true)
        requestedBy(nullable: true)
        dateEstablished(nullable: true)
    }

    int getQuantityUndrawn() {
        return status == STATUS_ESTABLISHED ? quantity - quantityDrawn : 0
    }
}
