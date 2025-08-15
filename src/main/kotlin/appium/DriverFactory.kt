package appium

import io.appium.java_client.android.AndroidDriver
import io.appium.java_client.android.options.UiAutomator2Options
import util.AppiumHealth
import java.net.URL
import java.time.Duration

object DriverFactory {
    fun startAndroid(
        udid: String,
        appPackage: String,
        appActivity: String,
        serverUrl: String = "http://127.0.0.1:4723/"
    ): AndroidDriver {
        require(udid.isNotBlank()) { "UDID is empty" }
        require(appPackage.isNotBlank()) { "Package is empty" }
        require(appActivity.isNotBlank()) { "Activity is empty" }

        if (!AppiumHealth.isReachable(serverUrl)) {
            throw IllegalStateException("Appium server not reachable at $serverUrl. Start Appium with: appium --address 127.0.0.1 --port 4723")
        }

        val caps = UiAutomator2Options()
            .setUdid(udid)
            .setDeviceName(udid)
            .setAppPackage(appPackage)
            .setAppActivity(appActivity)
            .setNoReset(true)
            .setAutoGrantPermissions(true)
            .setDisableWindowAnimation(true)
            .setNewCommandTimeout(Duration.ofSeconds(120))

        return AndroidDriver(URL(serverUrl), caps)
    }
}
