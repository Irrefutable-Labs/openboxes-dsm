package org.pih.warehouse.dsm

import spock.lang.Specification

class DsmAuthorityServiceSpec extends Specification {

    private static final String DEVICE = "D" * 52
    private static final String GENESIS = "G" * 52
    private static final String ACCOUNT = "A" * 52
    private static final String ANCHOR = "T" * 52

    private static byte[] seat(String account = ACCOUNT, String anchor = ANCHOR, String role = "ROLE_BROWSER", int number = 1,
                               String location = "1", String user = "u1", String genesis = GENESIS, String device = DEVICE) {
        return DsmAuthorityService.externalCommitment(
                DsmAuthorityService.seatAgreement(account, anchor, role, number, location, user, genesis, device))
    }

    void 'the commitment here is the one DSM made: a vault the live storage nodes hold'() {
        expect:
        Base32Crockford.encode(DsmAuthorityService.externalCommitment(
                "openboxes/seat/v2|P1EA24CVTS2NBY5767643MQR9E2HYSV2HXTBBCXMSXQS18WKXXZG|EHWY9QN0BYE680MAVSKXQZVSCWYCFHKQN2CT9G555ABJBJGQ20ZG|ROLE_MANAGER|1|1|402884aea1a1844b01a1a18e93ea0004|1A4PXK249RBWQWJ7JP0TH2DHQCTJB0RH7Z9H9T0GSZFQTF119YFG|P58TPCQX8EPSTEQ69FB0Z4MKS8MTKCSESASR1WY76RAFXGZ025X0")) == "J06PGDQ2VGW6MY7773RYDQB4S9C97ZC8HV7PBGNTXN62NNNNJ2ZG"
    }

    void 'changing anything a seat claims changes what its vault must commit to'() {
        expect:
        seat() == seat()
        seat() != seat("B" * 52)
        seat() != seat(ACCOUNT, "U" * 52)
        seat() != seat(ACCOUNT, ANCHOR, "ROLE_ADMIN")
        seat() != seat(ACCOUNT, ANCHOR, "ROLE_BROWSER", 2)
        seat() != seat(ACCOUNT, ANCHOR, "ROLE_BROWSER", 1, "2")
        seat() != seat(ACCOUNT, ANCHOR, "ROLE_BROWSER", 1, "1", "u2")
        seat() != seat(ACCOUNT, ANCHOR, "ROLE_BROWSER", 1, "1", "u1", "H" * 52)
        seat() != seat(ACCOUNT, ANCHOR, "ROLE_BROWSER", 1, "1", "u1", GENESIS, "E" * 52)
    }

    void 'a published record reads back as its fields, with an equals sign in a value intact'() {
        when:
        Map<String, String> fields = DsmAuthorityService.parseRecord("kind=OWNER\nwallet=W\nsigningKey=abc=\nno separator here")

        then:
        fields == [kind: "OWNER", wallet: "W", signingKey: "abc="]
    }
}
