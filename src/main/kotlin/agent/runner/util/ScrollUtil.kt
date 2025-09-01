package agent.runner.util

import io.appium.java_client.AppiumBy
import io.appium.java_client.android.AndroidDriver
import org.openqa.selenium.Dimension
import org.openqa.selenium.WebElement
import org.openqa.selenium.interactions.PointerInput
import org.openqa.selenium.interactions.Sequence
import java.time.Duration
import java.util.*

object ScrollUtil {
    enum class Direction { UP, DOWN }

    fun ensureInView(driver: AndroidDriver, el: WebElement, maxSwipes: Int = 5) {
        var tries = 0
        while (!isInViewport(driver, el) && tries < maxSwipes) {
            val rect = el.rect
            val h = driver.manage().window().size.height
            val dir = if (rect.y < h * 0.15) Direction.DOWN else Direction.UP
            swipeInScrollableOrWindow(driver, dir, 320)
            tries++
        }
    }

    fun scrollTextIntoViewMonotonic(
        driver: AndroidDriver,
        text: String,
        direction: Direction = Direction.DOWN,
        maxSwipes: Int = 8
    ): Boolean {
        val okUi = runCatching {
            driver.findElement(
                AppiumBy.androidUIAutomator(
                    "new UiScrollable(new UiSelector().scrollable(true)).setAs${if (direction == Direction.DOWN) "Vertical" else "Vertical"}Scroll().scrollTextIntoView(\"$text\")"
                )
            ); true
        }.getOrNull() == true
        if (okUi) return true

        var swipes = 0
        var lastHash = driver.pageSource.hashCode()
        var stallCount = 0
        while (swipes < maxSwipes) {
            if (pageHas(driver, text)) return true
            swipeInScrollableOrWindow(driver, direction, 320)
            swipes++
            val now = driver.pageSource.hashCode()
            stallCount = if (now == lastHash) stallCount + 1 else 0
            lastHash = now
            if (stallCount >= 2) break
        }
        return pageHas(driver, text)
    }

    private fun pageHas(driver: AndroidDriver, text: String) =
        driver.pageSource.lowercase(Locale.ROOT).contains(text.lowercase(Locale.ROOT))

    private fun isInViewport(driver: AndroidDriver, el: WebElement): Boolean {
        val r = el.rect
        val size: Dimension = driver.manage().window().size
        return r.y >= 8 && (r.y + r.height) <= size.height - 8
    }

    private fun swipeInScrollableOrWindow(driver: AndroidDriver, dir: Direction, durationMs: Long) {
        val ups = dir == Direction.UP
        val scrollables = runCatching {
            driver.findElements(AppiumBy.xpath("//*[contains(@scrollable,'true') or @scrollable='true']"))
        }.getOrNull().orEmpty()
        if (scrollables.isNotEmpty()) {
            val el = scrollables.first()
            val r = el.rect
            val x = r.x + (r.width * 0.5).toInt()
            val startY = if (ups) r.y + (r.height * 0.25).toInt() else r.y + (r.height * 0.75).toInt()
            val endY = if (ups) r.y + (r.height * 0.75).toInt() else r.y + (r.height * 0.25).toInt()
            swipe(driver, x, startY, x, endY, durationMs); return
        }
        val size: Dimension = driver.manage().window().size
        val x = (size.width * 0.5).toInt()
        val startY = (size.height * if (ups) 0.20 else 0.80).toInt()
        val endY = (size.height * if (ups) 0.80 else 0.20).toInt()
        swipe(driver, x, startY, x, endY, durationMs)
    }

    private fun swipe(driver: AndroidDriver, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        val finger = PointerInput(PointerInput.Kind.TOUCH, "finger1")
        val seq = Sequence(finger, 1)
        seq.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), x1, y1))
        seq.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()))
        seq.addAction(finger.createPointerMove(Duration.ofMillis(durationMs), PointerInput.Origin.viewport(), x2, y2))
        seq.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()))
        driver.perform(listOf(seq))
    }
}
