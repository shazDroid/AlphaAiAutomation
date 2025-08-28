package agent.vision

import java.io.File
import java.util.concurrent.TimeUnit

object DekiFactory {
    fun auto(log: (String) -> Unit = {}): DekiYoloClient? {
        val script = listOf(
            "BackEnd/deki_cli.py",
            "./BackEnd/deki_cli.py",
            "../BackEnd/deki_cli.py"
        ).firstOrNull { File(it).exists() } ?: run { log("yolo:init script_missing"); return null }

        val python = detectPython() ?: run { log("yolo:init python_missing"); return null }
        log("yolo:init enabled:$python $script")
        return DekiYoloClient(python = python, scriptPath = File(script).absolutePath)
    }

    private fun detectPython(): String? {
        val candidates = listOf(
            "python",
            "python3",
            "C:\\\\Python312\\\\python.exe",
            "C:\\\\Users\\\\Public\\\\AppData\\\\Local\\\\Programs\\\\Python\\\\Python312\\\\python.exe"
        )
        return candidates.firstOrNull { okVersion(it) }
    }

    private fun okVersion(bin: String): Boolean {
        return try {
            val p = ProcessBuilder(listOf(bin, "--version")).redirectErrorStream(true).start()
            p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
    }
}
