// agent/memory/MemoryMaintenance.kt
package agent.memory

data class DeletePlanMemoryReport(
    val attempted: Int,
    val removed: Int
)

//fun deleteMemoryForPlan(
//    plan: ActionPlan,
//    memory: SelectorMemory,
//    appPkg: String? = null
//): DeletePlanMemoryReport {
//    val pairs = plan.steps
//        .mapNotNull { s ->
//            val h = (s.targetHint ?: s.value)?.trim()?.lowercase()
//            if (h.isNullOrEmpty()) null else s.type to h
//        }
//        .distinct()
//
//    var removed = 0
//    pairs.forEach { (op, hint) ->
//        removed += memory.delete(appPkg = appPkg, activity = null, op = op, hint = hint)
//    }
//    return DeletePlanMemoryReport(attempted = pairs.size, removed = removed)
//}
