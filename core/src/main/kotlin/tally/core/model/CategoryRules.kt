package tally.core.model

/** A category as the tree rules see it. */
data class CategoryNode(val id: String, val name: String, val parentId: String?)

/** The rules for naming and nesting categories, shared by the phone and the dashboard. */
object CategoryRules {
    /** Why [name] can't go under [parentId] (null for top level), or null if it can. [editingId] is the category being changed. */
    fun validate(all: List<CategoryNode>, name: String, parentId: String?, editingId: String?): String? {
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

    /** Why [id] can't be deleted, or null. */
    fun deleteError(all: List<CategoryNode>, id: String): String? {
        val current = all.find { it.id == id } ?: return null
        return if (all.any { it.parentId == id }) "Move or delete the subcategories of ${current.name} first" else null
    }
}
