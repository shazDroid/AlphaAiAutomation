package agent.runner.services

import adb.UiDumpParser.xpathLiteral
import agent.Locator
import agent.PlanStep
import agent.StepType
import agent.Strategy
import agent.runner.RunContext
import io.appium.java_client.AppiumBy
import org.openqa.selenium.By

/**
 * Detects and resolves blocking dialogs and provides post-step windows.
 */
class DialogService(private val ctx: RunContext) {
    private val pollMs = 140L

    fun afterStep(step: PlanStep, windowMs: Long): Boolean {
        val handled = pollForDialog(windowMs)
        if (!handled) Thread.sleep(220)
        return handled
    }

    fun afterStepSilent(windowMs: Long): Boolean = pollForDialog(windowMs)

    private fun pollForDialog(windowMs: Long): Boolean {
        val start = System.currentTimeMillis()
        var handled = false
        while (System.currentTimeMillis() - start < windowMs) {
            val dlg = detect()
            if (dlg == null) {
                Thread.sleep(pollMs); continue
            }
            val primary = dlg.buttons.first()
            ctx.driver.findElement(AppiumBy.xpath(primary.second)).click()
            ctx.store.capture(
                -1,
                StepType.TAP,
                "DIALOG: ${primary.first}",
                Locator(Strategy.XPATH, primary.second),
                true,
                "AUTO_DIALOG"
            )
            handled = true
            break
        }
        return handled
    }

    private fun detect(): DetectedDialog? {
        val btnNodes = ctx.driver.findElements(
            AppiumBy.xpath("//*[self::android.widget.Button or (self::android.widget.TextView and @clickable='true') or (self::android.widget.CheckedTextView and @clickable='true')]")
        )
        if (btnNodes.isEmpty()) return null
        val labelSet = setOf(
            "ok",
            "okay",
            "retry",
            "try again",
            "cancel",
            "close",
            "dismiss",
            "continue",
            "yes",
            "no",
            "allow",
            "deny",
            "got it",
            "understood",
            "confirm"
        )
        val candidate = btnNodes.firstOrNull {
            val t = (runCatching { it.text }.getOrNull() ?: "").trim().lowercase()
            t.isNotEmpty() && t in labelSet
        } ?: return null
        val container = candidate.findElements(By.xpath("ancestor::*[@resource-id][1]")).firstOrNull()
            ?: candidate.findElements(By.xpath("ancestor::*[1]")).firstOrNull() ?: return null
        val rid = runCatching { container.getAttribute("resource-id") }.getOrNull()?.trim().orEmpty()
        val rootXp = if (rid.isNotEmpty()) "//*[@resource-id=${xpathLiteral(rid)}]" else null
        val buttons =
            container.findElements(AppiumBy.xpath(".//*[self::android.widget.Button or (self::android.widget.TextView and @clickable='true') or (self::android.widget.CheckedTextView and @clickable='true')]"))
                .mapNotNull {
                    val t = (runCatching { it.text }.getOrNull() ?: "").trim()
                    if (t.isEmpty()) null else t to (rootXp?.let { rx -> "$rx//*[normalize-space(@text)=${xpathLiteral(t)}]" }
                        ?: "//*[normalize-space(@text)=${xpathLiteral(t)}]")
                }
                .distinctBy { it.first.lowercase() }
        if (buttons.isEmpty()) return null
        return DetectedDialog(buttons)
    }

    private data class DetectedDialog(val buttons: List<Pair<String, String>>)
}
