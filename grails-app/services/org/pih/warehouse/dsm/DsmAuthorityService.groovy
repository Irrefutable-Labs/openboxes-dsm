package org.pih.warehouse.dsm

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import dsm.types.proto.AuthoredObjectReadV1
import dsm.types.proto.AuthoredObjectsResponse
import dsm.types.proto.AuthoredPublishRequestV1
import dsm.types.proto.AuthoredReadRequestV1
import dsm.types.proto.ConnectReplyV1
import dsm.types.proto.ConnectSessionStatus
import dsm.types.proto.ConnectSessionV1
import dsm.types.proto.EscrowLockedRequest
import dsm.types.proto.EscrowReleaseRequest
import dsm.types.proto.EscrowSignerV1
import dsm.types.proto.EscrowVaultOutcomeV1
import dsm.types.proto.EscrowVaultV1
import dsm.types.proto.EscrowVerdictRequest
import dsm.types.proto.EscrowVerdictResponse
import dsm.types.proto.EscrowVerdictState
import dsm.types.proto.Envelope
import dsm.types.proto.SofiVaultStatus
import grails.gorm.transactions.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.bouncycastle.crypto.digests.Blake3Digest
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.hibernate.FlushMode
import org.hibernate.Session
import org.pih.warehouse.core.Role
import org.pih.warehouse.core.RoleType
import org.pih.warehouse.core.User
import org.pih.warehouse.dsm.DsmHostClient.Route
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder

/**
 * Who may do what, once an organization has an owner: DSM says, and nothing in this application's database does.
 *
 * Every answer starts from three things a database edit cannot reach: the host named in this server's launch settings, the
 * sessions that host holds, and what is on the storage nodes. The owner is the first owner's claim this account published.
 * A person holds a seat only if a vault this account locked stands now, with a commitment recomputed here over the organization,
 * the token, the role, the seat, the person and their proven wallet. A row can name a candidate. It cannot make one pass.
 *
 * Someone who controls the host itself holds the account's keys, and is outside what this can stop.
 */
class DsmAuthorityService {

    static final String KIND_OWNER = "OWNER"
    static final String KIND_SIGN_IN = "SIGN_IN"
    static final String KIND_SEAT_ASSIGNED = "SEAT_ASSIGNED"
    static final String KIND_SEAT_RETURNED = "SEAT_RETURNED"

    static final String OUTCOME_REVOKE = "revoke"
    static final String OUTCOME_OWNER_REVOKE = "revoke-by-owner"

    /** The name these objects are published under. */
    private static final byte[] TOPIC = MessageDigest.getInstance("SHA-256")
            .digest("openboxes/authority/v1".getBytes(StandardCharsets.UTF_8))
    private static final String NO_OWNER_READ = "dsmNoOwnerRead"
    private static final String AUTHORITY = "dsmAuthority"

    def grailsApplication
    def sessionFactory

    /** The first owner's claim on the storage nodes. It never changes once published, so holding it is not a second answer. */
    private volatile Map<String, String> ownerClaimRead
    private volatile String hostDeviceRead

    /** DSM's external commitment over an agreement: `Y = H(DSM/external/v1 || 0 || X)`. */
    static byte[] externalCommitment(String agreement) {
        Blake3Digest digest = new Blake3Digest()
        byte[] tag = "DSM/external/v1".getBytes(StandardCharsets.US_ASCII)
        digest.update(tag, 0, tag.length)
        digest.update((byte) 0)
        byte[] body = agreement.getBytes(StandardCharsets.UTF_8)
        digest.update(body, 0, body.length)
        byte[] out = new byte[32]
        digest.doFinal(out, 0)
        return out
    }

    /** What a seat's vault commits to: the role and the person are in it, so no row can change either. */
    static String seatAgreement(String accountDevice, String anchor, String role, int number, String locationId, String userId,
                                String genesis, String device) {
        return ["openboxes/seat/v2", accountDevice, anchor, role, number, locationId, userId, genesis, device].join("|")
    }

    static Map<String, String> parseRecord(String payload) {
        return payload.readLines().findAll { String line -> line.contains("=") }.collectEntries { String line ->
            int at = line.indexOf("=")
            [(line.substring(0, at)): line.substring(at + 1)]
        }
    }

    private boolean hostConfigured() {
        return grailsApplication.config.getProperty("openboxes.dsm.host.origin", String)
    }

    /** This organization's host, from the launch settings: never from a row. */
    DsmHostClient hostClient() {
        String origin = grailsApplication.config.getProperty("openboxes.dsm.host.origin", String)
        String dataDir = grailsApplication.config.getProperty("openboxes.dsm.host.dataDir", String)
        if (!origin || !dataDir) {
            throw new DsmCustodyException("this server has no DSM host configured")
        }
        return new DsmHostClient(origin, new File(dataDir))
    }

    String hostDevice() {
        if (!hostDeviceRead) {
            hostDeviceRead = Base32Crockford.encode(hostClient().deviceId())
        }
        return hostDeviceRead
    }

