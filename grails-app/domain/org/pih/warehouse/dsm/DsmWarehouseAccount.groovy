package org.pih.warehouse.dsm

import org.pih.warehouse.core.Location

/**
 * A warehouse's own DSM account: the dsm-app-host process that holds its identity, reached through the loopback
 * protobuf ingress the host serves on this machine. OpenBoxes holds no DSM key; it asks this account to act and
 * reads what the account established.
 */
class DsmWarehouseAccount {

    String id

    Location location

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
        location(unique: true)
        ingressOrigin(blank: false, maxSize: 255)
        dataDir(blank: false, maxSize: 1024)
        deviceId(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH, unique: true)
    }

    String toString() {
        return "${location?.name} (${deviceId?.take(8)})"
    }
}
