package agent.vision

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter

object ImageScaler {
    fun downscaleToShortSide(
        bytes: ByteArray,
        shortSide: Int,
        format: String = "jpg",
        quality: Float = 0.8f
    ): ByteArray {
        val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return bytes
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return bytes
        val currentShort = minOf(w, h)
        if (shortSide <= 0 || currentShort <= shortSide) return bytes
        val scale = shortSide.toDouble() / currentShort.toDouble()
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val dstType = if (format.equals("jpg", true) || format.equals(
                "jpeg",
                true
            )
        ) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
        val dst = BufferedImage(nw, nh, dstType)
        val g2: Graphics2D = dst.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        if (dstType == BufferedImage.TYPE_INT_RGB) {
            g2.color = java.awt.Color.WHITE
            g2.fillRect(0, 0, nw, nh)
        }
        g2.drawImage(src, 0, 0, nw, nh, null)
        g2.dispose()
        val bos = ByteArrayOutputStream()
        if (format.equals("jpg", true) || format.equals("jpeg", true)) {
            val writer: ImageWriter = ImageIO.getImageWritersByFormatName("jpg").next()
            val ios = ImageIO.createImageOutputStream(bos)
            writer.output = ios
            val param = writer.defaultWriteParam
            if (param.canWriteCompressed()) {
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = quality.coerceIn(0.05f, 1.0f)
            }
            writer.write(null, IIOImage(dst, null, null), param)
            ios.close()
            writer.dispose()
        } else {
            ImageIO.write(dst, format, bos)
        }
        return bos.toByteArray()
    }
}
