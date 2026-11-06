package org.pih.warehouse.dsm

import org.pih.warehouse.RequestUtil
import grails.converters.JSON

/**
 * Authority is read from DSM on every request (DsmAuthorityService). Once an organization has an owner, a request goes ahead
 * only for the wallet this browser proved at sign-in, as the owner or with a seat whose vault stands on the storage nodes right
 * now. If the nodes cannot be read, the request is refused.
 *
 * Every request also starts with no grant to write stock (DsmStockWrites).
 */
class DsmOperationGateInterceptor {

    int order = HIGHEST_PRECEDENCE + 100

    private static final List<String> OPEN = ["auth", "dsmWalletSignIn", "errors"]

    DsmAuthorityService dsmAuthorityService

    DsmOperationGateInterceptor() {
        matchAll().except(uri: "/static/**").except(uri: "/health").except(uri: "/info")
    }

    boolean before() {
        DsmStockWrites.reset()
        if (!session.user || controllerName in OPEN) {
            return true
        }
        Map authority
        try {
            if (!dsmAuthorityService.ownerExists()) {
                return true
            }
            authority = dsmAuthorityService.current()
        } catch (DsmCustodyException | DsmHostException unread) {
            return refuse(503, "DSM could not be read, so this cannot go ahead: ${unread.message}", false)
        }
        return (authority.owner || authority.roles) ?: refuse(403, "Your seat is no longer standing on DSM, so this was refused. Nothing else had to change.", true)
    }

    boolean after() {
        return true
    }

    private boolean refuse(int status, String why, boolean endSession) {
        if (endSession) {
            try {
                session.invalidate()
            } catch (IllegalStateException alreadyGone) {
                // two requests of the same session were refused at once (a page and the overlay's poll): the first ended it
            }
        }
        if (RequestUtil.isAjax(request)) {
            response.status = status
            render([errorCode: status, errorMessage: why] as JSON)
        } else if (endSession) {
            redirect(controller: "dsmWalletSignIn", action: "index", params: [refused: "seat"])
        } else {
            response.status = status
            render(text: "<!doctype html><title>Refused</title><h1>Refused</h1><p>${why.encodeAsHTML()}</p>", contentType: "text/html")
        }
        return false
    }
}
