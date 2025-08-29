package agent.vision

import org.json.JSONArray
import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO

class DekiYoloClient(
    private val python: String = System.getenv("DEKI_PYTHON") ?: "python",
    private val scriptPath: String = System.getenv("DEKI_CLI") ?: "BackEnd/deki_cli.py",
    private val weightsPath: String? = System.getenv("DEKI_WEIGHTS"),
    private val defaultImgWidth: Int = (System.getenv("DEKI_IMG_WIDTH")?.toIntOrNull() ?: 1280),
    private val defaultImgSz: Int = (System.getenv("DEKI_NET_IMGSZ")?.toIntOrNull() ?: 640),
    private val defaultConf: Float = (System.getenv("DEKI_CONF")?.toFloatOrNull() ?: 0.30f),
    private val defaultOCR: Boolean = (System.getenv("DEKI_OCR")?.toIntOrNull() ?: 1) != 0,
    private val serverUrl: String? = System.getenv("DEKI_SERVER") // e.g. http://127.0.0.1:8765
) {

    private fun toScaledJpeg(png: ByteArray, maxW: Int): ByteArray {
        val img = ImageIO.read(ByteArrayInputStream(png))
        val scale = if (img.width > maxW) maxW.toDouble() / img.width else 1.0
        val w = (img.width * scale).toInt().coerceAtLeast(1)
        val h = (img.height * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().use { g ->
            g.drawImage(img.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
        }
        val baos = ByteArrayOutputStream()
        ImageIO.write(out, "jpg", baos)
        return baos.toByteArray()
    }

    fun analyzePng(png: ByteArray): VisionResult? = analyzePngWithLogs(png, log = { })

    fun analyzePngWithLogs(
        png: ByteArray,
        log: (String) -> Unit,
        maxW: Int = defaultImgWidth,
        imgsz: Int = defaultImgSz,
        conf: Float = defaultConf,
        ocr: Boolean = defaultOCR
    ): VisionResult? {
        val jpg = toScaledJpeg(png, maxW)
        runCatching {
            val meta = ImageIO.read(ByteArrayInputStream(jpg))
            log("yolo:input_scaled ${meta.width}x${meta.height}")
        }

        // Prefer warm server if configured
        serverUrl?.let { base ->
            return analyzeViaServer(base, jpg, imgsz, conf, ocr, log)
        }

        // Fallback: spawn CLI once
        val workDir = Files.createTempDirectory("deki_local_${UUID.randomUUID()}").toFile()
        val inImg = File(workDir, "in.jpg").apply { writeBytes(jpg) }

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
        if (scriptDir != null && scriptDir.exists()) pb.directory(scriptDir)

        log("yolo:cmd ${cmd.joinToString(" ")} (cwd=${pb.directory()?.absolutePath ?: "."})")
        val p = pb.start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val code = p.waitFor()
        if (code != 0) {
            log("yolo:fail exit=$code out=${out.take(600)}"); return null
        }
        log("yolo:ok stdout bytes=${out.length}")

        val jsonStdout = Regex("""(?s)\{.*\}\s*$""").find(out)?.value
        val jsonFile = File(workDir, "out.json")
        val json = when {
            jsonStdout != null -> jsonStdout
            jsonFile.exists() -> jsonFile.readText()
            else -> out
        }

        return parse(json).also {
            if (it == null) log("yolo:parse:null")
            else log("yolo:result image=${it.imageW}x${it.imageH} elements=${it.elements.size}")
        }
    }

    private fun analyzeViaServer(
        base: String,
        jpg: ByteArray,
        imgsz: Int,
        conf: Float,
        ocr: Boolean,
        log: (String) -> Unit
    ): VisionResult? {
        val boundary = "----Deki${System.nanoTime()}"
        val url = URL("$base/analyze?imgsz=$imgsz&conf=$conf&ocr=${if (ocr) 1 else 0}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 5000
            readTimeout = 15000
        }
        conn.outputStream.use { os ->
            os.write("--$boundary\r\n".toByteArray())
            os.write("Content-Disposition: form-data; name=\"image\"; filename=\"in.jpg\"\r\n".toByteArray())
            os.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
            os.write(jpg)
            os.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader(Charsets.UTF_8).readText()
        if (code !in 200..299) {
            log("yolo:http:$code ${body.take(200)}"); return null
        }
        log("yolo:http ok bytes=${body.length}")
        return parse(body)
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
