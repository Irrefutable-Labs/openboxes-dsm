package org.pih.warehouse.dsm

import dsm.types.proto.AppHostActivityEntryV1
import dsm.types.proto.AppHostActivityV1
import dsm.types.proto.ConnectAppOfferV1
import grails.converters.JSON
import org.springframework.http.HttpStatus

import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User
import org.pih.warehouse.product.ProductAvailability

/**
 * The organization's DSM screen: wallets, levels of access, seats, partners, product tokens, admissions and custody
 * that has arrived. Everyone uses this and their wallet; nobody needs an API. It also feeds the WEB2/DSM overlay.
 */
class DsmWalletController {

    DsmAuthorityService dsmAuthorityService
    DsmWalletService dsmWalletService
    DsmAccountService dsmAccountService
    DsmReconcileService dsmReconcileService
    DsmCarriageService dsmCarriageService

    def index() {
        [locations: Location.list().findAll { Location location -> !location.parentLocation }]
    }

    def state() {
        attempt {
            DsmWarehouseAccount home = DsmWarehouseAccount.findAllByLocationIsNotNull([sort: "dateCreated", order: "asc", max: 1])[0]
            String ownerDevice = dsmAuthorityService.ownerClaim()?.wallet
            dsmWalletService.finishReturns()
            [
                    authority   : dsmAuthorityService.objects().collect { dsm.types.proto.AuthoredObjectReadV1 object ->
                        Map<String, String> fields = DsmAuthorityService.parseRecord(object.payload.toStringUtf8())
                        [kind: fields.kind, wallet: fields.wallet?.take(8), statement: fields.statement ?: fields.role, at: fields.at,
                         address: Base32Crockford.encode(object.address.toByteArray()).take(10)]
                    }.reverse(),
                    account     : home ? [deviceId: home.deviceId, location: home.location.name] : null,
                    canAttach   : !home && grailsApplication.config.getProperty("openboxes.dsm.host.origin", String) != null,
                    me          : walletOfSession()?.with { DsmWallet w -> [id: w.id, owner: w.deviceId == ownerDevice] },
                    wallets     : DsmWallet.list(sort: "dateCreated").collect { DsmWallet w ->
                        [id: w.id, label: w.label, deviceId: w.deviceId.take(8), owner: w.deviceId == ownerDevice,
                         seats: DsmSeat.countByHolderAndStatus(w, DsmSeat.STATUS_ASSIGNED)]
                    },
                    levels      : DsmAccessLevel.list(sort: "dateCreated").collect { DsmAccessLevel l ->
                        Map supply = dsmWalletService.supplyOf(l)
                        [id: l.id, name: l.name, role: l.roleType, ticker: l.ticker, anchor: l.policyAnchor,
                         creator: l.creator.label, cap: supply.cap, spare: supply.spare,
                         assigned: DsmSeat.countByLevelAndStatus(l, DsmSeat.STATUS_ASSIGNED)]
                    },
                    seats       : DsmSeat.list(sort: "dateCreated", order: "desc").collect { DsmSeat s ->
                        [id: s.id, level: s.level.name, number: s.number, holder: s.holder.label,
                         authority: s.authority.label, location: s.location.name, status: s.status,
                         cell: s.verdictCell, vault: s.vaultId.take(10)]
                    },
                    custody     : custodyTokens(home),
                    carriages   : DsmCarriage.list(sort: "dateCreated", order: "desc").collect { DsmCarriage c ->
                        [kind: c.kind, memo: c.memo, units: c.units, wallet: c.walletDeviceId.take(8), ticker: c.token.ticker,
                         at: c.dateCreated]
                    },
            ]
        }
    }

    /** Attach this organization to the DSM account this server was set up with. */
    def attachAccount() {
        attempt {
            String origin = grailsApplication.config.getProperty("openboxes.dsm.host.origin", String)
            String dataDir = grailsApplication.config.getProperty("openboxes.dsm.host.dataDir", String)
            DsmWarehouseAccount account = dsmAccountService.registerAccount(Location.get(session.warehouse.id), origin, dataDir)
            [deviceId: account.deviceId]
        }
    }

    /** A connect code for a wallet to scan from this screen. */
    def offer() {
        attempt {
            ConnectAppOfferV1 offer = dsmWalletService.offerToWallet()
            [code: offer.code, offerDigest: Base32Crockford.encode(offer.offerDigest.toByteArray())]
        }
    }

