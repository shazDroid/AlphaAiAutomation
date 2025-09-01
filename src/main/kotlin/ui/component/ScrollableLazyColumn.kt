package ui.component

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Scrollable list with a desktop scrollbar.
 */
@Composable
fun <T> ScrollableLazyColumn(
    items: List<T>,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    state: LazyListState = rememberLazyListState(),
    itemContent: @Composable (T) -> Unit
) {
    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 12.dp)
        ) {
            if (key != null) {
                items(items, key = key) { itemContent(it) }
            } else {
                items(items) { itemContent(it) }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
        )
    }
}

/**
 * Scrollable list builder variant.
 */
@Composable
fun ScrollableLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 12.dp),
            content = content
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
        )
    }
}