    Map<String, String> ownerClaim() {
        if (ownerClaimRead || !hostConfigured()) {
            return ownerClaimRead
        }
        RequestAttributes request = RequestContextHolder.getRequestAttributes()
        if (request?.getAttribute(NO_OWNER_READ, RequestAttributes.SCOPE_REQUEST)) {
            return null
        }
        Map<String, String> found = objects().collect { AuthoredObjectReadV1 object ->
            parseRecord(object.payload.toStringUtf8())
        }.find { Map<String, String> fields -> fields.kind == KIND_OWNER }
        if (found) {
            ownerClaimRead = found
        } else {
            request?.setAttribute(NO_OWNER_READ, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST)
        }
        return found
    }

    boolean ownerExists() {
        return ownerClaim() != null
    }

    /** Every record this account published, in order, each checked against the account's key by DSM as it is read. */
    List<AuthoredObjectReadV1> objects() {
        AuthoredReadRequestV1 request = AuthoredReadRequestV1.newBuilder()
                .setAuthorDeviceId(ByteString.copyFrom(Base32Crockford.decodeId32(hostDevice(), "the account")))
                .setTopic(ByteString.copyFrom(TOPIC))
                .build()
        AuthoredObjectsResponse read = DsmHostClient
                .expect("authored.read", hostClient().query("authored.read", request), Envelope.PayloadCase.AUTHORED_OBJECTS_RESPONSE)
                .authoredObjectsResponse
        if (!read.complete) {
            throw new DsmHostException("authored.read", "the storage nodes did not give every authority record")
        }
        return read.objectsList
    }

    /** A connected session as the host holds it: this is what names a wallet. */
    ConnectSessionV1 sessionOf(String sessionId) {
        byte[] id = Base32Crockford.decodeId32(sessionId, "the session id")
        return DsmSteps.reply(hostClient().connect("connect.app.sessions", Empty.getDefaultInstance(), Route.QUERY),
                ConnectReplyV1.ReplyCase.SESSIONS, "connect.app.sessions").sessions.sessionsList.find { ConnectSessionV1 session ->
            session.sessionId.toByteArray() == id && session.status == ConnectSessionStatus.CONNECT_SESSION_STATUS_CONNECTED
        }
    }

    /**
     * What this proven wallet may do as this person, read now.
     *
     * @return `owner`, the role names of the seats that stand, and those seats
     */
    Map authorityOf(String device, String genesis, User user) {
        Map<String, String> claim = ownerClaim()
        if (claim && claim.wallet == device && claim.user == user.id) {
            return [owner: true, roles: [], seats: []]
        }
        List<DsmSeat> candidates = unflushed {
            DsmSeat.findAllByStatus(DsmSeat.STATUS_ASSIGNED).findAll { DsmSeat seat -> seat.holder.deviceId == device }
        }
        List<DsmSeat> standing = candidates.findAll { DsmSeat seat -> seatStands(seat, device, genesis, user, claim) }
        return [owner: false, roles: standing*.level*.roleType.unique(), seats: standing]
    }

    /** This request's answer, worked out once: each new request reads again. */
    Map current() {
        GrailsWebRequest request = RequestContextHolder.getRequestAttributes() as GrailsWebRequest
        Map known = request?.getAttribute(AUTHORITY, RequestAttributes.SCOPE_REQUEST) as Map
        if (known) {
            return known
        }
        def session = request?.currentRequest?.getSession(false)
        User user = session?.user ? User.load(session.user.id) : null
        String device = session?.dsmDevice
        Map answer = user && device ? authorityOf(device, session.dsmGenesis as String, user) : [owner: false, roles: [], seats: []]
        answer.userId = user?.id
        request?.setAttribute(AUTHORITY, answer, RequestAttributes.SCOPE_REQUEST)
        return answer
    }

    /** Roles for the person making this request. Nobody else's are read from here, and outside a request there is nobody. */
    Collection<Role> rolesFor(User user) {
        if (!RequestContextHolder.getRequestAttributes()) {
            return []
        }
        Map authority = current()
        if (!user?.id || authority.userId != user.id) {
            return []
        }
        return unflushed {
            authority.owner ? [Role.findByRoleType(RoleType.ROLE_SUPERUSER)]
                    : authority.roles.collect { String name -> Role.findByRoleType(RoleType.valueOf(name)) }
        }
    }

    /** The vault on the storage nodes, checked against everything the seat claims, and no verdict against it. */
    private boolean seatStands(DsmSeat seat, String device, String genesis, User user, Map<String, String> claim) {
        EscrowVaultV1 vault = vaultFor(seat, device, genesis, user, claim)
        if (!vault) {
            return false
        }
        EscrowVerdictResponse verdict = verdictOf(vault)
        String outcome = verdict.outcome.toStringUtf8()
        if (verdict.state == EscrowVerdictState.ESCROW_VERDICT_STATE_FINAL && outcome in [OUTCOME_REVOKE, OUTCOME_OWNER_REVOKE]) {
            carryOutReturn(seat, vault, outcome)
            return false
        }
        return verdict.state == EscrowVerdictState.ESCROW_VERDICT_STATE_NONE
    }

