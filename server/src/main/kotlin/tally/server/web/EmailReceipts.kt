package tally.server.web

import tally.core.capture.AccountNames
import tally.core.email.MailMessage
import tally.core.email.Receipt
import tally.core.email.ReceiptFilter
import tally.core.email.ReceiptReader
import tally.core.model.Kind
import tally.core.model.Source
import tally.core.model.TransactionStatus
import tally.core.normalize.Alias
import tally.core.normalize.MerchantIds
import tally.core.normalize.MerchantNormalizer
import tally.core.rules.Categorizer
import tally.core.rules.MerchantMemory
import tally.core.rules.RulesEngine
import tally.core.sync.SyncChange
import tally.core.sync.SyncServer
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Which transaction an order became, so a later receipt for it updates rather than adds. */
interface OrderStore {
    fun get(orderKey: String): StoredOrder?
    fun put(orderKey: String, order: StoredOrder)
}

data class StoredOrder(val transactionId: String, val totalCents: Long)

class InMemoryOrderStore : OrderStore {
    private val orders = ConcurrentHashMap<String, StoredOrder>()
    override fun get(orderKey: String) = orders[orderKey]
    override fun put(orderKey: String, order: StoredOrder) {
        orders[orderKey] = order
    }
}

/**
 * Receipts by email (Phase 7 step 4): a receipt becomes one transaction per order, waiting in
 * the inbox like any capture. If a tap or a statement row already has that charge, the
 * receipt adds the store and items to it instead. The email itself is never stored.
 */
class EmailReceipts(
    private val sync: SyncServer,
    private val reader: ReceiptReader,
    private val orders: OrderStore,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    sealed interface Outcome {
        /** Not a receipt by its subject: nothing left the server. */
        data object NotCandidate : Outcome

        /** Read, but not a new charge (or it failed the checks). */
        data object NotReceipt : Outcome

        data class Added(val transaction: TransactionRow) : Outcome

        /** The charge was already in Tally (a tap, a statement row): the receipt filled in its details. */
        data class Matched(val transaction: TransactionRow) : Outcome

        /** A later receipt for an order already in Tally. */
        data class Updated(val transaction: TransactionRow) : Outcome

        data object AlreadySeen : Outcome
    }

    fun handle(message: MailMessage): Outcome {
        if (!ReceiptFilter.isCandidate(message.subject)) return Outcome.NotCandidate
        val receipt = reader.read(message) ?: return Outcome.NotReceipt
        return record(receipt, message)
    }

    fun record(receipt: Receipt, message: MailMessage): Outcome {
        val s = Snapshot.load(sync)
        val now = clock.millis()
        val key = receipt.orderKey

        key?.let { orders.get(it) }?.let { stored ->
            val t = s.transactions.find { it.id == stored.transactionId } ?: return@let
            if (receipt.totalCents == stored.totalCents || t.amountCents == receipt.totalCents) return Outcome.AlreadySeen
            orders.put(key, StoredOrder(t.id, receipt.totalCents))
            // The statement's amount is final; otherwise the latest receipt wins (holds drop off).
            if (t.status == TransactionStatus.RECONCILED.name) return Outcome.AlreadySeen
            val updated = t.copy(amountCents = receipt.totalCents, updatedAt = now)
            sync.write(listOf(Rows.transactions.encode(updated, s.stored(Rows.transactions.table, t.id))), SOURCE)
            return Outcome.Updated(updated)
        }

        val id = UUID.nameUUIDFromBytes("tally:email:${key ?: message.id}".toByteArray()).toString()
        if (s.transactions.any { it.id == id }) return Outcome.AlreadySeen

        val occurredAt = occurredAt(receipt, message)
        val accountId = s.accountByLast4[receipt.last4]?.takeIf { a -> s.accounts.any { it.id == a } }
            ?: receipt.cardBrand?.let { brand -> AccountNames.match(brand, s.accounts.map { it.id to it.name }) }
        val note = receipt.items.take(3).joinToString(", ").ifEmpty { null }

        val normalizer = MerchantNormalizer(s.aliases.filter { !it.deleted }.sortedBy { it.priority }.mapNotNull { a ->
            runCatching { Alias(Regex(a.pattern), a.merchantName) }.getOrNull()
        })
        val name = normalizer.normalize(receipt.store)
        val existingMerchant = s.merchantsById.values.firstOrNull { !it.deleted && it.name == name }
        val merchant = existingMerchant ?: MerchantRow(MerchantIds.forName(name), name, null, 0, now)
        val writes = mutableListOf<SyncChange>()
        if (existingMerchant == null) writes += Rows.merchants.encode(merchant, null)

        // Already in Tally: the same amount on this card (or an unknown one) within a day and a half.
        val same = s.transactions
            .filter { t ->
                t.amountCents == receipt.totalCents && t.kind != Kind.TRANSFER.name && t.source != Source.EMAIL.name &&
                    (accountId == null || t.accountId == null || t.accountId == accountId) &&
                    abs(t.occurredAt - occurredAt) <= MATCH_WINDOW_MS
            }
            .minByOrNull { abs(it.occurredAt - occurredAt) }
        if (same != null) {
            val enriched = same.copy(
                merchantId = same.merchantId ?: merchant.id,
                merchantRaw = same.merchantRaw ?: receipt.store,
                accountId = same.accountId ?: accountId,
                note = same.note ?: note,
                updatedAt = now,
            )
            if (enriched.merchantId == same.merchantId) writes.clear() // No new merchant needed.
            if (enriched.copy(updatedAt = same.updatedAt) != same) {
                writes += Rows.transactions.encode(enriched, s.stored(Rows.transactions.table, same.id))
                sync.write(writes, SOURCE)
            }
            key?.let { orders.put(it, StoredOrder(same.id, receipt.totalCents)) }
            return Outcome.Matched(enriched)
        }

        val categorizer = Categorizer(RulesEngine(s.rules.filter { !it.deleted }.map { it.toRule() }))
        val suggestion = categorizer.categorize(name, receipt.totalCents, accountId, MerchantMemory(merchant.lastCategoryId, merchant.confirmCount))
        val categoryId = suggestion.categoryId?.takeIf { c -> s.categoryTree.any { it.id == c } }
        val transaction = TransactionRow(
            id = id, amountCents = receipt.totalCents, currency = "CAD", occurredAt = occurredAt, zoneId = zone.id,
            accountId = accountId, merchantId = merchant.id, merchantRaw = receipt.store, categoryId = categoryId, note = note,
            source = Source.EMAIL.name,
            // Always to the inbox: a receipt is a good guess, not something you confirmed.
            status = TransactionStatus.CAPTURED.name,
            categorySource = suggestion.source?.name?.takeIf { categoryId != null },
            kind = Kind.EXPENSE.name, updatedAt = now,
        )
        writes += Rows.transactions.encode(transaction, null)
        sync.write(writes, SOURCE)
        key?.let { orders.put(it, StoredOrder(id, receipt.totalCents)) }
        return Outcome.Added(transaction)
    }

    /** When the email came, unless the receipt names a different day (then noon that day). */
    private fun occurredAt(receipt: Receipt, message: MailMessage): Long {
        val received = Instant.ofEpochMilli(message.receivedAt).atZone(zone)
        val date = receipt.date ?: return message.receivedAt
        return if (date == received.toLocalDate()) message.receivedAt else date.atTime(NOON).atZone(zone).toInstant().toEpochMilli()
    }

    companion object {
        /** Who wrote the rows, in sync history. */
        const val SOURCE = "email"
        private const val MATCH_WINDOW_MS = 36 * 3_600_000L
        private val NOON = LocalTime.NOON
    }
}
