package agent

/**
 * Adapter for persisting and retrieving successful selectors.
 */
interface SelectorMemory {
    fun find(appPkg: String, activity: String?, op: StepType, hint: String?): List<Locator>
    fun success(appPkg: String, activity: String?, op: StepType, hint: String?, locator: Locator)
    fun failure(appPkg: String, activity: String?, op: StepType, hint: String?, prior: Locator)
    fun delete(appPkg: String? = null, activity: String? = null, op: StepType? = null, hint: String? = null): Int {
        return 0
    }

    fun clearAll(): Int = delete()
}