    /** The wallet that answered a code; `purpose` says what the connection is for. */
    def answered() {
        attempt {
            String digest = params.offerDigest
            if (params.purpose == "owner") {
                Map step = dsmWalletService.claimOwnership(digest, User.get(session.user.id), session.dsmOwnerAsk as Map)
                session.dsmOwnerAsk = step.asked
                if (step.owner) {
                    session.dsmDevice = step.device
                    session.dsmGenesis = step.genesis
                }
                return [answered: step.owner == true, waiting: step.asked != null, deviceId: (step.device as String)?.take(8)]
            }
            // a person who will never hold a seat here, a carrier for instance, is named when their wallet connects
            DsmWallet wallet = dsmWalletService.walletAnswering(digest, params.name)
            if (wallet && params.purpose == "registry") {
                dsmAccountService.confirmConnection(dsmWalletService.homeAccount(), digest, null, true)
            }
            [answered: wallet != null, deviceId: wallet?.deviceId?.take(8)]
        }
    }

    /** The custody tokens this organization knows: its own, with the units its account can dispatch, and its partners'. */
    private Map custodyTokens(DsmWarehouseAccount home) {
        if (!home) {
            return [own: null, partners: [], connections: []]
        }
        DsmCustodyToken own = dsmCarriageService.ownToken()
        [
                own        : own ? [id: own.id, ticker: own.ticker, anchor: own.policyAnchor, creator: own.creator.label,
                                    spare: dsmCarriageService.spareUnits()] : null,
                partners   : DsmCustodyToken.findAllByPeerIsNotNull().collect { DsmCustodyToken t ->
                    [id: t.id, ticker: t.ticker, anchor: t.policyAnchor, peer: t.peer.toString()]
                },
                connections: DsmConnection.findAllByAccountAndPeerLocationIsNotNull(home).collect { DsmConnection c ->
                    [id: c.id, name: c.toString()]
                },
        ]
    }

    def addCustodyToken() {
        attempt {
            DsmCustodyToken token = params.connectionId
                    ? dsmCarriageService.addPartnerToken(params.policyAnchor?.trim(), DsmConnection.get(params.connectionId))
                    : dsmCarriageService.addOwnToken(params.policyAnchor?.trim(), DsmWallet.get(params.creatorId))
            [id: token.id, ticker: token.ticker]
        }
    }

    def requestCustodyUnits() {
        attempt { [seq: dsmCarriageService.requestUnits((params.quantity ?: "0") as long)] }
    }

    /** Hand a sent shipment to a carrier: the account pays its custody units to the carrier's wallet. */
    def dispatch() {
        attempt {
            DsmCarriage carriage = dsmCarriageService.dispatch(params.memo, DsmWallet.get(params.walletId))
            [units: carriage.units, ticker: carriage.token.ticker, wallet: carriage.walletDeviceId.take(8)]
        }
    }

    /** Ask a carrier's wallet to hand a partner's shipment over to this organization's account. */
    def askHandover() {
        attempt {
            DsmWarehouseAccount home = dsmWalletService.homeAccount()
            DsmConnection peer = DsmConnection.findByAccountAndPeerDeviceId(home, params.peerDeviceId)
            if (!peer) {
                throw new DsmCustodyException("no partner ${params.peerDeviceId} is connected to ${home}")
            }
            [seq: dsmCarriageService.askHandover(peer, params.memo, (params.units ?: "0") as int, DsmWallet.get(params.walletId))]
        }
    }

    def addLevel() {
        attempt {
            DsmAccessLevel level = dsmWalletService.addLevel(params.name, params.roleType, params.policyAnchor,
                    DsmWallet.get(params.creatorId))
            [id: level.id, ticker: level.ticker]
        }
    }

    def requestSeats() {
        attempt {
            [seq: dsmWalletService.requestSeats(DsmAccessLevel.get(params.levelId), (params.quantity ?: "0") as long)]
        }
    }

    def assignSeat() {
        attempt {
            DsmSeat seat = dsmWalletService.assignSeat(DsmAccessLevel.get(params.levelId), DsmWallet.get(params.walletId),
                    Location.get(params.locationId), walletOfSession(), params.firstName, params.lastName)
            [id: seat.id, cell: seat.verdictCell]
        }
    }

    def refreshSeat() {
        attempt { dsmWalletService.refreshSeat(DsmSeat.get(params.id)) }
    }

