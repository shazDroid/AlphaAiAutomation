package agent

import agent.planner.RunGraphAdapter
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
import java.io.File

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

        val runsDir = File(System.getProperty("user.dir")).resolve("FrontEnd/AI Automation/runs")
        val inferred = if (plan.steps.isEmpty()) RunGraphAdapter.planFromLatestRun(plan.title, runsDir, onLog) else null
        val effectivePlan = inferred ?: plan
        val autoRun = inferred != null
        val graphFile =
            effectivePlan.steps.firstOrNull { it.meta?.get("__fromGraph") == "1" }?.meta?.get("graphFile") as? String

        val ctx = RunContext(
            driver = driver,
            resolver = resolver,
            store = store,
            plan = effectivePlan,
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
        val flowId = effectivePlan.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "flow" }
        ctx.beginFlow(flowId)

        val fileSink = util.RunLog.start(flowId)
        val teeLog: (String) -> Unit = { m -> fileSink(m); onLog(m) }

        teeLog(if (autoRun) "autorun:source=graph" else "autorun:source=user")
        if (autoRun) teeLog("autorun:graphFile=" + (graphFile ?: "unknown"))
        teeLog("Plan started: \"${effectivePlan.title}\" (${effectivePlan.steps.size} steps)")
        teeLog(if (deki == null) "vision:disabled" else "vision:enabled")
        teeLog(if (memory == null) "memory:disabled" else "memory:enabled")

        val runId = System.currentTimeMillis().toString()
        val flowRec = agent.recorder.FlowGraphRecorder(
            runId = runId,
            title = effectivePlan.title,
            appPkg = runCatching { driver.currentPackage }.getOrNull()
        )

        var pc = 0
        while (pc in effectivePlan.steps.indices) {
            if (stopSignal()) {
                onStatus("⏹️ Stopped by user")
                teeLog("⏹️ Stopped by user")
                break
            }

            val step = effectivePlan.steps[pc]
            onStatus("Step ${step.index}/${effectivePlan.steps.size}: ${step.type} ${step.targetHint ?: ""}")
            teeLog("➡️  ${step.index} ${step.type} target='${step.targetHint}' value='${step.value}'")

            val outcome: StepOutcome = dispatcher.execute(step, pc)

            val snap = store.capture(step.index, step.type, step.targetHint, outcome.chosen, outcome.ok, outcome.notes)
            val activity = runCatching { driver.currentActivity() }.getOrNull()
            val screenTitle = guessScreenTitle(activity, step.targetHint)
            flowRec.observe(step, activity, screenTitle)

            teeLog("⬅️  ${step.index} ok=${outcome.ok} chosen='${outcome.chosen}' notes='${outcome.notes}'")
            ctx.recordFlow(step, outcome)
            out += snap
            onStep(snap)

            pc = when {
                outcome.nextPc != null -> outcome.nextPc
                outcome.advance -> pc + 1
                else -> pc
            }
        }

        val runDir = model.plan.latestRunDir()
        model.plan.PlanRecorder.recordSuccess(effectivePlan, runDir = runDir)

        val flowFile = flowRec.write(runsDir)
        teeLog("graph:flow written $flowFile")
        teeLog("Plan completed. Success ${out.count { it.success }}/${out.size}")

        runCatching { ctx.commitFlow() }
        return out
    }

    private fun guessScreenTitle(activity: String?, hint: String?): String? {
        val a = (activity ?: "").substringAfterLast('.').lowercase()
        val h = (hint ?: "").lowercase()
        fun nice(s: String) = s.split(Regex("[_\\s]")).filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
        return when {
            a.contains("login") || h.contains("login") || h.contains("signin") -> "Login Page"
            a.contains("home") || a.contains("main") || h.contains("home") -> "Home"
            a.contains("transfer") || h.contains("transfer") -> "Transfer Page"
            a.contains("payment") || h.contains("payment") || h.contains("pay") -> "Payments Page"
            a.isNotBlank() -> nice(a)
            else -> null
        }
    }
}
