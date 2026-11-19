package cc.monomer.catalog.domain

import cc.monomer.catalog.api.CatalogIngestTaskId

/** Derived read-only loading closure for Catalog job GET responses. */
object CatalogIngestTaskTree {
    fun loadingTaskIds(tasks: List<CatalogIngestTask>): Set<CatalogIngestTaskId> {
        val byId = tasks.associateBy(CatalogIngestTask::id)
        val active = tasks.filter { it.state == CatalogIngestTaskState.RUNNING }
        return buildSet {
            active.forEach { task ->
                var current: CatalogIngestTask? = task
                while (current != null) {
                    if (!add(current.id)) break
                    current = current.parentTaskId?.let(byId::get)
                }
            }
        }
    }
}
