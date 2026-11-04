package org.pih.warehouse.dsm

import com.google.protobuf.ByteString
import dsm.types.proto.AuthoredPublishRequestV1
import dsm.types.proto.Envelope
import grails.gorm.transactions.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.pih.warehouse.core.Constants
import org.pih.warehouse.core.User
import org.pih.warehouse.inventory.InventoryItem
import org.pih.warehouse.inventory.Transaction
import org.pih.warehouse.inventory.TransactionEntry
import org.pih.warehouse.inventory.TransactionIdentifierService
import org.pih.warehouse.inventory.TransactionType
import org.pih.warehouse.product.Product

/**
 * Whether the books agree with DSM. For each account and product:
 *
 *     books on hand = DSM holding - admitted units not yet recorded - units here not yet received
 *
 * The books are added up from the stock transactions, not read from the availability table the screens use, so a direct
 * edit of either shows up. DSM is the authority: a correction moves the books to match it, as a new adjustment, and never
 * the other way round. This compares totals, so it does not see an edit behind a stock count, or a change that leaves the
 * total the same.
 */
@Transactional
class DsmReconcileService {

    /** The name these objects are published under. */
    private static final byte[] TOPIC = MessageDigest.getInstance("SHA-256")
            .digest("openboxes/reconcile/v1".getBytes(StandardCharsets.UTF_8))

    /** The last reading per account and product: a finding is recorded only when two readings in a row agree. */
    private static final Map<String, Long> LAST_GAP = new java.util.concurrent.ConcurrentHashMap<String, Long>()
    private static final Map<String, Long> LAST_CACHED = new java.util.concurrent.ConcurrentHashMap<String, Long>()

    DsmAccountService dsmAccountService
    DsmCustodyService dsmCustodyService
    def inventoryService
    def productAvailabilityService
    TransactionIdentifierService transactionIdentifierService

    /** Positive: the books show units DSM does not. Negative: DSM holds units the books do not show. */
    static long gapOf(long books, long dsmHolding, long undrawn, long inTransit) {
        return books - (dsmHolding - undrawn - inTransit)
    }

    /** Every account and product, measured now. */
    List<Map> measure() {
        Map<String, Long> notTakenIn = [:]
        dsmCustodyService.inboundCustody().each { Map document ->
            document.lines.each { Map line ->
                String key = "${document.accountId}|${line.productId}".toString()
                notTakenIn[key] = (notTakenIn[key] ?: 0L) + (line.quantity as long)
            }
        }
        List<Map> rows = []
        DsmWarehouseAccount.findAllByLocationIsNotNull().each { DsmWarehouseAccount account ->
            DsmProductToken.list().each { DsmProductToken token ->
                Product product = token.product
                long undrawn = (DsmAdmission.findAllByAccountAndProductAndStatus(account, product, DsmAdmission.STATUS_ESTABLISHED)
                        .sum { DsmAdmission a -> (a.quantity as long) - (a.quantityDrawn as long) } ?: 0L) as long
                long unreceived = (DsmCustodyTransfer.findAllByAccountAndProductAndKind(account, product, DsmCustodyTransfer.KIND_INBOUND)
                        .sum { DsmCustodyTransfer t -> (t.quantity as long) - ((t.quantityReceived ?: 0) as long) } ?: 0L) as long
                long inTransit = unreceived + (notTakenIn["${account.id}|${product.id}".toString()] ?: 0L)
                long dsm = dsmAccountService.balanceOf(account, token)
                long books = booksOnHand(account, product)
                long cached = (productAvailabilityService.getQuantityOnHand(product, account.location) ?: 0) as long
                rows << [account: account, product: product, dsmHolding: dsm, undrawn: undrawn, inTransit: inTransit,
                         books: books, cached: cached, gap: gapOf(books, dsm, undrawn, inTransit)]
            }
        }
        return rows
    }

