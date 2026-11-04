package org.pih.warehouse.api

import dsm.types.proto.ConnectAppOfferV1
import grails.converters.JSON
import org.springframework.http.HttpStatus

import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User
import org.pih.warehouse.dsm.Base32Crockford
import org.pih.warehouse.dsm.DsmAccountService
import org.pih.warehouse.dsm.DsmAdmission
import org.pih.warehouse.dsm.DsmAdmissionService
import org.pih.warehouse.dsm.DsmCarriage
import org.pih.warehouse.dsm.DsmConnection
import org.pih.warehouse.dsm.DsmCustodyException
import org.pih.warehouse.dsm.DsmCustodyService
import org.pih.warehouse.dsm.DsmCustodyTransfer
import org.pih.warehouse.dsm.DsmHostException
import org.pih.warehouse.dsm.DsmProductToken
import org.pih.warehouse.dsm.DsmReleaseDecision
import org.pih.warehouse.dsm.DsmWarehouseAccount
import org.pih.warehouse.product.Product
import org.pih.warehouse.shipping.Shipment

/**
 * An organization's administrators and operators, on their own instance: their own accounts, tokens and sessions,
 * the admissions they ask for or (at a registry) decide, the custody peers have sent them, and a view of what their
 * accounts established (docs/plans/2026-11-01-dsm-custody-integration.md). Every call acts on this instance's own
 * accounts; a DSM refusal is answered as 400 with DSM's own reason.
 */
class DsmCustodyApiController extends BaseApiController {

    DsmAccountService dsmAccountService
    DsmAdmissionService dsmAdmissionService
    DsmCustodyService dsmCustodyService

    // ------------------------------------------------------------------ accounts

    def accounts() {
        render([data: DsmWarehouseAccount.list().collect { accountJson(it) }] as JSON)
    }

    def registerWarehouse() {
        attempt {
            accountJson(dsmAccountService.registerAccount(location(params.locationId), params.ingressOrigin, params.dataDir))
        }
    }

    def registerRegistry() {
        attempt {
            accountJson(dsmAccountService.registerRegistry(params.label, params.ingressOrigin, params.dataDir))
        }
    }

    def claimFees() {
        attempt { accountJson(dsmAccountService.claimNetworkFees(account(params.accountId))) }
    }

    // ------------------------------------------------------------------ tokens

    def tokens() {
        attempt {
            DsmProductToken.list().collect { DsmProductToken token -> tokenJson(token, holdings(token)) }
        }
    }

    def issueToken() {
        attempt {
            Product product = product(params.productId)
            DsmProductToken token = params.supply ?
                    dsmAccountService.issueProductToken(product, wholeNumber(params.supply, "supply")) :
                    dsmAccountService.issueProductToken(product)
            tokenJson(token, holdings(token))
        }
    }

    def adoptToken() {
        attempt {
            DsmProductToken token = dsmAccountService.adoptToken(product(params.productId), params.policyAnchor)
            tokenJson(token, holdings(token))
        }
    }

    // ------------------------------------------------------------------ this side of a session

    def connections() {
        render([data: DsmConnection.list().collect { connectionJson(it) }] as JSON)
    }

    def offerConnection() {
        attempt {
            ConnectAppOfferV1 offer = dsmAccountService.offerConnection(account(params.accountId), params.boolean("toRegistry", false))
            [code: offer.code, offerDigest: Base32Crockford.encode(offer.offerDigest.toByteArray())]
        }
    }

    def acceptConnection() {
        attempt {
            Location peerLocation = params.peerLocationId ? location(params.peerLocationId) : null
            connectionJson(dsmAccountService.acceptConnection(account(params.accountId), params.code, peerLocation))
        }
    }

    def confirmConnection() {
        attempt {
            Location peerLocation = params.peerLocationId ? location(params.peerLocationId) : null
            connectionJson(dsmAccountService.confirmConnection(account(params.accountId), params.offerDigest,
                    peerLocation, params.boolean("peerRegistry", false)))
        }
    }

    // ------------------------------------------------------------------ admissions: the organization's side

    def admissions() {
        attempt {
            DsmAdmission.list(sort: "dateCreated", order: "desc").collect { DsmAdmission admission ->
                admissionJson(dsmAdmissionService.refreshAdmission(admission))
            }
        }
    }

    def requestAdmission() {
        attempt {
            admissionJson(dsmAdmissionService.requestAdmission(account(params.accountId), product(params.productId),
                    wholeNumber(params.quantity, "quantity") as int, params.reference, currentUser()))
        }
    }

    // ------------------------------------------------------------------ releases: the registry's side

    def releases() {
        attempt { dsmAdmissionService.pendingReleases() }
    }

    def releaseDecisions() {
        render([data: DsmReleaseDecision.list(sort: "dateCreated", order: "desc").collect { decisionJson(it) }] as JSON)
    }

    def approveRelease() {
        attempt {
            dsmAdmissionService.approveRelease(params.sessionId, wholeNumber(params.seq, "seq"), currentUser())
        }
    }

    def declineRelease() {
        attempt {
            dsmAdmissionService.declineRelease(params.sessionId, wholeNumber(params.seq, "seq"), currentUser())
        }
    }

    // ------------------------------------------------------------------ custody peers sent

    def inbound() {
        attempt { dsmCustodyService.inboundCustody() }
    }

    def establishInbound() {
        attempt {
            Shipment shipment = dsmCustodyService.establishInbound(params.peerDeviceId, params.memo)
            [shipmentId: shipment.id, shipmentNumber: shipment.shipmentNumber]
        }
    }

