package agent.semantic

interface FieldResolver {
    fun resolve(goal: String, rawField: String): String
}

object DefaultFieldResolver : FieldResolver {
    override fun resolve(goal: String, rawField: String): String =
        rawField.trim().replace(Regex("\\s+"), " ")
}
