package model.plan

import java.io.File

private val stepRe = Regex("""step_(\d+)\.(png|jpg|jpeg|webp)""", RegexOption.IGNORE_CASE)

fun runsRoot(): File = System.getProperty("alpha.runs.dir")?.let { File(it) } ?: File("runs")

fun latestRunDir(): File? =
    runsRoot().takeIf { it.exists() }?.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.lastModified() }

fun collectStepScreensFromRun(runDir: File): Map<Int, String> =
    runDir.listFiles()
        ?.filter { it.isFile && stepRe.matches(it.name) }
        ?.mapNotNull { f ->
            val idx = stepRe.find(f.name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            idx to f.absolutePath
        }?.toMap() ?: emptyMap()
