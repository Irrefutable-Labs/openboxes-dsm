package org.pih.warehouse.dsm

import org.pih.warehouse.core.User
import org.pih.warehouse.RequestUtil
import grails.converters.JSON

/**
 * Authority is read from DSM at the operation, not only at sign-in. A person whose wallet is bound here acts only while a
 * seat of theirs stands and was verified against the storage nodes recently (DsmSeatWatchJob). A seat that was revoked,
 * or that nothing can verify, ends the session, and a role written into the database by hand gives nothing. An owner's
 * wallet is the root the seats descend from, so it holds no seat.
 *
 * Every request also starts with no grant to write stock (DsmStockWrites).
 */
class DsmOperationGateInterceptor {

    int order = HIGHEST_PRECEDENCE + 100

    private static final List<String> OPEN = ["auth", "dsmWalletSignIn", "errors"]
    private static final int DEFAULT_MAX_AGE_SECONDS = 120

    DsmOperationGateInterceptor() {
        matchAll().except(uri: "/static/**").except(uri: "/health").except(uri: "/info")
    }

    boolean before() {
        DsmStockWrites.reset()
        if (!session.user || controllerName in OPEN) {
            return true
        }
        DsmWallet wallet = DsmWallet.findByUser(User.load(session.user.id))
        if (!wallet || wallet.owner) {
            return true
        }
        int maxAge = grailsApplication.config.getProperty("openboxes.dsm.seat.maxVerifiedAgeSeconds", Integer, DEFAULT_MAX_AGE_SECONDS)
        Date oldest = new Date(System.currentTimeMillis() - maxAge * 1000L)
        if (DsmSeat.countByHolderAndStatusAndDateVerifiedGreaterThanEquals(wallet, DsmSeat.STATUS_ASSIGNED, oldest) > 0) {
            return true
        }
        session.invalidate()
        if (RequestUtil.isAjax(request)) {
            response.status = 403
            render([errorCode: 403, errorMessage: "No standing seat could be verified for this wallet. Sign in again."] as JSON)
        } else {
            redirect(controller: "dsmWalletSignIn", action: "index")
        }
        return false
    }

    boolean after() {
        return true
    }
}
