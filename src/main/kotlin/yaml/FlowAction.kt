package yaml

data class FlowAction(
    val action: String,
    val target_text: String? = null,
    var target: String? = null,
    val value: String? = null,
    val packageName: String? = null
)



data class TestFlow(
    val flow: List<FlowAction>
)