    private static final java.util.concurrent.locks.ReentrantLock RUNNING = new java.util.concurrent.locks.ReentrantLock()
    /** The gap each (account, product) was last recorded at by this process: a second run sees it before the first commits. */
    private static final Map<String, Long> LAST_RECORDED = new java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Runs each check, and records what changed. One run at a time: a click while the schedule runs measures and
     * returns, it does not queue behind remote calls.
     */
    List<Map> runChecks() {
        if (!RUNNING.tryLock()) {
            return measure()
        }
        try {
            return checkAll()
        } finally {
            RUNNING.unlock()
        }
    }

    private List<Map> checkAll() {
        List<Map> rows = measure()
        rows.each { Map row ->
            String key = "${row.account.id}|${row.product.id}".toString()
            Long previous = LAST_GAP.put(key, row.gap as Long)
            DsmReconcileEvent latest = latestOf(row, [DsmReconcileEvent.KIND_GAP, DsmReconcileEvent.KIND_CLEARED, DsmReconcileEvent.KIND_CORRECTED])
            long recorded = LAST_RECORDED.containsKey(key) ? LAST_RECORDED[key] : (latest ? (latest.gap as long) : 0L)
            if (previous != null && previous == row.gap && row.gap != recorded) {
                record(row, row.gap == 0 ? DsmReconcileEvent.KIND_CLEARED : DsmReconcileEvent.KIND_GAP, null, null, null)
                LAST_RECORDED[key] = row.gap as Long
            }
            long cacheOff = (row.cached as long) - (row.books as long)
            Long previousOff = LAST_CACHED.put(key, cacheOff)
            DsmReconcileEvent latestCache = latestOf(row, [DsmReconcileEvent.KIND_CACHE, DsmReconcileEvent.KIND_CACHE_CLEARED])
            boolean open = latestCache?.kind == DsmReconcileEvent.KIND_CACHE
            if (previousOff != null && previousOff == cacheOff) {
                if (cacheOff != 0 && (!open || (latestCache.cached as long) - (latestCache.books as long) != cacheOff)) {
                    record(row, DsmReconcileEvent.KIND_CACHE, "the availability screens show ${row.cached}, the transactions add up to ${row.books}", null, null)
                } else if (cacheOff == 0 && open) {
                    record(row, DsmReconcileEvent.KIND_CACHE_CLEARED, null, null, null)
                }
            }
        }
        return rows
    }

    /**
     * Move the books to match DSM, by a new adjustment naming why. It closes exactly the gap measured now, and moves nothing
     * on DSM. This is for books that are wrong: a real shortage is a count, which burns the units.
     */
    DsmReconcileEvent correctBooks(String accountId, String productId, String reason, User by) {
        if (!reason?.trim()) {
            throw new DsmCustodyException("a correction names why it is being made")
        }
        Map row = measure().find { Map r -> r.account.id == accountId && r.product.id == productId }
        if (!row) {
            throw new DsmCustodyException("no such account and product on DSM")
        }
        long gap = row.gap as long
        if (gap == 0) {
            throw new DsmCustodyException("the books already match DSM")
        }
        Map<InventoryItem, Integer> lots = lotsOnHand(row.account as DsmWarehouseAccount, row.product as Product)
        if (gap > 0 && lots.values().sum(0) < gap) {
            throw new DsmCustodyException("the books hold fewer units than the gap of ${gap}: this needs a count")
        }
        if (gap < 0 && !lots) {
            throw new DsmCustodyException("no lot is on the books to credit ${-gap} to: record stock instead")
        }
        DsmStockWrites.grant("a book correction")
        String typeId = gap > 0 ? Constants.ADJUSTMENT_DEBIT_TRANSACTION_TYPE_ID : Constants.ADJUSTMENT_CREDIT_TRANSACTION_TYPE_ID
        Transaction transaction = new Transaction(
                inventory: row.account.location.inventory,
                transactionDate: new Date(),
                transactionType: TransactionType.get(typeId),
                comment: "Correction to match DSM: ${reason.trim()}".toString().take(255),
        )
        transaction.transactionNumber = transactionIdentifierService.generate(transaction)
        long remaining = Math.abs(gap)
        (lots.sort { -it.value }).each { InventoryItem item, Integer held ->
            if (remaining > 0) {
                long part = gap > 0 ? Math.min(remaining, held as long) : remaining
                transaction.addToTransactionEntries(new TransactionEntry(inventoryItem: item, quantity: part as int))
                remaining -= part
            }
        }
        transaction.save(failOnError: true, flush: true)
        Map after = new LinkedHashMap(row)
        after.books = (row.books as long) - gap
        after.gap = 0L
        LAST_GAP.remove("${row.account.id}|${row.product.id}".toString())
        return record(after, DsmReconcileEvent.KIND_CORRECTED, reason.trim(), by, transaction.transactionNumber)
    }

