package org.pih.warehouse.dsm

import org.pih.warehouse.product.Product

/**
 * The DSM token that stands for one product's inventory units: a policy of 0 decimals the issuing warehouse's
 * account committed, its whole supply released to that account at creation. The policy anchor is the product's
 * identity on DSM; a unit of inventory is one base unit of the token. The product's own data stays here.
 */
class DsmProductToken {

    String id

    Product product

    /** The account whose policy it is. */
    DsmWarehouseAccount issuer

    /** The policy anchor, Base32 Crockford. */
    String policyAnchor

    /** The ticker the policy names, as DSM routes name the token. */
    String ticker

    /** The whole supply that will ever exist, in units. */
    Long supply

    Date dateCreated

    static mapping = {
        table 'dsm_product_token'
        id generator: 'uuid'
        version false
    }

    static constraints = {
        product(unique: true)
        policyAnchor(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH, unique: true)
        ticker(blank: false, maxSize: 8)
        supply(min: 1L)
    }
}
