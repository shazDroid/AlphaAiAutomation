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
}
