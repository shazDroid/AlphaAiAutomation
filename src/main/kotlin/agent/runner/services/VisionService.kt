package agent.runner.services

import agent.runner.RunContext
import agent.vision.VisionResult
import javax.imageio.ImageIO

/**
 * Thin wrapper around the vision client with scope-aware caching.
 */
class VisionService(private val ctx: RunContext) {
    fun fast(scope: String?): VisionResult? {
        val client = ctx.deki ?: return null
        val hash = ctx.pageHash()
        if (ctx.lastVision != null && ctx.lastVisionHash == hash && ctx.lastVisionScope == scope) return ctx.lastVision
        val shot = screenshot()
        val cropped = cropForScope(shot, scope)
        val res = runCatching {
            client.analyzePngWithLogs(
                png = cropped,
                log = { },
                maxW = 480,
                imgsz = 320,
                conf = 0.25f,
                ocr = false
            )
        }.getOrNull()
        ctx.lastVision = res
        ctx.lastVisionHash = hash
        ctx.lastVisionScope = scope
        return res
    }

    fun slowForText(scope: String?): VisionResult? {
        val client = ctx.deki ?: return null
        val shot = screenshot()
        val cropped = cropForScope(shot, scope)
        return runCatching {
            client.analyzePngWithLogs(
                png = cropped,
                log = { },
                maxW = 640,
                imgsz = 416,
                conf = 0.25f,
                ocr = true
            )
        }.getOrNull()
    }

    fun determineScopeByY(y: Int?, v: VisionResult?): String? {
        if (y == null) return null
        fun yOfVision(token: String): Int? = v?.elements?.firstOrNull { it.text?.equals(token, true) == true }?.y
        val fromY = yOfVision("from") ?: runCatching {
            ctx.driver.findElements(io.appium.java_client.AppiumBy.xpath("//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='from']"))
                .firstOrNull()?.rect?.y
        }.getOrNull()
        val toY = yOfVision("to") ?: runCatching {
            ctx.driver.findElements(io.appium.java_client.AppiumBy.xpath("//*[translate(normalize-space(@text),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')='to']"))
                .firstOrNull()?.rect?.y
        }.getOrNull()
        return when {
            fromY != null && toY != null -> if (y < toY) "from" else "to"
            fromY != null -> if (y >= fromY) "from" else null
            toY != null -> if (y >= toY) "to" else null
            else -> null
        }
    }

    private fun screenshot(): ByteArray = ctx.driver.getScreenshotAs(org.openqa.selenium.OutputType.BYTES)

    private fun cropForScope(png: ByteArray, scope: String?): ByteArray {
        if (scope == null) return png
        return runCatching {
            val img = ImageIO.read(java.io.ByteArrayInputStream(png))
            val h = img.height
            val w = img.width
            val (y0, y1) = when (scope.lowercase()) {
                "from" -> 0 to (h * 0.55).toInt()
                "to" -> (h * 0.45).toInt() to h
                else -> 0 to h
            }
            val sub = img.getSubimage(0, y0.coerceAtLeast(0), w, (y1 - y0).coerceAtLeast(1))
            val baos = java.io.ByteArrayOutputStream()
            ImageIO.write(sub, "png", baos)
            baos.toByteArray()
        }.getOrElse { png }
    }
}
