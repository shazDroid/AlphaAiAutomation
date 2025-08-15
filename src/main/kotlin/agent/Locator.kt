package agent

import io.appium.java_client.AppiumBy
import org.openqa.selenium.By

enum class Strategy { ID, TEXT, DESC, UIAUTOMATOR, XPATH, OCR }

data class Locator(
    val strategy: Strategy,
    val value: String,
    val alternatives: List<Pair<Strategy,String>> = emptyList()
) {
    fun toBy(): By = when(strategy) {
        Strategy.ID -> AppiumBy.id(value)
        Strategy.DESC -> AppiumBy.accessibilityId(value)
        Strategy.UIAUTOMATOR -> AppiumBy.androidUIAutomator(value)
        Strategy.XPATH, Strategy.TEXT, Strategy.OCR -> AppiumBy.xpath(value)
    }
}
