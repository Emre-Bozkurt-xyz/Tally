package tally.core.model

/** Where a transaction came from. Stored by name, so never rename entries. */
enum class Source {
    MANUAL,
    RBC_ALERT,
    SAMSUNG_WALLET,
    GOOGLE_WALLET,
    CSV,
    EMAIL,
}

/**
 * EXPENSE and INCOME count toward totals; TRANSFER (e.g. chequing paying off your own
 * credit card) must not, or spending is double-counted.
 */
enum class Kind {
    EXPENSE,
    INCOME,
    TRANSFER,
}

enum class TransactionStatus {
    CAPTURED,
    CONFIRMED,
    RECONCILED,
}

enum class CategorySource {
    RULE,
    MEMORY,
    LLM,
    MANUAL,
}

enum class ParseStatus {
    PENDING,
    PARSED,
    IGNORED,
    FAILED,
}
