package org.pih.warehouse.dsm

import org.pih.warehouse.core.User

/**
 * A person's DSM wallet, connected to this organization's account by scanning a connect code. The wallet holds its own
 * keys on the person's phone; OpenBoxes knows it by what the account's own connection names. It is how that person signs
 * in: a user has no other way of proving who they are here once a wallet is bound to them.
 */
class DsmWallet {

    String id

    /** The organization's account whose connection this is. */
    DsmWarehouseAccount account

    /** The wallet's device id, genesis and signing key, Base32 Crockford, read from the account's connection. */
    String deviceId
    String genesis
    String signingKey

    /** The wallet's latest connection, and the offer it answered. */
    String sessionId
    String offerDigest

    /** Whoever this wallet is, once an authority has given it a seat. */
    User user

    /** Who this wallet belongs to when no OpenBoxes user does: a carrier, say, named when their wallet connected. */
    String name

    Date dateCreated
    Date lastUpdated

    static mapping = {
        table 'dsm_wallet'
        id generator: 'uuid'
    }

    static constraints = {
        deviceId(blank: false, unique: true, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        genesis(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        signingKey(blank: false, maxSize: 255)
        sessionId(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        offerDigest(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        user(nullable: true)
        name(nullable: true, maxSize: 120)
    }

    String getLabel() {
        return user ? user.name : (name ?: "Unassigned wallet ${deviceId?.take(8)}")
    }
}
