package util

import model.UiElement
import yaml.TestFlow

object TargetMapper {
    fun mapTargets(flow: TestFlow, uiElements: List<UiElement>): TestFlow {
        val updatedActions = flow.flow.map { action ->
            if (action.target_text != null) {
                val matchedElement = uiElements.find { it.text.equals(action.target_text, ignoreCase = true) }
                action.copy(target = matchedElement?.resourceId)
            } else {
                action
            }
        }
        return TestFlow(updatedActions)
    }
}
