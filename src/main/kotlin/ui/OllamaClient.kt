package ui

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit


object OllamaClient {

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val mapper = ObjectMapper().registerKotlinModule()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    val apiKey = "AIzaSyBAB1n3XuO7Ra1wfrZNXPTWJRigDNvPtbE"

    // --- Public APIs --------------------------------------------------------

    fun completeRawBlocking(
        system: String,
        user: String,
        model: String = "gemini-2.5-flash-lite",
        temperature: Double = 0.0
    ): String {
        val url = endpoint(model, apiKey)
        val payload = buildGeminiPayload(
            system = system,
            user = user,
            temperature = temperature,
            // For raw text we do NOT force JSON mime type
            responseMimeType = null
        )

        val reqJson = mapper.writeValueAsString(payload)
        println("🟦 [Gemini] REQUEST RAW (${reqJson.length} chars)")
        println(reqJson.take(4000) + if (reqJson.length > 4000) " ...[truncated]" else "")

        val request = Request.Builder()
            .url(url)
            .post(reqJson.toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty().trim()
            println("🟩 [Gemini] RAW TEXT (${raw.length} chars)")
            println(raw.take(4000) + if (raw.length > 4000) " ...[truncated]" else "")

            return extractTextFromGemini(raw).ifBlank { raw }
        }
    }

    fun completeJsonBlocking(system: String, user: String): String {
        val model = "gemini-2.5-flash-lite"
        val url = endpoint(model, apiKey)
        val payload = buildGeminiPayload(
            system = system,
            user = user,
            temperature = 0.0,
            responseMimeType = "application/json"
        )

        val reqJson = mapper.writeValueAsString(payload)
        println("🟦 [Gemini] REQUEST JSON (${reqJson.length} chars)")
        println(reqJson.take(4000) + if (reqJson.length > 4000) " ...[truncated]" else "")

        val request = Request.Builder()
            .url(url)
            .post(reqJson.toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty().trim()
            println("🟩 [Gemini] RAW RESPONSE (${raw.length} chars)")
            println(raw.take(4000) + if (raw.length > 4000) " ...[truncated]" else "")

            val text = extractTextFromGemini(raw)
            if (text.isNotBlank()) return text

            val i = raw.indexOf('{');
            val j = raw.lastIndexOf('}')
            return if (i != -1 && j > i) raw.substring(i, j + 1) else raw
        }
    }

    // --- Internals ---------------------------------------------------------------------------

    private fun endpoint(model: String, apiKey: String): String =
        "https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=$apiKey"

    /**
     * Gemini request schema:
     * {
     *   "systemInstruction": {"role":"system","parts":[{"text": "..."}]},
     *   "contents": [{"role":"user","parts":[{"text":"..."}]}],
     *   "generationConfig": {"temperature":0.0,"response_mime_type":"application/json"?}
     * }
     */
    private fun buildGeminiPayload(
        system: String,
        user: String,
        temperature: Double,
        responseMimeType: String?
    ): Map<String, Any> {

        val generationCfg = mutableMapOf<String, Any>(
            "temperature" to temperature
        )
        if (!responseMimeType.isNullOrBlank()) {
            generationCfg["response_mime_type"] = responseMimeType
        }

        val body = mutableMapOf<String, Any>(
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to user))
                )
            ),
            "generationConfig" to generationCfg
        )

        if (system.isNotBlank()) {
            body["systemInstruction"] = mapOf(
                "role" to "system",
                "parts" to listOf(mapOf("text" to system))
            )
        }

        return body
    }


    private fun extractTextFromGemini(raw: String): String {
        return try {
            val root: JsonNode = mapper.readTree(raw)
            val candidates = root.path("candidates")
            if (!candidates.isArray || candidates.size() == 0) return ""

            val content = candidates[0].path("content")
            val parts = content.path("parts")
            if (!parts.isArray || parts.size() == 0) return ""

            val sb = StringBuilder()
            for (p in parts) {
                val t = p.path("text").asText("")
                if (t.isNotBlank()) sb.append(t)
            }
            sb.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }
}
