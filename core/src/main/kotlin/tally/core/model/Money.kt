package tally.core.model

import kotlin.math.absoluteValue

/** Money is always `Long` cents. This is the one place that turns cents into display text. */
object Money {
    fun format(cents: Long): String {
        val abs = cents.absoluteValue
        val dollars = abs / 100
        val rem = abs % 100
        val grouped = dollars.toString().reversed().chunked(3).joinToString(",").reversed()
        val sign = if (cents < 0) "-" else ""
        return "$sign$$grouped.${rem.toString().padStart(2, '0')}"
    }
}
