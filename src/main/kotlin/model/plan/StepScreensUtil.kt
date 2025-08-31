package model.plan

import java.io.File

typealias StepScreens = Map<Int, String>

fun stepScreensFromResults(results: List<Pair<Int, File?>>): StepScreens =
    results.mapNotNull { (idx, f) -> f?.absolutePath?.let { idx to it } }.toMap()

fun stepScreensFromDir(dir: File): StepScreens =
    if (!dir.exists()) emptyMap()
    else dir.listFiles()?.mapNotNull { f ->
        val m = Regex("""(\d+)""").find(f.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (m != null) m to f.absolutePath else null
    }?.toMap() ?: emptyMap()
