// ui/component/MemoryTicker.kt
package ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest

// post from AgentRunner (or anywhere): MemoryBus.saved("message")
object MemoryBus {
    val events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    fun saved(msg: String) = events.tryEmit(msg)
}

@Composable
fun MemoryTicker(modifier: Modifier = Modifier) {
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        MemoryBus.events.collectLatest { msg ->
            message = msg
            delay(2200)
            message = null
        }
    }

    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
        modifier = modifier
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(42.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                backgroundColor = Color(0xffe4ffe4),
                border = BorderStroke(1.dp, color = Color(0xffaddaad))
            ) {
                Text(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(all = 8.dp),
                    text = message ?: "",
                    color = Color.Black,
                    style = MaterialTheme.typography.subtitle1
                )
            }
        }
    }
}

@Preview
@Composable
fun previewMemoryTicker() {
    MemoryTicker(modifier = Modifier.fillMaxSize())
}
