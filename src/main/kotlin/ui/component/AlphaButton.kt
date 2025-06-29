package ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import ui.theme.BLUE

@Preview
@Composable
fun AlphaButton(
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(BLUE),
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = null,
        border = BorderStroke(0.dp, Color.Transparent)
    ) {
        Row() {

            Text(
                modifier = Modifier
                    .then(
                        if (isLoading) Modifier.weight(1f) else Modifier
                    ),
                text = text,
                color = Color.White,
                fontSize = TextUnit(12f, TextUnitType.Sp),
                fontWeight = MaterialTheme.typography.h6.fontWeight
            )
            AnimatedVisibility(isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
            }
        }
    }
}