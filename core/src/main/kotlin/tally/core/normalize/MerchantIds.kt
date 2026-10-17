package tally.core.normalize

import java.util.UUID

/**
 * Ids for new merchants, derived from the cleaned-up name: when the phone and the server both
 * re-apply an alias, they create the same "DoorDash" instead of two. (Merchants created before
 * this have random ids; they're found by name first.)
 */
object MerchantIds {
    fun forName(name: String): String = UUID.nameUUIDFromBytes("tally:merchant:${name.lowercase()}".toByteArray()).toString()
}
