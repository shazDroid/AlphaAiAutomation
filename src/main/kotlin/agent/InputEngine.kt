// agent/InputEngine.kt
package agent

import io.appium.java_client.android.AndroidDriver
import io.appium.java_client.android.nativekey.AndroidKey
import io.appium.java_client.android.nativekey.KeyEvent
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.remote.RemoteWebElement

object InputEngine {

    fun type(
        driver: AndroidDriver,
        resolver: LocatorResolver,
        targetHint: String,
        value: String,
        log: (String) -> Unit
    ) {
        log("INPUT → \"$targetHint\" = \"$value\"")

        // Resolve an actual editable
        val loc = resolver.resolveForInputAdvanced(targetHint, log)
        val el = driver.findElement(loc.toBy())

        // Focus
        runCatching { el.click() }.onFailure { log("  click() failed: ${it.message}") }
        Thread.sleep(120)

        // Try to clear
        runCatching { el.clear() }.onFailure { log("  clear() failed (non-fatal): ${it.message}") }

        // Primary sendKeys
        runCatching {
            log("  try sendKeys")
            el.sendKeys(value)
            log("  ✓ sendKeys OK")
            return
        }.onFailure { log("  sendKeys failed: ${it.message}") }

        // Fallback: clipboard + AndroidKey.PASTE (NO adb_shell needed)
        runCatching {
            log("  try clipboard + AndroidKey.PASTE")
            driver.setClipboardText(value)
            driver.pressKey(KeyEvent(AndroidKey.PASTE))
            log("  ✓ paste OK")
            return
        }.onFailure { log("  paste failed: ${it.message}") }

        // Optional (some drivers support this):
        runCatching {
            log("  try mobile:setText")
            val id = (el as RemoteWebElement).id
            (driver as JavascriptExecutor).executeScript(
                "mobile: setText",
                mapOf("elementId" to id, "text" to value, "replace" to true)
            )
            log("  ✓ mobile:setText OK")
            return
        }.onFailure { log("  mobile:setText not supported: ${it.message}") }

        // Last resort (requires enabling adb_shell on the server; see below)
        runCatching {
            log("  try adb input text (requires --allow-insecure=adb_shell)")
            val escaped = escapeForAdb(value)
            (driver as JavascriptExecutor).executeScript(
                "mobile: shell",
                mapOf("command" to "input", "args" to listOf("text", escaped))
            )
            log("  ✓ adb input OK")
            return
        }.onFailure { log("  adb input failed: ${it.message}") }

        error("Cannot set text for \"$targetHint\". Custom view may block programmatic input.")
    }

    private fun escapeForAdb(text: String): String =
        text.replace(" ", "%s")
            .replace("&", "\\&").replace("(", "\\(").replace(")", "\\)")
            .replace("|", "\\|").replace("<", "\\<").replace(">", "\\>")
            .replace(";", "\\;").replace("\"", "\\\"").replace("'", "\\'")
            .replace("@", "\\@").replace("$", "\\$")
}
