package util

import model.UiElement
import yaml.TestFlow

object PromptBuilder {
    fun buildPrompt(featureFlowName: String, flow: TestFlow, uiElements: List<UiElement>): String {
        val builder = StringBuilder()

        builder.append("You are a test automation code generator.\n\n")
        builder.append("Feature Flow Name: ").append(featureFlowName).append("\n\n")

        builder.append("Here is the YAML flow (each action has index, action type, target resource ID, and text for clarity):\n")
        flow.flow.forEachIndexed { index, action ->
            builder.append("${index + 1}. Action: ${action.action}\n")
            action.packageName?.let { builder.append("   Package: $it\n") }
            action.target?.let { builder.append("   Target Resource ID: $it\n") }
            action.target_text?.let { builder.append("   Target Text: ${action.target_text}\n") }
            action.value?.let { builder.append("   Value: ${action.value}\n") }
            builder.append("\n")
        }

        // Extract unique target resourceIds and target texts from YAML flow
        val yamlTargetIds = flow.flow.mapNotNull { it.target }.toSet()
        val yamlTargetTexts = flow.flow.mapNotNull { it.target_text?.lowercase() }.toSet()
        val yamlEffectiveTargets = flow.flow.mapNotNull { it.target }.toSet()


        // Filter actionable UI elements only if they are in YAML targets
        val actionableElements = uiElements.filter { element ->
            val resourceMatch = element.resourceId in yamlTargetIds
            val effectiveTargetMatch = element.effectiveTarget in yamlEffectiveTargets
            val textMatch = element.text.lowercase() in yamlTargetTexts
            resourceMatch || effectiveTargetMatch || textMatch
        }


        builder.append("Here are the actionable extracted UI elements with aliases for page object getters:\n")
        actionableElements.forEach { element ->
            val alias = buildAlias(element)
            builder.append("- Alias: $alias\n")
            builder.append("  Resource ID: ${element.effectiveTarget ?: element.resourceId}\n")
            builder.append("  Class: ${element.clazz}\n")
            builder.append("  Text: ${element.text}\n")
            builder.append("  Bounds: ${element.bounds}\n")
            builder.append("  Index: ${element.index}\n")
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
        builder.append("where elementName is meaningful based on the provided aliases.\n")
        builder.append("Wrap the code in a TypeScript fenced code block.\n")
        builder.append("IMPORTANT: Ensure you include the closing marker ### BASE_CLASS_END after the code block.\n")
        builder.append("### BASE_CLASS_END\n\n")

        // PLATFORM CLASS
        builder.append("### PLATFORM_CLASS_START\n")
        builder.append("Generate a TypeScript class named Android${featureFlowName.capitalize()}Page that extends Base${featureFlowName.capitalize()}.\n")
        builder.append("Override each getter with its actual locator using Appium/WebdriverIO selectors based on the following rules:\n")
        builder.append("1. If resourceId is not blank, use $('id=package:id').\n")
        builder.append("2. If resourceId is blank but text is present, use XPath based on text.\n")
        builder.append("3. If both resourceId and text are blank, use XPath with index or bounds as fallback.\n")
        builder.append("Example for unique id: return $('id=com.shazdroid.messapp:id/shazButton');\n")
        builder.append("Example for text only: return $('//android.widget.EditText[@text=\"Username\"]');\n")
        builder.append("Example with index fallback: return $('(//android.widget.EditText)[2]');\n")
        builder.append("Example with bounds fallback: return $('//android.widget.EditText[@bounds=\"[100,200][300,400]\"]');\n")
        builder.append("Wrap the code in a TypeScript fenced code block.\n")
        builder.append("IMPORTANT: Ensure you include the closing marker ### PLATFORM_CLASS_END after the code block.\n")
        builder.append("### PLATFORM_CLASS_END\n\n")

        // STEP DEFINITIONS
        builder.append("### STEP_DEFS_START\n")
        builder.append("Generate TypeScript step definitions using WebdriverIO + Cucumber syntax.\n")
        builder.append("Follow these examples:\n")
        builder.append("When('I enter the password as {string}', async (password) => {\n")
        builder.append("  const passwordField = await loginPage.passwordField;\n")
        builder.append("  await passwordField.setValue(password);\n")
        builder.append("});\n\n")
        builder.append("Use the page object class and call its elements with clear actions.\n")
        builder.append("Wrap the code in a TypeScript fenced code block.\n")
        builder.append("IMPORTANT: Ensure you include the closing marker ### STEP_DEFS_END after the code block.\n")
        builder.append("### STEP_DEFS_END\n\n")

        // FEATURE FILE
        builder.append("### FEATURE_FILE_START\n")
        builder.append("Generate a .feature file using Gherkin syntax for this flow.\n")
        builder.append("Include Feature description, Scenario, Given, When, Then steps clearly describing the flow.\n")
        builder.append("Wrap the code in a Gherkin fenced code block.\n")
        builder.append("Write positive and negative cases for each action.\n")
        builder.append("IMPORTANT: Ensure you include the closing marker ### FEATURE_FILE_END after the code block.\n")
        builder.append("### FEATURE_FILE_END\n\n")

        builder.append("IMPORTANT:\n")
        builder.append("- Output strictly within these markers with no explanation outside them.\n")

        return builder.toString()
    }

    private fun buildAlias(element: UiElement): String {
        val idPart = element.resourceId?.split("/")?.lastOrNull()?.replace(Regex("[^A-Za-z0-9]"), "") ?: "unknown"
        val textPart = element.text.takeIf { it.isNotBlank() }?.replace("\\s+".toRegex(), "_") ?: ""
        return (idPart + textPart).decapitalize()
    }
}
