package agent.runner

import agent.*
import agent.llm.LlmDisambiguator
import agent.llm.MultiAgentSelector
import agent.semantic.SemanticReranker
import agent.vision.DekiYoloClient
import agent.vision.VisionResult
import io.appium.java_client.android.AndroidDriver
import org.openqa.selenium.JavascriptExecutor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mutable context shared by handlers.
 */
class RunContext(
    val driver: AndroidDriver,
    val resolver: LocatorResolver,
    val store: SnapshotStore,
    val plan: ActionPlan,
    val selector: MultiAgentSelector,
    val llmDisambiguator: LlmDisambiguator?,
    val semanticReranker: SemanticReranker?,
    val deki: DekiYoloClient?,
    val memory: SelectorMemory?,
    val memoryEvent: (String) -> Unit,
    val onLog: (String) -> Unit,
    val onStatus: (String) -> Unit,
    val stopSignal: () -> Boolean
) {
    val flowStore = agent.memory.FlowGraphStore(java.io.File("graphs"))
    var flowRecorder: agent.memory.FlowRecorder? = null
    var activeScope: String? = null
    var scopeTtlSteps: Int = 0
    var lastTapY: Int? = null
    var lastVisionHash: Int? = null
    var lastVision: VisionResult? = null
    var lastVisionScope: String? = null
    private val hashCounter = AtomicInteger(0)

    fun beginFlow(flowId: String) {
        val pkg = runCatching { driver.currentPackage }.getOrNull().orEmpty()
        flowRecorder = agent.memory.FlowRecorder(
            store = flowStore,
            appPkg = pkg,
            flowId = flowId,
            title = plan.title,
            activityProvider = { runCatching { driver.currentActivity() }.getOrNull() }
        )
    }

    fun recordFlow(step: PlanStep, outcome: StepOutcome) {
        val hintKey = step.targetHint ?: step.value ?: step.type.name
        val body = when (step.type) {
            StepType.INPUT_TEXT -> "${(step.targetHint ?: "value")}: ${step.value.orEmpty()}"
            StepType.CHECK -> "${step.targetHint ?: ""}: ${step.value ?: ""}".trim()
            else -> (step.targetHint ?: step.value ?: "")
        }
        flowRecorder?.addStep(step.type, hintKey, body.ifBlank { null })
    }

    fun commitFlow() {
        runCatching { flowRecorder?.commitRun() }
    }

    fun setScope(s: String?) {
        activeScope = s?.lowercase()
        scopeTtlSteps = if (s == null) 0 else 3
    }

    fun tickScopeTtl() {
        if (scopeTtlSteps > 0) scopeTtlSteps--
        if (scopeTtlSteps == 0) activeScope = null
    }

    fun currentActivitySafe(): String = runCatching { driver.currentActivity() }.getOrNull().orEmpty()

    fun pageHash(): Int = runCatching { driver.pageSource.hashCode() }.getOrDefault(hashCounter.incrementAndGet())

    fun execShell(cmd: String) {
        runCatching {
            (driver as JavascriptExecutor).executeScript("mobile: shell", mapOf("command" to cmd))
        }
    }
}
