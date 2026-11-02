package org.pih.warehouse.api

import grails.converters.JSON
import org.springframework.http.HttpStatus

import org.pih.warehouse.core.Location
import org.pih.warehouse.dsm.DsmCustodyException
import org.pih.warehouse.dsm.DsmCustodyService
import org.pih.warehouse.dsm.DsmCustodyTransfer
import org.pih.warehouse.dsm.DsmHostException
import org.pih.warehouse.dsm.DsmProductToken
import org.pih.warehouse.dsm.DsmWarehouseAccount
import org.pih.warehouse.dsm.DsmWarehouseLink
import org.pih.warehouse.product.Product

/**
 * The setup an administrator does once per account, product and pair of warehouses, and a view of what the
 * accounts established (docs/plans/2026-11-01-dsm-custody-integration.md). Every call is the service's; a DSM
 * refusal is answered as 400 with DSM's own reason.
 */
class DsmCustodyApiController extends BaseApiController {

    DsmCustodyService dsmCustodyService

    def accounts() {
        render([data: DsmWarehouseAccount.list().collect { accountJson(it) }] as JSON)
    }

    def registerWarehouse() {
        attempt {
            Location location = Location.get(params.locationId)
            if (!location) {
                throw new DsmCustodyException("no location ${params.locationId}")
            }
            accountJson(dsmCustodyService.registerAccount(location, params.ingressOrigin, params.dataDir))
        }
    }

    def registerRegistry() {
        attempt {
            accountJson(dsmCustodyService.registerRegistry(params.label, params.ingressOrigin, params.dataDir))
        }
    }

    def tokens() {
        render([data: DsmProductToken.list().collect { tokenJson(it) }] as JSON)
    }

    def issueToken() {
        attempt {
            Product product = Product.get(params.productId)
            if (!product) {
                throw new DsmCustodyException("no product ${params.productId}")
            }
            DsmWarehouseAccount issuer = DsmWarehouseAccount.get(params.issuerId)
            if (!issuer) {
                throw new DsmCustodyException("no DSM account ${params.issuerId}")
            }
            long supply
            try {
                supply = Long.parseLong(params.supply?.toString() ?: "")
            } catch (NumberFormatException e) {
                throw new DsmCustodyException("supply must be a whole number of units, not ${params.supply}")
            }
            tokenJson(dsmCustodyService.issueProductToken(issuer, product, supply))
        }
    }

    def links() {
        render([data: DsmWarehouseLink.list().collect { linkJson(it) }] as JSON)
    }

    def pair() {
        attempt {
            DsmWarehouseAccount offering = DsmWarehouseAccount.get(params.offeringId)
            DsmWarehouseAccount accepting = DsmWarehouseAccount.get(params.acceptingId)
            if (!offering || !accepting) {
                throw new DsmCustodyException("no DSM account ${!offering ? params.offeringId : params.acceptingId}")
            }
            linkJson(dsmCustodyService.pair(offering, accepting))
        }
    }

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

    private void attempt(Closure<Map> action) {
        try {
            render([data: action()] as JSON)
        } catch (DsmCustodyException | DsmHostException refused) {
            response.status = HttpStatus.BAD_REQUEST.value()
            render([errorCode: HttpStatus.BAD_REQUEST.value(), errorMessage: refused.message] as JSON)
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

    static Map tokenJson(DsmProductToken token) {
        return [
                id          : token.id,
                productId   : token.product.id,
                productCode : token.product.productCode,
                ticker      : token.ticker,
                policyAnchor: token.policyAnchor,
                supply      : token.supply,
                issuerId    : token.issuer.id,
        ]
    }

    static Map linkJson(DsmWarehouseLink link) {
        return [
                id         : link.id,
                offeringId : link.offering.id,
                acceptingId: link.accepting.id,
                sessionId  : link.sessionId,
                offerDigest: link.offerDigest,
        ]
    }

    static Map transferJson(DsmCustodyTransfer transfer) {
        return [
                id                   : transfer.id,
                kind                 : transfer.kind,
                shipmentId           : transfer.shipment?.id,
                productCode          : transfer.product.productCode,
                originId             : transfer.origin.id,
                destinationId        : transfer.destination?.id,
                quantity             : transfer.quantity,
                memo                 : transfer.memo,
                originStateHash      : transfer.originStateHash,
                dateEstablished      : transfer.dateEstablished,
                destinationTransferId: transfer.destinationTransferId,
                provenAmount         : transfer.provenAmount,
                provenPosition       : transfer.provenPosition,
        ]
    }
}
