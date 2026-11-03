package org.pih.warehouse.dsm

import grails.gorm.transactions.Transactional
import org.hibernate.FlushMode
import org.hibernate.Session
import org.pih.warehouse.core.Role
import org.pih.warehouse.core.RoleType
import org.pih.warehouse.core.User

/**
 * Who may do what, once an organization has an owner: DSM says, and nothing else. The owner's wallet is the root of the
 * organization's authority. Everyone else has what their standing seats give, and a role written into OpenBoxes gives nothing.
 */
@Transactional(readOnly = true)
class DsmAuthorityService {

    def sessionFactory

    boolean ownerExists() {
        return unflushed { DsmWallet.countByOwner(true) > 0 }
    }

    Collection<Role> rolesFor(User user) {
        return unflushed {
            DsmWallet wallet = user.id ? DsmWallet.findByUser(user) : null
            if (!wallet) {
                return []
            }
            if (wallet.owner) {
                return [Role.findByRoleType(RoleType.ROLE_SUPERUSER)]
            }
            return DsmSeat.findAllByHolderAndStatus(wallet, DsmSeat.STATUS_ASSIGNED)*.level*.roleType.toSet().collect { String name ->
                Role.findByRoleType(RoleType.valueOf(name))
            }
        }
    }

    /** A role check can come in the middle of someone else's unit of work, and reading authority must not flush it. */
    private <T> T unflushed(Closure<T> read) {
        Session session = sessionFactory.currentSession
        FlushMode mode = session.hibernateFlushMode
        session.hibernateFlushMode = FlushMode.MANUAL
        try {
            return read.call()
        } finally {
            session.hibernateFlushMode = mode
        }
    }
}
