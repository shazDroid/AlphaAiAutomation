package agent.vision

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

object ImageScaler {
    /**
     * Downscale so the SHORT side equals [maxShortSide], keep aspect ratio.
     * If the image is already smaller, returns original bytes.
     *
     * @param format  "jpg" or "png"
     * @param quality Only used for jpg (0.0..1.0)
     */
    fun downscaleToShortSide(
        imageBytes: ByteArray,
        maxShortSide: Int,
        format: String = "jpg",
        quality: Float = 0.85f
    ): ByteArray {
        val src = ImageIO.read(ByteArrayInputStream(imageBytes)) ?: return imageBytes
        val short = minOf(src.width, src.height)
        if (short <= maxShortSide) return encode(src, format, quality)

        val scale = maxShortSide.toDouble() / short.toDouble()
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)

        val outImg = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = outImg.createGraphics()
        g.drawImage(src.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
        g.dispose()
        return encode(outImg, format, quality)
    }

    private fun encode(img: BufferedImage, format: String, quality: Float): ByteArray {
        val baos = ByteArrayOutputStream()
        // Keeping it simple with JDK encoders (no custom quality control for PNG)
        ImageIO.write(img, format.lowercase(), baos)
        return baos.toByteArray()
    }
}
