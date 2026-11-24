package org.pih.warehouse.dsm

/**
 * One movement of custody units for one shipment, from this organization's own account's side:
 * - DISPATCHED: the account sent the shipment's units of its own custody object to a carrier's wallet. The carrier
 *   holds the goods from here;
 * - HANDED_OVER: a wallet sent the shipment's units of a partner's custody object to the account. The goods are at
 *   the door, and the shipment can be taken in.
 * The memo is the shipment's, the same one the product units travel under, so the two match without a shared system.
 */
class DsmCarriage {

    static final String KIND_DISPATCHED = "DISPATCHED"
    static final String KIND_HANDED_OVER = "HANDED_OVER"

    String id

    String kind

    /** This organization's own account. */
    DsmWarehouseAccount account

    DsmCustodyToken token

    /** The shipment memo the units travel under (DsmCustodyService.MEMO_SHIPMENT and the shipment number). */
    String memo

    /** The wallet that holds, or handed over, the custody units: its device id, Base32 Crockford. */
    String walletDeviceId

    Integer units

    /** DISPATCHED: the account's state hash after the send, Base32 Crockford. */
    String stateHash

    /** HANDED_OVER: the account's own history row that credited the units. */
    String transferId

    Date dateCreated

    static mapping = {
        table 'dsm_carriage'
        id generator: 'uuid'
        version false
    }

    static constraints = {
        kind(inList: [KIND_DISPATCHED, KIND_HANDED_OVER])
        memo(blank: false, maxSize: 256, unique: 'kind')
        walletDeviceId(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        units(min: 1)
        stateHash(nullable: true, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        transferId(nullable: true, maxSize: 255)
    }
}
