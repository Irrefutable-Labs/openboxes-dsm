package org.pih.warehouse.dsm

import com.google.protobuf.ByteString
import dsm.types.proto.AmountDirection
import dsm.types.proto.TransactionInfo
import dsm.types.proto.TransactionType
import spock.lang.Specification

class DsmCarriageServiceSpec extends Specification {

    // Device ids as DSM encodes them: 32 bytes, so they read back from the rows exactly as written.
    private static final String ACCOUNT = device(1)
    private static final String CARRIER = device(2)
    private static final String PARTNER = device(3)
    private static final String OTHER = device(4)
    private static final String MEMO = "openboxes:shipment:834JBY"

    private static String device(int fill) {
        byte[] bytes = new byte[32]
        Arrays.fill(bytes, (byte) fill)
        return Base32Crockford.encode(bytes)
    }

    private static TransactionInfo credit(String from, String ticker = "NGCUST", long amount = 120L, String memo = MEMO,
                                          AmountDirection direction = AmountDirection.AMOUNT_DIRECTION_CREDIT,
                                          TransactionType type = TransactionType.TX_TYPE_ONLINE) {
        return TransactionInfo.newBuilder()
                .setId("row-${from.take(4)}-${amount}".toString())
                .setTxType(type)
                .setDirection(direction)
                .setFromDeviceId(ByteString.copyFrom(Base32Crockford.decodeId32(from, "from")))
                .setToDeviceId(ByteString.copyFrom(Base32Crockford.decodeId32(ACCOUNT, "to")))
                .setTokenId(ticker)
                .setAmount(amount)
                .setMemo(memo)
                .build()
    }

    void 'a hand-over is the custody units a wallet paid the account under the shipment memo'() {
        when:
        Map handover = DsmCarriageService.handoverIn([credit(CARRIER)], "NGCUST", MEMO, [ACCOUNT, PARTNER])

        then:
        handover.required
        handover.units == 120L
        handover.by == CARRIER
        handover.transferId == "row-${CARRIER.take(4)}-120".toString()
    }

    void 'units from another organization account, another token, another memo, a debit or an offline row do not count'() {
        expect:
        DsmCarriageService.handoverIn(rows, "NGCUST", MEMO, [ACCOUNT, PARTNER]).units == 0L

        where:
        rows << [
                [credit(PARTNER)],
                [credit(CARRIER, "AMX500")],
                [credit(CARRIER, "NGCUST", 120L, "openboxes:shipment:OTHER")],
                [credit(CARRIER, "NGCUST", 120L, MEMO, AmountDirection.AMOUNT_DIRECTION_DEBIT)],
                [credit(CARRIER, "NGCUST", 120L, MEMO, AmountDirection.AMOUNT_DIRECTION_CREDIT, TransactionType.TX_TYPE_BILATERAL_OFFLINE)],
                [],
        ]
    }

    void 'a payment made on a request this account sent over a connected wallet session carries the memo behind its reference'() {
        given:
        String session = device(9)
        String paid = "dsm-connect:${session}:3 ${MEMO}".toString()

        expect:
        DsmCarriageService.handoverIn([credit(CARRIER, "NGCUST", 120L, paid)], "NGCUST", MEMO, [ACCOUNT], [session]).units == 120L
        DsmCarriageService.handoverIn([credit(CARRIER, "NGCUST", 120L, paid)], "NGCUST", MEMO, [ACCOUNT], [device(8)]).units == 0L
        DsmCarriageService.handoverIn([credit(CARRIER, "NGCUST", 120L, paid)], "NGCUST", MEMO, [ACCOUNT]).units == 0L
        !DsmCarriageService.paysFor("dsm-connect:${session}:3 openboxes:shipment:OTHER".toString(), MEMO, [session])
        !DsmCarriageService.paysFor("dsm-connect:${session}:3".toString(), MEMO, [session])
        DsmCarriageService.paysFor(MEMO, MEMO, [])
    }

    void 'two hand-overs of one shipment add up, and the first wallet is the one named'() {
        when:
        Map handover = DsmCarriageService.handoverIn([credit(CARRIER, "NGCUST", 70L), credit(OTHER, "NGCUST", 50L)],
                "NGCUST", MEMO, [ACCOUNT])

        then:
        handover.units == 120L
        handover.by == CARRIER
    }

    void 'a shipment of two products is one carriage of all its units'() {
        expect:
        DsmCarriageService.unitsOf([new DsmCustodyTransfer(quantity: 120), new DsmCustodyTransfer(quantity: 30)]) == 150
        DsmCarriageService.unitsOf([]) == 0
    }
}
