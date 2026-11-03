package org.pih.warehouse.dsm

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import dsm.types.proto.ConnectAppOfferV1
import grails.converters.JSON
import java.security.SecureRandom

import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User

/**
 * Signing in with a wallet: the page shows a connect code, the person scans it in their wallet (Apps, Scan code) and
 * approves it on their phone, and this browser is signed in as whoever that wallet is, once the storage nodes show a
 * seat for it. What the browser keeps afterwards is an ordinary login session, never DSM authority.
 */
class DsmWalletSignInController {

    /** How long a browser waits on one sign-in request. */
    private static final long SIGN_IN_REQUEST_MS = 300000L
    private static final String BROWSER_CODE_LETTERS = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    private static final SecureRandom RANDOM = new SecureRandom()

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

    /**
     * A connected wallet's person names themselves; their phone is asked to sign, in words naming the code this browser
     * shows. The person approves only if the two match, so an approval signs in this browser and no other. The answer is
     * the same for any name.
     */
    def ask() {
        try {
            String code = browserCode()
            Map asked = dsmWalletService.askToSignIn(params.username, request.remoteAddr, code)
            String name = params.username?.trim()
            if (asked) {
                session.dsmSignInWallet = asked.walletId
                session.dsmSignInSeq = asked.seq
                session.dsmSignInStatement = asked.statement
                session.dsmSignInCode = code
                session.dsmSignInName = name
                session.dsmSignInAt = System.currentTimeMillis()
            } else if (session.dsmSignInName != name) {
                // Nothing new was asked. A request this browser made for this same name stays; one for another name
                // must not sign it in as someone it no longer asked to be.
                session.dsmSignInWallet = null
            }
            render([state: "ASKED", code: session.dsmSignInWallet ? session.dsmSignInCode : code] as JSON)
        } catch (DsmCustodyException | DsmHostException refused) {
            render([state: "FAILED", reason: refused.message] as JSON)
        }
    }

    /** What the phone answered; signs the browser in once the wallet has signed this browser's words and a seat stands. */
    def answer() {
        if (!session.dsmSignInWallet) {
            render([state: "WAITING"] as JSON)
            return
        }
        if (System.currentTimeMillis() - (session.dsmSignInAt as long) > SIGN_IN_REQUEST_MS) {
            session.dsmSignInWallet = null
            render([state: "FAILED", reason: "That request has expired. Ask again."] as JSON)
            return
        }
        try {
            DsmWallet wallet = DsmWallet.get(session.dsmSignInWallet)
            String answer = dsmWalletService.signInAnswer(wallet, session.dsmSignInSeq as long, session.dsmSignInStatement as String)
            if (answer.startsWith("DECLINED")) {
                session.dsmSignInWallet = null
                render([state: "DECLINED", reason: answer.substring("DECLINED".length()).trim()] as JSON)
                return
            }
            if (answer == "WAITING") {
                render([state: "WAITING"] as JSON)
                return
            }
            session.dsmSignInWallet = null
            finishSignIn(wallet)
        } catch (DsmCustodyException | DsmHostException refused) {
            render([state: "FAILED", reason: refused.message] as JSON)
        }
    }

    /** Four characters a person can compare between two screens. */
    private static String browserCode() {
        return (1..4).collect { BROWSER_CODE_LETTERS[RANDOM.nextInt(BROWSER_CODE_LETTERS.length())] }.join("")
    }

    private void finishSignIn(DsmWallet wallet) {
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
