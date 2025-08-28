package agent.vision

import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO
import java.awt.Image as AwtImage

class DekiYoloClient(
    private val python: String = System.getenv("DEKI_PYTHON") ?: "python",
    private val scriptPath: String = System.getenv("DEKI_CLI") ?: "BackEnd/deki_cli.py",
    private val weightsPath: String? = System.getenv("DEKI_WEIGHTS"),
    private val defaultImgWidth: Int = (System.getenv("DEKI_IMG_WIDTH")?.toIntOrNull() ?: 1280),
    private val defaultImgSz: Int = (System.getenv("DEKI_NET_IMGSZ")?.toIntOrNull() ?: 640),
    private val defaultConf: Float = (System.getenv("DEKI_CONF")?.toFloatOrNull() ?: 0.30f),
    private val defaultOCR: Boolean = (System.getenv("DEKI_OCR")?.toIntOrNull() ?: 1) != 0
) {
    /** Simple downscale-to-JPEG to speed up Python startup/io */
    private fun toScaledJpeg(png: ByteArray, maxW: Int, quality: Float = 0.82f): ByteArray {
        val img = ImageIO.read(ByteArrayInputStream(png))
        val scale = if (img.width > maxW) maxW.toDouble() / img.width else 1.0
        val w = (img.width * scale).toInt().coerceAtLeast(1)
        val h = (img.height * scale).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.drawImage(img.getScaledInstance(w, h, AwtImage.SCALE_SMOOTH), 0, 0, null)
        g.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(scaled, "jpg", baos) // keep it simple; JDK JPEG encoder
        return baos.toByteArray()
    }

    fun analyzePngWithLogs(
        png: ByteArray,
        log: (String) -> Unit,
        maxW: Int = defaultImgWidth,
        imgsz: Int = defaultImgSz,
        conf: Float = defaultConf,
        ocr: Boolean = defaultOCR
    ): VisionResult? {
        // Prepare temp IO
        val workDir = Files.createTempDirectory("deki_local_${UUID.randomUUID()}").toFile()
        val inImg = File(workDir, "in.jpg").apply { writeBytes(toScaledJpeg(png, maxW)) }

        // Build command
        val cmd = mutableListOf(
            python, scriptPath,
            "--in", inImg.absolutePath,
            "--out", workDir.absolutePath,
            "--json-mini",
            "--imgsz", imgsz.toString(),
            "--conf", "%.2f".format(conf)
        )
        if (ocr) cmd += "--ocr"
        weightsPath?.let { cmd += listOf("--weights", it) }

        val scriptDir = File(scriptPath).parentFile
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        if (scriptDir != null && scriptDir.exists()) {
            pb.directory(scriptDir) // so relative weights next to script also work
        }

        log("yolo:cmd ${cmd.joinToString(" ")} (cwd=${pb.directory()?.absolutePath ?: "."})")
        val p = pb.start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val code = p.waitFor()
        if (code != 0) {
            log("yolo:fail exit=$code out=${out.take(600)}")
            return null
        }
        log("yolo:ok stdout bytes=${out.length}")

        // Try to extract the last JSON object from stdout, else read out.json
        val jsonStdout = Regex("""(?s)\{.*\}\s*$""").find(out)?.value
        val jsonFile = File(workDir, "out.json")
        val json = when {
            jsonStdout != null -> jsonStdout
            jsonFile.exists() -> jsonFile.readText()
            else -> out // hope it's pure JSON
        }

        val res = runCatching { parse(json) }.getOrNull()
        if (res == null) log("yolo:parse:null")
        else log("yolo:result image=${res.imageW}x${res.imageH} elements=${res.elements.size}")
        return res
    }

    private fun parse(json: String): VisionResult? {
        val o = JSONObject(json)
        val iw = o.optInt("imageW", 0)
        val ih = o.optInt("imageH", 0)
        val arr = o.optJSONArray("elements") ?: JSONArray()
        val list = mutableListOf<VisionElement>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            list += VisionElement(
                id = it.optString("id", "el_$i"),
                x = it.optInt("x", 0),
                y = it.optInt("y", 0),
                w = it.optInt("w", 0),
                h = it.optInt("h", 0),
                text = it.optString("text", null),
                type = it.optString("type", null),
                score = it.optDouble("score", Double.NaN).let { s -> if (s.isNaN()) null else s }
            )
        }
        return VisionResult(iw, ih, list)
    }
}
