package agent.vision

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class DekiYoloClient(
    private val serverUrl: String = System.getenv("DEKI_SERVER") ?: "http://127.0.0.1:8765"
) {
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false) // be lenient

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun analyzePngWithLogs(
        png: ByteArray,
        log: (String) -> Unit,
        maxW: Int = 480,   // pre-downscale before upload
        imgsz: Int = 320,
        conf: Float = 0.25f,
        ocr: Boolean = true
    ): VisionResult? {
        val scaledBytes = ImageScaler.downscaleToShortSide(
            png, maxShortSide = maxW, format = "jpg", quality = 0.85f
        )

        val urlStr = serverUrl.trimEnd('/') + "/analyze"
        val httpUrl = (urlStr.toHttpUrlOrNull()
            ?: return log("yolo:http:bad_url $urlStr").let { null })
            .newBuilder()
            .addQueryParameter("imgsz", imgsz.toString())
            .addQueryParameter("conf", conf.toString())
            .addQueryParameter("ocr", if (ocr) "1" else "0")
            .build()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                "screenshot.jpg",
                scaledBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
            )
            .build()

        val req = Request.Builder().url(httpUrl).post(body).build()

        return try {
            client.newCall(req).execute().use { resp ->
                val payload = resp.body?.string()
                if (!resp.isSuccessful || payload == null) {
                    log("yolo:http:${resp.code} ${payload?.take(200)}")
                    return null
                }
                log("yolo:http:ok bytes=${payload.length}")
                mapper.readValue<VisionResult>(payload)
            }
        } catch (e: Exception) {
            log("yolo:request_failed error=${e.message}")
            null
        }
    }
}
