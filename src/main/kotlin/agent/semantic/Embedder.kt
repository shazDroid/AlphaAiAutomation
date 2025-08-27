package agent.semantic

interface Embedder {
    fun embed(text: String): FloatArray
}
