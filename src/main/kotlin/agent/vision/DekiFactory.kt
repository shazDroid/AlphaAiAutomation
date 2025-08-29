package agent.vision

import java.net.HttpURLConnection
import java.net.URL

object DekiFactory {

    /**
     * Creates a DekiYoloClient that talks to the Flask server.
     * Uses DEKI_SERVER if set, otherwise defaults to http://127.0.0.1:8765
     * Returns null if the server doesn't appear reachable.
     */
    fun auto(log: (String) -> Unit = {}): DekiYoloClient? {
        val url = System.getenv("DEKI_SERVER") ?: "http://127.0.0.1:8765"
        val ok = isServerReachable(url)
        if (!ok) {
            log("yolo:init server_unreachable: $url (start your Flask server or set DEKI_SERVER)")
            return null
        }
        log("yolo:init server=$url")
        return DekiYoloClient(serverUrl = url)
    }

    private fun isServerReachable(base: String): Boolean {
        return try {
            val u = URL("$base/analyze")
            val conn = (u.openConnection() as HttpURLConnection).apply {
                // OPTIONS works even when route expects POST (Flask responds 200/204)
                requestMethod = "OPTIONS"
                connectTimeout = 800
                readTimeout = 800
            }
            val code = conn.responseCode
            // consider any non-network failure as reachable (e.g., 200..499)
            code in 200..499
        } catch (_: Throwable) {
            false
        }
    }
}
