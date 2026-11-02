package org.pih.warehouse.dsm

import org.pih.warehouse.core.Location

/**
 * A DSM account this application acts through: a dsm-app-host process that holds the identity, reached through
 * the loopback protobuf ingress the host serves on this machine. OpenBoxes holds no DSM key; it asks the account
 * to act and reads what the account established.
 *
 * Two kinds, told apart by `location`:
 * - a warehouse's account (`location` set): its balance of a product's token is the units in that warehouse's
 *   custody;
 * - a registry account (`location` null, `label` set): the organization's account that issues product tokens and
 *   holds the supply no warehouse has custody of yet. Units enter DSM custody by a transfer from the registry to
 *   the warehouse that took them in from outside DSM (a supplier's delivery, a count that found stock), and leave
 *   it by a burn at the warehouse (consumption, expiry, damage). A registry has no physical stock, so its balance
 *   never conflates with custody.
 */
class DsmWarehouseAccount {

    String id

    /** The warehouse, for a warehouse's account; null for a registry. */
    Location location

    /** A registry's name, for a registry; null for a warehouse's account. */
    String label

    /** The host's game-facing ingress, a loopback origin such as http://127.0.0.1:8787. */
    String ingressOrigin

    /** The host's data directory on this machine; each launch's ingress secret is read from it. */
    String dataDir

    /** The account's device id, Base32 Crockford. */
    String deviceId

    Date dateCreated
    Date lastUpdated

    static mapping = {
        table 'dsm_warehouse_account'
        id generator: 'uuid'
        version false
    }

    static constraints = {
        location(nullable: true, unique: true, validator: { Location location, DsmWarehouseAccount account ->
            if (!location && !account.label?.trim()) {
                return ['dsmWarehouseAccount.locationOrLabel']
            }
        })
        label(nullable: true, maxSize: 255)
        ingressOrigin(blank: false, maxSize: 255)
        dataDir(blank: false, maxSize: 1024)
        deviceId(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH, unique: true)
    }

    boolean isRegistry() {
        return location == null
    }

    String toString() {
        return "${location ? location.name : "registry ${label}"} (${deviceId?.take(8)})"
    }
}
