package org.pih.warehouse.dsm

import org.pih.warehouse.core.User
import org.pih.warehouse.RequestUtil
import grails.converters.JSON

/**
 * Authority is read from DSM on every request. Once an organization has an owner, a request goes ahead only for the owner's
 * wallet, or for a person with a seat whose vault stands on the storage nodes right now. There is no copy of that answer: if
 * the nodes cannot be read, the request is refused. An owner's wallet is the root the seats descend from, so it holds no seat.
 *
 * Every request also starts with no grant to write stock (DsmStockWrites).
 */
class DsmOperationGateInterceptor {

    int order = HIGHEST_PRECEDENCE + 100

    private static final List<String> OPEN = ["auth", "dsmWalletSignIn", "errors"]

    DsmAuthorityService dsmAuthorityService
    DsmWalletService dsmWalletService

    DsmOperationGateInterceptor() {
        matchAll().except(uri: "/static/**").except(uri: "/health").except(uri: "/info")
    }

    boolean before() {
        DsmStockWrites.reset()
        if (!session.user || controllerName in OPEN || !dsmAuthorityService.ownerExists()) {
            return true
        }
        DsmWallet wallet = DsmWallet.findByUser(User.load(session.user.id))
        if (wallet?.owner) {
            return true
        }
        boolean stands
        try {
            stands = wallet && dsmWalletService.seatStands(wallet)
        } catch (DsmCustodyException | DsmHostException unread) {
            return refuse(503, "DSM could not be read, so this cannot go ahead: ${unread.message}", false)
        }
        return stands ?: refuse(403, "No seat stands for this wallet. Sign in with your DSM wallet.", true)
    }

    boolean after() {
        return true
    }

    private boolean refuse(int status, String why, boolean endSession) {
        if (endSession) {
            session.invalidate()
        }
        if (RequestUtil.isAjax(request)) {
            response.status = status
            render([errorCode: status, errorMessage: why] as JSON)
        } else if (endSession) {
            redirect(controller: "dsmWalletSignIn", action: "index")
        } else {
            response.status = status
            render(text: "<!doctype html><title>Refused</title><h1>Refused</h1><p>${why.encodeAsHTML()}</p>", contentType: "text/html")
        }
        return false
    }
}
