package tally.app.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import java.time.Clock
import java.util.UUID

/**
 * Edits to the two-level category tree. Every write bumps `updatedAt` so it syncs, and
 * deletes are soft. Methods return an error message for the user, or null on success.
 */
class CategoryRepository(
    private val db: TallyDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val dao = db.categoryDao()

    suspend fun add(name: String, parentId: String?): String? = write { all ->
        val trimmed = name.trim()
        validate(all, trimmed, parentId, editingId = null)?.let { return@write it }
        val siblings = all.filter { it.parentId == parentId }
        dao.upsertAll(
            listOf(
                CategoryEntity(
                    id = UUID.randomUUID().toString(),
                    name = trimmed,
                    parentId = parentId,
                    monthlyBudgetCents = null,
                    sortOrder = (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                    pinned = false,
                    updatedAt = clock.millis(),
                ),
            ),
        )
        null
    }

    /** Renames and/or moves a category. Moving puts it last among its new siblings. */
    suspend fun update(id: String, name: String, parentId: String?): String? = write { all ->
        val current = all.find { it.id == id } ?: return@write "That category no longer exists"
        val trimmed = name.trim()
        validate(all, trimmed, parentId, editingId = id)?.let { return@write it }
        val moved = parentId != current.parentId
        val sortOrder = if (moved) {
            (all.filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
        } else {
            current.sortOrder
        }
        dao.upsertAll(
            listOf(current.copy(name = trimmed, parentId = parentId, sortOrder = sortOrder, updatedAt = clock.millis())),
        )
        null
    }

    /** Sets or clears ([cents] null) a monthly budget. */
    suspend fun setBudget(id: String, cents: Long?): String? = write { all ->
        val current = all.find { it.id == id } ?: return@write "That category no longer exists"
        if (cents != null && cents <= 0) return@write "A budget must be more than \$0"
        dao.upsertAll(listOf(current.copy(monthlyBudgetCents = cents, updatedAt = clock.millis())))
        null
    }

    suspend fun setPinned(id: String, pinned: Boolean): String? = write { all ->
        val current = all.find { it.id == id } ?: return@write "That category no longer exists"
        dao.upsertAll(listOf(current.copy(pinned = pinned, updatedAt = clock.millis())))
        null
    }

    /** Swaps a category with its previous ([by] = -1) or next ([by] = 1) sibling. */
    suspend fun move(id: String, by: Int): String? = write { all ->
        val current = all.find { it.id == id } ?: return@write "That category no longer exists"
        val siblings = all.filter { it.parentId == current.parentId }.sortedBy { it.sortOrder }.toMutableList()
        val from = siblings.indexOfFirst { it.id == id }
        val to = from + by
        if (to !in siblings.indices) return@write null
        siblings.add(to, siblings.removeAt(from))
        // Renumber densely; only rows whose position changed are written.
        val now = clock.millis()
        dao.upsertAll(
            siblings.mapIndexedNotNull { index, category ->
                if (category.sortOrder == index) null else category.copy(sortOrder = index, updatedAt = now)
            },
        )
        null
    }

    /** Past transactions keep pointing at it and still show its name. */
    suspend fun delete(id: String): String? = write { all ->
        val current = all.find { it.id == id } ?: return@write null
        if (all.any { it.parentId == id }) {
            return@write "Move or delete the subcategories of ${current.name} first"
        }
        dao.upsertAll(listOf(current.copy(deleted = true, updatedAt = clock.millis())))
        null
    }

    private fun validate(all: List<CategoryEntity>, name: String, parentId: String?, editingId: String?): String? {
        if (name.isEmpty()) return "Enter a name"
        if ('/' in name) return "Names can't contain /"
        if (parentId != null) {
            val parent = all.find { it.id == parentId } ?: return "That parent no longer exists"
            if (parent.parentId != null) return "Categories only go two levels deep"
            if (parentId == editingId) return "A category can't be its own parent"
            if (editingId != null && all.any { it.parentId == editingId }) {
                return "A category with subcategories can't be moved under another one"
            }
        }
        val clash = all.any { it.id != editingId && it.parentId == parentId && it.name.equals(name, ignoreCase = true) }
        return if (clash) "$name already exists there" else null
    }

    /** Runs [block] with the active categories inside one write transaction. */
    private suspend fun write(block: suspend (List<CategoryEntity>) -> String?): String? =
        db.useWriterConnection { connection ->
            connection.immediateTransaction { block(dao.getActive()) }
        }
}
