package agent.env

object Env {
    fun str(name: String?, default: String? = null): String? {
        if (name.isNullOrBlank()) return default
        val v = try {
            System.getenv(name)
        } catch (_: Throwable) {
            null
        }
        return if (v.isNullOrBlank()) default else v
    }

    fun bool(name: String?, default: Boolean = false): Boolean {
        val v = str(name) ?: return default
        return when (v.trim().lowercase()) {
            "1", "true", "yes", "y", "on" -> true
            "0", "false", "no", "n", "off" -> false
            else -> default
        }
    }
}
