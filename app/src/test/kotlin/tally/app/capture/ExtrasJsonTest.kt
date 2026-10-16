package tally.app.capture

import android.app.Notification
import android.os.Bundle
import android.text.SpannableString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExtrasJsonTest {
    @Test
    fun `dumps the common extras types`() {
        val bundle = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, SpannableString("Purchase"))
            putString(Notification.EXTRA_TEXT, "\$4.25 at TIM HORTONS")
            putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("line 1", "line 2"))
            putInt("count", 3)
            putLong("long", 1_760_000_000_000)
            putBoolean(Notification.EXTRA_SHOW_WHEN, true)
            putIntArray("ints", intArrayOf(1, 2))
            putString("nothing", null)
            putBundle("nested", Bundle().apply { putString("inner", "value") })
        }

        val json = ExtrasJson.toJson(bundle)

        assertEquals(JsonPrimitive("Purchase"), json[Notification.EXTRA_TITLE])
        assertEquals(JsonPrimitive("\$4.25 at TIM HORTONS"), json[Notification.EXTRA_TEXT])
        assertEquals(
            JsonArray(listOf(JsonPrimitive("line 1"), JsonPrimitive("line 2"))),
            json[Notification.EXTRA_TEXT_LINES],
        )
        assertEquals(JsonPrimitive(3), json["count"])
        assertEquals(JsonPrimitive(1_760_000_000_000), json["long"])
        assertEquals(JsonPrimitive(true), json[Notification.EXTRA_SHOW_WHEN])
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), json["ints"])
        assertEquals(JsonNull, json["nothing"])
        assertEquals(JsonObject(mapOf("inner" to JsonPrimitive("value"))), json["nested"])
    }

    @Test
    fun `describes opaque parcelables instead of failing`() {
        val bundle = Bundle().apply {
            putParcelable("intent", android.content.Intent("tally.TEST"))
        }
        val value = ExtrasJson.toJson(bundle)["intent"]!!.jsonPrimitive.content
        assertTrue(value, value.startsWith("<android.content.Intent>"))
    }
}
