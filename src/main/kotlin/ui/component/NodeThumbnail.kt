package ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Paths
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun NodeThumbnail(path: String, heightDp: Int = 88) {
    val bmp = remember(path) {
        runCatching {
            val bytes = Files.readAllBytes(Paths.get(path))
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .background(Color(0xFFF2F4F8), RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .background(Color(0xFFF2F4F8), RoundedCornerShape(8.dp))
        )
    }
}
