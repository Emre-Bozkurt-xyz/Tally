package tally.core.entry

import java.time.ZonedDateTime

enum class TimeOfDay {
    MORNING, // 05:00 to 11:59
    AFTERNOON, // 12:00 to 16:59
    EVENING, // 17:00 to 21:59
    NIGHT, // 22:00 to 04:59
    ;

    companion object {
        fun of(hour: Int): TimeOfDay = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> NIGHT
        }
    }
}

/** One past use of a category, in the zone it happened in. */
data class CategoryUse(val categoryId: String, val at: ZonedDateTime)

/** Orders the quick-entry category chips. */
object CategoryRanking {
    /** How far back uses count. The app only passes uses from this window. */
    const val WINDOW_DAYS = 60L

    /**
     * Pinned categories first, in [treeOrder], so they never move under your thumb. The rest
     * by uses in the current [TimeOfDay], then by uses overall, then in [treeOrder].
     */
    fun rank(
        treeOrder: List<String>,
        pinned: Set<String>,
        uses: List<CategoryUse>,
        now: ZonedDateTime,
    ): List<String> {
        val bucket = TimeOfDay.of(now.hour)
        val inBucket = uses.filter { TimeOfDay.of(it.at.hour) == bucket }
            .groupingBy { it.categoryId }.eachCount()
        val overall = uses.groupingBy { it.categoryId }.eachCount()
        val (pinnedIds, rest) = treeOrder.partition { it in pinned }
        // sortedWith is stable, so ties keep tree order.
        return pinnedIds + rest.sortedWith(
            compareByDescending<String> { inBucket[it] ?: 0 }.thenByDescending { overall[it] ?: 0 },
        )
    }
}
