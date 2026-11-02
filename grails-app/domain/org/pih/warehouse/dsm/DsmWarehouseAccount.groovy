package org.pih.warehouse.dsm

import org.pih.warehouse.core.Location

/**
 * A DSM account this instance acts through: a dsm-app-host process that holds the identity, reached through the
 * loopback protobuf ingress the host serves on this machine. OpenBoxes holds no DSM key; it asks the account to act
 * and reads what the account established. Every account here belongs to the organization that runs this instance:
 * another organization's account is known only as a device id on a DsmConnection.
 *
 * Two kinds, told apart by `location`, and never on one instance together, so release authority never shares a
 * server with custody:
 * - a warehouse's account (`location` set): its balance of a product's token is the units in that warehouse's
 *   custody;
 * - a registry account (`location` null, `label` set): it commits product tokens and holds the fixed supply no
 *   warehouse has custody of yet. Its balance is capacity to admit, not physical stock.
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
