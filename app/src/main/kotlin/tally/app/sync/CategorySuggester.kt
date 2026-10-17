package tally.app.sync

import tally.app.data.CapturePipeline
import tally.app.data.TallyDatabase
import tally.app.data.inTreeOrder
import tally.core.llm.CategorizeRequest
import tally.core.llm.CategorizeResponse
import java.time.Clock
import java.time.Duration

/**
 * Asks the server's LLM about captures that neither your rules nor merchant memory could
 * categorize. An answer becomes a suggestion (`categorySource = LLM`) that still waits for
 * your tap; confirming it teaches merchant memory, so that merchant never needs asking again.
 */
class CategorySuggester(
    private val db: TallyDatabase,
    private val ask: suspend (CategorizeRequest) -> CategorizeResponse,
    private val clock: Clock = Clock.systemUTC(),
    /** Called for each suggestion made, e.g. to update its capture notification. */
    private val onSuggested: (CapturePipeline.Outcome.Captured) -> Unit = {},
) {
    /** Returns how many captures got a suggestion. Server errors stop the run (tried again next sync). */
    suspend fun suggestPending(): Int {
        val since = clock.instant().minus(LOOKBACK).toEpochMilli()
        val pending = db.transactionDao().awaitingSuggestion(since, MAX_PER_RUN)
        if (pending.isEmpty()) return 0

        val categories = db.categoryDao().getActive().inTreeOrder()
        val byId = categories.associateBy { it.id }
        val pathToId = categories.associate { category ->
            (category.parentId?.let { "${byId[it]?.name}/${category.name}" } ?: category.name) to category.id
        }
        val paths = pathToId.keys.toList()

        var suggested = 0
        for (transaction in pending) {
            val merchant = transaction.merchantId?.let { db.merchantDao().getById(it) } ?: continue
            val answer = ask(CategorizeRequest(merchant.name, transaction.amountCents, paths)).category ?: continue
            val categoryId = pathToId[answer] ?: continue
            if (db.transactionDao().suggestCategory(transaction.id, categoryId, clock.millis()) == 1) {
                suggested++
                val updated = db.transactionDao().getById(transaction.id) ?: continue
                onSuggested(CapturePipeline.Outcome.Captured(updated, merchant.name, byId[categoryId]?.name))
            }
        }
        return suggested
    }

    private companion object {
        /** Older uncategorized captures are history; you'd categorize those by hand. */
        val LOOKBACK: Duration = Duration.ofDays(30)
        const val MAX_PER_RUN = 25
    }
}
