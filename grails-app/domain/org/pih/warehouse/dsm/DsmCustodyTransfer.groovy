package org.pih.warehouse.dsm

import org.pih.warehouse.product.Product
import org.pih.warehouse.shipping.Shipment

/**
 * OpenBoxes' record of one custody transition on DSM: the transfer of a shipment's units of one product from the
 * origin warehouse's account to the destination's. The record is bookkeeping; the transition itself is the
 * transfer the two accounts hold. Written in its own transaction right after the origin's account sent it, so a
 * shipment whose OpenBoxes save fails afterwards is not sent twice.
 */
class DsmCustodyTransfer {

    String id

    Shipment shipment
    Product product

    DsmWarehouseAccount origin
    DsmWarehouseAccount destination

    /** Units sent: the shipped quantity of the product. */
    Integer quantity

    /** The memo the transfer carried, sealed to the two accounts: names the shipment. */
    String memo

    /**
     * The origin account's state hash after the send, Base32 Crockford, as its send reported it. Names the
     * origin's own step; the destination's row is named by its own recomputed submission id (see
     * destinationTransferId), so the two sides are matched by sender, token, amount and the sealed memo.
     */
    String originStateHash

    Date dateCreated

    /** When the destination's own history was read to hold the transfer as accepted; null until then. */
    Date dateEstablished

    /** The destination's name for the accepted transfer (its history row's id); null until established. */
    String destinationTransferId

    /** The destination's balance of the token as its holdings proof established it, and the position proven. */
    Long provenAmount
    Long provenPosition

    static mapping = {
        table 'dsm_custody_transfer'
        id generator: 'uuid'
        version false
    }

    static constraints = {
        product(unique: 'shipment')
        quantity(min: 1)
        memo(blank: false, maxSize: 256)
        originStateHash(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH, unique: true)
        dateEstablished(nullable: true)
        destinationTransferId(nullable: true, maxSize: 255)
        provenAmount(nullable: true)
        provenPosition(nullable: true)
    }
}
