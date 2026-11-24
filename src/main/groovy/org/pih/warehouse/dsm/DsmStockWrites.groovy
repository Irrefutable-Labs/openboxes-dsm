package org.pih.warehouse.dsm

import grails.util.Holders
import groovy.sql.Sql
import javax.sql.DataSource
import org.pih.warehouse.inventory.Transaction

/**
 * The rule for stock records once an organization is on DSM: a transaction or its entries are written, changed or deleted only by a
 * flow that goes through DSM. Everything else is refused, so a path nobody knew about fails rather than slipping past.
 * A flow takes the grant for its own request, and each new request starts without one. Raw SQL and direct database
 * edits do not reach these hooks.
 */
class DsmStockWrites {

    private static final ThreadLocal<String> GRANT = new ThreadLocal<String>()

    private DsmStockWrites() {
    }

    /** This request is a flow DSM backs: `why` names it. */
    static void grant(String why) {
        GRANT.set(why)
    }

    /** A new request: it has earned nothing yet. */
    static void reset() {
        GRANT.remove()
    }

    /** Called from the domain hooks of Transaction and TransactionEntry. */
    static void check(Transaction transaction, String verb) {
        if (GRANT.get()) {
            return
        }
        // A hook runs inside Hibernate's flush, so this reads with plain SQL and leaves the session alone.
        def row = new Sql((DataSource) Holders.applicationContext.getBean("dataSource")).firstRow(
                "select count(*) as accounts from dsm_warehouse_account where location_id is not null")
        if (row.accounts > 0) {
            throw new DsmCustodyException(
                    "this organization's stock is on DSM, and this path cannot ${verb} a stock transaction: custody only " +
                            "changes through a DSM-backed flow, and a correction is a new forward record, never an edit")
        }
    }
}
