// agent/InputEngine.kt
package agent

import io.appium.java_client.AppiumBy
import io.appium.java_client.android.AndroidDriver
import io.appium.java_client.android.nativekey.AndroidKey
import io.appium.java_client.android.nativekey.KeyEvent
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.interactions.Pause
import org.openqa.selenium.interactions.PointerInput
import org.openqa.selenium.interactions.PointerInput.Origin
import org.openqa.selenium.interactions.Sequence
import org.openqa.selenium.remote.RemoteWebElement
import java.time.Duration

object InputEngine {

    fun type(
        driver: AndroidDriver,
        resolver: LocatorResolver,
        targetHint: String,
        value: String,
        log: (String) -> Unit
    ): Locator {
        val redacted = if (targetHint.contains("pass", ignoreCase = true)) mask(value) else value
        log("INPUT → \"$targetHint\" = \"$redacted\"")

        resolver.waitForStableUi()

        val loc = resolver.resolveForInputAdvanced(targetHint) { msg -> log("  $msg") }
        var el = driver.findElement(loc.toBy())

        // Focus
        runCatching { el.click() }.onFailure { log("  click() failed: ${it.message}") }
        Thread.sleep(120)

        val focused = runCatching {
            io.appium.java_client.AppiumBy.androidUIAutomator("new UiSelector().focused(true)")
        }.mapCatching { by -> driver.findElement(by) }
            .onSuccess { f ->
                log("  using focused node: class=${f.getAttribute("className")}, id=${f.getAttribute("resource-id")}")
            }.getOrNull()

        if (focused != null) el = focused
        else {
            val desc = runCatching {
                el.findElement(
                    AppiumBy.xpath(
                        ".//*[contains(@class,'EditText') or contains(@class,'TextInputEditText') or contains(@class,'AutoCompleteTextView')]"
                    )
                )
            }.onSuccess { d -> log("  using descendant editable: class=${d.getAttribute("className")}") }
                .getOrNull()
            if (desc != null) el = desc
        }

        runCatching { el.clear() }.onFailure { log("  clear() failed (non-fatal): ${it.message}") }

        // Primary sendKeys
        runCatching {
            log("  try sendKeys")
            el.sendKeys(value)
            log("  ✓ sendKeys OK")
            driver.hideKeyboard()
            return loc
        }.onFailure { log("  sendKeys failed: ${it.message}") }

        runCatching {
            log("  try clipboard + AndroidKey.PASTE")
            driver.setClipboardText(value)
            driver.pressKey(KeyEvent(AndroidKey.PASTE))
            log("  ✓ paste OK")
            driver.hideKeyboard()
            return loc
        }.onFailure { log("  paste failed: ${it.message}") }

        runCatching {
            log("  try mobile:setText")
            val id = (el as RemoteWebElement).id
            (driver as JavascriptExecutor).executeScript(
                "mobile: setText",
                mapOf("elementId" to id, "text" to value, "replace" to true)
            )
            log("  ✓ mobile:setText OK")
            driver.hideKeyboard()
            return loc
        }.onFailure { log("  mobile:setText not supported: ${it.message}") }

        runCatching {
            log("  try adb input text")
            val escaped = escapeForAdb(value)
            (driver as JavascriptExecutor).executeScript(
                "mobile: shell",
                mapOf("command" to "input", "args" to listOf("text", escaped))
            )
            log("  ✓ adb input OK")
            driver.hideKeyboard()
            return loc
        }.onFailure { log("  adb input failed: ${it.message}") }

        error("Cannot set text for \"$targetHint\". Custom view may block programmatic input.")
    }

    fun slideRightByHint(
        driver: AndroidDriver,
        resolver: LocatorResolver,
        targetHint: String,
        log: (String) -> Unit
    ) {
        log("SLIDE → \"$targetHint\" (left → right)")

        val loc = resolver.resolve(targetHint, log)
        val el = driver.findElement(loc.toBy())
        val r = el.rect
        val startX = (r.x + r.width * 0.12).toInt()
        val endX   = (r.x + r.width * 0.88).toInt()
        val midY   = (r.y + r.height * 0.5).toInt()

        fun performDrag(x1: Int, y1: Int, x2: Int, y2: Int, holdMs: Long = 150L, moveMs: Long = 700L) {
            val finger = PointerInput(PointerInput.Kind.TOUCH, "finger")
            val seq = Sequence(finger, 0)
            seq.addAction(finger.createPointerMove(Duration.ZERO, Origin.viewport(), x1, y1))
            seq.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()))
            seq.addAction(Pause(finger, Duration.ofMillis(holdMs)))
            seq.addAction(finger.createPointerMove(Duration.ofMillis(moveMs), Origin.viewport(), x2, y2))
            seq.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()))
            driver.perform(listOf(seq))
        }

        runCatching {
            log("  drag #1 ($startX,$midY) → ($endX,$midY)")
            performDrag(startX, midY, endX, midY)
            return
        }.onFailure { log("  drag #1 failed: ${it.message}") }

        runCatching {
            val y2 = (midY + r.height * 0.12).toInt()
            log("  drag #2 ($startX,$y2) → ($endX,$y2)")
            performDrag(startX, y2, endX, y2)
            return
        }.onFailure { log("  drag #2 failed: ${it.message}") }

        runCatching {
            val y3 = (midY - r.height * 0.12).toInt()
            log("  drag #3 ($startX,$y3) → ($endX,$y3)")
            performDrag(startX, y3, endX, y3)
            return
        }.onFailure { log("  drag #3 failed: ${it.message}") }

        error("Slide failed for \"$targetHint\"")
    }

    private fun escapeForAdb(text: String): String =
        text.replace(" ", "%s")
            .replace("&", "\\&").replace("(", "\\(").replace(")", "\\)")
            .replace("|", "\\|").replace("<", "\\<").replace(">", "\\>")
            .replace(";", "\\;").replace("\"", "\\\"").replace("'", "\\'")
            .replace("@", "\\@").replace("$", "\\$")

    private fun mask(s: String): String =
        if (s.length <= 2) "***" else s.first() + "*".repeat(s.length - 2) + s.last()
}
