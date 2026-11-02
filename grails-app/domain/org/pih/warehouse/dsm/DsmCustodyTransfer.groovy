package org.pih.warehouse.dsm

import org.pih.warehouse.product.Product
import org.pih.warehouse.shipping.Shipment

/**
 * OpenBoxes' record of one custody transition on DSM for one product. The record is bookkeeping; the transition
 * itself is what the accounts hold:
 * - SHIPMENT: a shipment's units moved from the origin warehouse's account to the destination's;
 * - STOCK_TRANSFER: a stock transfer's units moved between two warehouses' accounts;
 * - REGISTRY_CREDIT: units entered custody, moved from the token's registry to the warehouse that took them in;
 * - BURN: units left custody, burned at the warehouse (consumption, expiry, damage, a count that found less).
 *
 * The memo names the OpenBoxes document the transition backs (a shipment, a receipt, a transaction number) and
 * travels sealed inside the transfer, so the destination's own history is matched on it. Written right after
 * the account acted; the origin account's own history decides whether a retried move was already made.
 */
class DsmCustodyTransfer {

    static final String KIND_SHIPMENT = "SHIPMENT"
    static final String KIND_STOCK_TRANSFER = "STOCK_TRANSFER"
    static final String KIND_REGISTRY_CREDIT = "REGISTRY_CREDIT"
    static final String KIND_BURN = "BURN"

    String id

    String kind

    /** The shipment, for a SHIPMENT; null otherwise. */
    Shipment shipment
    Product product

    /** The account the units left (a warehouse, or the registry for a REGISTRY_CREDIT). */
    DsmWarehouseAccount origin

    /** The account the units reached; null for a BURN. */
    DsmWarehouseAccount destination

    /** Units moved or burned. */
    Integer quantity

    /** The memo the transfer carried, sealed to the two accounts: names the OpenBoxes document it backs. */
    String memo

    /**
     * The origin account's state hash after the send, Base32 Crockford, as its send reported it; null for a BURN,
     * whose answer carries no hash. Names the origin's own step; the destination's row is named by its own
     * recomputed submission id (see destinationTransferId), so the two sides are matched by sender, token,
     * amount and the sealed memo.
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
        kind(inList: [KIND_SHIPMENT, KIND_STOCK_TRANSFER, KIND_REGISTRY_CREDIT, KIND_BURN])
        shipment(nullable: true)
        destination(nullable: true)
        product(unique: 'memo')
        quantity(min: 1)
        memo(blank: false, maxSize: 256)
        originStateHash(nullable: true, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        dateEstablished(nullable: true)
        destinationTransferId(nullable: true, maxSize: 255)
        provenAmount(nullable: true)
        provenPosition(nullable: true)
    }
}
