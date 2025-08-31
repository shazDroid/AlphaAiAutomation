package ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

@Composable
fun NodeThumbnail(path: String) {
    val bmp: ImageBitmap? = remember(path) { loadThumb(path, 420) }
    if (bmp != null) {
        val ratio = bmp.width.toFloat() / bmp.height.toFloat()
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit
        )
    }
}

private fun loadThumb(path: String, maxW: Int): ImageBitmap? {
    val src = runCatching { ImageIO.read(File(path)) }.getOrNull() ?: return null
    val targetW = maxW.coerceAtMost(src.width)
    val targetH = ((src.height.toFloat() * targetW) / src.width.toFloat()).roundToInt().coerceAtLeast(1)
    val out = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(src, 0, 0, targetW, targetH, null)
    g.dispose()
    return out.toComposeImageBitmap()
}
