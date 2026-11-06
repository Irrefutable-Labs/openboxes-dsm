package org.pih.warehouse.dsm

import com.google.protobuf.ByteString
import dsm.types.proto.BalanceGetResponse
import dsm.types.proto.BalancesListResponse
import dsm.types.proto.ConnectAppOfferV1
import dsm.types.proto.ConnectAppRequestIntentV1
import dsm.types.proto.ConnectAppStatusV1
import dsm.types.proto.ConnectFact
import dsm.types.proto.ConnectSignV1
import dsm.types.proto.ConnectOutcome
import dsm.types.proto.ConnectPayV1
import dsm.types.proto.ConnectReplyV1
import dsm.types.proto.ConnectRequestRefV1
import dsm.types.proto.ConnectSessionStatus
import dsm.types.proto.ConnectSessionV1
import dsm.types.proto.EscrowCreatedResponse
import dsm.types.proto.EscrowLockOutcomeV1
import dsm.types.proto.EscrowLockRequest
import dsm.types.proto.EscrowVaultV1
import dsm.types.proto.EscrowVerdictRequest
import dsm.types.proto.EscrowVerdictResponse
import dsm.types.proto.EscrowVerdictState
import dsm.types.proto.Envelope
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.RoleType
import org.pih.warehouse.core.User
import org.pih.warehouse.dsm.DsmHostClient.Route

/**
 * People and their authority, through their own DSM wallets (DSM Amendment A11, as Wildstate connects its players).
 * A wallet connects by scanning a code. Levels of access are tokens made in a wallet, seats are units of them locked in
 * vaults on the storage nodes (SoFi section 19.9), and what a person may do here is read from those vaults each time
 * they sign in. Everything goes through DSM's own routes; nothing here decides what a vault means.
 */
@Transactional
class DsmWalletService {

    /** The outcome an assigning authority decides, and the one the owner can decide over any authority. */
    static final String OUTCOME_REVOKE = DsmAuthorityService.OUTCOME_REVOKE
    static final String OUTCOME_OWNER_REVOKE = DsmAuthorityService.OUTCOME_OWNER_REVOKE

    /**
     * How often a wallet can be asked to sign in, so nobody can flood a person's phone with requests: once in 30 seconds
     * from one client, and a bounded number in ten minutes from everyone. Someone determined can still use up the second
     * limit and hold a person out until the window passes; that is the price of bounding what reaches their phone.
     */
    private static final long SIGN_IN_CLIENT_INTERVAL_MS = 30000L
    private static final long SIGN_IN_WALLET_WINDOW_MS = 600000L
    private static final int SIGN_IN_WALLET_MAX = 12
    private static final Map<String, Long> SIGN_IN_LAST_BY_CLIENT = new HashMap<String, Long>()
    private static final Map<String, List<Long>> SIGN_IN_RECENT_BY_WALLET = new HashMap<String, List<Long>>()

    /** One owner claim at a time: the first to publish wins, and the second sees it. */
    private static final ReentrantLock CLAIM = new ReentrantLock()
    /** The one-time setup code's file, in the data root, readable only by the account the server runs as. */
    private static final String SETUP_CODE_FILE = "setup-code"
    private static final String SETUP_CODE_LETTERS = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    private static final int SETUP_CODE_LENGTH = 12
    private static final SecureRandom RANDOM = new SecureRandom()

    DsmAccountService dsmAccountService
    DsmAuthorityService dsmAuthorityService
    GrailsApplication grailsApplication

    // ------------------------------------------------------------------ connecting

    /**
     * The administrator account a fresh installation's owner is bound to when their wallet claims the organization: the
     * oldest user holding an administrator role. OpenBoxes needs a user behind every session; the wallet is the login.
     */
    User firstAdministrator() {
        User admin = User.list(sort: "dateCreated", order: "asc").find { User u ->
            u.roles?.any { it.roleType in [RoleType.ROLE_SUPERUSER, RoleType.ROLE_ADMIN] }
        }
        if (!admin) {
            throw new DsmCustodyException("this installation has no administrator account to bind the owner to")
        }
        return admin
    }

