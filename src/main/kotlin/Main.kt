import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ui.AppUI

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Alpha Ui Automation") {
        AppUI()
    }
}
