package tally.app.data

import tally.core.model.CategoryKinds

/**
 * Orders categories as a tree: each top-level category (by sortOrder) followed by its
 * subcategories (by sortOrder). Subcategories whose parent is missing are dropped.
 */
fun List<CategoryEntity>.inTreeOrder(): List<CategoryEntity> {
    val children = filter { it.parentId != null }.groupBy { it.parentId }
    return filter { it.parentId == null }.sortedBy { it.sortOrder }.flatMap { parent ->
        listOf(parent) + children[parent.id].orEmpty().sortedBy { it.sortOrder }
    }
}

/** Whether [categoryId] is under the top-level "Income" category (see [CategoryKinds]). */
fun List<CategoryEntity>.isIncome(categoryId: String?): Boolean {
    val category = find { it.id == categoryId } ?: return false
    val top = category.parentId?.let { parent -> find { it.id == parent } } ?: category
    return CategoryKinds.isIncome(top.name)
}

/**
 * The categories offered as quick-entry chips: subcategories, plus top-level categories
 * that have none. In tree order.
 */
fun List<CategoryEntity>.chipCategories(): List<CategoryEntity> {
    val parentsWithChildren = mapNotNull { it.parentId }.toSet()
    return inTreeOrder().filter { it.id !in parentsWithChildren }
}