    /**
     * A fresh installation's account, made and funded on first run so the owner can be claimed: the server's own host,
     * named in its configuration, for the installation's first warehouse. Nothing from a request decides any of it.
     */
    DsmWarehouseAccount firstRunAccount(String origin, String dataDir, String registryLabel) {
        DsmWarehouseAccount home = DsmWarehouseAccount.count() ? homeAccount() : null
        if (!home) {
            if (!origin || !dataDir) {
                throw new DsmCustodyException("this server was started without a DSM host, so nothing can be claimed")
            }
            if (registryLabel?.trim()) {
                // a product registry's instance: release authority, no custody, and no warehouse account ever
                home = dsmAccountService.registerRegistry(registryLabel.trim(), origin, dataDir)
            } else {
                Location warehouse = Location.list(sort: "dateCreated", order: "asc").find { Location l -> l.active && !l.parentLocation }
                if (!warehouse) {
                    throw new DsmCustodyException("this installation has no warehouse to attach a DSM account to")
                }
                home = dsmAccountService.registerAccount(warehouse, origin, dataDir)
            }
        }
        try {
            dsmAccountService.claimNetworkFees(home)
        } catch (DsmHostException | DsmCustodyException busy) {
            // the network's faucet answers "retry later" while others claim; the next look at this page tries again
            log.info("first run: network fees not claimed yet: ${busy.message}")
        }
        return home
    }

    /** The account people connect to: the first warehouse account this organization registered. */
    DsmWarehouseAccount homeAccount() {
        DsmWarehouseAccount home = DsmWarehouseAccount.findAllByLocationIsNotNull([sort: "dateCreated", order: "asc", max: 1])[0]
                ?: DsmWarehouseAccount.findAllByLocationIsNull([sort: "dateCreated", order: "asc", max: 1])[0]
        if (!home) {
            throw new DsmCustodyException("this organization has no DSM account yet")
        }
        return home
    }

    /** What this organization is called in the words a wallet signs: its warehouse, or a registry's label. */
    String homeName() {
        DsmWarehouseAccount home = homeAccount()
        return home.location?.name ?: home.label
    }

    /** Where an owner's session stands: the home warehouse, or on a registry's instance the installation's first location. */
    Location homeLocation() {
        return homeAccount().location ?: Location.list(sort: "dateCreated", order: "asc").find { Location l -> l.active && !l.parentLocation }
    }

    /** A connect code for a person's wallet to scan. */
    ConnectAppOfferV1 offerToWallet() {
        return dsmAccountService.offerConnection(homeAccount(), false)
    }

    /**
     * The wallet that has answered `offerDigest`, by the session the account now holds; null until one has. A `name` is
     * kept for a wallet no OpenBoxes user will ever stand behind, a carrier's for instance.
     */
    DsmWallet walletAnswering(String offerDigest, String name = null) {
        DsmWarehouseAccount home = homeAccount()
        byte[] digest = Base32Crockford.decodeId32(offerDigest, "the offer digest")
        ConnectSessionV1 session = dsmAccountService.sessionsHeldBy(home).find { ConnectSessionV1 held ->
            held.offerDigest.toByteArray() == digest && held.status == ConnectSessionStatus.CONNECT_SESSION_STATUS_CONNECTED
        }
        if (!session) {
            return null
        }
        String deviceId = Base32Crockford.encode(session.peerDeviceId.toByteArray())
        DsmWallet wallet = DsmWallet.findByDeviceId(deviceId) ?: new DsmWallet(account: home, deviceId: deviceId)
        wallet.genesis = Base32Crockford.encode(session.peerGenesis.toByteArray())
        wallet.signingKey = Base64.encoder.encodeToString(session.peerSigningKey.toByteArray())
        wallet.sessionId = Base32Crockford.encode(session.sessionId.toByteArray())
        wallet.offerDigest = offerDigest
        if (name?.trim() && !wallet.user) {
            wallet.name = name.trim().take(120)
        }
        wallet.save(failOnError: true, flush: true)
        return wallet
    }

    // ------------------------------------------------------------------ the first run's setup code

