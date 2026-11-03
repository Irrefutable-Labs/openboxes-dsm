package org.pih.warehouse.jobs

/**
 * Reads every standing seat's verdict cell from the storage nodes. A revocation takes effect from this, with nobody having
 * to press anything, and each read that finds a seat still standing is what DsmOperationGateInterceptor relies on.
 */
class DsmSeatWatchJob {

    def dsmWalletService

    static concurrent = false

    // Each read is a transaction of the service's own: the job holds no session to flush afterwards.
    def sessionRequired = false

    static triggers = {
        simple name: "dsmSeatWatch", startDelay: 30000L, repeatInterval: 20000L
    }

    void execute() {
        dsmWalletService.standingSeatIds().each { String id ->
            try {
                dsmWalletService.refreshSeatById(id)
            } catch (Exception unread) {
                // This seat stays unverified, and the gate refuses its holder once the last reading is too old.
                log.warn("seat ${id} could not be read: ${unread.message}")
            }
        }
    }
}
