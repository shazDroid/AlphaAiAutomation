package model

import java.io.File

data class UiElement(
    val resourceId: String,
    val text: String,
    val clazz: String,
    val bounds: String,
    val index: String? = null,
    var effectiveTarget: String? = null
)

data class GenState(
    val visible: Boolean = false,
    val isGenerating: Boolean = false,
    val step: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val error: String? = null,
    val outDir: File? = null
)
