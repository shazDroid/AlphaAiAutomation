package ui

import KottieAnimation
import adb.AdbExecutor
import adb.UiDumpParser
import adb.UiDumpParser.cleanUiDumpXml
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import contentScale.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kottieComposition.KottieCompositionSpec
import model.UiElement
import ui.component.AlphaButton
import ui.component.AlphaInputText
import ui.component.AlphaInputTextMultiline
import ui.component.AlphaTabRow
import ui.theme.BLUE
import util.CodeBlock
import util.OllamaClient
import util.PromptBuilder
import util.TargetMapper
import util.extractCodeBetweenMarkers
import yaml.TestFlow
import yaml.YamlFlowLoader
import java.io.File

@Preview
@Composable
fun AppUI() {
    val mapper = ObjectMapper().registerKotlinModule()
    var devices by remember { mutableStateOf(listOf<String>()) }
    var selectedDevice by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("com.shazdroid.messapp") }
    var uiElements by remember { mutableStateOf(listOf<UiElement>()) }
    var showAnimation by remember { mutableStateOf(true) }

    var yamlContent by remember { mutableStateOf("") }
    var parsedFlow by remember { mutableStateOf<TestFlow?>(null) }

    var isLoading by remember { mutableStateOf(false) }
    var featureFlowName by remember { mutableStateOf("") }
    var isCapturedDone by remember { mutableStateOf(false) }


    // Tab states
    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("UI Dump", "Base Class", "Platform class", "Step Definitions", "Feature File")
    var baseClassOutput by remember { mutableStateOf("") }
    var platformClassOutput by remember { mutableStateOf("") }
    var stepDefinitionsOutput by remember { mutableStateOf("") }
    var featureFileOutput by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    val inputStream = {}.javaClass.getResourceAsStream("/drawable/robot_animation.json")
        ?: throw IllegalArgumentException("Resource not found")
    val json = inputStream.bufferedReader().use { it.readText() }

    Row(modifier = Modifier.fillMaxSize()) {

        // Left Pane
        Column(
            modifier = Modifier.weight(0.2f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {

            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(color = Color(0xFF2C3EAF), shape = RoundedCornerShape(size = 12.dp)).padding(12.dp)
            ) {
                Row(modifier = Modifier, verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        modifier = Modifier.size(64.dp),
                        painter = painterResource("drawable/logo.png"),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(Color.White)
                    )

                    Spacer(modifier = Modifier.size(16.dp))

                    Column() {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = "Alpha Automation",
                            fontWeight = MaterialTheme.typography.h6.fontWeight,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.size(2.dp))

                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = "developed by shahbaz ansari",
                            fontWeight = MaterialTheme.typography.h2.fontWeight,
                            fontSize = TextUnit(12f, TextUnitType.Sp),
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Devices", fontWeight = MaterialTheme.typography.h6.fontWeight)

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(color = Color(0xFFEDF3FF), shape = RoundedCornerShape(size = 12.dp)).padding(12.dp)
            ) {
                Column() {
                    if (selectedDevice.isEmpty()) {
                        DeviceNotSelectedError {
                            devices = AdbExecutor.listDevices()
                        }
                    } else {
                        DeviceSelected()
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // show device list if visible
                    if (devices.isNotEmpty()) {
                        Text(
                            text = if (selectedDevice.isEmpty()) "Device list" else "Selected device: $selectedDevice",
                            fontWeight = MaterialTheme.typography.h6.fontWeight
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AnimatedVisibility(selectedDevice.isEmpty()) {
                            LazyColumn(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                                items(devices) { item ->
                                    DeviceItem(item, onDeviceSelected = { currentSelectedDevice ->
                                        selectedDevice = currentSelectedDevice
                                    })
                                }
                            }
                        }

                        AnimatedVisibility(selectedDevice.isNotEmpty()) {
                            AlphaButton(text = "Rescan devices") {
                                selectedDevice = ""
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(selectedDevice.isNotEmpty()) {
                CaptureUiDump(isCaptureDone = isCapturedDone, onClick = {
                    isCapturedDone = false
                    scope.launch(Dispatchers.IO) {
                        val xml = UiDumpParser.getUiDumpXml(selectedDevice)
                        val cleanedXml = cleanUiDumpXml(xml)
                        val parsed = UiDumpParser.parseUiDump(cleanedXml)
                        uiElements = parsed
                        isCapturedDone = true
                    }
                }, onPackageNameChange = {
                    packageName = it
                }, onFlowFeatureNameChange = {
                    featureFlowName = it
                })
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Yaml Editor", fontWeight = MaterialTheme.typography.h6.fontWeight)

            Spacer(modifier = Modifier.height(8.dp))

            AlphaInputTextMultiline(
                value = yamlContent,
                onValueChange = { yamlContent = it },
                hint = "Enter YAML flow here",
                backgroundColor = Color(0xFFEDF3FF)
            )

            Spacer(modifier = Modifier.height(16.dp))

            AlphaButton(isLoading = isLoading, text = "Generate with AI", onClick = {
                showAnimation = true

                if (yamlContent.isBlank()) {
                    baseClassOutput = "⚠️ Please enter YAML flow first."
                    return@AlphaButton
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
                                showAnimation = false
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

            })
        }

        // Right Pane
        Column(
            modifier = Modifier
                .weight(0.6f)
                .padding(16.dp)
        ) {
            if (showAnimation) {
                KottieAnimation(
                    composition = KottieCompositionSpec.JsonString(json),
                    modifier = Modifier.size(200.dp),
                    progress = {
                        50f
                    },
                    backgroundColor = Color.Transparent,
                    contentScale = ContentScale.Fit
                )
            } else {
                AlphaTabRow(
                    tabs = tabTitles,
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it }
                )

                when (selectedTab) {
                    0 -> CodeBlock(uiElements.toString(), "plain")
                    1 -> CodeBlock(baseClassOutput, "typescript")
                    2 -> CodeBlock(platformClassOutput, "typescript")
                    3 -> CodeBlock(stepDefinitionsOutput, "typescript")
                    4 -> CodeBlock(featureFileOutput, "gherkin")
                }
            }
        }
    }
}

@Preview
@Composable
fun DeviceNotSelectedError(
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier, verticalAlignment = Alignment.CenterVertically) {
            Image(painter = painterResource("drawable/warning.svg"), contentDescription = null)
            Spacer(modifier = Modifier.size(4.dp))
            Column() {
                Text(text = "No device selected!", fontWeight = MaterialTheme.typography.h6.fontWeight)
                Text(
                    text = "Please select device to proceed",
                    fontWeight = MaterialTheme.typography.h1.fontWeight,
                    fontSize = TextUnit(12f, TextUnitType.Sp)
                )
                Spacer(modifier = Modifier.size(4.dp))
            }
        }

        Spacer(modifier = Modifier.size(8.dp))

        AlphaButton(text = "Scan devices") {
            onClick.invoke()
        }
    }
}

@Preview
@Composable
fun DeviceSelected() {
    Row(modifier = Modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(painter = painterResource("drawable/device.svg"), contentDescription = null)
        Spacer(modifier = Modifier.size(4.dp))
        Column() {
            Text(text = "Device selected!", fontWeight = MaterialTheme.typography.h6.fontWeight)
            Text(
                text = "You can proceed with UI Dump",
                fontWeight = MaterialTheme.typography.h1.fontWeight,
                fontSize = TextUnit(12f, TextUnitType.Sp)
            )

        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DeviceItem(deviceName: String, onDeviceSelected: (String) -> Unit) {
    var isActive by remember {
        mutableStateOf(false)
    }
    var isSelected by remember {
        mutableStateOf(false)
    }
    Box(modifier = Modifier.fillMaxWidth()
        .background(color = if (isSelected) Color(BLUE) else Color(0xFFFFFFFF), shape = RoundedCornerShape(8.dp))
        .onPointerEvent(eventType = PointerEventType.Enter) {
            isActive = true
        }.onPointerEvent(eventType = PointerEventType.Exit) {
            isActive = false
        }.onPointerEvent(eventType = PointerEventType.Press) {
            isSelected = true
            onDeviceSelected.invoke(deviceName)
        }) {

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                modifier = Modifier.weight(0.8f).padding(8.dp),
                text = deviceName,
                fontSize = TextUnit(16f, TextUnitType.Sp),
                color = Color.DarkGray
            )
            AnimatedVisibility(isActive) {
                Image(
                    painter = painterResource("drawable/arrow_right.svg"),
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp).size(24.dp)
                )
            }
        }
    }
}

@Composable
fun CaptureUiDump(
    isCaptureDone: Boolean,
    onClick: () -> Unit,
    onPackageNameChange: (String) -> Unit,
    onFlowFeatureNameChange: (String) -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(color = Color(0xFFEDF3FF), shape = RoundedCornerShape(size = 12.dp)).padding(12.dp)
    ) {
        Column() {
            var packageName by remember { mutableStateOf("com.shazdroid.messapp") }
            var flowFeatureName by remember { mutableStateOf("Login") }

            PackageInput(hint = "Package Name", onTextChange = { value ->
                packageName = value
                onPackageNameChange.invoke(value)
            })
            Spacer(modifier = Modifier.padding(8.dp))
            AnimatedVisibility(packageName.isNotEmpty()) {
                FlowInput(hint = "Feature Flow Name", onTextChange = {
                    flowFeatureName = it
                    onFlowFeatureNameChange.invoke(it)
                })
            }
            Spacer(modifier = Modifier.padding(8.dp))
            AnimatedVisibility(isCaptureDone) {
                isLoading = false
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        modifier = Modifier.size(24.dp),
                        painter = painterResource("drawable/success.svg"),
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.padding(4.dp))
                    Row() {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = "Captured UI Dump",
                            fontWeight = MaterialTheme.typography.h6.fontWeight
                        )

                        Image(
                            modifier = Modifier
                                .size(24.dp)
                                .clickable {
                                    onClick.invoke()
                                }, painter = painterResource("drawable/restart.svg"), contentDescription = null
                        )
                    }
                }
                Spacer(modifier = Modifier.padding(4.dp))
            }

            AnimatedVisibility(flowFeatureName.isNotEmpty()) {
                AnimatedVisibility(isCaptureDone.not()) {
                    AlphaButton(isLoading = isLoading, text = "Capture UI Dump") {
                        isLoading = true
                        onClick.invoke()
                    }
                }
            }
        }
    }
}

@Composable
fun PackageInput(hint: String, onTextChange: (String) -> Unit) {
    val text = remember { mutableStateOf("") }
    AlphaInputText(
        value = text.value,
        onValueChange = { textValue ->
            text.value = textValue
            onTextChange.invoke(textValue)
        },
        hint = hint
    )
}

@Composable
fun FlowInput(hint: String, onTextChange: (String) -> Unit) {
    val text = remember { mutableStateOf("") }
    AlphaInputText(
        value = text.value,
        onValueChange = { textValue ->
            text.value = textValue
            onTextChange.invoke(textValue)
        },
        hint = hint
    )
}

fun loadAnimationFromResources(path: String): File {
    val inputStream = {}.javaClass.getResourceAsStream(path)
        ?: throw IllegalArgumentException("Resource not found: $path")
    val tempFile = kotlin.io.path.createTempFile(suffix = ".json").toFile()
    inputStream.use { input ->
        tempFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return tempFile
}


