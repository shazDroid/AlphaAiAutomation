package agent.semantic

class LlmFieldResolver(
    private val resolveFn: (goal: String, rawField: String) -> String?
) : FieldResolver {
    override fun resolve(goal: String, rawField: String): String =
        resolveFn(goal, rawField)?.takeIf { it.isNotBlank() } ?: DefaultFieldResolver.resolve(goal, rawField)
}
