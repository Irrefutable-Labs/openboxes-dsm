package org.pih.warehouse.dsm

import groovy.transform.CompileStatic

/**
 * DSM has not established the custody transition an OpenBoxes inventory transaction would describe, so the
 * transaction is not written. The message says what DSM would need.
 */
@CompileStatic
class DsmCustodyException extends RuntimeException {

    DsmCustodyException(String reason) {
        super(reason)
    }

    DsmCustodyException(String reason, Throwable cause) {
        super(reason, cause)
    }
}
