package ui

import adb.AdbExecutor
import adb.UiDumpParser
import adb.UiDumpParser.cleanUiDumpXml
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import model.UiElement
import util.OllamaClient
import util.PromptBuilder
import util.TargetMapper
import util.extractCodeBetweenMarkers
import yaml.TestFlow
import yaml.YamlFlowLoader


@Composable
fun AppUI() {
    val mapper = ObjectMapper().registerKotlinModule()
    var devices by remember { mutableStateOf(listOf<String>()) }
    var selectedDevice by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("com.shazdroid.messapp") }
    var uiElements by remember { mutableStateOf(listOf<UiElement>()) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    var yamlContent by remember { mutableStateOf("") }
    var parsedFlow by remember { mutableStateOf<TestFlow?>(null) }

    var isLoading by remember { mutableStateOf(false) }
    var featureFlowName by remember { mutableStateOf("") }


    // Tab states
    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Base Class", "Platform class", "Step Definitions", "Feature File")
    var baseClassOutput by remember { mutableStateOf("") }
    var platformClassOutput by remember { mutableStateOf("") }
    var stepDefinitionsOutput by remember { mutableStateOf("") }
    var featureFileOutput by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    Row(modifier = Modifier.fillMaxSize()) {

        // Left Pane
        Column(
            modifier = Modifier
                .weight(0.4f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Button(onClick = {
                devices = AdbExecutor.listDevices()
            }) {
                Text("Refresh Devices")
            }

            if (devices.isNotEmpty()) {
                Button(onClick = { isDropdownExpanded = true }) {
                    Text("Select Device")
                }

                DropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { isDropdownExpanded = false },
                ) {
                    devices.forEach { device ->
                        DropdownMenuItem(onClick = {
                            selectedDevice = device
                            isDropdownExpanded = false
                        }) {
                            Text(device)
                        }
                    }
                }
            }

            if (selectedDevice.isNotEmpty()) {
                Text("Selected device: $selectedDevice")
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("Package Name") }
                )

                OutlinedTextField(
                    value = featureFlowName,
                    onValueChange = { featureFlowName = it },
                    label = { Text("Feature Flow Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        AdbExecutor.launchApp(packageName, selectedDevice)
                    }
                }) {
                    Text("Launch App")
                }

                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val xml = UiDumpParser.getUiDumpXml(selectedDevice)
                        val cleanedXml = cleanUiDumpXml(xml)
                        val parsed = UiDumpParser.parseUiDump(cleanedXml)
                        uiElements = parsed
                    }
                }) {
                    Text("Capture UI Dump")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("YAML Flow Editor")
            OutlinedTextField(
                value = yamlContent,
                onValueChange = { yamlContent = it },
                label = { Text("Enter YAML flow here") },
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )

            Button(onClick = {
                if (yamlContent.isBlank()) {
                    baseClassOutput = "⚠️ Please enter YAML flow first."
                    return@Button
                }

                scope.launch(Dispatchers.IO) {
                    isLoading = true

                    try {
                        val buffer = StringBuilder()
                        val fullOutput = StringBuilder()


                        var flow = YamlFlowLoader.loadFlowFromString(yamlContent)
                        flow = TargetMapper.mapTargets(flow, uiElements)
                        parsedFlow = flow

                        // Clear outputs before streaming
                        baseClassOutput = ""
                        platformClassOutput = ""
                        stepDefinitionsOutput = ""
                        featureFileOutput = ""

                        val prompt = PromptBuilder.buildPrompt(featureFlowName, flow, uiElements)

                        OllamaClient.sendPromptStreaming(
                            prompt,
                            onChunk = { chunk ->
                                val cleanedChunk = chunk.trim()
                                if (cleanedChunk.isNotEmpty()) {
                                    try {
                                        val jsonNode = mapper.readTree(cleanedChunk)
                                        val content = jsonNode["message"]?.get("content")?.asText() ?: ""
                                        fullOutput.append(content)
                                    } catch (e: Exception) {
                                        // ignore parsing error; buffer grows until valid JSON
                                        println("Chunk parsing issue: ${e.message}")
                                    }
                                }
                            },
                            onComplete = {
                                isLoading = false
                                val outputText = fullOutput.toString()
                                println("Full text output: $outputText")
                                baseClassOutput = extractCodeBetweenMarkers(outputText, "BASE_CLASS_START", "BASE_CLASS_END")
                                platformClassOutput = extractCodeBetweenMarkers(outputText, "PLATFORM_CLASS_START", "PLATFORM_CLASS_END")
                                stepDefinitionsOutput = extractCodeBetweenMarkers(outputText, "STEP_DEFS_START", "STEP_DEFS_END")
                                featureFileOutput = extractCodeBetweenMarkers(outputText, "FEATURE_FILE_START", "FEATURE_FILE_END")
                                isLoading = false
                            }

                        )
                    } catch (e: Exception) {
                        println("Error: ${e.message}")
                        isLoading = false
                    }
                }
            }) {
                Text("Generate with AI")
            }



            if (isLoading) {
                CircularProgressIndicator()
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("UI Elements")

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                items(uiElements) { element ->
                    Text("${element.clazz} | ${element.resourceId} | ${element.text}")
                }
            }
        }

        // Right Pane
        Column(
            modifier = Modifier
                .weight(0.6f)
                .padding(16.dp)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    ) {
                        Text(title, modifier = Modifier.padding(8.dp))
                    }
                }
            }

            when (selectedTab) {
                0 -> CodeBlock(baseClassOutput)
                1 -> CodeBlock(platformClassOutput)
                2 -> CodeBlock(stepDefinitionsOutput)
                3 -> CodeBlock(featureFileOutput)
            }
        }
    }
}

@Composable
fun CodeBlock(code: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.surface)
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            code,
            fontFamily = FontFamily.Monospace
        )
    }
}