    private EscrowVaultV1 vaultFor(DsmSeat seat, String device, String genesis, User user, Map<String, String> claim) {
        if (!claim) {
            return null
        }
        String anchor = seat.level.policyAnchor
        byte[] commitment = externalCommitment(seatAgreement(hostDevice(), anchor, seat.level.roleType, seat.number,
                seat.location.id, user.id, genesis, device))
        byte[] account = Base32Crockford.decodeId32(hostDevice(), "the account")
        byte[] ownerKey = Base64.decoder.decode(claim.signingKey)
        EscrowLockedRequest request = EscrowLockedRequest.newBuilder()
                .setVerdictCell(ByteString.copyFrom(Base32Crockford.decodeId32(seat.verdictCell, "the verdict cell")))
                .build()
        return DsmHostClient.expect("escrow.locked", hostClient().invokeQuietly("escrow.locked", request),
                Envelope.PayloadCase.ESCROW_VAULTS_RESPONSE).escrowVaultsResponse.vaultsList.find { EscrowVaultV1 vault ->
            vault.ownerDeviceId.toByteArray() == account &&
                    vault.status == SofiVaultStatus.SOFI_VAULT_STATUS_ACTIVE &&
                    vault.amount == 1L &&
                    vault.externalCommitment.toByteArray() == commitment &&
                    Base32Crockford.encode(vault.tokenPolicyCommit.toByteArray()) == anchor &&
                    ownerCanRevoke(vault, ownerKey, account)
        }
    }

    /** The vault's own terms let the owner's key decide a revocation that pays this account. */
    private static boolean ownerCanRevoke(EscrowVaultV1 vault, byte[] ownerKey, byte[] account) {
        return vault.outcomesList.any { EscrowVaultOutcomeV1 outcome ->
            outcome.outcome.toStringUtf8() in [OUTCOME_REVOKE, OUTCOME_OWNER_REVOKE] &&
                    outcome.recipientDeviceId.toByteArray() == account &&
                    outcome.signersList.any { EscrowSignerV1 signer -> signer.publicKey.toByteArray() == ownerKey }
        }
    }

    private EscrowVerdictResponse verdictOf(EscrowVaultV1 vault) {
        EscrowVerdictRequest request = EscrowVerdictRequest.newBuilder().setVaultId(vault.vaultId).build()
        return DsmHostClient.expect("escrow.verdict", hostClient().invokeQuietly("escrow.verdict", request),
                Envelope.PayloadCase.ESCROW_VERDICT_RESPONSE).escrowVerdictResponse
    }

    /** A final revocation: the account, which that outcome pays, takes the seat back, and says so where it stays said. */
    @Transactional
    void carryOutReturn(DsmSeat seat, EscrowVaultV1 vault, String outcome) {
        EscrowReleaseRequest release = EscrowReleaseRequest.newBuilder().setVaultId(vault.vaultId).build()
        DsmHostClient.expect("escrow.release", hostClient().invoke("escrow.release", release), Envelope.PayloadCase.SOFI_POSITION_RESPONSE)
        seat.status = DsmSeat.STATUS_RETURNED
        seat.dateReturned = new Date()
        seat.save(failOnError: true, flush: true)
        record(KIND_SEAT_RETURNED, [seat: seat.number.toString(), level: seat.level.policyAnchor, wallet: seat.holder.deviceId,
                                    vault: Base32Crockford.encode(vault.vaultId.toByteArray()), outcome: outcome])
    }

    /**
     * Publish one authority change, signed by this account, and keep the wallet's own signature beside it: at about 50 KB that
     * does not fit in a published object, so the object carries its digest. If DSM cannot take the record, nothing goes ahead.
     */
    @Transactional
    DsmAuthorityRecord record(String kind, Map<String, String> fields, byte[] answerBody = null, byte[] answerSignature = null) {
        Map<String, String> all = new LinkedHashMap<String, String>()
        all.put("kind", kind)
        all.put("account", hostDevice())
        all.putAll(fields)
        if (answerBody) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256")
            digest.update(answerBody)
            all.put("answer", digest.digest(answerSignature).encodeHex().toString())
        }
        all.put("at", System.currentTimeMillis().toString())
        AuthoredPublishRequestV1 request = AuthoredPublishRequestV1.newBuilder()
                .setTopic(ByteString.copyFrom(TOPIC))
                .setPayload(ByteString.copyFromUtf8(all.collect { String k, String v -> "${k}=${v}" }.join("\n")))
                .build()
        String address = Base32Crockford.encode(DsmHostClient
                .expect("authored.publish", hostClient().invoke("authored.publish", request), Envelope.PayloadCase.AUTHORED_PUBLISHED_RESPONSE)
                .authoredPublishedResponse.address.toByteArray())
        DsmAuthorityRecord saved = new DsmAuthorityRecord(kind: kind, address: address, wallet: fields.wallet, statement: fields.statement,
                answerBody: answerBody, answerSignature: answerSignature)
        saved.save(failOnError: true, flush: true)
        if (kind == KIND_OWNER && !ownerClaimRead) {
            ownerClaimRead = all
        }
        return saved
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
