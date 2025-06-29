package util

import model.UiElement
import yaml.TestFlow

object TargetMapper {

    /**
     * Map targets in TestFlow using hierarchical container traversal.
     */
    fun mapTargets(
        flow: TestFlow,
        uiElements: List<UiElement>
    ): Pair<TestFlow, List<UiElement>> {

        val actionableElements = mutableListOf<UiElement>()

        flow.flow.forEach { action ->
            when (action.action) {

                "input_text" -> {
                    // Step 1: Find the TextView label matching the action target_text
                    val label = uiElements.find {
                        it.text.equals(action.target_text, ignoreCase = true)
                                && it.clazz.endsWith("TextView")
                    }

                    if (label != null) {
                        val labelIndex = uiElements.indexOf(label)

                        // Step 2: Find nearest EditText below this label in UI dump order
                        val editTextBelow = uiElements.subList(labelIndex, uiElements.size)
                            .find { it.clazz.endsWith("EditText") }

                        if (editTextBelow != null) {
                            val effectiveTarget = when {
                                editTextBelow.resourceId.isNotBlank() -> {
                                    // Use resource-id directly
                                    editTextBelow.resourceId
                                }
                                else -> {
                                    // Use sibling XPath referencing the label
                                    "//${label.clazz}[@text=\"${label.text}\"]/following::${editTextBelow.clazz}[1]"
                                }
                            }

                            action.target = effectiveTarget
                            editTextBelow.effectiveTarget = effectiveTarget

                            actionableElements.add(editTextBelow)

                            println("✅ Mapped '${action.target_text}' to EditText with effective target: $effectiveTarget")
                        } else {
                            println("❌ No EditText found below label ${action.target_text}")
                        }
                    } else {
                        println("❌ No TextView label found for ${action.target_text}")
                    }
                }

                "click" -> {
                    val clickable = uiElements.find {
                        (it.clazz.endsWith("Button") || it.clazz.endsWith("TextView"))
                                && it.text.equals(action.target_text, ignoreCase = true)
                    }

                    if (clickable != null) {
                        val effectiveTarget = if (clickable.resourceId.isNotBlank()) {
                            clickable.resourceId
                        } else {
                            "//${clickable.clazz}[@text=\"${clickable.text}\"]"
                        }

                        action.target = effectiveTarget
                        clickable.effectiveTarget = effectiveTarget

                        actionableElements.add(clickable)
                    } else {
                        println("❌ No clickable element found for ${action.target_text}")
                    }
                }

                else -> println("⚠️ Skipped unknown action ${action.action}")
            }
        }

        return Pair(flow, actionableElements)
    }
}
