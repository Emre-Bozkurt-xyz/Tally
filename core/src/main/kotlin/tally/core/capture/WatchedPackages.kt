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

    private val sources = mapOf(
        RBC to Source.RBC_ALERT,
        SAMSUNG_WALLET to Source.SAMSUNG_WALLET,
        GOOGLE_WALLET to Source.GOOGLE_WALLET,
    )

    val ALL: Set<String> = sources.keys

    fun sourceFor(packageName: String): Source? = sources[packageName]

    fun displayName(packageName: String): String = when (packageName) {
        RBC -> "RBC"
        SAMSUNG_WALLET -> "Samsung Wallet"
        GOOGLE_WALLET -> "Google Wallet"
        else -> packageName
    }
}
