package util

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object AppiumHealth {
    private val client = OkHttpClient.Builder()
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    fun isReachable(serverUrl: String = "http://127.0.0.1:4723"): Boolean {
        return runCatching {
            val req = Request.Builder()
                .url(if (serverUrl.endsWith("/")) serverUrl + "status" else "$serverUrl/status")
                .get()
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrElse { false }
    }
}
