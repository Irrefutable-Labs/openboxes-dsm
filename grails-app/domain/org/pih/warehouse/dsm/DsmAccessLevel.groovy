package org.pih.warehouse.dsm

/**
 * A level of access in this organization: a DSM token whose fixed supply is the most seats of that level there can ever
 * be. Its creator made it in their own wallet and holds what has not been released. Released units sit with the
 * organization's account until they are assigned, so every seat is accounted for and none can be conjured.
 */
class DsmAccessLevel {

    String id

    String name

    /** The OpenBoxes role a seat of this level gives: a RoleType name. */
    String roleType

    /** The token, Base32 Crockford, and the ticker its policy names. */
    String policyAnchor
    String ticker

    /** The wallet that created the token and holds what is unreleased: it approves every release on its own phone. */
    DsmWallet creator

    Date dateCreated

    static mapping = {
        table 'dsm_access_level'
        id generator: 'uuid'
        version false
    }

    static constraints = {
        name(blank: false, maxSize: 64, unique: true)
        roleType(inList: ['ROLE_ADMIN', 'ROLE_MANAGER', 'ROLE_ASSISTANT', 'ROLE_BROWSER'])
        policyAnchor(blank: false, unique: true, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        ticker(blank: false, maxSize: 8)
    }
}
