package model

data class UiElement(
    val resourceId: String,
    val text: String,
    val clazz: String,
    val bounds: String,
    val index: String? = null,
    var effectiveTarget: String? = null
)