    private long booksOnHand(DsmWarehouseAccount account, Product product) {
        List entries = inventoryService.getTransactionEntriesByInventoryAndProduct(account.location.inventory, [product])
        return (inventoryService.getQuantityByProductMap(entries)[product] ?: 0) as long
    }

    private Map<InventoryItem, Integer> lotsOnHand(DsmWarehouseAccount account, Product product) {
        List entries = inventoryService.getTransactionEntriesByInventoryAndProduct(account.location.inventory, [product])
        Map byItem = inventoryService.getQuantityByProductAndInventoryItemMap(entries, false)[product] ?: [:]
        return byItem.findAll { it.value > 0 } as Map<InventoryItem, Integer>
    }

    private DsmReconcileEvent latestOf(Map row, List<String> kinds) {
        return DsmReconcileEvent.findAllByAccountAndProductAndKindInList(row.account, row.product, kinds, [sort: "dateCreated", order: "desc", max: 1])[0]
    }

    private DsmReconcileEvent record(Map row, String kind, String note, User by, String transactionNumber) {
        DsmReconcileEvent event = new DsmReconcileEvent(
                kind: kind, account: row.account, product: row.product,
                dsmHolding: row.dsmHolding, undrawn: row.undrawn, inTransit: row.inTransit, books: row.books, cached: row.cached,
                gap: row.gap, note: note, recordedBy: by, transactionNumber: transactionNumber,
        )
        event.signedAddress = publish(event)
        event.save(failOnError: true, flush: true)
        log.warn("DSM and the books: ${kind} ${row.product.productCode} at ${row.account.location.name}, gap ${row.gap}")
        return event
    }

    /** Signed by this organization's account. A finding DSM cannot take is not recorded: the next check finds it again. */
    private String publish(DsmReconcileEvent event) {
        String text = [
                "openboxes/reconcile/v1", event.kind, event.account.deviceId, dsmAccountService.tokenFor(event.product).policyAnchor,
                "dsm=${event.dsmHolding}", "undrawn=${event.undrawn}", "inTransit=${event.inTransit}", "books=${event.books}",
                "cached=${event.cached}", "gap=${event.gap}", "note=${event.note ?: ""}", "transaction=${event.transactionNumber ?: ""}",
                "by=${event.recordedBy?.username ?: "reconciler"}", "at=${System.currentTimeMillis()}",
        ].join("\n")
        AuthoredPublishRequestV1 request = AuthoredPublishRequestV1.newBuilder()
                .setTopic(ByteString.copyFrom(TOPIC))
                .setPayload(ByteString.copyFromUtf8(text))
                .build()
        Envelope envelope = dsmAccountService.clientFor(event.account).invoke("authored.publish", request)
        return Base32Crockford.encode(DsmHostClient
                .expect("authored.publish", envelope, Envelope.PayloadCase.AUTHORED_PUBLISHED_RESPONSE)
                .authoredPublishedResponse.address.toByteArray())
    }
}
