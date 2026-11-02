package org.pih.warehouse.dsm

import org.pih.warehouse.core.User

/**
 * The registry's record of what it decided on one release request and what its account answered: the audit trail
 * of its release authority, beside the account's own history and the approvals that led to it.
 */
class DsmReleaseDecision {

    static final String DECISION_APPROVED = "APPROVED"
    static final String DECISION_DECLINED = "DECLINED"

    String id

    String sessionId
    Long seq

    /** The organization's account that asked, Base32 Crockford. */
    String requesterDeviceId

    /** The request as DSM renders it. */
    String summary

    String decision

    /** What DSM says became of the decision. */
    String line

    /** The operator whose decision took effect. */
    User decidedBy

    Date dateCreated

    static mapping = {
        table 'dsm_release_decision'
        id generator: 'uuid'
        version false
    }

    static constraints = {
        sessionId(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        seq(unique: 'sessionId')
        requesterDeviceId(blank: false, size: Base32Crockford.ID32_LENGTH..Base32Crockford.ID32_LENGTH)
        summary(blank: false, maxSize: 1024)
        decision(inList: [DECISION_APPROVED, DECISION_DECLINED])
        line(nullable: true, maxSize: 1024)
    }
}
