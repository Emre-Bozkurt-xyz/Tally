package tally.core.report

/** An "owed to me" part not paid back yet. */
data class OwedPart(val splitId: String, val owedBy: String, val amountCents: Long)

/**
 * Money from a person that pays back what they owe: the open part with exactly that amount
 * whose name agrees with the sender's ("Danya" or "Danya Ijaz" for "DANYA IJAZ"). Oldest first
 * when there are several.
 */
object OwedMatch {
    fun find(sender: String?, receivedCents: Long, open: List<OwedPart>): OwedPart? {
        if (sender.isNullOrBlank() || receivedCents <= 0) return null
        val senderWords = words(sender)
        return open.firstOrNull { part ->
            part.amountCents == receivedCents && words(part.owedBy).let { owed ->
                owed.isNotEmpty() && (senderWords.containsAll(owed) || owed.first() == senderWords.firstOrNull())
            }
        }
    }

    private fun words(name: String) = name.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
}
