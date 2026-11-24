package org.pih.warehouse.dsm

import groovy.transform.CompileStatic

/** A warehouse's dsm-app-host refused a call, or could not be reached. The message is the host's own reason. */
@CompileStatic
class DsmHostException extends RuntimeException {

    final String route

    DsmHostException(String route, String reason) {
        super("${route}: ${reason}".toString())
        this.route = route
    }

    DsmHostException(String route, String reason, Throwable cause) {
        super("${route}: ${reason}".toString(), cause)
        this.route = route
    }
}
