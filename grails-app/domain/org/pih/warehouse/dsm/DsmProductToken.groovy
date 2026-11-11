package org.pih.warehouse.dsm

import org.pih.warehouse.product.Product

/**
 * The DSM object that stands for one product's inventory units, on this instance: a policy of 0 decimals a registry
 * committed, its whole fixed supply released to the registry when it was issued. The policy anchor is the product's
 * identity across organizations; each organization maps it to a product of its own catalog, whatever that product
 * is coded there. A unit of inventory in a warehouse's custody is one of the object's own units.
 *
 * What the registry owns is capacity to admit, not stock: it stands for no physical unit until it is released into
 * a warehouse's custody.
 */
class DsmProductToken {

    String id

    Product product

    /** The registry that committed the policy, when this instance is that registry; null where the object was adopted. */
    String issuerDeviceId

    /** The policy anchor, Base32 Crockford. */
    String policyAnchor

    /** The ticker the policy names, as DSM routes name the object. */
    String ticker

    /** The fixed supply, in units, at the registry that issued it; null where the object was adopted. */
    Long supply

    Date dateCreated

    static mapping = {
        table 'dsm_product_token'
        id generator: 'uuid'
        version false
    }

    static constraints = {
        product(unique: true)
        issuerDeviceId(nullable: true, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        policyAnchor(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH, unique: true)
        ticker(blank: false, maxSize: 8)
        supply(nullable: true, min: 1L)
    }
}
