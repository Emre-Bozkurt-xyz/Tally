package tally.server.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The dashboard's rows must match the phone's Room entities exactly, or its edits won't
 * decode on the phone. Checked against the newest committed Room schema.
 */
class RowsSchemaTest {
    private val schema = File("../app/schemas/tally.app.data.TallyDatabase")
        .listFiles { f -> f.extension == "json" }!!
        .maxBy { it.nameWithoutExtension.toInt() }
        .let { Json.parseToJsonElement(it.readText()).jsonObject["database"]!!.jsonObject }

    @Test
    fun `every row type has the phone's columns with the same nullability`() {
        val tables = schema["entities"]!!.jsonArray.associate { entity ->
            val e = entity.jsonObject
            e["tableName"]!!.jsonPrimitive.content to e["fields"]!!.jsonArray.associate { field ->
                val f = field.jsonObject
                f["fieldPath"]!!.jsonPrimitive.content to (f["notNull"]?.jsonPrimitive?.boolean ?: false)
            }
        }
        val types = listOf(
            Rows.transactions, Rows.splits, Rows.tags, Rows.transactionTags, Rows.categories,
            Rows.accounts, Rows.merchants, Rows.aliases, Rows.rules,
        )
        for (type in types) {
            val descriptor = type.serializer.descriptor
            val ours = (0 until descriptor.elementsCount).associate { i ->
                descriptor.getElementName(i) to !descriptor.getElementDescriptor(i).isNullable
            }
            assertEquals(tables[type.table], ours, "columns of ${type.table}")
        }
        assert(schema["version"]!!.jsonPrimitive.int >= 6)
    }
}
