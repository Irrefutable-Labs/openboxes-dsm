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

    DsmWalletService dsmWalletService
    DsmAccountService dsmAccountService

    def index() {
        [locations: Location.list().findAll { Location location -> !location.parentLocation }]
    }

    def state() {
        attempt {
            DsmWarehouseAccount home = DsmWarehouseAccount.findAllByLocationIsNotNull([sort: "dateCreated", order: "asc", max: 1])[0]
            [
                    account     : home ? [deviceId: home.deviceId, location: home.location.name] : null,
                    canAttach   : !home && grailsApplication.config.getProperty("openboxes.dsm.host.origin", String) != null,
                    me          : walletOfSession()?.with { DsmWallet w -> [id: w.id, owner: w.owner] },
                    wallets     : DsmWallet.list(sort: "dateCreated").collect { DsmWallet w ->
                        [id: w.id, label: w.label, deviceId: w.deviceId.take(8), owner: w.owner,
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
            DsmWallet wallet
            if (params.purpose == "owner") {
                wallet = dsmWalletService.claimOwnership(digest, User.get(session.user.id))
            } else {
                wallet = dsmWalletService.walletAnswering(digest)
            }
            if (wallet && params.purpose == "registry") {
                dsmAccountService.confirmConnection(dsmWalletService.homeAccount(), digest, null, true)
            }
            [answered: wallet != null, deviceId: wallet?.deviceId?.take(8)]
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
        return session.user ? DsmWallet.findByUser(User.get(session.user.id)) : null
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
