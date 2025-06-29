import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import ui.AppUI

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Alpha Ui Automation", state = WindowState(width = 1400.dp, height = 900.dp)) {
        AppUI()
    }
}
