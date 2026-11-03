package org.pih.warehouse.dsm

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import dsm.types.proto.ConnectAppOfferV1
import grails.converters.JSON

import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User

/**
 * Signing in with a wallet: the page shows a connect code, the person scans it in their wallet (Apps, Scan code) and
 * approves it on their phone, and this browser is signed in as whoever that wallet is, once the storage nodes show a
 * seat for it. What the browser keeps afterwards is an ordinary login session, never DSM authority.
 */
class DsmWalletSignInController {

    DsmWalletService dsmWalletService

    /** The sign-in page, with a fresh code for this browser. */
    def index() {
        try {
            ConnectAppOfferV1 offer = dsmWalletService.offerToWallet()
            session.dsmOfferDigest = Base32Crockford.encode(offer.offerDigest.toByteArray())
            session.dsmOfferCode = offer.code
            [code: offer.code, failure: null]
        } catch (DsmCustodyException | DsmHostException refused) {
            [code: null, failure: refused.message]
        }
    }

    /** The current code as a QR image. */
    def qr() {
        String code = params.code ?: session.dsmOfferCode
        if (!code) {
            response.status = 404
            return
        }
        response.contentType = "image/png"
        MatrixToImageWriter.writeToStream(
                new QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 360, 360), "PNG", response.outputStream)
        response.outputStream.flush()
    }

    /** What has become of this browser's code; signs the browser in once a wallet with a standing seat has answered. */
    def status() {
        String digest = session.dsmOfferDigest
        if (!digest) {
            render([state: "NO_CODE"] as JSON)
            return
        }
        try {
            DsmWallet wallet = dsmWalletService.walletAnswering(digest)
            if (!wallet) {
                render([state: "WAITING"] as JSON)
                return
            }
            Map seat = dsmWalletService.signInFor(wallet)
            if (!seat) {
                render([state: "NO_SEAT", deviceId: wallet.deviceId.take(8)] as JSON)
                return
            }
            User user = seat.user as User
            session.user = user
            session.userName = user.username
            session.warehouse = seat.location as Location
            session.timezone = TimeZone.getTimeZone(user.timezone ?: "America/New_York")
            session.dsmOfferDigest = null
            session.dsmOfferCode = null
            render([state: "SIGNED_IN", redirect: createLink(controller: "dashboard", action: "index")] as JSON)
        } catch (DsmCustodyException | DsmHostException refused) {
            render([state: "FAILED", reason: refused.message] as JSON)
        }
    }
}
