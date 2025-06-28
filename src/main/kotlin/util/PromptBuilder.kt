package util

import model.UiElement
import yaml.TestFlow

object PromptBuilder {
    fun buildPrompt(featureFlowName: String, flow: TestFlow, uiElements: List<UiElement>): String {
        val builder = StringBuilder()

        builder.append("You are a test automation code generator.\n\n")

        builder.append("Feature Flow Name: ").append(featureFlowName).append("\n\n")

        builder.append("Here is the YAML flow:\n")
        builder.append(flow.toString()).append("\n\n")

        builder.append("Here are the extracted UI elements:\n")
        uiElements.forEach { element ->
            builder.append("- resourceId: ${element.resourceId}\n")
            builder.append("  class: ${element.clazz}\n")
            builder.append("  text: ${element.text}\n")
        }

        builder.append("\nGenerate outputs with **clear markers**:\n\n")

        builder.append("### BASE_CLASS_START\n")
        builder.append("Generate an abstract TypeScript class named Base${featureFlowName.capitalize()}.\n")
        builder.append("It should include abstract getters for each UI element required across all pages in this flow.\n")
        builder.append("e.g public abstract get loginBtn(): ChainablePromise<WebdriverIO.Element>;.\n")
        builder.append("Each getter should return ChainablePromise<WebdriverIO.Element>.\n")
        builder.append("No constructor or extra code is required.\n")
        builder.append("Wrap the code in a TypeScript fenced code block.\n")
        builder.append("### BASE_CLASS_END\n\n")

        builder.append("### PLATFORM_CLASS_START\n")
        builder.append("Generate one or multiple TypeScript classes for platform-specific pages.\n")
        builder.append("Each class should extend Base${featureFlowName.capitalize()}.\n")
        builder.append("Override each getter with its actual locator using $('resourceId').\n")
        builder.append("Example class names: AndroidLoginPage, AndroidKycPage.\n")
        builder.append("Wrap the code in a TypeScript fenced code block.\n")
        builder.append("No constructor or extra code is required.\n")
        builder.append("### PLATFORM_CLASS_END\n\n")

        builder.append("### STEP_DEFS_START\n")
        builder.append("Generate step definitions in TypeScript that use the platform page classes to perform actions with Cucumber syntax.\n")
        builder.append("Ensure the steps handle multiple pages in the flow sequentially.\n")
        builder.append("Wrap the code in a TypeScript fenced code block.\n")
        builder.append("### STEP_DEFS_END\n\n")

        builder.append("### FEATURE_FILE_START\n")
        builder.append("Generate a .feature file using Gherkin syntax for this flow.\n")
        builder.append("Include Feature, Scenario, Given, When, Then steps covering the flow clearly.\n")
        builder.append("Wrap the code in a Gherkin fenced code block.\n")
        builder.append("### FEATURE_FILE_END\n\n")

        builder.append("IMPORTANT: Output strictly within these markers with no explanation outside them.\n")

        return builder.toString()
    }
}
