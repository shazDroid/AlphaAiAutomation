package generator

import agent.ActionPlan
import agent.Snapshot
import ui.OllamaClient
import util.CodeFenceParser
import util.PromptBuilder
import java.io.File

class LlmScriptGenerator(
    private val ollama: OllamaClient,
    private val outDir: File
) {
    fun generate(plan: ActionPlan, timeline: List<Snapshot>) {
        outDir.mkdirs()

        val summaryJson = PromptBuilder.buildActionSummaryJson(plan, timeline)
        val uiHintsJson = util.UiHintsBuilder.extractHints(timeline)
        val prompt = PromptBuilder.buildWdioCucumberPrompt(summaryJson, uiHintsJson)

        val llmResponse = ollama.completeJsonBlocking(
            system = """
                You are a senior test automation generator.
                Produce production-ready WebdriverIO + Cucumber code for Android using Appium.
                Must output EXACTLY four files: BasePage.ts, PlatformPage.ts, StepDefinitions.ts, feature.feature.
                BasePage.ts: only abstract getters shaped like:
                public abstract get SOME_ELEMENT(): ChainablePromise<WebdriverIO.Element>;
                PlatformPage.ts extends BasePage and implements getters using stable selectors.
                StepDefinitions.ts calls getters, no inline selectors.
                Feature must describe scenario succinctly.
            """.trimIndent(),
            user = prompt
        )

        val files = CodeFenceParser.split(llmResponse) // implement a tiny parser
        write(outDir.resolve("pages"), "BasePage.ts", files["BasePage.ts"])
        write(outDir.resolve("pages"), "PlatformPage.ts", files["PlatformPage.ts"])
        write(outDir.resolve("steps"), "StepDefinitions.ts", files["StepDefinitions.ts"])
        write(outDir.resolve("features"), "feature.feature", files["feature.feature"])
    }

    private fun write(dir: File, name: String, content: String?) {
        val d = if (name.endsWith(".ts") && name.contains("Page")) File(dir, "") else dir
        d.mkdirs()
        File(d, name).writeText(content ?: error("LLM missing $name"))
    }
}