    /** The overlay's data: what the host recorded, what OpenBoxes did, and the custody each side reports. */
    def timeline() {
        attempt {
            DsmWarehouseAccount home = dsmWalletService.homeAccount()
            AppHostActivityV1 activity = dsmAccountService.clientFor(home).activity((params.after ?: "0") as long)
            [
                    next          : activity.next,
                    deviceId      : Base32Crockford.encode(activity.deviceId.toByteArray()),
                    storageSetId  : Base32Crockford.encode(activity.storageSetId.toByteArray()),
                    signatureBytes: activity.signatureBytes,
                    dsm           : activity.entriesList.collect { AppHostActivityEntryV1 e ->
                        [seq: e.seq, name: e.name, summary: e.summary, result: e.result, error: e.error,
                         relay: e.kind.name().endsWith("RELAY"),
                         before: e.hasPositionBefore() ? e.positionBefore : null,
                         after: e.hasPositionAfter() ? e.positionAfter : null,
                         root: e.econRootAfter.empty ? null : Base32Crockford.encode(e.econRootAfter.toByteArray()).take(12),
                         deviceTree: e.deviceTreeAfter.empty ? null : Base32Crockford.encode(e.deviceTreeAfter.toByteArray()).take(12)]
                    },
                    web2          : DsmTimeline.since((params.web2After ?: "0") as long),
                    custody       : custody(),
            ]
        }
    }

    /** Whether the books agree with DSM, now, and every finding so far. */
    def reconcile() {
        attempt { reconcileJson(dsmReconcileService.measure()) }
    }

    def checkNow() {
        attempt { reconcileJson(dsmReconcileService.runChecks()) }
    }

    /** Only the owner's wallet moves the books to match DSM. */
    def correctBooks() {
        attempt {
            if (!dsmAuthorityService.current().owner) {
                throw new DsmCustodyException("only the owner corrects the books: sign in with the owner's wallet")
            }
            DsmReconcileEvent event = dsmReconcileService.correctBooks(params.accountId, params.productId, params.reason,
                    User.get(session.user.id))
            [transactionNumber: event.transactionNumber, signed: event.signedAddress != null]
        }
    }

    private Map reconcileJson(List<Map> rows) {
        return [
                rows  : rows.collect { Map r ->
                    [accountId: r.account.id, location: r.account.location.name, productId: r.product.id, productCode: r.product.productCode,
                     dsmHolding: r.dsmHolding, undrawn: r.undrawn, inTransit: r.inTransit, books: r.books, cached: r.cached, gap: r.gap,
                     value: (r.product.pricePerUnit ?: BigDecimal.ZERO) * (r.gap as long)]
                },
                events: DsmReconcileEvent.list(sort: "dateCreated", order: "desc", max: 30).collect { DsmReconcileEvent e ->
                    [kind: e.kind, location: e.account.location.name, productCode: e.product.productCode, gap: e.gap, books: e.books,
                     dsmHolding: e.dsmHolding, note: e.note, by: e.recordedBy?.name ?: "reconciler", transactionNumber: e.transactionNumber,
                     signedAddress: e.signedAddress, at: e.dateCreated]
                },
        ]
    }

    private List<Map> custody() {
        DsmProductToken.list().collect { DsmProductToken token ->
            BigDecimal price = token.product.pricePerUnit ?: BigDecimal.ZERO
            DsmWarehouseAccount.findAllByLocationIsNotNull().collect { DsmWarehouseAccount account ->
                long held = dsmAccountService.balanceOf(account, token)
                Long onHand = ProductAvailability.executeQuery(
                        "select coalesce(sum(quantityOnHand), 0) from ProductAvailability where product = :p and location = :l",
                        [p: token.product, l: account.location])[0] as Long
                [product: token.product.name, code: token.product.productCode, location: account.location.name,
                 dsmUnits: held, onHand: onHand, unitPrice: price, dsmValue: price * held]
            }
        }.flatten() as List<Map>
    }

    private DsmWallet walletOfSession() {
        return session.dsmDevice ? DsmWallet.findByDeviceId(session.dsmDevice as String) : null
    }

    private void attempt(Closure action) {
        try {
            render([data: action()] as JSON)
        } catch (DsmCustodyException | DsmHostException refused) {
            response.status = HttpStatus.BAD_REQUEST.value()
            render([errorMessage: refused.message] as JSON)
        }
    }
}
