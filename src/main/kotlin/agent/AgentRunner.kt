package agent

import agent.runner.RunContext
import agent.runner.StepDispatcher
import agent.runner.StepOutcome
import agent.runner.handlers.AssertAndWaitHandler
import agent.runner.handlers.InputHandler
import agent.runner.handlers.NavHandler
import agent.runner.handlers.ScrollHandler
import agent.runner.handlers.TapHandler
import agent.runner.handlers.ToggleHandler
import agent.runner.services.DialogService
import agent.runner.services.MemoryService
import agent.runner.services.RankService
import agent.runner.services.UiService
import agent.runner.services.VisionService
import agent.runner.services.XPathService
import io.appium.java_client.android.AndroidDriver
import org.openqa.selenium.JavascriptExecutor

/**
 * Executes an action plan step-by-step using pluggable handlers.
 */
class AgentRunner(
    private val driver: AndroidDriver,
    private val resolver: LocatorResolver,
    private val store: SnapshotStore,
    private val llmDisambiguator: agent.llm.LlmDisambiguator? = null,
    private val semanticReranker: agent.semantic.SemanticReranker? = null,
    private val selector: agent.llm.MultiAgentSelector? = null,
    private val deki: agent.vision.DekiYoloClient? = null,
    private val memory: SelectorMemory? = null,
    private val memoryEvent: (String) -> Unit = {}
) {
    /**
     * Runs the given plan and returns captured snapshots.
     */
    fun run(
        plan: ActionPlan,
        onStep: (Snapshot) -> Unit = {},
        onLog: (String) -> Unit = {},
        onStatus: (String) -> Unit = {},
        stopSignal: () -> Boolean = { false }
    ): List<Snapshot> {
        runCatching {
            val js = driver as JavascriptExecutor
            js.executeScript("mobile: setSettings", mapOf("settings" to mapOf("enforceXPath1" to true)))
        }
        val ctx = RunContext(
            driver = driver,
            resolver = resolver,
            store = store,
            plan = plan,
            selector = selector ?: agent.llm.MultiAgentSelector(driver, llmDisambiguator),
            llmDisambiguator = llmDisambiguator,
            semanticReranker = semanticReranker,
            deki = deki,
            memory = memory,
            memoryEvent = memoryEvent,
            onLog = onLog,
            onStatus = onStatus,
            stopSignal = stopSignal
        )
        val dialog = DialogService(ctx)
        val vision = VisionService(ctx)
        val xpaths = XPathService(ctx)
        val memorySvc = MemoryService(ctx)
        val ui = UiService(ctx, vision, xpaths)
        val rank = RankService(ctx, xpaths, vision)
        val dispatcher = StepDispatcher(
            TapHandler(ctx, ui, vision, xpaths, memorySvc, rank, dialog),
            InputHandler(ctx, ui, xpaths, memorySvc, dialog),
            ToggleHandler(ctx, ui, memorySvc, dialog),
            AssertAndWaitHandler(ctx, ui, xpaths, dialog),
            ScrollHandler(ctx, ui, dialog),
            NavHandler(ctx, ui, vision, dialog)
        )
        val out = mutableListOf<Snapshot>()
        val flowId = plan.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "flow" }
        ctx.beginFlow(flowId)
        onLog("Plan started: \"${plan.title}\" (${plan.steps.size} steps)")
        onLog(if (deki == null) "vision:disabled" else "vision:enabled")
        onLog(if (memory == null) "memory:disabled" else "memory:enabled")
        var pc = 0
        while (pc in plan.steps.indices) {
            if (stopSignal()) {
                onStatus("⏹️ Stopped by user")
                onLog("⏹️ Stopped by user")
                break
            }
            val step = plan.steps[pc]
            onStatus("Step ${step.index}/${plan.steps.size}: ${step.type} ${step.targetHint ?: ""}")
            onLog("➡️  ${step.index} ${step.type} target='${step.targetHint}' value='${step.value}'")
            val outcome: StepOutcome = dispatcher.execute(step, pc)
            val snap = store.capture(step.index, step.type, step.targetHint, outcome.chosen, outcome.ok, outcome.notes)
            ctx.recordFlow(step, outcome)
            out += snap
            onStep(snap)
            pc = when {
                outcome.nextPc != null -> outcome.nextPc!!
                outcome.advance -> pc + 1
                else -> pc
            }
        }
        val runDir = model.plan.latestRunDir()
        model.plan.PlanRecorder.recordSuccess(plan, runDir = runDir)
        onLog("Plan completed. Success ${out.count { it.success }}/${out.size}")
        runCatching { ctx.commitFlow() }
        return out
    }
}
