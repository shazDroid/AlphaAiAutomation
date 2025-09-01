package agent.runner.handlers

import agent.Locator
import agent.PlanStep
import agent.StepType
import agent.runner.RunContext
import agent.runner.StepOutcome
import agent.runner.services.DialogService
import agent.runner.services.MemoryService
import agent.runner.services.UiService
import agent.runner.services.VisionService
import agent.runner.services.XPathService
import io.appium.java_client.AppiumBy
import org.openqa.selenium.By
import org.openqa.selenium.WebElement
import kotlin.math.abs

/**
 * Handles CHECK steps with memory, vision, DOM and proximity fallbacks.
 */
class ToggleHandler(
    private val ctx: RunContext,
    private val ui: UiService,
    private val memory: MemoryService,
    private val dialog: DialogService,
    private val xpaths: XPathService = XPathService(ctx),
    private val vision: VisionService = VisionService(ctx)
) {
    fun check(step: PlanStep): StepOutcome {
        val th = step.targetHint ?: return StepOutcome(false, notes = "Missing target for CHECK")
        val preferredSection = step.meta["section"] ?: ui.parseSectionFromHint(th)
        val effectiveSection = preferredSection ?: ctx.activeScope
        val desired = desiredState(step.value)

        agent.ui.Gestures.hideKeyboardIfOpen(ctx.driver) { }

        val saved = memory.find(StepType.CHECK, th)
        for (loc in saved) {
            if (ui.tryCheckByLocator(loc, desired)) {
                dialog.afterStep(step, 2400)
                memory.save(StepType.CHECK, th, loc, null)
                return StepOutcome(true, chosen = loc)
            }
        }

        if (isGenericToggleHint(th)) {
            val ensured = ensureVisibleCheckable(6)
            if (ensured != null) {
                val chosen = applyDesired(ensured.first, ensured.second, desired)
                dialog.afterStep(step, 2400)
                memory.save(StepType.CHECK, th, chosen, saved.firstOrNull())
                return StepOutcome(true, chosen = chosen)
            }
        }

        val visionHit = findByVision(th, effectiveSection)
        if (visionHit != null) {
            val chosen = applyDesired(visionHit.first, visionHit.second, desired)
            dialog.afterStep(step, 2400)
            memory.save(StepType.CHECK, th, chosen, saved.firstOrNull())
            return StepOutcome(true, chosen = chosen)
        }

        val domLoc: Locator? = ctx.resolver.findSwitchOrCheckableForLabel(th, preferredSection)
        if (domLoc != null) {
            val el = runCatching { ctx.driver.findElement(AppiumBy.xpath(domLoc.value)) }.getOrNull()
            if (el != null) {
                val chosen = applyDesired(domLoc, el, desired)
                dialog.afterStep(step, 2400)
                memory.save(StepType.CHECK, th, chosen, saved.firstOrNull())
                return StepOutcome(true, chosen = chosen)
            }
        }

        val ensured = ensureVisibleCheckable(4)
        if (ensured != null) {
            val chosen = applyDesired(ensured.first, ensured.second, desired)
            dialog.afterStep(step, 2400)
            memory.save(StepType.CHECK, th, chosen, saved.firstOrNull())
            return StepOutcome(true, chosen = chosen)
        }

        return StepOutcome(false, notes = "CHECK failed: \"$th\"")
    }

    private fun desiredState(token: String?): Boolean? {
        val t = token?.trim()?.lowercase()
        return when (t) {
            "on", "true", "checked", "tick", "select" -> true
            "off", "false", "unchecked", "untick", "deselect" -> false
            else -> null
        }
    }

    private fun isGenericToggleHint(hint: String): Boolean {
        val s = hint.trim().lowercase()
        return s in setOf("toggle", "switch", "checkbox") ||
                Regex("\\b(toggle|switch|check(box)?)\\b").containsMatchIn(s)
    }

    private fun findByVision(hint: String, section: String?): Pair<Locator, WebElement>? {
        val vFast = vision.fast(section)
        val viaFast = tryFindToggle(hint, section, vFast)
        if (viaFast != null) return viaFast
        val vOcr = vision.slowForText(section)
        return tryFindToggle(hint, section, vOcr)
    }

    private fun tryFindToggle(
        hint: String,
        section: String?,
        v: agent.vision.VisionResult?
    ): Pair<Locator, WebElement>? {
        if (v == null) return null
        val pair = agent.vision.ScreenVision.findToggleForLabel(ctx.driver, v, hint, section) ?: return null
        val loc = pair.first
        val el = runCatching { ctx.driver.findElement(By.xpath(loc.value)) }.getOrNull() ?: pair.second
        return loc to el
    }

    private fun applyDesired(loc: Locator, el: WebElement, desired: Boolean?): Locator {
        val isChecked = (runCatching { el.getAttribute("checked") }.getOrNull() ?: "false") == "true"
        val want = desired ?: !isChecked
        if (isChecked != want) el.click()
        return loc
    }

    private fun ensureVisibleCheckable(maxScrolls: Int): Pair<Locator, WebElement>? {
        repeat(maxScrolls + 1) { _ ->
            val visible = visibleCheckables()
            val best = pickNearestToAnchor(visible)
            if (best != null) {
                val loc = xpaths.locatorOf(ui.buildLocatorForElement(best))
                return loc to best
            }
            agent.ui.Gestures.standardScrollUp(ctx.driver)
            ctx.resolver.waitForStableUi()
        }
        return null
    }

    private fun visibleCheckables(): List<WebElement> {
        val all = ctx.driver.findElements(
            AppiumBy.xpath("//android.widget.Switch | //*[@checkable='true']")
        )
        if (all.isEmpty()) return emptyList()
        return all.filter { el ->
            val r = el.rect
            val vp = ctx.driver.manage().window().size
            val topSafe = 40
            val botSafe = vp.height - 40
            r.y >= topSafe && (r.y + r.height) <= botSafe
        }
    }

    private fun pickNearestToAnchor(list: List<WebElement>): WebElement? {
        if (list.isEmpty()) return null
        val anchorY = ctx.lastTapY ?: ctx.driver.manage().window().size.height / 2
        return list.minByOrNull { el -> abs((el.rect.y + el.rect.height / 2) - anchorY) }
    }
}
