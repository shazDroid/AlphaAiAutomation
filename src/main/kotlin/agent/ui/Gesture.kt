package agent.ui

import io.appium.java_client.android.AndroidDriver
import io.appium.java_client.android.nativekey.AndroidKey
import io.appium.java_client.android.nativekey.KeyEvent
import org.openqa.selenium.Dimension
import org.openqa.selenium.interactions.PointerInput
import org.openqa.selenium.interactions.Sequence
import java.time.Duration
import kotlin.math.max

object Gestures {

    fun hideKeyboardIfOpen(driver: AndroidDriver, onLog: ((String) -> Unit)? = null) {
        runCatching { if (driver.isKeyboardShown) driver.hideKeyboard() }.onSuccess {
            onLog?.invoke("keyboard:hide")
            return
        }
        runCatching { driver.hideKeyboard() }.onSuccess {
            onLog?.invoke("keyboard:hide(fallback)")
            return
        }
        runCatching { driver.pressKey(KeyEvent(AndroidKey.BACK)) }.onSuccess {
            onLog?.invoke("keyboard:back")
        }
    }

    fun swipe(driver: AndroidDriver, sx: Int, sy: Int, ex: Int, ey: Int, durationMs: Long = 320L) {
        val finger = PointerInput(PointerInput.Kind.TOUCH, "finger")
        val seq = Sequence(finger, 1)
        seq.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), sx, sy))
        seq.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()))
        seq.addAction(finger.createPointerMove(Duration.ofMillis(durationMs), PointerInput.Origin.viewport(), ex, ey))
        seq.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()))
        driver.perform(listOf(seq))
    }

    fun viewport(driver: AndroidDriver): Dimension = driver.manage().window().size

    fun standardScrollUp(driver: AndroidDriver): Quad {
        val d = viewport(driver)
        val sx = d.width / 2
        val sy = (d.height * 0.78).toInt()
        val ex = sx
        val ey = max(80, (d.height * 0.28).toInt())
        swipe(driver, sx, sy, ex, ey)
        return Quad(sx, sy, ex, ey)
    }

    fun standardScrollDown(driver: AndroidDriver): Quad {
        val d = viewport(driver)
        val sx = d.width / 2
        val sy = (d.height * 0.30).toInt()
        val ex = sx
        val ey = (d.height * 0.80).toInt()
        swipe(driver, sx, sy, ex, ey)
        return Quad(sx, sy, ex, ey)
    }

    data class Quad(val sx: Int, val sy: Int, val ex: Int, val ey: Int)
}
