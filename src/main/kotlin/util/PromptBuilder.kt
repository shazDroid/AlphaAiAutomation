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

        // Filter actionable UI elements only
        val actionableElements = uiElements.filter { element ->
            val clazz = element.clazz.lowercase()
            clazz.contains("edittext") ||
                    clazz.contains("button") ||
                    clazz.contains("textview") ||
                    clazz.contains("imageview") ||
                    clazz.contains("checkbox") ||
                    clazz.contains("radiobutton") ||
                    clazz.contains("switch")
        }

        builder.append("Here are the actionable extracted UI elements:\n")
        actionableElements.forEach { element ->
            builder.append("- resourceId: ${element.resourceId}\n")
            builder.append("  class: ${element.clazz}\n")
            builder.append("  text: ${element.text}\n")
        }

        builder.append("\nGenerate outputs with **clear markers**:\n\n")

        builder.append("IMPORTANT:\n")
        builder.append("- Do NOT add any comments, docs, or explanations outside the markers.\n")
        builder.append("- Each section MUST start and end with its respective marker exactly as instructed.\n")
        builder.append("- Never omit the closing markers.\n\n")

        // BASE CLASS
        builder.append("### BASE_CLASS_START\n")
        builder.append("Generate an abstract TypeScript class named Base${featureFlowName.capitalize()}.\n")
        builder.append("Each UI element should have an abstract getter in this exact format:\n")
        builder.append("public abstract get elementName(): ChainablePromise<WebdriverIO.Element>;\n")
        builder.append("where elementName is meaningful based on resource ID or text.\n")
        builder.append("No constructor or extra code is required.\n")
        builder.append("Wrap the code in a TypeScript fenced code block.\n")
        builder.append("IMPORTANT: Ensure you include the closing marker ### BASE_CLASS_END after the code block.\n")
        builder.append("### BASE_CLASS_END\n\n")

        // PLATFORM CLASS
        builder.append("### PLATFORM_CLASS_START\n")
        builder.append("Generate a TypeScript class named Android${featureFlowName.capitalize()}Page that extends Base${featureFlowName.capitalize()}.\n")
        builder.append("Override each getter with its actual locator in this format:\n")
        builder.append("return $('package:id');\n")
        builder.append("Use the full resource ID including package, e.g. $('com.shazdroid.messapp:id/shazButton').\n")
        builder.append("Wrap the code in a TypeScript fenced code block.\n")
        builder.append("No constructor or extra code is required.\n")
        builder.append("IMPORTANT: Ensure you include the closing marker ### PLATFORM_CLASS_END after the code block.\n")
        builder.append("### PLATFORM_CLASS_END\n\n")

        // STEP DEFINITIONS
        builder.append("### STEP_DEFS_START\n")
        builder.append("Generate step definitions in TypeScript using WebdriverIO + Cucumber syntax.\n")
        builder.append("Use the page object class and call its elements with clear actions.\n")
        builder.append("Wrap the code in a TypeScript fenced code block.\n")
        builder.append("IMPORTANT: Ensure you include the closing marker ### STEP_DEFS_END after the code block.\n")
        builder.append("### STEP_DEFS_END\n\n")

        // FEATURE FILE
        builder.append("### FEATURE_FILE_START\n")
        builder.append("Generate a .feature file using Gherkin syntax for this flow.\n")
        builder.append("Include Feature description, Scenario, Given, When, Then steps clearly describing the flow.\n")
        builder.append("Wrap the code in a Gherkin fenced code block.\n")
        builder.append("IMPORTANT: Ensure you include the closing marker ### FEATURE_FILE_END after the code block.\n")
        builder.append("### FEATURE_FILE_END\n\n")

        builder.append("IMPORTANT:\n")
        builder.append("- Output strictly within these markers with no explanation outside them.\n")

        return builder.toString()
    }
}
