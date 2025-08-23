package ui

import CodeBlock
import KottieAnimation
import adb.AdbExecutor
import adb.UiDumpParser
import adb.UiDumpParser.cleanUiDumpXml
import agent.ActionPlan
import agent.AgentRunner
import agent.IntentParser
import agent.LocatorResolver
import agent.Snapshot
import agent.SnapshotStore
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import appium.DriverFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import contentScale.ContentScale
import generator.LlmScriptGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kottieComposition.KottieCompositionSpec
import kottieComposition.animateKottieCompositionAsState
import kottieComposition.rememberKottieComposition
import model.UiElement
import ui.component.AlphaButton
import ui.component.AlphaInputText
import ui.component.AlphaInputTextMultiline
import ui.component.AlphaTabRow
import ui.theme.BLUE
import util.PromptBuilder
import util.TargetMapper
import util.extractCodeBetweenMarkers
import utils.KottieConstants
import yaml.TestFlow
import yaml.YamlFlowLoader
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream

@Preview
@Composable
fun AppUI() {
    val mapper = ObjectMapper().registerKotlinModule()
    var devices by remember { mutableStateOf(listOf<String>()) }
    var selectedDevice by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("com.ksaemiratesnbd.android.uat") }
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
    var animation by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    var parsingUiDump by remember { mutableStateOf(false) }

    var playing by remember { mutableStateOf(true) }

    var agentTimeline by remember { mutableStateOf<List<Snapshot>>(emptyList()) }
    val agentLogs = remember { mutableStateListOf<String>() }
    var agentStatus by remember { mutableStateOf<String?>(null) }
    var agentTotalSteps by remember { mutableStateOf(0) }
    var agentCompletedSteps by remember { mutableStateOf(0) }
    var isAgentRunning by remember { mutableStateOf(false) }
    var showAgentView by remember { mutableStateOf(false) }

    // live screen
    var agentImage by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(isAgentRunning, selectedDevice) {
        while (isAgentRunning && selectedDevice.isNotBlank()) {
            AdbExecutor.screencapPng(selectedDevice)?.let { bytes ->
                val img = org.jetbrains.skia.Image.makeFromEncoded(bytes).asImageBitmap()
                agentImage = img
            }
            delay(700)
        }
    }


    LaunchedEffect(Unit) {
        animation = ({}.javaClass.getResourceAsStream("/drawable/robot_animation.json")
            ?: throw IllegalArgumentException("Resource not found")).bufferedReader().use { it.readText() }
    }

    val composition = rememberKottieComposition(
        spec = KottieCompositionSpec.File(animation) // Or KottieCompositionSpec.Url || KottieCompositionSpec.JsonString
    )

    val animationState by animateKottieCompositionAsState(
        composition = composition,
        isPlaying = playing,
        iterations = KottieConstants.IterateForever
    )

    LaunchedEffect(key1 = "1") {
        scope.launch(Dispatchers.IO) {
            delay(4000L)
            playing = false
        }
    }

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
                        painter = painterResource("drawable/app_icon.png"),
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
                Text(text = "Yaml Editor", fontWeight = MaterialTheme.typography.h6.fontWeight)
            }

            Spacer(modifier = Modifier.height(8.dp))

            AnimatedVisibility(selectedDevice.isNotEmpty()) {
                AlphaInputTextMultiline(
                    value = yamlContent,
                    onValueChange = { yamlContent = it },
                    hint = "Enter YAML flow here",
                    backgroundColor = Color(0xFFEDF3FF)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            AnimatedVisibility(yamlContent.isNotEmpty()) {
                CaptureUiDump(isCaptureDone = isCapturedDone, onClick = {
                    isCapturedDone = false
                    scope.launch(Dispatchers.IO) {
                        playing = true
                        // ✅ Parse YAML here to initialize parsedFlow
                        if (yamlContent.isNotBlank()) {
                            try {
                                val flow = YamlFlowLoader.loadFlowFromString(yamlContent)
                                parsedFlow = flow
                                println("Parsed flow loaded before UI dump: $parsedFlow")
                            } catch (e: Exception) {
                                println("YAML parsing error: ${e.message}")
                            }
                        } else {
                            println("YAML content is blank. parsedFlow remains null.")
                        }

                        val xml = UiDumpParser.getUiDumpXml(selectedDevice)
                        println("XML Dump: \n $xml")

                        val cleanedXml = cleanUiDumpXml(xml)
                        val parsed = UiDumpParser.parseUiDump(cleanedXml).toMutableList()

                        // Dynamic resolution for text_input actions using parsedFlow
                        parsedFlow?.flow?.forEach { action ->
                            if (action.action == "input_text" && !action.target_text.isNullOrEmpty()) {
                                val editText = UiDumpParser.findEditTextForLabel(cleanedXml, action.target_text)
                                if (editText != null) {
                                    println("Resolved EditText for target '${action.target_text}': $editText")
                                    parsed.add(editText)
                                } else {
                                    println("No EditText found for target '${action.target_text}'")
                                }
                            }
                        }

                        uiElements = parsed
                        println("UI Dump (updated): \n$uiElements")
                        isCapturedDone = true
                        playing = false
                    }
                }, onPackageNameChange = {
                    packageName = it
                }, onFlowFeatureNameChange = {
                    featureFlowName = it
                })
            }



            Spacer(modifier = Modifier.height(16.dp))

            AgentComponent(
                selectedDevice = selectedDevice,
                packageName = packageName,
                onShowAgentView = { showAgentView = true; showAnimation = false },
                onStatus = { s -> agentStatus = s },
                onLog = { line ->
                    val ts = java.time.LocalTime.now().toString().substring(0, 8)
                    agentLogs.add(0, "[$ts] $line")
                    if (agentLogs.size > 400) agentLogs.removeLast()
                },
                onTimelineUpdate = { list ->
                    agentTimeline = list
                    agentCompletedSteps = list.size
                },
                onRunState = { running, total ->
                    isAgentRunning = running
                    agentTotalSteps = total
                    if (running) showAgentView = true
                }
            )


            Spacer(modifier = Modifier.height(16.dp))

            AlphaButton(isLoading = isLoading, text = "Generate with AI", onClick = {
                showAnimation = true
                playing = true

                if (yamlContent.isBlank()) {
                    baseClassOutput = "Please enter YAML flow first."
                    return@AlphaButton
                }

                scope.launch(Dispatchers.IO) {
                    isLoading = true

                    try {
                        val fullOutput = StringBuilder()


                        var flow = YamlFlowLoader.loadFlowFromString(yamlContent)
                        val (updatedFlow, actionableElements) = TargetMapper.mapTargets(flow, uiElements)

                        parsedFlow = flow


                        baseClassOutput = ""
                        platformClassOutput = ""
                        stepDefinitionsOutput = ""
                        featureFileOutput = ""

                        val prompt = PromptBuilder.buildPrompt(featureFlowName, updatedFlow, actionableElements)


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
                                        println("Chunk parsing issue: ${e.message}")
                                    }
                                }
                            },
                            onComplete = {
                                playing = false
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

        // Divider
        VerticalDivider(modifier = Modifier.padding(vertical = 24.dp), color = Color(0xffe3e3e3))

        // Right Pane

        Column(
            modifier = Modifier.weight(0.6f).padding(16.dp)
        ) {
            val showAgent = showAgentView || isAgentRunning || agentTimeline.isNotEmpty()

            if (!showAgent) {
                if (showAnimation) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            KottieAnimation(
                                composition = composition,
                                modifier = Modifier.size(300.dp),
                                progress = {
                                    animationState.progress
                                },
                                backgroundColor = Color.Transparent,
                                contentScale = ContentScale.Fit
                            )
                            Spacer(modifier = Modifier.size(26.dp))
                            Text(
                                text = if (isLoading) "Please wait" else "Welcome to Alpha AI Automation",
                                fontWeight = MaterialTheme.typography.h6.fontWeight,
                                fontSize = TextUnit(22f, TextUnitType.Sp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(
                                text = if (isLoading) "Generating response..." else "Generate BaseClass, Platform, StepsDefs, Feature file with AI",
                                fontWeight = MaterialTheme.typography.h1.fontWeight,
                                fontSize = TextUnit(14f, TextUnitType.Sp)
                            )
                            Spacer(modifier = Modifier.size(16.dp))
                            AnimatedVisibility(isLoading.not()) {
                                AlphaButton(
                                    modifier = Modifier.fillMaxWidth(fraction = 0.3f),
                                    text = "Need help, read documentation"
                                ) {
                                    openPdfFromResources("files/documentation.pdf")
                                }
                            }
                        }
                    }
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
            } else {
                // ==== AGENT VIEW ====
                Text("Agent run", fontWeight = MaterialTheme.typography.h6.fontWeight)
                Spacer(Modifier.height(8.dp))

                // top row: live device + progress
                Row(Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier.weight(0.55f)
                            .height(360.dp)
                            .background(Color(0xFFF8F8F8), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFFE3E3E3), RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    ) {
                        if (agentImage != null) {
                            Image(
                                bitmap = agentImage!!,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                        } else {
                            Text("Live screen will appear here…", color = Color.Gray)
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(0.45f)) {
                        val progress = if (agentTotalSteps > 0) agentCompletedSteps.toFloat() / agentTotalSteps else 0f
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("Progress: $agentCompletedSteps / $agentTotalSteps")
                        Spacer(Modifier.height(4.dp))
                        Text(agentStatus ?: "", color = Color.Gray)
                        Spacer(Modifier.height(12.dp))

                        Text("Timeline", fontWeight = MaterialTheme.typography.h6.fontWeight)
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier.fillMaxWidth().height(180.dp)
                                .background(Color.White, RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFFE3E3E3), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            if (agentTimeline.isEmpty()) {
                                Text("Waiting for steps…", color = Color.Gray)
                            } else {
                                LazyColumn {
                                    items(agentTimeline) { snap ->
                                        val ok = if (snap.success) "✅" else "❌"
                                        Text("$ok #${snap.stepIndex} ${snap.action} ${snap.targetHint ?: ""}")
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Agent logs", fontWeight = MaterialTheme.typography.h6.fontWeight)
                    Spacer(Modifier.weight(1f))
                    AlphaButton(text = "Clear logs", modifier = Modifier.width(120.dp)) {
                        agentLogs.clear()        // ← wipes the live log list
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().height(180.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFE3E3E3), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (agentLogs.isEmpty()) {
                        Text("Logs will appear here…", color = Color.Gray)
                    } else {
                        LazyColumn {
                            items(agentLogs) { line ->
                                Text(line, fontSize = TextUnit(12f, TextUnitType.Sp))
                            }
                        }
                    }
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


@Composable
fun VerticalDivider(
    modifier: Modifier = Modifier,
    color: Color = Color.LightGray,
    thickness: Dp = 1.dp,
    height: Dp = Dp.Unspecified, // Use fillMaxHeight if unspecified
) {
    Box(
        modifier = modifier
            .width(thickness)
            .then(
                if (height == Dp.Unspecified) Modifier.fillMaxHeight()
                else Modifier.height(height)
            )
            .background(color)
    )
}

fun openPdfFromResources(resourcePath: String) {
    val inputStream = object {}.javaClass.classLoader.getResourceAsStream(resourcePath)
    if (inputStream != null) {
        // Create a temp file
        val tempFile = File.createTempFile("documentation", ".pdf")
        tempFile.deleteOnExit()

        // Write resource to temp file
        FileOutputStream(tempFile).use { output ->
            inputStream.copyTo(output)
        }

        // Open the PDF using default viewer
        Desktop.getDesktop().open(tempFile)
    } else {
        println("❌ PDF resource not found at $resourcePath")
    }
}

@Composable
fun AgentComponent(
    selectedDevice: String,
    packageName: String,
    onShowAgentView: () -> Unit,
    onStatus: (String) -> Unit,
    onLog: (String) -> Unit,
    onTimelineUpdate: (List<Snapshot>) -> Unit,
    onRunState: (running: Boolean, totalSteps: Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    var nlTask by remember { mutableStateOf("") }
    var appActivity by remember { mutableStateOf("com.emiratesnbd.uat.MainActivity") }
    var plan by remember { mutableStateOf<ActionPlan?>(null) }
    var timeline by remember { mutableStateOf<List<Snapshot>>(emptyList()) }
    var outputDir by remember { mutableStateOf("automation-output") }
    var isParsing by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }

    val intentParser = remember { IntentParser() }

    Box(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFFEDF3FF), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column {
            Text("Agent (Natural Language)", fontWeight = MaterialTheme.typography.h6.fontWeight)
            Spacer(Modifier.height(8.dp))

            AlphaInputText(value = packageName, onValueChange = {}, hint = "App package")
            Spacer(Modifier.height(8.dp))
            AlphaInputText(value = appActivity, onValueChange = { appActivity = it }, hint = "Main activity")

            Spacer(Modifier.height(12.dp))
            AlphaInputTextMultiline(
                value = nlTask,
                onValueChange = { nlTask = it },
                hint = "Describe the task…",
                backgroundColor = Color.White
            )

            Spacer(Modifier.height(12.dp))

            Row {
                AlphaButton(text = "Parse Task", isLoading = isParsing, onClick = {
                    if (nlTask.isBlank()) {
                        onStatus("Please enter a task."); return@AlphaButton
                    }
                    onShowAgentView()
                    isParsing = true; onStatus("Parsing task…")
                    scope.launch(Dispatchers.IO) {
                        try {
                            val p = intentParser.parse(nlTask)
                            // normalize indices
                            val fixed = p.copy(steps = p.steps.mapIndexed { i, s -> s.copy(index = i + 1) })
                            plan = fixed
                            onStatus("Parsed ${fixed.steps.size} steps.")
                            onLog("Parsed plan: ${fixed.steps.joinToString { "${it.index}:${it.type}" }}")
                        } catch (e: Exception) {
                            onStatus("Parse error: ${e.message}")
                            onLog("Parse error: ${e}")
                        } finally {
                            isParsing = false
                        }
                    }
                })
            }
            Spacer(Modifier.height(8.dp))
            Row {
                AlphaButton(text = "Run agent", onClick = {
                    if (selectedDevice.isBlank()) { onStatus("Select a device first."); onLog("Preflight: no device"); return@AlphaButton }
                    if (!util.AppiumHealth.isReachable()) { onStatus("❌ Appium not reachable at 127.0.0.1:4723"); onLog("Preflight: appium down"); return@AlphaButton }
                    if (!adb.AdbExecutor.isPackageInstalled(selectedDevice, packageName)) { onStatus("❌ Package $packageName not installed"); onLog("Preflight: package missing"); return@AlphaButton }

                    onShowAgentView()
                    val p = plan ?: run { onStatus("Parse the task first."); return@AlphaButton }
                    onRunState(true, p.steps.size)
                    onStatus("Starting driver/session…")
                    timeline = emptyList(); onTimelineUpdate(timeline)

                    scope.launch(Dispatchers.IO) {
                        try {
                            val driver = appium.DriverFactory.startAndroid(
                                udid = selectedDevice,
                                appPackage = packageName,
                                appActivity = appActivity
                            )
                            val resolver = LocatorResolver(driver, onLog)
                            val store = SnapshotStore(driver, File("runs/${System.currentTimeMillis()}"))

                            val result = AgentRunner(driver, resolver, store).run(
                                plan = p,
                                onStep = { snap ->
                                    timeline = timeline + snap
                                    onTimelineUpdate(timeline)
                                    onLog("Step ${snap.stepIndex} ${snap.action} -> ${if (snap.success) "OK" else "FAIL"}")
                                    onLog("  xml: ${snap.pageSourcePath}")
                                    onLog("  png: ${snap.screenshotPath}")
                                }
                                ,
                                onLog = onLog,
                                onStatus = onStatus
                            )

                            onStatus("Done: ${result.count { it.success }}/${result.size} steps OK")
                            driver.quit()
                        } catch (e: Exception) {
                            onStatus("❌ Agent error: ${e.message}")
                            onLog("Agent error: $e")
                        } finally {
                            onRunState(false, p.steps.size)
                        }
                    }
                })

                Spacer(Modifier.width(8.dp))

                AlphaButton(text = "Generate with AI", onClick = {
                    val p = plan ?: run { onStatus("Parse the task first."); return@AlphaButton }
                    if (timeline.isEmpty()) { onStatus("Run the agent first."); return@AlphaButton }
                    onShowAgentView(); onStatus("Generating scripts with AI…")
                    scope.launch(Dispatchers.IO) {
                        try { generator.LlmScriptGenerator(OllamaClient, java.io.File(outputDir)).generate(p, timeline)
                            onStatus("Scripts written to $outputDir") }
                        catch (e: Exception) { onStatus("Generation error: ${e.message}"); onLog("Generation error: $e") }
                    }
                })
            }

            Spacer(Modifier.height(8.dp))

            Row {
                AlphaButton(text = "Generate scripts", isLoading = isGenerating, onClick = {
                    val p = plan ?: run { onStatus("Parse the task first."); return@AlphaButton }
                    if (timeline.isEmpty()) {
                        onStatus("Run the agent first."); return@AlphaButton
                    }
                    onShowAgentView()
                    onStatus("Generating scripts with AI…")
                    isGenerating = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            LlmScriptGenerator(OllamaClient, File(outputDir))
                                .generate(p, timeline)
                            onStatus("✅ Scripts written to $outputDir")
                        } catch (e: Exception) {
                            onStatus("Generation error: ${e.message}")
                            onLog("Generation error: $e")
                        } finally {
                            isGenerating = false
                        }
                    }
                })
            }

            Spacer(Modifier.height(8.dp))
            AlphaInputText(value = outputDir, onValueChange = { outputDir = it }, hint = "Output folder")
        }
    }
}




