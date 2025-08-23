package agent

inline fun <T> withRetry(
    attempts: Int = 3,
    delayMs: Long = 600,
    onError: (Int, Throwable) -> Unit = { _, _ -> },
    block: () -> T
): T {
    var last: Throwable? = null
    repeat(attempts) { i ->
        try { return block() } catch (e: Throwable) {
            last = e; onError(i + 1, e); Thread.sleep(delayMs)
        }
    }
    throw last ?: IllegalStateException("withRetry failed with no exception")
}
