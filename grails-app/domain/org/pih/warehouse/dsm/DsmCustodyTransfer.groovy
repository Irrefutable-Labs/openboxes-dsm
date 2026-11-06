package org.pih.warehouse.dsm

import org.pih.warehouse.product.Product
import org.pih.warehouse.shipping.Shipment

/**
 * This instance's record of one custody transition on DSM for one product, from its own account's side. The record
 * is bookkeeping; the transition itself is what the accounts hold:
 * - SHIPMENT: a shipment's units left this account for the peer;
 * - STOCK_TRANSFER: a stock transfer's units moved between two of this organization's own accounts;
 * - INBOUND: units a peer sent are accepted on this account, for this instance's own shipment record of them;
 * - ADMITTED: stock recorded against units the registry released into this account (see DsmAdmission);
 * - BURN: units left custody, burned at this account (consumption, expiry, damage, a count that found less).
 *
 * The memo names the document the transition backs and travels sealed inside the transfer, so the two sides match
 * on it without sharing a database. A memo is the sender's document number, so one is unique per peer.
 */
class DsmCustodyTransfer {

    static final String KIND_SHIPMENT = "SHIPMENT"
    static final String KIND_STOCK_TRANSFER = "STOCK_TRANSFER"
    static final String KIND_INBOUND = "INBOUND"
    static final String KIND_ADMITTED = "ADMITTED"
    static final String KIND_BURN = "BURN"

    String id

    String kind

    /** This instance's shipment, for a SHIPMENT or INBOUND; null otherwise. */
    Shipment shipment
    Product product

    /** This instance's own account: the one whose state the transition changed. */
    DsmWarehouseAccount account

    /** The other side's device id, Base32 Crockford; null for a BURN. */
    String peerDeviceId

    /** Units moved or burned. */
    Integer quantity

    /** INBOUND: the units this instance's receipts have recorded so far. */
    Integer quantityReceived = 0

    /** The memo the transfer carried, sealed to the two accounts: names the document it backs. */
    String memo

    /** The sending account's state hash after the send, Base32 Crockford; null on any other row. */
    String originStateHash

    Date dateCreated

    /** When this account's or the destination's own history was read to hold the transfer as accepted. */
    Date dateEstablished

    /** The accepting account's name for the transfer (its history row's id). */
    String destinationTransferId

    /** The peer's balance of the token as its holdings proof established it, and the position proven. */
    Long provenAmount
    Long provenPosition

    static mapping = {
        table 'dsm_custody_transfer'
        id generator: 'uuid'
        version false
    }

    static constraints = {
        kind(inList: [KIND_SHIPMENT, KIND_STOCK_TRANSFER, KIND_INBOUND, KIND_ADMITTED, KIND_BURN])
        shipment(nullable: true)
        peerDeviceId(nullable: true, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        product(unique: ['kind', 'memo', 'peerDeviceId'])
        quantity(min: 1)
        quantityReceived(min: 0)
        memo(blank: false, maxSize: 256)
        originStateHash(nullable: true, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        dateEstablished(nullable: true)
        destinationTransferId(nullable: true, maxSize: 255)
        provenAmount(nullable: true)
        provenPosition(nullable: true)
    }
}
