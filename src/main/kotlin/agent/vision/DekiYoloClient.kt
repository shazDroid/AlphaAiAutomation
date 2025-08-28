package agent.vision

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

class DekiYoloClient(
    private val python: String = "python",
    private val scriptPath: String = "BackEnd/deki_cli.py"
) {
    init {
        println("yolo:init python=$python script=$scriptPath script_exists=${File(scriptPath).exists()}")
    }

    fun analyzePng(png: ByteArray): VisionResult? {
        println("yolo:analyze:start")
        val workDir = Files.createTempDirectory("deki_local_${UUID.randomUUID()}").toFile()
        val inPng = File(workDir, "in.png")
        inPng.writeBytes(png)

        val pb = ProcessBuilder(listOf(python, scriptPath, "--in", inPng.absolutePath, "--out", workDir.absolutePath))
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val code = p.waitFor()
        println("yolo:analyze:exit=$code bytes_out=${out.length}")

        // Clean up temporary files
        runCatching { workDir.deleteRecursively() }

        if (code != 0 || out.isBlank()) {
            println("yolo:analyze:failed with exit code $code. Output: $out")
            return null
        }

        val res = parse(out)
        println("yolo:analyze:parsed elements=${res?.elements?.size ?: 0}")
        return res
    }

    private fun parse(json: String): VisionResult? {
        // Your existing JSON parsing logic is robust and well-designed.
        // It correctly handles different key names, so no changes are needed here.
        val o = JSONObject(json)
        val iw = intPick(o, "imageW", "image_width", "width") ?: 0
        val ih = intPick(o, "imageH", "image_height", "height") ?: 0
        val arr = arrPick(o, "elements", "nodes", "detections") ?: JSONArray()
        val list = mutableListOf<VisionElement>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val id = strPick(it, "id", "name", "label") ?: "el_$i"
            val x = intPick(it, "x", "left") ?: 0
            val y = intPick(it, "y", "top") ?: 0
            val w = intPick(it, "w", "width") ?: 0
            val h = intPick(it, "h", "height") ?: 0
            val text = strPick(it, "text", "string", "value")
            val score = dblPick(it, "score", "confidence")
            val type = strPick(it, "type", "kind", "cls")
            list += VisionElement(id = id, type = type, text = text, x = x, y = y, w = w, h = h, score = score)
        }
        return VisionResult(iw, ih, list)
    }

    private fun strPick(o: JSONObject, vararg keys: String): String? {
        for (k in keys) if (o.has(k) && !o.isNull(k)) return o.optString(k, null)
        return null
    }

    private fun intPick(o: JSONObject, vararg keys: String): Int? {
        for (k in keys) if (o.has(k) && !o.isNull(k)) return runCatching { o.getInt(k) }.getOrNull()
        return null
    }

    private fun dblPick(o: JSONObject, vararg keys: String): Double? {
        for (k in keys) if (o.has(k) && !o.isNull(k)) return runCatching { o.getDouble(k) }.getOrNull()
        return null
    }

    private fun arrPick(o: JSONObject, vararg keys: String): JSONArray? {
        for (k in keys) if (o.has(k) && !o.isNull(k)) return o.optJSONArray(k)
        return null
    }
}