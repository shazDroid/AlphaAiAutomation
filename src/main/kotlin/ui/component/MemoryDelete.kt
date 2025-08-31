package ui.component

import agent.StepType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

private val mapper = ObjectMapper().registerKotlinModule()

// ---- small helpers (no kotlin-reflect; just Java reflection) ----
private fun readStringField(obj: Any, vararg names: String): String? {
    for (n in names) {
        try {
            val f = obj.javaClass.getDeclaredField(n)
            f.isAccessible = true
            val v = f.get(obj) as? String
            if (!v.isNullOrBlank()) return v
        } catch (_: Throwable) { /* ignore and try next */
        }
    }
    return null
}

private fun readStepType(obj: Any): StepType? {
    for (n in listOf("type", "action", "op")) {
        try {
            val f = obj.javaClass.getDeclaredField(n)
            f.isAccessible = true
            val v = f.get(obj)
            when (v) {
                is StepType -> return v
                is String -> {
                    val name = v.trim().uppercase()
                    return runCatching { StepType.valueOf(name) }.getOrNull()
                }
            }
        } catch (_: Throwable) { /* ignore and try next */
        }
    }
    return null
}

private fun normHint(s: String?): String? =
    s?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

