package org.pih.warehouse.dsm

/**
 * A DSM Connect session between two warehouse accounts (DSM Amendment A11): `offering` made the offer and holds
 * the session as the application side, `accepting` approved it as the wallet side. Over it `offering` asks
 * `accepting` for holdings proofs of the tokens it issued, and to root the anchors of tokens it issues later.
 */
class DsmWarehouseLink {

    String id

    DsmWarehouseAccount offering
    DsmWarehouseAccount accepting

    /** The session id, Base32 Crockford: H(DSM/connect/session ‖ offer digest ‖ accepting device id). */
    String sessionId

    /** The digest of the signed offer the session answered, Base32 Crockford. */
    String offerDigest

    Date dateCreated

    static mapping = {
        table 'dsm_warehouse_link'
        id generator: 'uuid'
        version false
    }

    static constraints = {
        accepting(unique: 'offering')
        sessionId(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH, unique: true)
        offerDigest(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
    }
}
