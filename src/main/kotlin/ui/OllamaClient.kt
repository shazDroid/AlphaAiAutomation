package ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object OllamaClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()



    private val mapper = ObjectMapper().registerKotlinModule()

    fun sendPromptStreaming(prompt: String, onChunk: (String) -> Unit, onComplete: () -> Unit) {
        println("Request body: \n$prompt")
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val payload = mapOf(
            "model" to "deepseek-r1:8b",
            "messages" to listOf(
                mapOf("role" to "system", "content" to "You are a test automation code generator."),
                mapOf("role" to "user", "content" to prompt)
            ),
            "stream" to false,
            "temperature" to 0.3
        )

        val body = RequestBody.create(mediaType, ObjectMapper().writeValueAsString(payload))

        val request = Request.Builder()
            .url("http://localhost:11434/api/chat")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                val source = response.body?.source()
                while (!source!!.exhausted()) {
                    val line = source.readUtf8Line()
                    onChunk(line ?: "")
                }
                onComplete()
            }
        })
    }


    fun completeBlocking(prompt: String): String {
        val latch = CountDownLatch(1)
        val out = StringBuilder()
        sendPromptStreaming(
            prompt = prompt,
            onChunk = { chunk -> out.append(chunk) },
            onComplete = { latch.countDown() }
        )
        latch.await()
        return out.toString().trim()
    }


    fun completeJsonBlocking(system: String, user: String): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val payload = mapOf(
            "model" to "deepseek-coder:6.7b",
            "messages" to listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user)
            ),
            "stream" to false,
            "temperature" to 0.0,
            "format" to "json"
        )
        val reqJson = mapper.writeValueAsString(payload)
        println("🟦 [Ollama] REQUEST JSON (${reqJson.length} chars)")
        println(reqJson.take(4000) + if (reqJson.length > 4000) " ...[truncated]" else "")

        val body = reqJson.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("http://localhost:11434/api/chat")
            .post(body)
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty().trim()
            println("🟩 [Ollama] RAW RESPONSE (${raw.length} chars)")
            println(raw.take(4000) + if (raw.length > 4000) " ...[truncated]" else "")
            val node = mapper.readTree(raw)
            val content = node.path("message").path("content").asText("")
            if (content.isBlank()) {
                println("🟨 [Ollama] WARNING: 'message.content' empty, returning RAW response")
                return raw
            }
            return content
        }
    }
}
