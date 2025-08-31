package model.plan

import java.io.File

private val exts = listOf("png", "jpg", "jpeg", "webp")

fun screensRoot(): File = File(System.getProperty("user.home"), ".alpha-ui-automation/screens")
fun planScreensDir(planId: String): File = File(screensRoot(), planId)
fun fileFromPlanDir(planId: String, index: Int): String {
    val dir = planScreensDir(planId)
    exts.forEach { ext ->
        val f = File(dir, "$index.$ext")
        if (f.exists()) return f.absolutePath
    }
    return ""
}
