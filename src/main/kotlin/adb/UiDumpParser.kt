package adb

import model.UiElement
import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import javax.xml.parsers.DocumentBuilderFactory

object UiDumpParser {


    fun getUiDumpXml(deviceId: String): String {
        val userHome = System.getProperty("user.home")
        val dumpFile = File(userHome, "uidump.xml")

        // Dump UI to device
        AdbExecutor.runCommand("adb -s $deviceId shell uiautomator dump /sdcard/uidump.xml")

        // Pull dump to absolute path
        AdbExecutor.runCommand("adb -s $deviceId pull /sdcard/uidump.xml ${dumpFile.absolutePath}")

        // Read content
        val xmlContent = if (dumpFile.exists()) {
            dumpFile.readText()
        } else {
            throw RuntimeException("❌ Failed to pull UI dump from device")
        }

        // Delete pulled file
        dumpFile.delete()

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
            val bounds = attrs.getNamedItem("bounds")?.nodeValue ?: ""
            val index = attrs.getNamedItem("index")?.nodeValue ?: ""
            elements.add(UiElement(resourceId, text, clazz, bounds, index))
        }

        return elements
    }

    /**
     * Find EditText associated with a label TextView having given target text.
     */
    fun findEditTextForLabel(xml: String, targetText: String): UiElement? {
        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc: Document = dBuilder.parse(ByteArrayInputStream(xml.toByteArray()))
        val nodeList = doc.getElementsByTagName("node")

        for (i in 0 until nodeList.length) {
            val node = nodeList.item(i)
            val attrs = node.attributes
            val clazz = attrs.getNamedItem("class")?.nodeValue ?: ""
            val text = attrs.getNamedItem("text")?.nodeValue ?: ""

            if (clazz == "android.widget.TextView" && text == targetText) {
                val containerNode = findEnclosingViewGroup(node)
                val editTextNode = containerNode?.let { findChildEditText(it) }
                if (editTextNode != null) {
                    return editTextNode.toUiElement()
                }
            }
        }


        for (i in 0 until nodeList.length) {
            val node = nodeList.item(i)
            val clazz = node.attributes?.getNamedItem("class")?.nodeValue ?: ""
            if (clazz == "android.widget.EditText") {
                return node.toUiElement()
            }
        }

        return null
    }

    private fun findEnclosingViewGroup(node: Node): Node? {
        var current = node.parentNode
        while (current != null) {
            val clazz = current.attributes?.getNamedItem("class")?.nodeValue ?: ""
            if (clazz.startsWith("android.view.ViewGroup") ||
                clazz.startsWith("android.widget.LinearLayout") ||
                clazz.startsWith("android.widget.RelativeLayout") ||
                clazz.startsWith("androidx.constraintlayout.widget.ConstraintLayout")
            ) {
                return current
            }
            current = current.parentNode
        }
        return null
    }

    private fun Node.toUiElement(): UiElement {
        val attrs = this.attributes
        val resourceId = attrs.getNamedItem("resource-id")?.nodeValue ?: ""
        val text = attrs.getNamedItem("text")?.nodeValue ?: ""
        val clazz = attrs.getNamedItem("class")?.nodeValue ?: ""
        val bounds = attrs.getNamedItem("bounds")?.nodeValue ?: ""
        val index = attrs.getNamedItem("index")?.nodeValue ?: ""
        return UiElement(resourceId, text, clazz, bounds, index)
    }



    private fun findChildEditText(parent: Node): Node? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val attrs = child.attributes
                val clazz = attrs?.getNamedItem("class")?.nodeValue ?: ""
                if (clazz == "android.widget.EditText") {
                    return child
                }
                val descendant = findChildEditText(child)
                if (descendant != null) return descendant
            }
        }
        return null
    }
}
