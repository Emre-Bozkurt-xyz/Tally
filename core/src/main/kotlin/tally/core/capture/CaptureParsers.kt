package tally.core.capture

/** Picks the parser for a notification's package. */
object CaptureParsers {
    private val byPackage: Map<String, CaptureParser> =
        listOf(RbcParser, GoogleWalletParser, SamsungWalletParser).associateBy { it.packageName }

    /**
     * Parses a notification from a watched package. Blank ones (a group's summary, which only
     * bundles the real notifications) are ignored. Watched packages without a parser fail so
     * they reach the inbox. Returns null for packages that aren't watched.
     */
    fun parse(n: RawNotification): ParseResult? {
        if (n.packageName !in WatchedPackages.ALL) return null
        if (listOf(n.title, n.text, n.bigText).all { it.isNullOrBlank() }) return ParseResult.Ignored("Blank notification (group summary)")
        val parser = byPackage[n.packageName]
            ?: return ParseResult.Failed("No parser for ${WatchedPackages.displayName(n.packageName)} yet")
        return parser.parse(n)
    }
}
