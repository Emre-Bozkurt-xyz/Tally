package tally.core.llm

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** Asks a language model; returns its text reply. */
fun interface CategoryModel {
    fun ask(system: String, user: String): String
}

data class CachedAnswer(val category: String?, val categoriesHash: String)

/** Answers by merchant key. Postgres on the server, a map in tests. */
interface CategoryCache {
    fun get(merchantKey: String): CachedAnswer?
    fun put(merchantKey: String, answer: CachedAnswer)
}

class DailyLimitReached(limit: Int) : RuntimeException("LLM limit of $limit calls a day reached")

/**
 * One model call per merchant, ever: a cached category is reused while it's still one of
 * your categories, and a cached "don't know" until the category list changes. A daily cap
 * bounds the bill if something loops.
 */
class CategorizeService(
    private val cache: CategoryCache,
    private val model: CategoryModel,
    private val dailyLimit: Int = 200,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var day: LocalDate? = null
    private var callsToday = 0

    @Synchronized
    fun categorize(request: CategorizeRequest): CategorizeResponse {
        require(request.merchant.isNotBlank()) { "merchant is blank" }
        require(request.categories.isNotEmpty()) { "no categories" }
        val key = CategoryPrompt.merchantKey(request.merchant)
        val hash = CategoryPrompt.categoriesHash(request.categories)
        cache.get(key)?.let { cached ->
            if (cached.category != null && cached.category in request.categories) return CategorizeResponse(cached.category, cached = true)
            if (cached.category == null && cached.categoriesHash == hash) return CategorizeResponse(null, cached = true)
        }

        countCall()
        val reply = model.ask(CategoryPrompt.system, CategoryPrompt.user(request))
        val category = CategoryPrompt.parse(reply, request.categories)
        cache.put(key, CachedAnswer(category, hash))
        return CategorizeResponse(category, cached = false)
    }

    private fun countCall() {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        if (today != day) {
            day = today
            callsToday = 0
        }
        if (callsToday >= dailyLimit) throw DailyLimitReached(dailyLimit)
        callsToday++
    }
}

class InMemoryCategoryCache : CategoryCache {
    private val answers = mutableMapOf<String, CachedAnswer>()

    @Synchronized
    override fun get(merchantKey: String) = answers[merchantKey]

    @Synchronized
    override fun put(merchantKey: String, answer: CachedAnswer) {
        answers[merchantKey] = answer
    }
}
