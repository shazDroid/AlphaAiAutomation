package agent.memory

import java.io.File

data class MemSelector(val strategy: String, val value: String, val ok: Int, val fail: Int, val last: Long)
data class MemEntry(
    val appPkg: String,
    val activity: String,
    val op: String,
    val hint: String,
    val file: File,
    val selectors: List<MemSelector>
)

data class MemActivity(val name: String, val entries: List<MemEntry>)
data class MemApp(val name: String, val activities: List<MemActivity>)
data class MemIndex(val apps: List<MemApp>, val totalEntries: Int, val totalSelectors: Int)