    /** Where the setup code lives: the DSM data root (`openboxes.dsm.dataRoot`), which only the server's operator can read. */
    File setupCodeFile() {
        String root = grailsApplication.config.getProperty("openboxes.dsm.dataRoot", String) ?:
                grailsApplication.config.getProperty("openboxes.dsm.host.dataDir", String)
        if (!root) {
            throw new DsmCustodyException("openboxes.dsm.dataRoot is not configured: there is nowhere to keep the setup code")
        }
        return new File(root, SETUP_CODE_FILE)
    }

    /**
     * The one-time setup code a fresh installation's claim page requires. Made on first need and written to the data
     * root with permissions 0600, so only whoever runs the server can read it: the claim page is reachable by anyone
     * until an owner exists, and the code keeps the claim with the installer. Deleted once the owner's claim is published.
     */
    String setupCode() {
        File file = setupCodeFile()
        CLAIM.lock()
        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                String code = (1..SETUP_CODE_LENGTH).collect { SETUP_CODE_LETTERS[RANDOM.nextInt(SETUP_CODE_LETTERS.length())] }.join("")
                try {
                    Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------")
                    Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(ownerOnly))
                    Files.setPosixFilePermissions(file.toPath(), ownerOnly)
                } catch (UnsupportedOperationException noPosix) {
                    file.createNewFile()
                    file.setReadable(false, false); file.setReadable(true, true)
                    file.setWritable(false, false); file.setWritable(true, true)
                }
                file.text = code + "\n"
                log.warn("no owner yet: the setup code for the first claim is in ${file.canonicalPath}")
            }
            return file.text.trim()
        } finally {
            CLAIM.unlock()
        }
    }

    /** Whether `given` is the setup code, compared in constant time. Nothing without an owner proceeds until it is. */
    boolean setupCodeGiven(String given) {
        if (!given?.trim()) {
            return false
        }
        return MessageDigest.isEqual(setupCode().getBytes("UTF-8"), given.trim().toUpperCase().getBytes("UTF-8"))
    }

    /**
     * The first wallet an administrator connects becomes the organization's owner, once it has signed that on its own phone
     * and this account has published the claim. Called again as the browser waits: `asked` is what the last call returned.
     *
     * @return `owner` once the claim is published, with the proven `device` and `genesis`; otherwise what was asked, if anything
     */
    Map claimOwnership(String offerDigest, User administrator, Map asked) {
        CLAIM.lock()
        try {
            return claimOwnershipLocked(offerDigest, administrator, asked)
        } finally {
            CLAIM.unlock()
        }
    }

    private Map claimOwnershipLocked(String offerDigest, User administrator, Map asked) {
        DsmWallet wallet = walletAnswering(offerDigest)
        Map<String, String> claim = dsmAuthorityService.ownerClaim()
        if (claim) {
            if (wallet?.deviceId == claim.wallet && claim.user == administrator.id) {
                return [owner: true, device: wallet.deviceId, genesis: wallet.genesis]
            }
            throw new DsmCustodyException("this organization already has an owner's wallet")
        }
        if (!wallet) {
            return [:]
        }
        ConnectSessionV1 held = dsmAuthorityService.sessionOf(wallet.sessionId)
        if (!held) {
            throw new DsmCustodyException("that wallet's session is not connected")
        }
        String device = Base32Crockford.encode(held.peerDeviceId.toByteArray())
        if (!asked) {
            String statement = "I own ${homeName()} on DSM. Account ${dsmAuthorityService.hostDevice()}. Wallet ${device}. User ${administrator.username}.".toString()
            return [asked: [seq: askToSign(wallet, statement), statement: statement]]
        }
        Map answer = signatureAnswer(wallet, asked.seq as long, asked.statement as String)
        if (answer.state == "WAITING") {
            return [asked: asked]
        }
        if (answer.state != "SIGNED") {
            throw new DsmCustodyException("the wallet did not sign the claim: ${answer.reason ?: "declined on the phone"}")
        }
        dsmAuthorityService.record(DsmAuthorityService.KIND_OWNER, [
                wallet    : device,
                genesis   : Base32Crockford.encode(held.peerGenesis.toByteArray()),
                signingKey: Base64.encoder.encodeToString(held.peerSigningKey.toByteArray()),
                user      : administrator.id,
                statement : asked.statement as String,
        ], answer.body as byte[], answer.signature as byte[])
        wallet.user = administrator
        wallet.save(failOnError: true, flush: true)
        try {
            setupCodeFile().delete()
        } catch (DsmCustodyException | SecurityException ignored) {
            // the code served its one purpose; the owner's claim now refuses every later claim on its own
        }
        return [owner: true, device: device, genesis: wallet.genesis]
    }

    /**
     * Who this wallet may sign in as, read now: the session the host holds names the wallet, and DSM decides the rest.
     *
     * @return the user, the location to work at and the proven wallet; null when it holds nothing here
     */
    Map signInFor(DsmWallet wallet) {
        ConnectSessionV1 held = dsmAuthorityService.sessionOf(wallet.sessionId)
        if (!held || !wallet.user) {
            log.warn("sign-in for ${wallet.deviceId.take(8)}: ${held ? "no user bound" : "the host holds no connected session " + wallet.sessionId.take(8)}")
            return null
        }
        String device = Base32Crockford.encode(held.peerDeviceId.toByteArray())
        String genesis = Base32Crockford.encode(held.peerGenesis.toByteArray())
        Map authority = dsmAuthorityService.authorityOf(device, genesis, wallet.user)
        if (!authority.owner && !authority.seats) {
            log.warn("sign-in for ${wallet.deviceId.take(8)} as ${wallet.user.username}: DSM shows no authority (owner claim ${dsmAuthorityService.ownerClaim()?.wallet?.take(8)}, candidates ${authority.seats?.size()})")
            return null
        }
        return [user: wallet.user, device: device, genesis: genesis,
                location: authority.owner ? homeLocation() : authority.seats.first().location]
    }

    /**
     * Ask a connected wallet to sign its person in. A connected wallet cannot answer a new code, so it is asked over the
     * session it already has: a signing request, which no grant covers and which waits on the person's phone (Apps,
     * Waiting). The words name the organization, the person and the code the asking browser shows, so someone who types
     * another person's name gets in only if that person approves a code they cannot see on their own screen.
     *
     * @return the wallet, the request's number and the words asked; null for a name no wallet here is bound to, or one asked too recently
     */
    Map askToSignIn(String username, String client, String code) {
        User user = username?.trim() ? User.findByUsernameOrEmail(username.trim(), username.trim()) : null
        DsmWallet wallet = user ? DsmWallet.findByUser(user) : null
        if (!wallet) {
            return null
        }
        if (!reserveSignInAsk(wallet.id, client)) {
            return null
        }
        String statement = "Sign in to ${homeName()} as ${user.username}. Browser code ${code}.".toString()
        return [walletId: wallet.id, seq: askToSign(wallet, statement), statement: statement]
    }

    private long askToSign(DsmWallet wallet, String statement) {
        ConnectAppRequestIntentV1 intent = ConnectAppRequestIntentV1.newBuilder()
                .setSessionId(ByteString.copyFrom(Base32Crockford.decodeId32(wallet.sessionId, "the session id")))
                .setSign(ConnectSignV1.newBuilder().setStatement(statement))
                .build()
        return DsmSteps.reply(
                dsmAccountService.clientFor(wallet.account).connect("connect.app.request", intent, Route.INVOKE),
                ConnectReplyV1.ReplyCase.REQUEST, "connect.app.request").request.seq
    }

    /** Takes one sign-in ask if the limits allow it, and forgets whatever has aged out, so the record stays bounded. */
    private static synchronized boolean reserveSignInAsk(String walletId, String client) {
        long now = System.currentTimeMillis()
        SIGN_IN_LAST_BY_CLIENT.values().removeAll { Long at -> now - at >= SIGN_IN_CLIENT_INTERVAL_MS }
        SIGN_IN_RECENT_BY_WALLET.values().each { List<Long> times -> times.removeAll { Long at -> now - at >= SIGN_IN_WALLET_WINDOW_MS } }
        SIGN_IN_RECENT_BY_WALLET.values().removeAll { List<Long> times -> times.isEmpty() }
        String asker = "${walletId}|${client}".toString()
        List<Long> recent = SIGN_IN_RECENT_BY_WALLET.get(walletId)
        if (SIGN_IN_LAST_BY_CLIENT.containsKey(asker) || (recent != null && recent.size() >= SIGN_IN_WALLET_MAX)) {
            return false
        }
        SIGN_IN_LAST_BY_CLIENT.put(asker, now)
        if (recent == null) {
            recent = new ArrayList<Long>()
            SIGN_IN_RECENT_BY_WALLET.put(walletId, recent)
        }
        recent.add(now)
        return true
    }

    /**
     * What has become of a sign-in request: the wallet's proof, verified by the account, or its refusal.
     *
     * @return PROVEN or WAITING, or DECLINED with the wallet's reason after it
     */
    Map signatureAnswer(DsmWallet wallet, long seq, String statement) {
        ConnectRequestRefV1 ref = ConnectRequestRefV1.newBuilder()
                .setSessionId(ByteString.copyFrom(Base32Crockford.decodeId32(wallet.sessionId, "the session id")))
                .setSeq(seq)
                .build()
        Envelope envelope = dsmAccountService.clientFor(wallet.account).invokeQuietly("connect.app.status", ref)
        ConnectAppStatusV1 status = DsmHostClient
                .expect("connect.app.status", envelope, Envelope.PayloadCase.CONNECT_REPLY)
                .connectReply.status
        if (status.fact == ConnectFact.CONNECT_FACT_SIGNED && status.signedStatement == statement) {
            return [state: "SIGNED", body: status.answerBody.toByteArray(), signature: status.answerSignature.toByteArray()]
        }
        if (status.answered && status.outcome in [ConnectOutcome.CONNECT_OUTCOME_DECLINED, ConnectOutcome.CONNECT_OUTCOME_FAILED]) {
            return [state: "DECLINED", reason: status.outcome == ConnectOutcome.CONNECT_OUTCOME_FAILED ? status.reason : ""]
        }
        return [state: "WAITING"]
    }

    // ------------------------------------------------------------------ levels of access

    /**
     * A level of access, from the anchor of a token its creator made in their own wallet. The account roots it by
     * fetching the policy itself and re-hashing it to the anchor.
     */
    DsmAccessLevel addLevel(String name, String roleType, String policyAnchor, DsmWallet creator) {
        Base32Crockford.decodeId32(policyAnchor, "the token's anchor")
        String ticker = dsmAccountService.rootAnchorAt(homeAccount(), policyAnchor)
        DsmAccessLevel level = new DsmAccessLevel(
                name: name?.trim(),
                roleType: roleType,
                policyAnchor: policyAnchor,
                ticker: ticker,
                creator: creator,
        )
        level.save(failOnError: true, flush: true)
        return level
    }

    /**
     * Ask the level's creator to release `quantity` seats to the organization's account. It is a payment request no
     * grant covers, so DSM holds it on the creator's phone (Apps, Waiting) until they approve it there.
     */
    long requestSeats(DsmAccessLevel level, long quantity) {
        if (quantity < 1) {
            throw new DsmCustodyException("release at least one seat")
        }
        ConnectAppRequestIntentV1 intent = ConnectAppRequestIntentV1.newBuilder()
                .setSessionId(ByteString.copyFrom(Base32Crockford.decodeId32(level.creator.sessionId, "the session id")))
                .setPay(ConnectPayV1.newBuilder()
                        .setPolicyCommit(ByteString.copyFrom(Base32Crockford.decodeId32(level.policyAnchor, "the anchor")))
                        .setAmount(quantity)
                        .setMemo("openboxes:seats ${level.name}".toString().take(96)))
                .build()
        return DsmSteps.reply(
                dsmAccountService.clientFor(homeAccount()).connect("connect.app.request", intent, Route.INVOKE),
                ConnectReplyV1.ReplyCase.REQUEST, "connect.app.request").request.seq
    }

    /** What the account holds of a level's token and the supply its policy fixed, as its own balance list reports them. */
    Map supplyOf(DsmAccessLevel level) {
        BalanceGetResponse row = balanceRow(homeAccount(), level.policyAnchor)
        return [spare: row ? row.available : 0L, cap: row?.genesisSupplyDisplay ?: null]
    }

    // ------------------------------------------------------------------ seats

    /**
     * Assign a seat: the account locks one unit in a vault whose terms name the holder and whose outcomes say who can
     * take it back. The assigning authority decides `revoke`; the owner can decide `revoke-by-owner`, so a seat outlives
     * the authority who assigned it. Both pay the account. The holder has no outcome: nothing they do moves the seat.
     */
    DsmSeat assignSeat(DsmAccessLevel level, DsmWallet holder, Location location, DsmWallet authority,
                       String firstName, String lastName) {
        if (!authority) {
            throw new DsmCustodyException("sign in with your wallet to assign a seat: its key is what can revoke it")
        }
        if (holder.id == authority.id) {
            throw new DsmCustodyException("a seat is assigned by someone with authority over its holder, not by them")
        }
        Map<String, String> claim = dsmAuthorityService.ownerClaim()
        if (!claim) {
            throw new DsmCustodyException("this organization has no owner yet")
        }
        ConnectSessionV1 held = dsmAuthorityService.sessionOf(holder.sessionId)
        if (!held || Base32Crockford.encode(held.peerDeviceId.toByteArray()) != holder.deviceId) {
            throw new DsmCustodyException("this account's session does not name that wallet")
        }
        DsmWarehouseAccount home = homeAccount()
        if (home.deviceId != dsmAuthorityService.hostDevice()) {
            throw new DsmCustodyException("this account's record names another device than its host")
        }
        if (!holder.user) {
            holder.user = newUser(holder, location, firstName, lastName)
            holder.save(failOnError: true, flush: true)
        }
        int number = ((DsmSeat.createCriteria().get {
            eq("level", level)
            projections { max("number") }
        } ?: 0) as int) + 1
        String agreement = DsmAuthorityService.seatAgreement(home.deviceId, level.policyAnchor, level.roleType, number, location.id,
                holder.user.id, Base32Crockford.encode(held.peerGenesis.toByteArray()), holder.deviceId)
        ByteString accountDevice = deviceBytes(home.deviceId)
        EscrowLockRequest.Builder lock = EscrowLockRequest.newBuilder()
                .setExternal(ByteString.copyFromUtf8(agreement))
                .setTokenPolicyCommit(ByteString.copyFrom(Base32Crockford.decodeId32(level.policyAnchor, "the anchor")))
                .setAmountEntered("1")
                .addOutcomes(outcome(OUTCOME_REVOKE, authority.deviceId, accountDevice))
        if (claim.wallet != authority.deviceId) {
            lock.addOutcomes(outcome(OUTCOME_OWNER_REVOKE, claim.wallet, accountDevice))
        }
        Envelope envelope = dsmAccountService.clientFor(home).invoke("escrow.lock", lock.build())
        EscrowCreatedResponse created = DsmHostClient
                .expect("escrow.lock", envelope, Envelope.PayloadCase.ESCROW_CREATED_RESPONSE)
                .escrowCreatedResponse
        if (created.externalCommitment.toByteArray() != DsmAuthorityService.externalCommitment(agreement)) {
            throw new DsmCustodyException("DSM committed to something other than this seat's agreement")
        }
        DsmSeat seat = new DsmSeat(
                level: level,
                number: number,
                holder: holder,
                authority: authority,
                location: location,
                account: home,
                agreement: agreement,
                vaultId: Base32Crockford.encode(created.vaultId.toByteArray()),
                verdictCell: Base32Crockford.encode(created.verdictCell.toByteArray()),
                externalCommitment: Base32Crockford.encode(created.externalCommitment.toByteArray()),
        )
        seat.save(failOnError: true, flush: true)
        dsmAuthorityService.record(DsmAuthorityService.KIND_SEAT_ASSIGNED, [
                seat: number.toString(), level: level.policyAnchor, role: level.roleType, wallet: holder.deviceId,
                user: holder.user.id, authority: authority.deviceId, vault: seat.vaultId,
        ])
        return seat
    }

    private static EscrowLockOutcomeV1 outcome(String name, String decidedBy, ByteString pays) {
        return EscrowLockOutcomeV1.newBuilder()
                .setOutcome(ByteString.copyFromUtf8(name))
                .addDecidedBy(deviceBytes(decidedBy))
                .setPays(pays)
                .build()
    }

    /**
     * What the seat's verdict cell says now. Once a revocation is final the account, which that outcome pays, releases the
     * vault: the seat is back with the organization without the holder's device taking any part.
     *
     * @return the verdict state's name and the outcome it names
     */
    /** Seats revoked while the account was still settling come back the next time the seats are shown. */
    void finishReturns() {
        DsmSeat.findAllByStatus(DsmSeat.STATUS_ASSIGNED).each { DsmSeat seat ->
            try {
                refreshSeat(seat)
            } catch (DsmHostException | DsmCustodyException unsettled) {
                log.info("seat ${seat.number} comes back later: ${unsettled.message}")
            }
        }
    }

    Map refreshSeat(DsmSeat seat) {
        EscrowVerdictRequest request = EscrowVerdictRequest.newBuilder()
                .setVaultId(ByteString.copyFrom(Base32Crockford.decodeId32(seat.vaultId, "the vault id")))
                .build()
        EscrowVerdictResponse verdict = DsmHostClient.expect("escrow.verdict", dsmAuthorityService.hostClient().invokeQuietly("escrow.verdict", request),
                Envelope.PayloadCase.ESCROW_VERDICT_RESPONSE).escrowVerdictResponse
        String outcome = verdict.outcome.toStringUtf8()
        if (seat.status == DsmSeat.STATUS_ASSIGNED && verdict.state == EscrowVerdictState.ESCROW_VERDICT_STATE_FINAL
                && outcome in [OUTCOME_REVOKE, OUTCOME_OWNER_REVOKE]) {
            dsmAuthorityService.carryOutReturn(seat, EscrowVaultV1.newBuilder()
                    .setVaultId(ByteString.copyFrom(Base32Crockford.decodeId32(seat.vaultId, "the vault id"))).build(), outcome)
        }
        return [state: verdict.state.name().replace("ESCROW_VERDICT_STATE_", ""), outcome: outcome]
    }

    // ------------------------------------------------------------------ what a seat gives in OpenBoxes

    private static User newUser(DsmWallet wallet, Location location, String firstName, String lastName) {
        if (!firstName?.trim() || !lastName?.trim()) {
            throw new DsmCustodyException("name the person this seat is for")
        }
        // They sign in with their wallet: this password is never given to anyone.
        byte[] unusable = new byte[32]
        new java.security.SecureRandom().nextBytes(unusable)
        User user = new User(
                username: usernameFor(firstName, lastName),
                password: Base64.encoder.encodeToString(unusable),
                firstName: firstName.trim(),
                lastName: lastName.trim(),
                active: true,
                warehouse: location,
                rememberLastLocation: true,
        )
        user.save(failOnError: true, flush: true)
        return user
    }

    /** The name a person types to sign in: their own, made unique. */
    private static String usernameFor(String firstName, String lastName) {
        String base = "${firstName.trim()}.${lastName.trim()}".toLowerCase().replaceAll(/[^a-z0-9.]/, "")
        String candidate = base
        int next = 2
        while (User.findByUsername(candidate)) {
            candidate = "${base}${next++}".toString()
        }
        return candidate
    }

    // ------------------------------------------------------------------ helpers

    private BalanceGetResponse balanceRow(DsmWarehouseAccount account, String anchor) {
        return balanceRows(account).find { BalanceGetResponse row -> row.policyAnchorB32 == anchor }
    }

    private List<BalanceGetResponse> balanceRows(DsmWarehouseAccount account) {
        Envelope envelope = dsmAccountService.clientFor(account).query("balance.list", new byte[0])
        return DsmHostClient
                .expect("balance.list", envelope, Envelope.PayloadCase.BALANCES_LIST_RESPONSE)
                .balancesListResponse.balancesList
    }

    private static ByteString deviceBytes(String deviceId) {
        return ByteString.copyFrom(Base32Crockford.decodeId32(deviceId, "a device id"))
    }
}
