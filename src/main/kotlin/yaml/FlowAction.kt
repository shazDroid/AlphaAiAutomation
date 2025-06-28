package yaml

data class FlowAction(
    val action: String,
    val target_text: String? = null,
    val target: String? = null,
    val value: String? = null,
    val packageName: String? = null // add this line
)



data class TestFlow(
    val flow: List<FlowAction>
)
