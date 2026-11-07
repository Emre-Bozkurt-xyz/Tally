package tally.core.capture

import tally.core.model.Source

/**
 * Packages whose notifications are recorded as raw events.
 *
 * These are starting guesses: confirm them with the "log all packages" toggle during
 * Phase 0 and fix them here if the phone shows something different.
 */
object WatchedPackages {
    const val RBC = "com.rbc.mobile.android"
    const val SAMSUNG_WALLET = "com.samsung.android.spay"
    const val GOOGLE_WALLET = "com.google.android.apps.walletnfcrel"
    const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"
    const val SAMSUNG_MESSAGES = "com.samsung.android.messaging"

    /** Messaging apps, for Interac e-Transfer texts only: nothing else from them is kept. */
    val MESSAGING: Set<String> = setOf(GOOGLE_MESSAGES, SAMSUNG_MESSAGES)

    private val sources = mapOf(
        RBC to Source.RBC_ALERT,
        SAMSUNG_WALLET to Source.SAMSUNG_WALLET,
        GOOGLE_WALLET to Source.GOOGLE_WALLET,
        GOOGLE_MESSAGES to Source.INTERAC,
        SAMSUNG_MESSAGES to Source.INTERAC,
    )

    val ALL: Set<String> = sources.keys

    /**
     * Whether a notification from [packageName] should be recorded at all. Bank and wallet
     * apps: always. Messaging apps: only an Interac e-Transfer text; private messages are
     * never stored.
     */
    fun wanted(packageName: String, text: String?): Boolean = when (packageName) {
        in MESSAGING -> InteracSmsParser.isInterac(text)
        else -> packageName in ALL
    }

    fun sourceFor(packageName: String): Source? = sources[packageName]

    fun displayName(packageName: String): String = when (packageName) {
        RBC -> "RBC"
        SAMSUNG_WALLET -> "Samsung Wallet"
        GOOGLE_WALLET -> "Google Wallet"
        GOOGLE_MESSAGES, SAMSUNG_MESSAGES -> "Interac text"
        else -> packageName
    }
}
