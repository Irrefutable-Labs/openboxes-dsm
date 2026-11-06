package org.pih.warehouse.jobs

/** Compares the books with DSM every minute. Two readings in a row must agree before anything is recorded. */
class DsmReconcileJob {

    def dsmReconcileService

    static concurrent = false

    // The service's own transaction does the work: the job holds no session to flush afterwards.
    def sessionRequired = false

    static triggers = {
        simple name: "dsmReconcile", startDelay: 60000L, repeatInterval: 60000L
    }

    void execute() {
        try {
            dsmReconcileService.runChecks()
        } catch (Exception unread) {
            log.warn("the books could not be compared with DSM: ${unread.message}")
        }
    }
}
