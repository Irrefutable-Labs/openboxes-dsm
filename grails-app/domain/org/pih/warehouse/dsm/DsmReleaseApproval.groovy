package org.pih.warehouse.dsm

import org.pih.warehouse.core.User

/**
 * One registry operator's approval of one release request. A request is approved on the registry's account only
 * after the configured number of different operators each recorded one (`openboxes.dsm.registry.approvalsRequired`).
 */
class DsmReleaseApproval {

    String id

    String sessionId
    Long seq

    /** The request as DSM renders it, as this operator saw it. */
    String summary

    User approver

    Date dateCreated

    static mapping = {
        table 'dsm_release_approval'
        id generator: 'uuid'
        version false
    }

    static constraints = {
        sessionId(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        summary(blank: false, maxSize: 1024)
        approver(unique: ['sessionId', 'seq'])
    }
}
