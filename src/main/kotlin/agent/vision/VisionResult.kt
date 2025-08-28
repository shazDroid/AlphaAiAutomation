package agent.vision

/**
 * Minimal, model-agnostic structures for local vision output.
 * You can fill these from any detector (YOLO, PaddleOCR, Tesseract, etc).
 */

data class VisionElement(
    val id: String,
    val type: String? = null,
    val text: String? = null,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val score: Double? = null
)

data class VisionResult(
    val imageW: Int,
    val imageH: Int,
    val elements: List<VisionElement>
)

