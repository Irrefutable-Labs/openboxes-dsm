package org.pih.warehouse.dsm

import org.pih.warehouse.core.Location

/**
 * One account's own record of a DSM Connect session with another account (DSM Amendment A11). Each side writes
 * its own row from what its own account holds; nothing on this instance reaches the other side's host, its
 * credentials or its database. Pairing is three calls, each by whoever runs that side: the offering side makes a
 * code and hands it over out of band, the accepting side reads and approves it, the offering side confirms the
 * session its account now holds.
 *
 * The peer is known here by device id only, with a local name for it: `peerLocation` when it is a warehouse this
 * instance ships to or receives from (another organization's warehouse, as this organization's own records name
 * it, or a second warehouse of its own), `peerRegistry` when it is the registry this account takes units from.
 */
class DsmConnection {

    /** This account made the offer: it holds the session as the application side, and may ask the peer for things. */
    static final String SIDE_OFFERED = "OFFERED"

    /** This account approved the offer: it holds the session as the wallet side, and answers what the peer asks. */
    static final String SIDE_ACCEPTED = "ACCEPTED"

    String id

    /** This instance's own account. */
    DsmWarehouseAccount account

    /** The peer's device id, Base32 Crockford: read from this account's own session or the signed offer. */
    String peerDeviceId

    /** This instance's Location that stands for the peer's warehouse; null for a registry or an organization a registry serves. */
    Location peerLocation

    /** The peer is the registry this account takes units from. */
    Boolean peerRegistry = Boolean.FALSE

    /** The name the peer's offer carried, or this instance's name for the peer. */
    String peerName

    String side

    /** The session id, Base32 Crockford. */
    String sessionId

    /** The digest of the signed offer the session answered, Base32 Crockford. */
    String offerDigest

    Date dateCreated

    static mapping = {
        table 'dsm_connection'
        id generator: 'uuid'
        version false
    }

    static constraints = {
        peerDeviceId(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH, unique: 'account')
        peerLocation(nullable: true)
        peerName(nullable: true, maxSize: 255)
        side(inList: [SIDE_OFFERED, SIDE_ACCEPTED])
        sessionId(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        offerDigest(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
    }

    String toString() {
        return "${peerRegistry ? "registry" : peerLocation?.name ?: peerName} (${peerDeviceId?.take(8)})"
    }
}
