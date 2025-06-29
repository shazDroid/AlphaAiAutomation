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

                "select_list_item" -> {
                    val targetItem = uiElements.find {
                        it.text.equals(action.target_text, ignoreCase = true)
                    }

                    if (targetItem != null) {
                        val recyclerView = uiElements.find { parent ->
                            parent.clazz.contains("RecyclerView") &&
                                    parent.bounds.contains(targetItem.bounds)
                        }

                        if (recyclerView != null) {
                            val effectiveTarget = if (targetItem.resourceId.isNotBlank()) {
                                targetItem.resourceId
                            } else {
                                "//${targetItem.clazz}[@text=\"${targetItem.text}\"]"
                            }

                            action.target = effectiveTarget
                            targetItem.effectiveTarget = effectiveTarget
                            actionableElements.add(targetItem)

                            println("✅ Mapped select_list_item for '${action.target_text}' within RecyclerView ${recyclerView.resourceId}")
                        } else {
                            println("❌ No RecyclerView parent found for item ${action.target_text}")
                        }
                    } else {
                        println("❌ No list item found with text ${action.target_text}")
                    }
                }

                "check_text_equals" -> {
                    val element = uiElements.find {
                        it.text.equals(action.target_text, ignoreCase = true)
                                && it.clazz.endsWith("TextView")
                    }

                    if (element != null) {
                        action.target = element.resourceId.ifBlank {
                            "//${element.clazz}[@text=\"${element.text}\"]"
                        }
                        actionableElements.add(element)
                    } else {
                        println("❌ No TextView found for ${action.target_text}")
                    }
                }

                "check_text_contains" -> {
                    val element = uiElements.find {
                        it.text.contains(action.target_text ?: "", ignoreCase = true)
                                && it.clazz.endsWith("TextView")
                    }

                    if (element != null) {
                        action.target = element.resourceId.ifBlank {
                            "//${element.clazz}[contains(@text,\"${action.target_text}\")]"
                        }
                        actionableElements.add(element)
                    } else {
                        println("❌ No TextView containing '${action.target_text}' found")
                    }
                }


                "scroll_to" -> {
                    val element = uiElements.find {
                        it.text.equals(action.target_text, ignoreCase = true)
                    }

                    if (element != null) {
                        action.target = element.resourceId.ifBlank {
                            "//${element.clazz}[@text=\"${element.text}\"]"
                        }
                        actionableElements.add(element)
                    } else {
                        println("❌ No element found to scroll to for '${action.target_text}'")
                    }
                }


                "toggle_switch" -> {
                    val element = uiElements.find {
                        (it.clazz.endsWith("Switch") || it.clazz.endsWith("CheckBox"))
                                && (it.text.equals(action.target_text, ignoreCase = true)
                                || uiElements.any { label -> label.text.equals(action.target_text, ignoreCase = true) && it.bounds.overlaps(label.bounds) })
                    }

                    if (element != null) {
                        action.target = element.resourceId.ifBlank {
                            "//${element.clazz}[@text=\"${element.text}\"]"
                        }
                        actionableElements.add(element)
                    } else {
                        println("❌ No Switch or CheckBox found for ${action.target_text}")
                    }
                }


                else -> println("⚠️ Skipped unknown action ${action.action}")
            }
        }

        return Pair(flow, actionableElements)
    }

    /**
     * Helper extension to check if two bounds overlap.
     */
    private fun String.overlaps(other: String): Boolean {
        val regex = "\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)]".toRegex()
        val match1 = regex.find(this)
        val match2 = regex.find(other)

        if (match1 != null && match2 != null) {
            val (x1, y1, x2, y2) = match1.destructured
            val (ox1, oy1, ox2, oy2) = match2.destructured

            val left1 = x1.toInt()
            val right1 = x2.toInt()
            val top1 = y1.toInt()
            val bottom1 = y2.toInt()

            val left2 = ox1.toInt()
            val right2 = ox2.toInt()
            val top2 = oy1.toInt()
            val bottom2 = oy2.toInt()

            val horizontallyOverlaps = left1 < right2 && right1 > left2
            val verticallyOverlaps = top1 < bottom2 && bottom1 > top2

            return horizontallyOverlaps && verticallyOverlaps
        }

        return false
    }

}
