package org.pih.warehouse.dsm

import org.pih.warehouse.core.Location

/**
 * One assignment of one seat: a unit of an access level's token locked in a vault on the storage nodes, whose terms
 * commit to who holds it and whose outcomes say who can take it back. A vault is the record: this row only remembers
 * where to find it, and every check reads the vault itself.
 *
 * Nothing is ever undone. Revoking is a decision the authority signs in their own wallet; the vault then pays the
 * account, and the seat is assigned again by a new vault, so the history of who held what stays.
 */
class DsmSeat {

    static final String STATUS_ASSIGNED = "ASSIGNED"
    static final String STATUS_RETURNED = "RETURNED"

    String id

    DsmAccessLevel level

    /** Which seat of the level this is: a released unit keeps one number through every assignment. */
    Integer number

    DsmWallet holder

    /** The wallet that assigned it, which can decide its revocation. */
    DsmWallet authority

    /** Where this seat gives access. */
    Location location

    /** The account that locked the unit and that revocation pays. */
    DsmWarehouseAccount account

    /** The exact bytes the vault's external commitment commits to: the holder's identity is in them. */
    String agreement

    /** The vault, its verdict cell and its external commitment, Base32 Crockford. */
    String vaultId
    String verdictCell
    String externalCommitment

    String status = STATUS_ASSIGNED

    Date dateCreated

    /** When the vault paid the seat back to the account. */
    Date dateReturned


    static mapping = {
        table 'dsm_seat'
        id generator: 'uuid'
    }

    static constraints = {
        agreement(blank: false, maxSize: 1024)
        vaultId(blank: false, unique: true, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        verdictCell(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        externalCommitment(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        status(inList: [STATUS_ASSIGNED, STATUS_RETURNED])
        dateReturned(nullable: true)
    }
}
