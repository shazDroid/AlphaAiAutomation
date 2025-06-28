package adb

import model.UiElement
import org.w3c.dom.Document
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import javax.xml.parsers.DocumentBuilderFactory

object UiDumpParser {

    fun getUiDumpXml(deviceId: String): String {
        AdbExecutor.runCommand("adb -s $deviceId shell uiautomator dump /sdcard/uidump.xml")
        AdbExecutor.runCommand("adb -s $deviceId pull /sdcard/uidump.xml ./uidump.xml")
        val xmlContent = File("./uidump.xml").readText()
        File("./uidump.xml").delete()
        return xmlContent
    }




    fun cleanUiDumpXml(rawXml: String): String {
        val endIndex = rawXml.indexOf("</hierarchy>") + "</hierarchy>".length
        return if (endIndex > 0) rawXml.substring(0, endIndex) else rawXml
    }

    fun runCommand(command: String, deviceId: String? = null): String {
        val finalCommand = if (deviceId != null) {
            "adb -s $deviceId $command"
        } else {
            command
        }

        val process = ProcessBuilder(*finalCommand.split(" ").toTypedArray())
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
        process.waitFor()
        return output
    }



    fun parseUiDump(xml: String): List<UiElement> {
        val elements = mutableListOf<UiElement>()
        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc: Document = dBuilder.parse(ByteArrayInputStream(xml.toByteArray()))
        val nodeList = doc.getElementsByTagName("node")

        for (i in 0 until nodeList.length) {
            val node = nodeList.item(i)
            val attrs = node.attributes
            val resourceId = attrs.getNamedItem("resource-id")?.nodeValue ?: ""
            val text = attrs.getNamedItem("text")?.nodeValue ?: ""
            val clazz = attrs.getNamedItem("class")?.nodeValue ?: ""
            elements.add(UiElement(resourceId, text, clazz,""))
        }

        return elements
    }
}

