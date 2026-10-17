package tally.core.capture

/** Picks the parser for a notification's package. */
object CaptureParsers {
    private val byPackage: Map<String, CaptureParser> =
        listOf(RbcParser, GoogleWalletParser).associateBy { it.packageName }

    /**
     * Parses a notification from a watched package. Watched packages without a parser yet
     * (Samsung Wallet, until a real notification shows its shape) fail so they reach the inbox.
     * Returns null for packages that aren't watched.
     */
    fun parse(n: RawNotification): ParseResult? {
        if (n.packageName !in WatchedPackages.ALL) return null
        val parser = byPackage[n.packageName]
            ?: return ParseResult.Failed("No parser for ${WatchedPackages.displayName(n.packageName)} yet")
        return parser.parse(n)
    }
}
