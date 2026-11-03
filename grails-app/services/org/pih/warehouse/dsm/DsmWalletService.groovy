package org.pih.warehouse.dsm

import com.google.protobuf.ByteString
import dsm.types.proto.BalanceGetResponse
import dsm.types.proto.BalancesListResponse
import dsm.types.proto.ConnectAppOfferV1
import dsm.types.proto.ConnectAppRequestIntentV1
import dsm.types.proto.ConnectAppStatusV1
import dsm.types.proto.ConnectFact
import dsm.types.proto.ConnectHoldingsV1
import dsm.types.proto.ConnectOutcome
import dsm.types.proto.ConnectPayV1
import dsm.types.proto.ConnectReplyV1
import dsm.types.proto.ConnectRequestRefV1
import dsm.types.proto.ConnectSessionStatus
import dsm.types.proto.ConnectSessionV1
import dsm.types.proto.EscrowCreatedResponse
import dsm.types.proto.EscrowLockOutcomeV1
import dsm.types.proto.EscrowLockRequest
import dsm.types.proto.EscrowLockedRequest
import dsm.types.proto.EscrowReleaseRequest
import dsm.types.proto.EscrowVaultV1
import dsm.types.proto.EscrowVerdictRequest
import dsm.types.proto.EscrowVerdictResponse
import dsm.types.proto.EscrowVerdictState
import dsm.types.proto.Envelope
import dsm.types.proto.SofiVaultStatus
import grails.gorm.transactions.Transactional
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.Role
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
    static final String OUTCOME_REVOKE = "revoke"
    static final String OUTCOME_OWNER_REVOKE = "revoke-by-owner"

    DsmAccountService dsmAccountService

    // ------------------------------------------------------------------ connecting

    /** The account people connect to: the first warehouse account this organization registered. */
    DsmWarehouseAccount homeAccount() {
        DsmWarehouseAccount home = DsmWarehouseAccount.findAllByLocationIsNotNull([sort: "dateCreated", order: "asc", max: 1])[0]
        if (!home) {
            throw new DsmCustodyException("this organization has no DSM account yet")
        }
        return home
    }

    /** A connect code for a person's wallet to scan. */
    ConnectAppOfferV1 offerToWallet() {
        return dsmAccountService.offerConnection(homeAccount(), false)
    }

    /** The wallet that has answered `offerDigest`, by the session the account now holds; null until one has. */
    DsmWallet walletAnswering(String offerDigest) {
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
        wallet.save(failOnError: true, flush: true)
        return wallet
    }

    /** The first wallet an administrator connects becomes the organization's owner, bound to that administrator. */
    DsmWallet claimOwnership(String offerDigest, User administrator) {
        DsmWallet wallet = walletAnswering(offerDigest)
        DsmWallet owner = DsmWallet.findByOwner(true)
        if (owner) {
            if (wallet && wallet.id == owner.id) {
                return owner
            }
            throw new DsmCustodyException("this organization already has an owner's wallet")
        }
        if (wallet) {
            wallet.owner = true
            wallet.user = administrator
            wallet.save(failOnError: true, flush: true)
        }
        return wallet
    }

    /**
     * Who this wallet may sign in as, read now from the storage nodes: the owner, or a holder of at least one seat whose
     * vault is still Active with no decision against it.
     *
     * @return the user and the location to work at; null when the wallet holds nothing here
     */
    Map signInFor(DsmWallet wallet) {
        if (wallet.owner && wallet.user) {
            return [user: wallet.user, location: homeAccount().location]
        }
        List<DsmSeat> standing = DsmSeat.findAllByHolderAndStatus(wallet, DsmSeat.STATUS_ASSIGNED).findAll { DsmSeat seat ->
            vaultStands(seat)
        }
        syncRoles(wallet)
        if (!standing || !wallet.user) {
            return null
        }
        return [user: wallet.user, location: standing.first().location]
    }

    /**
     * Ask a connected wallet to sign its person in. A connected wallet cannot answer a new code, so it is asked over the
     * session it already has: a holdings proof no grant covers, which waits on the person's phone (Apps, Waiting) until
     * they approve it there. The wallet signs the proof and the account verifies it, so only that phone can answer.
     *
     * @return the session and the request's number; null for a name no wallet here is bound to
     */
    Map askToSignIn(String username) {
        User user = username?.trim() ? User.findByUsernameOrEmail(username.trim(), username.trim()) : null
        DsmWallet wallet = user ? DsmWallet.findByUser(user) : null
        if (!wallet) {
            return null
        }
        // DSM requires a holdings request to name a token, and every wallet can prove what it holds of ERA, the
        // network's own token. What is proven is who answered, not how much they hold.
        BalanceGetResponse era = balanceRows(wallet.account).find { BalanceGetResponse row -> row.symbol == "ERA" }
        if (!era?.policyAnchorB32) {
            throw new DsmCustodyException("this organization's account does not list ERA yet")
        }
        ConnectHoldingsV1.Builder holdings = ConnectHoldingsV1.newBuilder()
                .addPolicyCommits(ByteString.copyFrom(Base32Crockford.decodeId32(era.policyAnchorB32, "ERA's anchor")))
        ConnectAppRequestIntentV1 intent = ConnectAppRequestIntentV1.newBuilder()
                .setSessionId(ByteString.copyFrom(Base32Crockford.decodeId32(wallet.sessionId, "the session id")))
                .setHoldings(holdings)
                .build()
        long seq = DsmSteps.reply(
                dsmAccountService.clientFor(wallet.account).connect("connect.app.request", intent, Route.INVOKE),
                ConnectReplyV1.ReplyCase.REQUEST, "connect.app.request").request.seq
        return [walletId: wallet.id, seq: seq]
    }

    /**
     * What has become of a sign-in request: the wallet's proof, verified by the account, or its refusal.
     *
     * @return PROVEN or WAITING, or DECLINED with the wallet's reason after it
     */
    String signInAnswer(DsmWallet wallet, long seq) {
        ConnectRequestRefV1 ref = ConnectRequestRefV1.newBuilder()
                .setSessionId(ByteString.copyFrom(Base32Crockford.decodeId32(wallet.sessionId, "the session id")))
                .setSeq(seq)
                .build()
        Envelope envelope = dsmAccountService.clientFor(wallet.account).invokeQuietly("connect.app.status", ref)
        ConnectAppStatusV1 status = DsmHostClient
                .expect("connect.app.status", envelope, Envelope.PayloadCase.CONNECT_REPLY)
                .connectReply.status
        if (status.fact == ConnectFact.CONNECT_FACT_HOLDINGS) {
            return "PROVEN"
        }
        if (status.answered && status.outcome in [ConnectOutcome.CONNECT_OUTCOME_DECLINED, ConnectOutcome.CONNECT_OUTCOME_FAILED]) {
            return "DECLINED ${status.outcome == ConnectOutcome.CONNECT_OUTCOME_FAILED ? status.reason : ""}".toString().trim()
        }
        return "WAITING"
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
        DsmWarehouseAccount home = homeAccount()
        int number = ((DsmSeat.createCriteria().get {
            eq("level", level)
            projections { max("number") }
        } ?: 0) as int) + 1
        String agreement = [
                "openboxes/seat/v1", home.deviceId, level.policyAnchor, number, holder.genesis, holder.deviceId,
        ].join("|")
        ByteString accountDevice = deviceBytes(home.deviceId)
        EscrowLockRequest.Builder lock = EscrowLockRequest.newBuilder()
                .setExternal(ByteString.copyFromUtf8(agreement))
                .setTokenPolicyCommit(ByteString.copyFrom(Base32Crockford.decodeId32(level.policyAnchor, "the anchor")))
                .setAmountEntered("1")
                .addOutcomes(outcome(OUTCOME_REVOKE, authority.deviceId, accountDevice))
        DsmWallet owner = DsmWallet.findByOwner(true)
        if (owner && owner.id != authority.id) {
            lock.addOutcomes(outcome(OUTCOME_OWNER_REVOKE, owner.deviceId, accountDevice))
        }
        Envelope envelope = dsmAccountService.clientFor(home).invoke("escrow.lock", lock.build())
        EscrowCreatedResponse created = DsmHostClient
                .expect("escrow.lock", envelope, Envelope.PayloadCase.ESCROW_CREATED_RESPONSE)
                .escrowCreatedResponse
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
        if (!holder.user) {
            holder.user = newUser(holder, location, firstName, lastName)
            holder.save(failOnError: true, flush: true)
        }
        syncRoles(holder)
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
     * What the seat's verdict cell says now. Once a revocation is final the account, which that outcome pays, releases
     * the vault: the seat is back with the organization without the holder's device taking any part.
     *
     * @return the verdict state's name and the outcome it names
     */
    Map refreshSeat(DsmSeat seat) {
        EscrowVerdictResponse verdict = verdictOf(seat)
        String outcome = verdict.outcome.toStringUtf8()
        if (seat.status == DsmSeat.STATUS_ASSIGNED && verdict.state == EscrowVerdictState.ESCROW_VERDICT_STATE_FINAL
                && outcome in [OUTCOME_REVOKE, OUTCOME_OWNER_REVOKE]) {
            EscrowReleaseRequest release = EscrowReleaseRequest.newBuilder()
                    .setVaultId(ByteString.copyFrom(Base32Crockford.decodeId32(seat.vaultId, "the vault id")))
                    .build()
            Envelope envelope = dsmAccountService.clientFor(seat.account).invoke("escrow.release", release)
            DsmHostClient.expect("escrow.release", envelope, Envelope.PayloadCase.SOFI_POSITION_RESPONSE)
            seat.status = DsmSeat.STATUS_RETURNED
            seat.dateReturned = new Date()
            seat.save(failOnError: true, flush: true)
            syncRoles(seat.holder)
        }
        return [state: verdict.state.name().replace("ESCROW_VERDICT_STATE_", ""), outcome: outcome]
    }

    /** The vault itself, walked to its head on the storage nodes: Active, one unit, under this seat's commitment, undecided. */
    boolean vaultStands(DsmSeat seat) {
        if (seat.status != DsmSeat.STATUS_ASSIGNED || verdictOf(seat).state != EscrowVerdictState.ESCROW_VERDICT_STATE_NONE) {
            return false
        }
        EscrowLockedRequest request = EscrowLockedRequest.newBuilder()
                .setVerdictCell(ByteString.copyFrom(Base32Crockford.decodeId32(seat.verdictCell, "the verdict cell")))
                .build()
        Envelope envelope = dsmAccountService.clientFor(seat.account).invokeQuietly("escrow.locked", request)
        List<EscrowVaultV1> vaults = DsmHostClient
                .expect("escrow.locked", envelope, Envelope.PayloadCase.ESCROW_VAULTS_RESPONSE)
                .escrowVaultsResponse.vaultsList
        return vaults.any { EscrowVaultV1 vault ->
            Base32Crockford.encode(vault.vaultId.toByteArray()) == seat.vaultId &&
                    vault.status == SofiVaultStatus.SOFI_VAULT_STATUS_ACTIVE &&
                    vault.amount == 1L &&
                    Base32Crockford.encode(vault.externalCommitment.toByteArray()) == seat.externalCommitment &&
                    Base32Crockford.encode(vault.tokenPolicyCommit.toByteArray()) == seat.level.policyAnchor
        }
    }

    private EscrowVerdictResponse verdictOf(DsmSeat seat) {
        EscrowVerdictRequest request = EscrowVerdictRequest.newBuilder()
                .setVaultId(ByteString.copyFrom(Base32Crockford.decodeId32(seat.vaultId, "the vault id")))
                .build()
        Envelope envelope = dsmAccountService.clientFor(seat.account).invokeQuietly("escrow.verdict", request)
        return DsmHostClient.expect("escrow.verdict", envelope, Envelope.PayloadCase.ESCROW_VERDICT_RESPONSE)
                .escrowVerdictResponse
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

    /** A person's roles are exactly what their standing seats give: a returned seat stops giving anything. */
    void syncRoles(DsmWallet wallet) {
        User user = wallet.user
        if (!user || wallet.owner) {
            return
        }
        List<RoleType> managed = [RoleType.ROLE_ADMIN, RoleType.ROLE_MANAGER, RoleType.ROLE_ASSISTANT, RoleType.ROLE_BROWSER]
        new ArrayList<Role>(user.roles ?: []).findAll { Role role -> role.roleType in managed }.each { Role role ->
            user.removeFromRoles(role)
        }
        DsmSeat.findAllByHolderAndStatus(wallet, DsmSeat.STATUS_ASSIGNED)*.level*.roleType.toSet().each { String name ->
            user.addToRoles(Role.findByRoleType(RoleType.valueOf(name)))
        }
        user.save(failOnError: true, flush: true)
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
