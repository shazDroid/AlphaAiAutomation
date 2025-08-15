package adb

import java.io.BufferedReader
import java.io.InputStreamReader

object AdbExecutor {
    fun runCommand(command: String): String {
        val process = ProcessBuilder(*command.split(" ").toTypedArray())
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
        process.waitFor()
        return output
    }

    fun launchApp(packageName: String, deviceId: String? = null): String {
        return UiDumpParser.runCommand("shell monkey -p $packageName -c android.intent.category.LAUNCHER 1", deviceId)
    }

    fun listDevices(): List<String> {
        val output = UiDumpParser.runCommand("adb devices")
        return output
            .split("\n")
            .drop(1) // remove header line
            .filter { it.contains("\tdevice") }
            .map { it.split("\t")[0] }
    }

    fun screencapPng(udid: String): ByteArray? = try {
        val proc = ProcessBuilder("adb", "-s", udid, "exec-out", "screencap", "-p")
            .redirectErrorStream(true).start()
        val bytes = proc.inputStream.readBytes()
        proc.waitFor()
        if (bytes.isNotEmpty()) bytes else null
    } catch (e: Exception) { null }

    fun isPackageInstalled(udid: String, pkg: String): Boolean = try {
        val proc = ProcessBuilder("adb", "-s", udid, "shell", "pm", "list", "packages", pkg)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        out.contains("package:$pkg")
    } catch (_: Exception) { false }

}
