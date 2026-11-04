package org.pih.warehouse.dsm

/**
 * A custody token: one unit stands for one unit of goods in transit, and whoever holds the units holds the goods. It
 * says nothing about ownership: the product units stay in the organizations' accounts, and a carrier holding custody
 * units can spend nothing of value.
 *
 * Each organization makes its own, in its owner's wallet, and the owner releases units to the organization's account
 * (`creator` set). A partner whose shipments are carried to this organization has this organization root the same
 * token (`peer` set), so a carrier can hand custody over to the account at the door; a shipment from that partner is
 * then taken in only once that has happened.
 */
class DsmCustodyToken {

    String id

    /** The policy anchor, Base32 Crockford, and the ticker its policy names. */
    String policyAnchor
    String ticker

    /** This organization's own token: the wallet that made it and holds what is unreleased. */
    DsmWallet creator

    /** A partner's token: the connection to the partner whose shipments hand custody over here. */
    DsmConnection peer

    Date dateCreated

    static mapping = {
        table 'dsm_custody_token'
        id generator: 'uuid'
        version false
    }

    static constraints = {
        policyAnchor(blank: false, unique: true, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        ticker(blank: false, maxSize: 8)
        creator(nullable: true, validator: { DsmWallet creator, DsmCustodyToken token ->
            if ((creator == null) == (token.peer == null)) {
                return ['dsmCustodyToken.creatorOrPeer']
            }
        })
        peer(nullable: true, unique: true)
    }

    boolean isOwn() {
        return creator != null
    }
}
