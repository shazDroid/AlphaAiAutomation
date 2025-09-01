package agent.runner.handlers

import adb.UiDumpParser.xpathLiteral
import agent.Locator
import agent.PlanStep
import agent.StepType
import agent.candidates.UICandidate
import agent.candidates.extractCandidatesForHint
import agent.runner.RunContext
import agent.runner.StepOutcome
import agent.runner.services.DialogService
import agent.runner.services.RankService
import agent.runner.services.UiService
import agent.runner.services.VisionService
import agent.runner.services.XPathService
import io.appium.java_client.AppiumBy
import org.openqa.selenium.WebElement

/**
 * Handles TAP steps.
 */
class TapHandler(
    private val ctx: RunContext,
    private val ui: UiService,
    private val vision: VisionService,
    private val xpaths: XPathService,
    private val memory: agent.runner.services.MemoryService,
    private val rank: RankService,
    private val dialog: DialogService
) {
    private val defaultTimeout = 20_000L
    private val stopWords = setOf("to","for","and","the","my","your","a","an","of","on","in","at","with")

    fun tap(step: PlanStep): StepOutcome {
        val rawHint = step.targetHint ?: return StepOutcome(false, notes = "Missing target for TAP")
        val th = rawHint.trim()
        val timeout = step.meta["timeoutMs"]?.toLongOrNull() ?: defaultTimeout
        val preferredSection = step.meta["section"] ?: ui.parseSectionFromHint(th)
        val effectiveSection = preferredSection ?: ctx.activeScope
        agent.ui.Gestures.hideKeyboardIfOpen(ctx.driver) { }

        val saved = memory.find(StepType.TAP, th)
        for (loc in saved) {
            if (tryTapByLocator(loc, effectiveSection)) {
                dialog.afterStep(step, 1600)
                memory.save(StepType.TAP, th, loc, null)
                return StepOutcome(true, chosen = loc)
            }
        }

        ui.ensureVisibleByAutoScrollBounded(th, preferredSection, step.index, step.type, 6)

        val tokenHit = firstClickableByTokens(th)
        if (tokenHit != null) {
            tokenHit.second.click()
            ctx.lastTapY = runCatching { tokenHit.second.rect.let { it.y + it.height / 2 } }.getOrNull()
            ctx.setScope(vision.determineScopeByY(ctx.lastTapY, vision.fast(effectiveSection)) ?: ctx.activeScope)
            dialog.afterStep(step, 1600)
            memory.save(StepType.TAP, th, tokenHit.first, null)
            return StepOutcome(true, chosen = tokenHit.first)
        }

        if (effectiveSection != null) {
            val sec = ui.tapByTextInSection(th, effectiveSection)
            if (sec != null) {
                dialog.afterStep(step, 1400)
                memory.save(StepType.TAP, th, sec, null)
                return StepOutcome(true, chosen = sec)
            }
        }

        val fastLoc = ctx.resolver.findSwitchOrCheckableForLabel(th, effectiveSection)
        if (fastLoc != null) {
            val el = runCatching { ctx.driver.findElement(AppiumBy.xpath(fastLoc.value)) }.getOrNull()
            if (el != null) {
                el.click()
                ctx.lastTapY = runCatching { el.rect.let { it.y + it.height / 2 } }.getOrNull()
                ctx.setScope(vision.determineScopeByY(ctx.lastTapY, vision.fast(effectiveSection)) ?: ctx.activeScope)
                dialog.afterStep(step, 1600)
                memory.save(StepType.TAP, th, fastLoc, saved.firstOrNull())
                return StepOutcome(true, chosen = fastLoc)
            }
        }

        val nextQ = nextQueryFor(step)
        val deadline = System.currentTimeMillis() + timeout
        var tapped: Locator? = null
        while (System.currentTimeMillis() < deadline && tapped == null) {
            ctx.resolver.waitForStableUi()
            if (ui.waitWhileBusy(2_500L)) continue
            val before = ctx.pageHash()
            val all = runCatching { extractCandidatesForHint(ctx.driver, th, limit = 80) }.getOrDefault(emptyList())
            if (all.isEmpty()) { Thread.sleep(280); continue }
            val v = vision.fast(effectiveSection)
            val scoped = if (effectiveSection != null) rank.scopeXY(all, effectiveSection, v) else all
            val ordered = rank.rank(th, scoped)
            for (cand in ordered.distinctBy { it.id }) {
                val ok = tryTapCandidate(cand, before, v, nextQ)
                if (ok) { tapped = xpaths.locatorOf(cand.xpath); break }
            }
            if (tapped == null) Thread.sleep(280)
        }

        if (tapped == null) {
            val before = ctx.pageHash()
            ctx.onStatus("""ACTION_REQUIRED::Tap "$th" on the device""")
            ctx.onLog("  waiting up to 12s for manual tap…")
            val manual = ui.waitChangedSince(before, 12_000L)
            if (!manual) return StepOutcome(false, notes = "Tap timeout: \"$th\"")
        }

        dialog.afterStep(step, 1600)
        tapped?.let { memory.save(StepType.TAP, th, it, saved.firstOrNull()) }
        return StepOutcome(true, chosen = tapped)
    }

    private fun tryTapByLocator(loc: Locator, section: String?): Boolean {
        return try {
            val by = xpaths.toBy(loc)
            var el = runCatching { ctx.driver.findElement(by) }.getOrNull() ?: return false
            var i = 0
            while (!ui.isFullyVisible(el) && i++ < 6) {
                agent.ui.Gestures.standardScrollUp(ctx.driver)
                ctx.resolver.waitForStableUi()
                el = runCatching { ctx.driver.findElement(by) }.getOrNull() ?: return false
            }
            val clickEl = ui.firstClickable(el)
            val before = ctx.pageHash()
            clickEl.click()
            ctx.lastTapY = runCatching { clickEl.rect.let { it.y + it.height / 2 } }.getOrNull()
            ctx.setScope(vision.determineScopeByY(ctx.lastTapY, vision.fast(section)) ?: ctx.activeScope)
            ui.changedSince(before, 1500L) || dialog.afterStepSilent(1200)
        } catch (_: Throwable) {
            false
        }
    }

    private fun tryTapCandidate(c: UICandidate, before: Int, v: agent.vision.VisionResult?, nextQuery: String?): Boolean {
        val loc = xpaths.locatorOf(c.xpath)
        val validated = xpaths.validate(loc)
        val by = AppiumBy.xpath((validated?.xpath ?: loc.value))
        val el = runCatching { ctx.driver.findElement(by) }.getOrNull() ?: return false
        el.click()
        ctx.lastTapY = runCatching { el.rect.let { it.y + it.height / 2 } }.getOrNull()
        ctx.setScope(vision.determineScopeByY(ctx.lastTapY, v) ?: ctx.activeScope)
        val dialogHandled = dialog.afterStepSilent(1600)
        val uiChanged = dialogHandled || ui.changedSince(before, 1800)
        if (!uiChanged) return false
        val okNext = if (nextQuery.isNullOrBlank()) true else runCatching { ctx.resolver.isPresentQuick(nextQuery, timeoutMs = 900) { } != null }.getOrDefault(false)
        if (!okNext) { runCatching { ctx.driver.navigate().back() }; return false }
        return true
    }

    private fun firstClickableByTokens(label: String): Pair<Locator, WebElement>? {
        val toks = label.lowercase().replace("&", " ").split(Regex("\\s+")).filter { it.length >= 3 && it !in stopWords }
        if (toks.isEmpty()) return null
        val cond = toks.joinToString(" and ") {
            "(contains(translate(@text,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),${xpathLiteral(it)}) or contains(translate(@content-desc,'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),${xpathLiteral(it)}))"
        }
        val xpClick = "//*[$cond][@clickable='true']"
        val clicks = runCatching { ctx.driver.findElements(AppiumBy.xpath(xpClick)) }.getOrNull().orEmpty()
        val visibleClick = clicks.firstOrNull { ui.isFullyVisible(it) } ?: clicks.firstOrNull()
        if (visibleClick != null) return xpaths.locatorOf(buildLocatorForElement(visibleClick)) to visibleClick
        val xpAny = "//*[$cond]"
        val any = runCatching { ctx.driver.findElements(AppiumBy.xpath(xpAny)) }.getOrNull().orEmpty()
        val hit = any.firstOrNull() ?: return null
        val xpAnc = "((//*[@resource-id=${xpathLiteral(hit.getAttribute("resource-id"))} or @content-desc=${xpathLiteral(hit.getAttribute("content-desc"))} or normalize-space(@text)=${xpathLiteral(hit.getAttribute("text"))}])/ancestor-or-self::*[@clickable='true'])[1]"
        val anc = runCatching { ctx.driver.findElements(AppiumBy.xpath(xpAnc)) }.getOrNull().orEmpty().firstOrNull() ?: return null
        return xpaths.locatorOf(buildLocatorForElement(anc)) to anc
    }

    private fun buildLocatorForElement(el: WebElement): String {
        val rid = ui.attr(el, "resource-id")
        if (rid.isNotBlank()) return "//*[@resource-id=${xpathLiteral(rid)}]"
        val txt = (runCatching { el.text }.getOrNull() ?: ui.attr(el, "text")).trim()
        if (txt.isNotEmpty()) return "//*[normalize-space(@text)=${xpathLiteral(txt)}]"
        return "(.//*[@clickable='true'])[1]"
    }

    private fun nextQueryFor(step: PlanStep): String? {
        val i = ctx.plan.steps.indexOfFirst { it.index == step.index }
        val next = if (i >= 0) ctx.plan.steps.getOrNull(i + 1) else null
        return next?.targetHint ?: next?.value
    }
}
