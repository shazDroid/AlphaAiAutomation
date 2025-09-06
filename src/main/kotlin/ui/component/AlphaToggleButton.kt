package ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A composable toggle switch that matches the provided UI theme.
 * It's designed to be reusable and customizable.
 *
 * @param checked The current state of the switch (on/off).
 * @param onCheckedChange A callback lambda that is invoked when the user clicks the switch.
 * @param modifier A [Modifier] for this composable.
 * @param onColor The color of the track when the switch is 'on'.
 * @param offColor The color of the track when the switch is 'off'.
 * @param thumbColor The color of the sliding thumb.
 * @param animationDuration The duration for the switching animation in milliseconds.
 */
@Composable
fun AlphaToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onColor: Color = Color(0xFF10B981), // A green matching Tailwind's emerald-500
    offColor: Color = Color(0xFFD1D5DB), // A gray matching Tailwind's gray-300
    thumbColor: Color = Color.White,
    animationDuration: Int = 300
) {
    val trackWidth = 56.dp
    val trackHeight = 32.dp
    val thumbSize = 24.dp
    val padding: Dp = (trackHeight - thumbSize) / 2

    // Animate the thumb's horizontal position
    val thumbPosition by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - padding else padding,
        animationSpec = tween(durationMillis = animationDuration)
    )

    // Animate the track's background color
    val trackColor by animateColorAsState(
        targetValue = if (checked) onColor else offColor,
        animationSpec = tween(durationMillis = animationDuration)
    )

    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(CircleShape)
            .background(trackColor)
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch
            )
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbPosition)
                .size(trackHeight) // Make the touch target for the thumb the full height
                .padding(padding)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}


// --- Preview Section ---
// This allows you to see the component in Android Studio's design view.
@Preview
@Composable
fun ToggleSwitchPreview() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF3F4F6) // A light gray background similar to the demo
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
            ) {
                // Example 1: Stateful toggle
                var isAutoRunOn by remember { mutableStateOf(true) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color.White)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Auto Run", style = MaterialTheme.typography.body1)
                    AlphaToggleButton(
                        checked = isAutoRunOn,
                        onCheckedChange = { isAutoRunOn = it }
                    )
                }

                // Example 2: Another stateful toggle
                var areNotificationsOn by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color.White)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Enable Notifications", style = MaterialTheme.typography.body1)
                    AlphaToggleButton(
                        checked = areNotificationsOn,
                        onCheckedChange = { areNotificationsOn = it }
                    )
                }
            }
        }
    }
}
