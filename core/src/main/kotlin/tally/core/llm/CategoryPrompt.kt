package tally.core.llm

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Phone to server: what this merchant is, and the category paths it may answer with. */
@Serializable
data class CategorizeRequest(
    /** Normalized merchant name ("Foodie Fruitie"). */
    val merchant: String,
    val amountCents: Long,
    /** Full paths, "Food" and "Food/Coffee". */
    val categories: List<String>,
)

@Serializable
data class CategorizeResponse(
    /** One of the request's paths, or null when the model doesn't know. */
    val category: String?,
    /** True when answered from the server's cache (no model call). */
    val cached: Boolean,
)

/**
 * The question asked about an unknown merchant, and the strict reading of the answer: it
 * must be exactly one of the paths offered, else it counts as "don't know".
 */
object CategoryPrompt {
    const val UNKNOWN = "UNKNOWN"

    val system = """
        You sort card purchases into categories for a personal expense tracker. The person lives
        in Toronto, Canada, so merchants are mostly Canadian chains and local businesses.
        Answer with exactly one category path from the list, copied exactly, and nothing else.
        Prefer the most specific path that fits. If you don't recognize the merchant or no
        category clearly fits, answer $UNKNOWN.
    """.trimIndent()

    fun user(request: CategorizeRequest): String = buildString {
        appendLine("Merchant: ${request.merchant}")
        appendLine("Amount: ${dollars(request.amountCents)}")
        appendLine("Categories:")
        request.categories.forEach { appendLine(it) }
    }.trimEnd()

    /** The model's reply as one of [categories], or null. Tolerates quotes and a full stop. */
    fun parse(reply: String, categories: List<String>): String? {
        val answer = reply.trim().trim('"', '\'', '`', '.', ' ').trim()
        if (answer.equals(UNKNOWN, ignoreCase = true)) return null
        return categories.firstOrNull { it == answer }
            ?: categories.firstOrNull { it.equals(answer, ignoreCase = true) }
    }

    /** Cache key: the same merchant however the capture spelled its case and spacing. */
    fun merchantKey(merchant: String): String = merchant.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Identifies a category list, so a cached "don't know" is asked again once categories change. */
    fun categoriesHash(categories: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(categories.sorted().joinToString("\n").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun dollars(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        return "$sign$${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }
}
