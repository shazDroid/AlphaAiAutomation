package ui.component

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*

private const val BLUE = 0xFF687EF5.toInt()

@Composable
fun AlphaTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    isLoadingTab: (Int) -> Boolean = { false }
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        tabs.forEachIndexed { index, title ->
            AlphaTabButton(
                modifier = Modifier.weight(1f),
                text = title,
                isSelected = selectedTabIndex == index,
                isLoading = isLoadingTab(index),
                onClick = { onTabSelected(index) }
            )
        }
    }
}

@Composable
fun AlphaTabButton(
    modifier: Modifier? = null,
    text: String,
    isSelected: Boolean,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier
            ?.padding(horizontal = 4.dp, vertical = 8.dp)
            ?.height(40.dp)!!,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (isSelected) Color(BLUE) else Color.LightGray,
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(8.dp),
        elevation = null,
        border = BorderStroke(0.dp, Color.Transparent)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = MaterialTheme.typography.h6.fontWeight
            )
            if (isLoading) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Preview
@Composable
fun PreviewAlphaTabRow() {
    var selectedTab by remember { mutableStateOf(0) }

    val tabs = listOf("Tab 1", "Tab 2", "Tab 3")
    val loadingTabs = setOf(1)

    AlphaTabRow(
        tabs = tabs,
        selectedTabIndex = selectedTab,
        onTabSelected = { selectedTab = it },
        isLoadingTab = { it in loadingTabs }
    )
}
