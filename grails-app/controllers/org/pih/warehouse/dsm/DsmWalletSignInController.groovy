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

    DsmAuthorityService dsmAuthorityService
    DsmWalletService dsmWalletService

    /** The sign-in page, with a fresh code for this browser. */
    def index() {
        if (session.user) {
            // Already signed in: a stale tab or a second visit goes where it was going.
            redirect(uri: sensible(params.next) ?: sensible(session.targetUri as String) ?: "/dashboard/index")
            return
        }
        try {
            ConnectAppOfferV1 offer = dsmWalletService.offerToWallet()
            session.dsmOfferDigest = Base32Crockford.encode(offer.offerDigest.toByteArray())
            session.dsmOfferCode = offer.code
            [code: offer.code, failure: null, next: sensible(session.targetUri as String)]
        } catch (DsmCustodyException | DsmHostException refused) {
            [code: null, failure: refused.message, next: sensible(session.targetUri as String)]
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
            // A browser whose sign-in already finished, on a poll that missed the answer, is told so again.
            render((session.user ? [state: "SIGNED_IN", redirect: afterSignIn(params.next)] : [state: "WAITING"]) as JSON)
            return
        }
        if (System.currentTimeMillis() - (session.dsmSignInAt as long) > SIGN_IN_REQUEST_MS) {
            session.dsmSignInWallet = null
            render([state: "FAILED", reason: "That request has expired. Ask again."] as JSON)
            return
        }
        try {
            DsmWallet wallet = DsmWallet.get(session.dsmSignInWallet)
            Map answer = dsmWalletService.signatureAnswer(wallet, session.dsmSignInSeq as long, session.dsmSignInStatement as String)
            if (answer.state == "DECLINED") {
                session.dsmSignInWallet = null
                render([state: "DECLINED", reason: answer.reason] as JSON)
                return
            }
            if (answer.state == "WAITING") {
                render([state: "WAITING"] as JSON)
                return
            }
            session.dsmSignInWallet = null
            finishSignIn(wallet, session.dsmSignInStatement as String, answer.body as byte[], answer.signature as byte[], params.next)
        } catch (DsmCustodyException | DsmHostException refused) {
            render([state: "FAILED", reason: refused.message] as JSON)
        }
    }

    /** Four characters a person can compare between two screens. */
    private static String browserCode() {
        return (1..4).collect { BROWSER_CODE_LETTERS[RANDOM.nextInt(BROWSER_CODE_LETTERS.length())] }.join("")
    }

    private void finishSignIn(DsmWallet wallet, String statement, byte[] answerBody, byte[] answerSignature, String next) {
        Map seat = dsmWalletService.signInFor(wallet)
        if (!seat) {
            render([state: "NO_SEAT", deviceId: wallet.deviceId.take(8)] as JSON)
            return
        }
        User user = seat.user as User
        // What the host's session proved this browser's wallet to be. It lives here, out of reach of any database edit.
        dsmAuthorityService.record(DsmAuthorityService.KIND_SIGN_IN, [wallet: seat.device as String, user: user.id,
                                                                      statement: statement ?: "Connected by scanning this browser's code"],
                answerBody, answerSignature)
        session.dsmDevice = seat.device
        session.dsmGenesis = seat.genesis
        session.user = user
        session.userName = user.username
        session.warehouse = seat.location as Location
        session.timezone = TimeZone.getTimeZone(user.timezone ?: "America/New_York")
        session.dsmOfferDigest = null
        session.dsmOfferCode = null
        render([state: "SIGNED_IN", redirect: afterSignIn(next)] as JSON)
    }

    /**
     * Where this browser was going when it was sent to sign in, as the password login honours it; else the dashboard.
     * The page carries it (`next`) because a stale tab's polling can replace the session the destination was saved in.
     */
    private String afterSignIn(String next) {
        String target = sensible(next) ?: sensible(session.targetUri as String)
        session.targetUri = null
        return target ? "${request.contextPath}${target}".toString() : createLink(controller: "dashboard", action: "index")
    }

    /** A path within this application, or nothing. */
    /** Pages only: a poll or an API read that was refused while signed out is never where a person was going. */
    private static final List<String> NOT_PAGES = ["/api/", "/dsmWallet/timeline", "/dsmWallet/state", "/dsmWallet/reconcile",
                                                   "/dsmPhone/state", "/dsmWalletSignIn", "/errors/", "/static/"]

    private static String sensible(String target) {
        if (!target || !target.startsWith("/") || target.startsWith("//") || target == "/") {
            return null
        }
        return NOT_PAGES.any { String prefix -> target.startsWith(prefix) } ? null : target
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
            finishSignIn(wallet, null, null, null, params.next)
        } catch (DsmCustodyException | DsmHostException refused) {
            render([state: "FAILED", reason: refused.message] as JSON)
        }
    }
}
