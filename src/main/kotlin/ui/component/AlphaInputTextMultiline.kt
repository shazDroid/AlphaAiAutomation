package ui.component

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.*

@Composable
fun AlphaInputTextMultiline(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    backgroundColor: Color = Color.White
) {
    var height by remember { mutableStateOf(100.dp) }
    var showPopup by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint) },
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp)),
            colors = TextFieldDefaults.textFieldColors(
                backgroundColor = backgroundColor,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            shape = RoundedCornerShape(16.dp),
            singleLine = false,
            maxLines = Int.MAX_VALUE
        )

        // Drag handle at bottom-right corner
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(16.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val newHeight = height + dragAmount.y.dp
                        height = newHeight.coerceAtLeast(56.dp)
                    }
                }
                .background(Color.Gray.copy(alpha = 0.5f))
        )

        // Open in new window button (top-right corner)
        IconButton(
            onClick = { showPopup = true },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                imageVector = Icons.Default.Create,
                contentDescription = "Open in new window",
                tint = Color.Gray
            )
        }
    }

    if (showPopup) {
        Dialog(onCloseRequest = { showPopup = false }) {
            Surface(
                modifier = Modifier.size(400.dp, 300.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.White
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Edit Text", style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxSize(),
                        singleLine = false,
                        maxLines = Int.MAX_VALUE
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun PreviewAlphaInputTextMultiline() {
    var text by remember { mutableStateOf("") }

    AlphaInputTextMultiline(
        value = text,
        onValueChange = { text = it },
        hint = "Enter multiline text here..."
    )
}
