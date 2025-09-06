package util

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object RunLog {
    private val fmtTs = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val fmtFile = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun start(flowId: String): (String) -> Unit {
        val base = File(System.getProperty("user.dir")).resolve("FrontEnd/AI Automation/runs")
        base.mkdirs()
        val f = base.resolve("${fmtFile.format(LocalDateTime.now())}_$flowId.log")
        return { msg -> f.appendText("[${fmtTs.format(LocalDateTime.now())}] $msg\n") }
    }
}
