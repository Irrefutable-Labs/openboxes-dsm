package org.pih.warehouse.dsm

/**
 * One authority change as this server took it: the address of the object the account published, and where a person approved
 * it, their wallet's signed answer. Append-only. The published object is the record, and this row keeps what is too large
 * to publish. Nothing decides anything from this table.
 */
class DsmAuthorityRecord {

    String id
    String kind
    String address
    String wallet
    String statement
    byte[] answerBody
    byte[] answerSignature
    Date dateCreated

    static mapping = {
        table 'dsm_authority_record'
        id generator: 'uuid'
        version false
        answerBody sqlType: 'mediumblob'
        answerSignature sqlType: 'mediumblob'
    }

    static constraints = {
        wallet(nullable: true)
        statement(nullable: true, maxSize: 512)
        answerBody(nullable: true, maxSize: 1048576)
        answerSignature(nullable: true, maxSize: 65535)
    }

    def beforeUpdate() {
        throw new DsmCustodyException("an authority record is never changed")
    }

    def beforeDelete() {
        throw new DsmCustodyException("an authority record is never removed")
    }
}
