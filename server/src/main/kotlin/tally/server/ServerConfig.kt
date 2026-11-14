package tally.server

/**
 * Everything the server reads from its environment (`.env.production` on the mini-PC).
 * Missing required values fail at startup, not on the first request.
 */
data class ServerConfig(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    /** Null only when ACCESS_DISABLED=true, for running locally without Cloudflare. */
    val access: AccessConfig?,
    /** Null without ANTHROPIC_API_KEY: `/api/categorize` then answers 503 and the phone carries on without. */
    val llm: LlmConfig? = null,
    /** Null without GMAIL_ADDRESS and GMAIL_APP_PASSWORD: no receipts by email. */
    val email: EmailConfig? = null,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() }
                ?: error("$name is required (see .env.example)")

            val accessDisabled = env["ACCESS_DISABLED"] == "true"
            return ServerConfig(
                port = env["PORT"]?.toInt() ?: 8080,
                databaseUrl = env["DATABASE_URL"] ?: "jdbc:postgresql://postgres:5432/tally",
                databaseUser = env["DATABASE_USER"] ?: "tally",
                databasePassword = required("POSTGRES_PASSWORD"),
                access = if (accessDisabled) {
                    null
                } else {
                    AccessConfig(
                        teamDomain = required("ACCESS_TEAM_DOMAIN").removePrefix("https://").trimEnd('/'),
                        audience = required("ACCESS_AUD"),
                    )
                },
                llm = env["ANTHROPIC_API_KEY"]?.takeIf { it.isNotBlank() }?.let { key ->
                    LlmConfig(
                        apiKey = key,
                        model = env["ANTHROPIC_MODEL"]?.takeIf { it.isNotBlank() } ?: LlmConfig.DEFAULT_MODEL,
                        dailyLimit = env["LLM_DAILY_LIMIT"]?.toInt() ?: 200,
                        rulesModel = env["ANTHROPIC_RULES_MODEL"]?.takeIf { it.isNotBlank() } ?: LlmConfig.DEFAULT_RULES_MODEL,
                    )
                },
                email = env["GMAIL_ADDRESS"]?.takeIf { it.isNotBlank() }?.let { address ->
                    EmailConfig(
                        address = address.trim(),
                        // Google shows app passwords in groups of four; spaces or not, it's the same password.
                        appPassword = required("GMAIL_APP_PASSWORD").filterNot(Char::isWhitespace),
                        pollMinutes = env["EMAIL_POLL_MINUTES"]?.toLong() ?: 5,
                        dailyLimit = env["EMAIL_DAILY_LIMIT"]?.toInt() ?: 100,
                        folder = env["EMAIL_FOLDER"]?.takeIf { it.isNotBlank() } ?: "INBOX",
                    )
                },
            )
        }
    }
}

/** Claude for categorizing unknown merchants. */
data class LlmConfig(
    val apiKey: String,
    val model: String,
    val dailyLimit: Int,
    /** For rule suggestions: writing regexes from a merchant list wants a stronger model. */
    val rulesModel: String = DEFAULT_RULES_MODEL,
) {
    override fun toString() = "LlmConfig(model=$model, dailyLimit=$dailyLimit, rulesModel=$rulesModel)"

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
        const val DEFAULT_RULES_MODEL = "claude-sonnet-5-5"
    }
}

/** Gmail over IMAP with an app password, for receipts by email. */
data class EmailConfig(
    val address: String,
    val appPassword: String,
    val pollMinutes: Long = 5,
    /** Receipts a day the model reads, at most. */
    val dailyLimit: Int = 100,
    val folder: String = "INBOX",
) {
    override fun toString() = "EmailConfig(address=$address, pollMinutes=$pollMinutes, dailyLimit=$dailyLimit, folder=$folder)"
}

/**
 * Cloudflare Access settings: the team domain ("yourteam.cloudflareaccess.com") and the
 * application's AUD tag (Zero Trust > Access > Applications > tally > Overview).
 */
data class AccessConfig(val teamDomain: String, val audience: String) {
    val issuer get() = "https://$teamDomain"
    val certsUrl get() = "$issuer/cdn-cgi/access/certs"
}
