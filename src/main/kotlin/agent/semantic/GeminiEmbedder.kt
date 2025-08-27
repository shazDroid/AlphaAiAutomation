package agent.semantic

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiEmbedder(private val apiKey: String) : Embedder {
    private val client = okhttp3.OkHttpClient()
    private val media = "application/json; charset=utf-8".toMediaType()

    override fun embed(text: String): FloatArray {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key=$apiKey"
        val body = """{"model":"text-embedding-004","content":{"parts":[{"text":${json(text)}]}}}"""
        val req = Request.Builder().url(url).post(body.toRequestBody(media)).build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val arr = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                .readTree(raw)["embedding"]["value"] ?: return FloatArray(0)
            return FloatArray(arr.size()) { i -> arr[i].asDouble().toFloat() }
        }
    }

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
