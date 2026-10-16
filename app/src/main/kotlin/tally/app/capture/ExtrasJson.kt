package tally.app.capture

import android.os.Bundle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Dumps a notification extras [Bundle] to JSON, as completely as possible without crashing.
 * Bitmaps, icons, PendingIntents and other opaque Parcelables become a short descriptive
 * string. A key whose value can't be read becomes an error string instead of failing the dump.
 */
object ExtrasJson {
    private const val MAX_STRING = 4_000
    private const val MAX_DEPTH = 5

    fun toJson(bundle: Bundle): JsonObject = bundleToJson(bundle, depth = 0)

    private fun bundleToJson(bundle: Bundle, depth: Int): JsonObject {
        val keys = try {
            bundle.keySet().sorted()
        } catch (t: Throwable) {
            return JsonObject(mapOf("_error" to JsonPrimitive(t.toString())))
        }
        return JsonObject(
            keys.associateWith { key ->
                try {
                    @Suppress("DEPRECATION")
                    valueToJson(bundle.get(key), depth)
                } catch (t: Throwable) {
                    JsonPrimitive("<unreadable: $t>")
                }
            },
        )
    }

    private fun valueToJson(value: Any?, depth: Int): JsonElement {
        if (depth > MAX_DEPTH) return JsonPrimitive("<too deep>")
        return when (value) {
            null -> JsonNull
            is CharSequence -> JsonPrimitive(value.toString().take(MAX_STRING))
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            is Bundle -> bundleToJson(value, depth + 1)
            is Array<*> -> JsonArray(value.map { valueToJson(it, depth + 1) })
            is Iterable<*> -> JsonArray(value.map { valueToJson(it, depth + 1) })
            is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
            is LongArray -> JsonArray(value.map { JsonPrimitive(it) })
            is FloatArray -> JsonArray(value.map { JsonPrimitive(it) })
            is DoubleArray -> JsonArray(value.map { JsonPrimitive(it) })
            is BooleanArray -> JsonArray(value.map { JsonPrimitive(it) })
            is ByteArray -> JsonPrimitive("<${value.size} bytes>")
            else -> JsonPrimitive("<${value.javaClass.name}> ${value.toString().take(MAX_STRING)}")
        }
    }
}
