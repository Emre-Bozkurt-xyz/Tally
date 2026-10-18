package tally.core.capture

/**
 * Finds the account a wallet's card name refers to, for captures without a last 4
 * (Samsung Wallet says "RBC Visa Card/Carte Visa RBC‡"). The English half before the "/" is
 * compared letters and digits only, without "card"; the longest account name it contains wins.
 */
object AccountNames {
    fun match(cardName: String, accounts: List<Pair<String, String>>): String? {
        val hint = key(cardName.substringBefore('/').replace(Regex("(?i)\\b(card|carte)\\b"), ""))
        if (hint.isEmpty()) return null
        return accounts
            .map { (id, name) -> id to key(name) }
            .filter { (_, name) -> name.isNotEmpty() && name in hint }
            .maxByOrNull { (_, name) -> name.length }
            ?.first
    }

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