    // ------------------------------------------------------------------ what the accounts established

    def transfers() {
        render([data: DsmCustodyTransfer.list(sort: "dateCreated", order: "desc").collect { transferJson(it) }] as JSON)
    }

    def proveHoldings() {
        attempt {
            DsmCustodyTransfer transfer = DsmCustodyTransfer.get(params.id)
            if (!transfer) {
                throw new DsmCustodyException("no custody transfer ${params.id}")
            }
            transferJson(dsmCustodyService.establishHoldingsProof(transfer))
        }
    }

    // ------------------------------------------------------------------ helpers

    private void attempt(Closure action) {
        try {
            render([data: action()] as JSON)
        } catch (DsmCustodyException | DsmHostException refused) {
            response.status = HttpStatus.BAD_REQUEST.value()
            render([errorCode: HttpStatus.BAD_REQUEST.value(), errorMessage: refused.message] as JSON)
        }
    }

    private User currentUser() {
        User user = User.get(session.user?.id)
        if (!user) {
            throw new DsmCustodyException("no signed-in user to act as")
        }
        return user
    }

    private static Location location(Object id) {
        Location location = Location.get(id as String)
        if (!location) {
            throw new DsmCustodyException("no location ${id}")
        }
        return location
    }

    private static Product product(Object id) {
        Product product = Product.get(id as String)
        if (!product) {
            throw new DsmCustodyException("no product ${id}")
        }
        return product
    }

    private static DsmWarehouseAccount account(Object id) {
        DsmWarehouseAccount account = DsmWarehouseAccount.get(id as String)
        if (!account) {
            throw new DsmCustodyException("no DSM account ${id}")
        }
        return account
    }

    private static long wholeNumber(Object value, String what) {
        try {
            return Long.parseLong(value?.toString() ?: "")
        } catch (NumberFormatException refused) {
            throw new DsmCustodyException("${what} must be a whole number, not ${value}", refused)
        }
    }

    private List<Map> holdings(DsmProductToken token) {
        return DsmWarehouseAccount.list().collect { DsmWarehouseAccount held ->
            [accountId: held.id, deviceId: held.deviceId, registry: held.registry,
             available: dsmAccountService.balanceOf(held, token)]
        }
    }

    static Map accountJson(DsmWarehouseAccount account) {
        return [
                id           : account.id,
                locationId   : account.location?.id,
                locationName : account.location?.name,
                label        : account.label,
                registry     : account.registry,
                ingressOrigin: account.ingressOrigin,
                deviceId     : account.deviceId,
        ]
    }

    static Map tokenJson(DsmProductToken token, List<Map> holdings) {
        Map registry = holdings.find { Map held -> held.registry }
        return [
                id            : token.id,
                productId     : token.product.id,
                productCode   : token.product.productCode,
                ticker        : token.ticker,
                policyAnchor  : token.policyAnchor,
                issuerDeviceId: token.issuerDeviceId,
                supply        : token.supply,
                // At the issuing registry: capacity to admit, and what has entered custody so far. Neither is stock.
                reserve       : registry?.available,
                released      : registry && token.supply != null ? token.supply - registry.available : null,
                holdings      : holdings,
        ]
    }

    static Map connectionJson(DsmConnection connection) {
        return [
                id            : connection.id,
                accountId     : connection.account.id,
                peerDeviceId  : connection.peerDeviceId,
                peerLocationId: connection.peerLocation?.id,
                peerRegistry  : connection.peerRegistry,
                peerName      : connection.peerName,
                side          : connection.side,
                sessionId     : connection.sessionId,
                offerDigest   : connection.offerDigest,
        ]
    }

    static Map admissionJson(DsmAdmission admission) {
        return [
                id              : admission.id,
                accountId       : admission.account.id,
                productId       : admission.product.id,
                quantity        : admission.quantity,
                quantityDrawn   : admission.quantityDrawn,
                reference       : admission.reference,
                memo            : admission.memo,
                sessionId       : admission.sessionId,
                seq             : admission.seq,
                status          : admission.status,
                reason          : admission.reason,
                creditTransferId: admission.creditTransferId,
                dateEstablished : admission.dateEstablished,
        ]
    }

    static Map decisionJson(DsmReleaseDecision decision) {
        return [
                id               : decision.id,
                sessionId        : decision.sessionId,
                seq              : decision.seq,
                requesterDeviceId: decision.requesterDeviceId,
                summary          : decision.summary,
                decision         : decision.decision,
                line             : decision.line,
                decidedBy        : decision.decidedBy.username,
                dateCreated      : decision.dateCreated,
        ]
    }

    static Map transferJson(DsmCustodyTransfer transfer) {
        return [
                id                   : transfer.id,
                kind                 : transfer.kind,
                shipmentId           : transfer.shipment?.id,
                productCode          : transfer.product.productCode,
                accountId            : transfer.account.id,
                peerDeviceId         : transfer.peerDeviceId,
                quantity             : transfer.quantity,
                quantityReceived     : transfer.quantityReceived,
                memo                 : transfer.memo,
                originStateHash      : transfer.originStateHash,
                dateEstablished      : transfer.dateEstablished,
                destinationTransferId: transfer.destinationTransferId,
                provenAmount         : transfer.provenAmount,
                provenPosition       : transfer.provenPosition,
                carrier              : transfer.kind == DsmCustodyTransfer.KIND_SHIPMENT
                        ? DsmCarriage.findByKindAndMemo(DsmCarriage.KIND_DISPATCHED, transfer.memo)?.walletDeviceId?.take(8) : null,
        ]
    }
}
