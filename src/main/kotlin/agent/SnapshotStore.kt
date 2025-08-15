package agent

import io.appium.java_client.android.AndroidDriver
import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot
import java.io.File

data class Snapshot(
    val stepIndex: Int,
    val action: StepType,
    val targetHint: String?,
    val resolvedLocator: Locator?,
    val pageSourcePath: String,
    val screenshotPath: String,
    val success: Boolean,
    val notes: String? = null
)

class SnapshotStore(
    private val driver: AndroidDriver,
    private val dir: File
) {
    init { dir.mkdirs() }

    fun capture(stepIndex: Int, action: StepType, hint: String?, locator: Locator?, success: Boolean, notes: String?): Snapshot {
        val xml = driver.pageSource
        val xmlPath = File(dir, "step_${stepIndex}.xml").apply { writeText(xml) }.absolutePath
        val bytes = (driver as TakesScreenshot).getScreenshotAs(OutputType.BYTES)
        val pngPath = File(dir, "step_${stepIndex}.png").apply { writeBytes(bytes) }.absolutePath
        return Snapshot(stepIndex, action, hint, locator, xmlPath, pngPath, success, notes)
    }
}
