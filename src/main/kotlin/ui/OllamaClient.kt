package util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
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
            "model" to "gemma3:latest",
            "messages" to listOf(
                mapOf("role" to "system", "content" to "You are a test automation code generator."),
                mapOf("role" to "user", "content" to prompt)
            ),
            "stream" to false
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

}
