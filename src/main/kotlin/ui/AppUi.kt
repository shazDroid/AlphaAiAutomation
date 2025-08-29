package ui

import CodeBlock
import KottieAnimation
import adb.AdbExecutor
import agent.ActionPlan
import agent.AgentRunner
import agent.IntentParser
import agent.LocatorResolver
import agent.Snapshot
import agent.SnapshotStore
import agent.llm.GeminiDisambiguator
import agent.llm.LlmDisambiguator
import agent.vision.DekiYoloClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
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
import model.GenState
import model.UiElement
import ui.component.AlphaButton
import ui.component.AlphaInputText
import ui.component.AlphaInputTextMultiline
import ui.component.AlphaTabRow
import ui.component.ChipPill
import ui.component.GenerationStatusCard
import ui.component.RightCard
import ui.component.TimelineItem
import ui.theme.BLUE
import utils.KottieConstants
import yaml.TestFlow
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/* ---------- Theme accents ---------- */
private val SurfaceBG = Color(0xFFF5F7FF)
private val CardBG = Color(0xFFFFFFFF)
private val Accent = Color(BLUE)
private val Subtle = Color(0xFFE8ECFF)
private val Line = Color(0xFFE3E3E3)

private val LOG_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("hh:mm:ss a")

@Preview
@Composable
fun AppUI() {
    val mapper = ObjectMapper().registerKotlinModule()

    // device & flow
    var devices by remember { mutableStateOf(listOf<String>()) }
    var selectedDevice by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("com.ksaemiratesnbd.android.uat") }
    var uiElements by remember { mutableStateOf(listOf<UiElement>()) }

    // main ui states
    var showAnimation by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // generator outputs & tabs
    var yamlContent by remember { mutableStateOf("") }
    var parsedFlow by remember { mutableStateOf<TestFlow?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var featureFlowName by remember { mutableStateOf("") }
    var isCapturedDone by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("UI Dump", "Base Class", "Platform class", "Step Definitions", "Feature File")
    var baseClassOutput by remember { mutableStateOf("") }
    var platformClassOutput by remember { mutableStateOf("") }
    var stepDefinitionsOutput by remember { mutableStateOf("") }
    var featureFileOutput by remember { mutableStateOf("") }

    // agent states
    var agentTimeline by remember { mutableStateOf<List<Snapshot>>(emptyList()) }
    val agentLogs = remember { mutableStateListOf<String>() }
    var agentStatus by remember { mutableStateOf<String?>(null) }
    var agentTotalSteps by remember { mutableStateOf(0) }
    var agentCompletedSteps by remember { mutableStateOf(0) }
    var isAgentRunning by remember { mutableStateOf(false) }
    var showAgentView by remember { mutableStateOf(false) }
    var genState by remember { mutableStateOf(GenState()) }



    // live screen
    var agentImage by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(isAgentRunning, selectedDevice) {
        while (isAgentRunning && selectedDevice.isNotBlank()) {
            AdbExecutor.screencapPng(selectedDevice)?.let { bytes ->
                agentImage = org.jetbrains.skia.Image.makeFromEncoded(bytes).asImageBitmap()
            }
            delay(100)
        }
    }

    // logs: prepend + auto scroll to top
    val logListState = rememberLazyListState()
    LaunchedEffect(agentLogs.size) {
        if (agentLogs.isNotEmpty()) {
            delay(10)
            logListState.scrollToItem(0)
        }
    }

    // log panel resize via drag
    var logHeight by remember { mutableStateOf(220.dp) }
    val minLogHeight = 120.dp
    val maxLogHeight = 600.dp
    val density = LocalDensity.current
    val dragState = rememberDraggableState { deltaYPx ->
        val dy = with(density) { deltaYPx.toDp() }
        logHeight = (logHeight + dy).coerceIn(minLogHeight, maxLogHeight)
    }

    // animation json
    var animation by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        animation = ({}.javaClass.getResourceAsStream("/drawable/robot_animation.json")
            ?: throw IllegalArgumentException("Resource not found"))
            .bufferedReader().use { it.readText() }
    }
    val composition = rememberKottieComposition(spec = KottieCompositionSpec.File(animation))
    val animationState by animateKottieCompositionAsState(
        composition = composition,
        isPlaying = playing,
        iterations = KottieConstants.IterateForever
    )
    LaunchedEffect(key1 = "intro") {
        scope.launch(Dispatchers.IO) {
            delay(2800L); playing = false
        }
    }

    val disambiguator = remember {
        // Your implementation of LlmDisambiguator (e.g., Gemini-backed)
        // Example: agent.llm.GeminiDisambiguator(ui.OllamaClient)
        null as LlmDisambiguator? // keep null if you don't have one yet
    }

    val reranker = remember {
        // Your implementation of SemanticReranker, or keep null to disable
        // Example: agent.semantic.SemanticReranker(agent.semantic.GeminiEmbedder(OllamaClient.apiKey))
        null as agent.semantic.SemanticReranker?
    }

    /* ===================== LAYOUT ===================== */
    Box(Modifier.fillMaxSize().background(SurfaceBG)) {
        Row(Modifier.fillMaxSize()) {

            /* -------- Left rail (controls) -------- */
            Column(
                modifier = Modifier.weight(0.24f)
                    .fillMaxHeight()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                BrandCard()
                Spacer(Modifier.height(16.dp))

                SectionTitle("Devices")

                Spacer(Modifier.height(8.dp))

                CardBox {
                    Column {
                        if (selectedDevice.isEmpty()) DeviceNotSelectedError {
                            devices = AdbExecutor.listDevices()
                        } else DeviceSelected()

                        Spacer(Modifier.height(8.dp))

                        if (devices.isNotEmpty()) {
                            Text(
                                text = if (selectedDevice.isEmpty()) "Device list" else "Selected: $selectedDevice",
                                fontWeight = MaterialTheme.typography.h6.fontWeight
                            )
                            Spacer(Modifier.height(8.dp))
                            AnimatedVisibility(selectedDevice.isEmpty()) {
                                LazyColumn(Modifier.fillMaxWidth().height(200.dp)) {
                                    items(devices) { item ->
                                        DeviceItem(item) { selectedDevice = it }
                                    }
                                }
                            }
                            AnimatedVisibility(selectedDevice.isNotEmpty()) {
                                AlphaButton(text = "Rescan devices") { selectedDevice = "" }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                /*  AnimatedVisibility(selectedDevice.isNotEmpty() && false) {
                      Column {
                          SectionTitle("YAML editor")
                          Spacer(Modifier.height(8.dp))
                          AlphaInputTextMultiline(
                              value = yamlContent,
                              onValueChange = { yamlContent = it },
                              hint = "Enter YAML flow here",
                              backgroundColor = Subtle
                          )
                          Spacer(Modifier.height(8.dp))
                          AnimatedVisibility(yamlContent.isNotEmpty()) {
                              CaptureUiDump(
                                  isCaptureDone = isCapturedDone,
                                  onClick = {
                                      isCapturedDone = false
                                      scope.launch(Dispatchers.IO) {
                                          // intro loop visual
                                          playing = true
                                          // parse flow
                                          if (yamlContent.isNotBlank()) {
                                              runCatching {
                                                  parsedFlow = YamlFlowLoader.loadFlowFromString(yamlContent)
                                              }
                                          }
                                          // capture & enrich
                                          val xml = UiDumpParser.getUiDumpXml(selectedDevice)
                                          val cleanedXml = cleanUiDumpXml(xml)
                                          val parsed = UiDumpParser.parseUiDump(cleanedXml).toMutableList()
                                          parsedFlow?.flow?.forEach { action ->
                                              if (action.action == "input_text" && !action.target_text.isNullOrEmpty()) {
                                                  UiDumpParser.findEditTextForLabel(cleanedXml, action.target_text)?.let {
                                                      parsed.add(it)
                                                  }
                                              }
                                          }
                                          uiElements = parsed
                                          isCapturedDone = true
                                          playing = false
                                      }
                                  },
                                  onPackageNameChange = { packageName = it },
                                  onFlowFeatureNameChange = { featureFlowName = it }
                              )
                          }
                      }
                  }*/

                Spacer(Modifier.height(16.dp))

                // --- Script generation UI state ---
                AgentComponent(
                    selectedDevice = selectedDevice,
                    packageName = packageName,
                    onShowAgentView = { showAgentView = true; showAnimation = false },
                    onStatus = { agentStatus = it },
                    onLog = { line ->
                        val ts = LocalDateTime.now().format(LOG_TIME_FMT)
                        agentLogs.add(0, "[$ts] $line")
                        if (agentLogs.size > 600) agentLogs.removeLast()
                    },
                    onTimelineUpdate = { list ->
                        agentTimeline = list; agentCompletedSteps = list.size
                    },
                    onRunState = { running, total ->
                        isAgentRunning = running; agentTotalSteps = total
                        if (running) showAgentView = true
                    },
                    isParsingTask = {
                        playing = it; showAnimation = it
                    },
                    onGenReset = { genState = GenState() },
                    onGenStart = { total ->
                        genState = GenState(visible = true, isGenerating = true, total = total, message = "Starting…")
                    },
                    onGenProgress = { step, total, msg ->
                        genState = genState.copy(
                            visible = true,
                            isGenerating = true,
                            step = step,
                            total = total,
                            message = msg,
                            error = null
                        )
                    },
                    onGenDone = { dir ->
                        genState =
                            genState.copy(isGenerating = false, step = genState.total, message = "Done", outDir = dir)
                    },
                    onGenError = { msg ->
                        genState = genState.copy(visible = true, isGenerating = false, error = msg, message = "Failed")
                    },
                    llmDisambiguator = disambiguator,
                    semanticReranker = reranker
                )

                Spacer(Modifier.height(16.dp))
                /* AlphaButton(isLoading = isLoading, text = "Generate with AI") {
                     if (yamlContent.isBlank()) {
                         baseClassOutput = "Please enter YAML flow first."
                         return@AlphaButton
                     }
                     showAnimation = true; playing = true
                     scope.launch(Dispatchers.IO) {
                         isLoading = true
                         try {
                             val full = StringBuilder()
                             val flow = YamlFlowLoader.loadFlowFromString(yamlContent)
                             val (updated, actionable) = TargetMapper.mapTargets(flow, uiElements)
                             parsedFlow = flow
                             baseClassOutput = ""; platformClassOutput = ""; stepDefinitionsOutput =
                                 ""; featureFileOutput = ""
                             val prompt = util.PromptBuilder.buildPrompt(featureFlowName, updated, actionable)

                             OllamaClient.sendPromptStreaming(
                                 prompt,
                                 onChunk = { chunk ->
                                     val t = chunk.trim()
                                     if (t.isNotEmpty()) {
                                         runCatching {
                                             val node = mapper.readTree(t)
                                             val content = node["message"]?.get("content")?.asText() ?: ""
                                             full.append(content)
                                         }
                                     }
                                 },
                                 onComplete = {
                                     playing = false; showAnimation = false; isLoading = false
                                     val out = full.toString()
                                     baseClassOutput =
                                         extractCodeBetweenMarkers(out, "BASE_CLASS_START", "BASE_CLASS_END")
                                     platformClassOutput =
                                         extractCodeBetweenMarkers(out, "PLATFORM_CLASS_START", "PLATFORM_CLASS_END")
                                     stepDefinitionsOutput =
                                         extractCodeBetweenMarkers(out, "STEP_DEFS_START", "STEP_DEFS_END")
                                     featureFileOutput =
                                         extractCodeBetweenMarkers(out, "FEATURE_FILE_START", "FEATURE_FILE_END")
                                 }
                             )
                         } catch (e: Exception) {
                             println("Error: ${e.message}")
                         } finally {
                             isLoading = false
                         }
                     }
                 }*/
            }

            VerticalDivider(modifier = Modifier.padding(vertical = 24.dp), color = Line)

            /* -------- Right content (workspace) -------- */
            Column(modifier = Modifier.weight(0.76f).padding(16.dp)) {
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
                                    modifier = Modifier.size(260.dp),
                                    progress = { animationState.progress },
                                    backgroundColor = Color.Transparent,
                                    contentScale = ContentScale.Fit
                                )
                                Spacer(Modifier.height(18.dp))
                                Text(
                                    text = if (isLoading) "Please wait" else "Welcome to Alpha AI Automation",
                                    fontWeight = MaterialTheme.typography.h6.fontWeight,
                                    fontSize = TextUnit(22f, TextUnitType.Sp)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = if (isLoading) "Generating response..." else "Generate Base, Platform, Steps & Feature via AI",
                                    fontWeight = MaterialTheme.typography.h1.fontWeight,
                                    fontSize = TextUnit(14f, TextUnitType.Sp),
                                    color = Color.DarkGray
                                )
                                Spacer(Modifier.height(16.dp))
                                AnimatedVisibility(isLoading.not()) {
                                    AlphaButton(
                                        modifier = Modifier.fillMaxWidth(0.32f),
                                        text = "Need help? Read documentation"
                                    ) { openPdfFromResources("files/documentation.pdf") }
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

                    LinearProgressIndicator(
                        progress = if (agentTotalSteps > 0) agentCompletedSteps.toFloat() / agentTotalSteps else 0f,
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF6A7BFF),
                        backgroundColor = Color(0xFFE9ECFF)
                    )
                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {

                        // --- Device preview (bigger) ---
                        RightCard(
                            modifier = Modifier.weight(0.52f).height(680.dp),
                            pad = 8.dp
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
                                Spacer(Modifier.height(8.dp))
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        KottieAnimation(
                                            composition = composition,
                                            modifier = Modifier.size(260.dp),
                                            progress = { animationState.progress },
                                            backgroundColor = Color.Transparent,
                                            contentScale = ContentScale.Fit
                                        )

                                        Spacer(Modifier.height(18.dp))

                                        if (playing) {
                                            Text(
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center,
                                                text = "Parsing tasks\nWorking on it...",
                                                color = Color.Gray,
                                                fontWeight = MaterialTheme.typography.body1.fontWeight
                                            )
                                        } else {
                                            Text(
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center,
                                                text = "Parsing Task\nDone now you can run the agent",
                                                color = Color.Gray,
                                                fontWeight = MaterialTheme.typography.body1.fontWeight
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.width(16.dp))

                        // --- Right column: chips + timeline ---
                        Column(Modifier.weight(0.40f)) {

                            // chips row like the screenshot
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ChipPill("Done", agentCompletedSteps, Color(0xFF4CAF50))
                                Spacer(Modifier.width(8.dp))
                                ChipPill("Total", agentTotalSteps, Color(0xFF2962FF))
                                Spacer(Modifier.width(8.dp))
                                ChipPill("Fail", agentTimeline.count { !it.success }, Color(0xFFFF5252))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (agentTotalSteps > 0) "Done: $agentCompletedSteps/$agentTotalSteps steps OK"
                                else (agentStatus ?: ""),
                                color = Color.Gray
                            )

                            Spacer(Modifier.height(12.dp))
                            Text("Timeline", fontWeight = MaterialTheme.typography.h6.fontWeight)
                            Spacer(Modifier.height(6.dp))

                            RightCard(modifier = Modifier.height(260.dp), pad = 10.dp) {
                                if (agentTimeline.isEmpty()) {
                                    Text(
                                        modifier = Modifier.fillMaxSize(),
                                        text = "Waiting for steps…",
                                        color = Color.Gray
                                    )
                                } else {
                                    LazyColumn {
                                        items(agentTimeline) { snap ->
                                            TimelineItem(snap)
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            if (genState.visible) {
                                Spacer(Modifier.height(8.dp))
                                GenerationStatusCard(
                                    isGenerating = genState.isGenerating,
                                    step = genState.step,
                                    total = genState.total,
                                    message = genState.message,
                                    error = genState.error,
                                    outDir = genState.outDir
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // --- Logs header row (buttons at right) ---
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Agent logs", fontWeight = MaterialTheme.typography.h6.fontWeight)
                        Spacer(Modifier.weight(1f))
                        AlphaButton(modifier = Modifier.width(120.dp), text = "Copy all") {
                            val sel = java.awt.datatransfer.StringSelection(agentLogs.joinToString("\n"))
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                        }
                        Spacer(Modifier.width(8.dp))
                        AlphaButton(modifier = Modifier.width(120.dp), text = "Clear logs") {
                            agentLogs.clear()
                            agentTimeline = emptyList()
                            agentCompletedSteps = 0
                            agentTotalSteps = 0
                            agentStatus = null
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    // --- Drag handle (thicker, centered) ---
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                            .background(Color(0xFF2A2A2A), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            .draggable(
                                state = dragState,
                                orientation = Orientation.Vertical
                            )
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .width(56.dp)
                                .height(4.dp)
                                .background(Color(0xFFBDBDBD), RoundedCornerShape(2.dp))
                        )
                    }

// --- Dark logs panel (prepend + auto-scroll-to-top already in your code) ---
                    Box(
                        Modifier.fillMaxWidth().height(logHeight)
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                            .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                            .padding(10.dp)
                    ) {
                        if (agentLogs.isEmpty()) {
                            Text("Logs will appear here…", color = Color.Gray)
                        } else {
                            SelectionContainer {
                                LazyColumn(state = logListState) {
                                    items(agentLogs) { line ->
                                        Text(
                                            util.UiHelper.styleLogLine(line),
                                            fontSize = TextUnit(12f, TextUnitType.Sp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- Small composables ---------------- */

@Composable
private fun BrandCard() {
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(Accent, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                modifier = Modifier.size(56.dp),
                painter = painterResource("drawable/app_icon.png"),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Alpha Automation", color = Color.White, fontWeight = MaterialTheme.typography.h6.fontWeight)
                Spacer(Modifier.height(2.dp))
                Text(
                    "developer Shahbaz Ansari",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = TextUnit(12f, TextUnitType.Sp)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = MaterialTheme.typography.h6.fontWeight)
}

@Composable
private fun CardBox(
    modifier: Modifier = Modifier,
    pad: Dp = 12.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(CardBG, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(pad)
    ) { content() }
}

@Composable
private fun StatChip(label: String, value: Int, tint: Color) {
    Box(
        Modifier.background(tint.copy(alpha = .12f), RoundedCornerShape(999.dp))
            .border(1.dp, tint.copy(alpha = .35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(tint, RoundedCornerShape(999.dp)))
            Spacer(Modifier.width(6.dp))
            Text("$label: $value", color = Color(0xFF222222))
        }
    }
}


@Preview
@Composable
fun DeviceNotSelectedError(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painter = painterResource("drawable/warning.svg"), contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Column {
                Text("No device selected!", fontWeight = MaterialTheme.typography.h6.fontWeight)
                Text("Please select device to proceed", fontSize = TextUnit(12f, TextUnitType.Sp))
            }
        }
        Spacer(Modifier.height(8.dp))
        AlphaButton(text = "Scan devices") { onClick.invoke() }
    }
}

@Preview
@Composable
fun DeviceSelected() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painter = painterResource("drawable/device.svg"), contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Column {
            Text("Device selected!", fontWeight = MaterialTheme.typography.h6.fontWeight)
            Text("You can proceed now", fontSize = TextUnit(12f, TextUnitType.Sp))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DeviceItem(deviceName: String, onDeviceSelected: (String) -> Unit) {
    var isActive by remember { mutableStateOf(false) }
    var isSelected by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxWidth()
            .background(color = if (isSelected) Color(BLUE) else Subtle, shape = RoundedCornerShape(8.dp))
            .onPointerEvent(PointerEventType.Enter) { isActive = true }
            .onPointerEvent(PointerEventType.Exit) { isActive = false }
            .onPointerEvent(PointerEventType.Press) {
                isSelected = true; onDeviceSelected.invoke(deviceName)
            }
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                modifier = Modifier.weight(0.8f).padding(8.dp),
                text = deviceName,
                fontSize = TextUnit(16f, TextUnitType.Sp),
                color = if (isSelected) Color.White else Color.DarkGray
            )
            AnimatedVisibility(isActive) {
                Image(
                    painter = painterResource("drawable/arrow_right.svg"),
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp).size(24.dp),
                    colorFilter = ColorFilter.tint(if (isSelected) Color.White else Color.DarkGray)
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

    CardBox {
        Column {
            var pkg by remember { mutableStateOf("com.shazdroid.messapp") }
            var flow by remember { mutableStateOf("Login") }

            PackageInput(hint = "Package Name") {
                pkg = it; onPackageNameChange.invoke(it)
            }
            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(pkg.isNotEmpty()) {
                FlowInput(hint = "Feature Flow Name") { name ->
                    flow = name; onFlowFeatureNameChange.invoke(name)
                }
            }
            Spacer(Modifier.height(8.dp))

            AnimatedVisibility(isCaptureDone) {
                isLoading = false
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource("drawable/success.svg"),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Captured UI Dump",
                            fontWeight = MaterialTheme.typography.h6.fontWeight,
                            modifier = Modifier.weight(1f)
                        )
                        Image(
                            painter = painterResource("drawable/restart.svg"),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp).clickable { onClick.invoke() }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            AnimatedVisibility(flow.isNotEmpty()) {
                AnimatedVisibility(isCaptureDone.not()) {
                    AlphaButton(isLoading = isLoading, text = "Capture UI Dump") {
                        isLoading = true; onClick.invoke()
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
        backgroundColor = Subtle,
        value = text.value,
        onValueChange = { v -> text.value = v; onTextChange(v) },
        hint = hint
    )
}

@Composable
fun FlowInput(hint: String, onTextChange: (String) -> Unit) {
    val text = remember { mutableStateOf("") }
    AlphaInputText(
        backgroundColor = Subtle,
        value = text.value,
        onValueChange = { v -> text.value = v; onTextChange(v) },
        hint = hint
    )
}

@Composable
fun VerticalDivider(
    modifier: Modifier = Modifier,
    color: Color = Color.LightGray,
    thickness: Dp = 1.dp,
    height: Dp = Dp.Unspecified
) {
    Box(
        modifier = modifier
            .width(thickness)
            .then(if (height == Dp.Unspecified) Modifier.fillMaxHeight() else Modifier.height(height))
            .background(color)
    )
}

fun openPdfFromResources(resourcePath: String) {
    val inputStream = object {}.javaClass.classLoader.getResourceAsStream(resourcePath)
    if (inputStream != null) {
        val tempFile = File.createTempFile("documentation", ".pdf")
        tempFile.deleteOnExit()
        FileOutputStream(tempFile).use { output -> inputStream.copyTo(output) }
        Desktop.getDesktop().open(tempFile)
    } else println("❌ PDF resource not found at $resourcePath")
}

/* ---------------- Agent block ---------------- */

@Composable
fun AgentComponent(
    selectedDevice: String,
    packageName: String,
    onShowAgentView: () -> Unit,
    onStatus: (String) -> Unit,
    onLog: (String) -> Unit,
    onTimelineUpdate: (List<Snapshot>) -> Unit,
    onRunState: (running: Boolean, totalSteps: Int) -> Unit,
    isParsingTask: (Boolean) -> Unit,
    onGenReset: () -> Unit,
    onGenStart: (total: Int) -> Unit,
    onGenProgress: (step: Int, total: Int, msg: String) -> Unit,
    onGenDone: (outDir: File) -> Unit,
    onGenError: (msg: String) -> Unit,
    llmDisambiguator: LlmDisambiguator? = null,
    semanticReranker: agent.semantic.SemanticReranker? = null
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

    // Run/stop plumbing
    var isRunActive by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var agentJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var driverRef by remember { mutableStateOf<io.appium.java_client.android.AndroidDriver?>(null) }
    var lastPlanSteps by remember { mutableStateOf(0) }

    val semanticReranker = remember {
        agent.semantic.SemanticReranker(
            agent.semantic.GeminiEmbedder(ui.OllamaClient.apiKey)
        )
    }

    CardBox {
        Column {
            SectionTitle("Agent (Natural Language)")
            Spacer(Modifier.height(8.dp))

            AlphaInputText(backgroundColor = Subtle, value = packageName, onValueChange = {}, hint = "App package")
            Spacer(Modifier.height(8.dp))
            AlphaInputText(
                backgroundColor = Subtle,
                value = appActivity,
                onValueChange = { appActivity = it },
                hint = "Main activity"
            )
            Spacer(Modifier.height(12.dp))

            AlphaInputTextMultiline(
                value = nlTask,
                onValueChange = { nlTask = it },
                hint = "Describe the task…",
                backgroundColor = Subtle
            )

            Spacer(Modifier.height(12.dp))

            Row {
                AlphaButton(text = "Parse Task", isLoading = isParsing) {
                    if (nlTask.isBlank()) {
                        isParsingTask(false)
                        onStatus("Please enter a task.")
                        return@AlphaButton
                    }
                    isParsingTask(true)
                    onShowAgentView()
                    isParsing = true
                    onStatus("Parsing task…")
                    scope.launch(Dispatchers.IO) {
                        try {
                            val p = intentParser.parse(nlTask, packageName)
                            val fixed = p.copy(steps = p.steps.mapIndexed { i, s -> s.copy(index = i + 1) })
                            plan = fixed
                            onStatus("Parsed ${fixed.steps.size} steps.")
                            onLog("Parsed plan: ${fixed.steps.joinToString { "${it.index}:${it.type}" }}")
                            isParsingTask(false)
                        } catch (e: Exception) {
                            onStatus("Parse error: ${e.message}")
                            onLog("Parse error: $e")
                        } finally {
                            isParsing = false
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row {
                // RUN
                AlphaButton(text = "Run agent") {
                    if (isRunActive) {
                        onStatus("Run already in progress…"); return@AlphaButton
                    }
                    if (selectedDevice.isBlank()) { onStatus("Select a device first."); onLog("Preflight: no device"); return@AlphaButton }
                    if (!util.AppiumHealth.isReachable()) { onStatus("❌ Appium not reachable at 127.0.0.1:4723"); onLog("Preflight: appium down"); return@AlphaButton }
                    if (!adb.AdbExecutor.isPackageInstalled(selectedDevice, packageName)) { onStatus("❌ Package $packageName not installed"); onLog("Preflight: package missing"); return@AlphaButton }

                    val p = plan ?: run { onStatus("Parse the task first."); return@AlphaButton }

                    stopRequested = false
                    isRunActive = true
                    lastPlanSteps = p.steps.size
                    onShowAgentView()
                    onRunState(true, p.steps.size)
                    onStatus("Starting driver/session…")
                    timeline = emptyList()
                    onTimelineUpdate(timeline)

                    agentJob = scope.launch(Dispatchers.IO) {
                        try {
                            val driver = appium.DriverFactory.startAndroid(
                                udid = selectedDevice,
                                appPackage = packageName,
                                appActivity = appActivity
                            )
                            driverRef = driver

                            val resolver = LocatorResolver(driver, onLog)
                            val store = SnapshotStore(driver, File("runs/${System.currentTimeMillis()}"))
                            val dekiClient = DekiYoloClient()

                            val gemini = GeminiDisambiguator(
                                apiKey = System.getenv("GEMINI_API_KEY") ?: "AIzaSyBAB1n3XuO7Ra1wfrZNXPTWJRigDNvPtbE",
                                model = System.getenv("GEMINI_MODEL") ?: "gemini-2.5-flash-lite"
                            )

                            val embedder = agent.semantic.GeminiEmbedder("AIzaSyBAB1n3XuO7Ra1wfrZNXPTWJRigDNvPtbE")
                            val reranker = agent.semantic.SemanticReranker(embedder)

                            val result = AgentRunner(
                                driver = driver,
                                resolver = resolver,
                                store = store,
                                llmDisambiguator = gemini,
                                semanticReranker = reranker,
                                deki = dekiClient
                            ).run(
                                plan = p,
                                onStep = { snap ->
                                    timeline = timeline + snap
                                    onTimelineUpdate(timeline)
                                    onLog("Step ${snap.stepIndex} ${snap.action} -> ${if (snap.success) "OK" else "FAIL"}")
                                    onLog("  xml: ${snap.pageSourcePath}")
                                    onLog("  png: ${snap.screenshotPath}")
                                },
                                onLog = onLog,
                                onStatus = onStatus,
                                stopSignal = { stopRequested }
                            )
                            onStatus("Done: ${result.count { it.success }}/${result.size} steps OK")
                        } catch (e: Exception) {
                            if (stopRequested) {
                                onStatus("⏹️ Stopped by user")
                                onLog("⏹️ Stopped by user")
                            } else {
                                onStatus("❌ Agent error: ${e.message}")
                                onLog("Agent error: $e")
                            }
                        } finally {
                            runCatching { driverRef?.quit() }
                            driverRef = null
                            onRunState(false, lastPlanSteps)
                            isRunActive = false
                            stopRequested = false
                            agentJob = null
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Generate with AI
                AlphaButton(text = "Generate with AI") {
                    if (isRunActive) {
                        onStatus("Please stop the run first."); return@AlphaButton
                    }
                    val p = plan ?: run { onStatus("Parse the task first."); return@AlphaButton }
                    if (timeline.isEmpty()) { onStatus("Run the agent first."); return@AlphaButton }
                    onShowAgentView()
                    onStatus("Generating scripts with AI…")
                    scope.launch(Dispatchers.IO) {
                        try {
                            LlmScriptGenerator(OllamaClient, File(outputDir)).generate(p, timeline)
                            onStatus("Scripts written to $outputDir")
                        } catch (e: Exception) {
                            onStatus("Generation error: ${e.message}")
                            onLog("Generation error: $e")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // STOP — always visible so it's never "missing"
            AnimatedVisibility(isRunActive) {
                AlphaButton(text = "Stop") {
                    if (!isRunActive) {
                        onStatus("Nothing to stop.")
                        return@AlphaButton
                    }
                    onStatus("Stopping…")
                    stopRequested = true
                    runCatching { driverRef?.quit() }
                    driverRef = null
                    agentJob?.cancel()
                    isRunActive = false
                    onRunState(false, lastPlanSteps)
                }
            }

            Spacer(Modifier.height(8.dp))

            Row {
                AlphaButton(text = "Generate scripts", isLoading = isGenerating) {
                    if (isRunActive) {
                        onStatus("Please stop the run first."); return@AlphaButton
                    }
                    val p = plan ?: run { onStatus("Parse the task first."); return@AlphaButton }
                    if (timeline.isEmpty()) {
                        onStatus("Run the agent first."); return@AlphaButton
                    }

                    onShowAgentView()
                    onStatus("Generating scripts with AI…")
                    onGenReset()
                    onGenStart(6)
                    isGenerating = true

                    scope.launch(Dispatchers.IO) {
                        try {
                            val out = LlmScriptGenerator(OllamaClient, File(outputDir))
                                .generate(p, timeline) { step, total, msg ->
                                    onGenProgress(step, total, msg)
                                }
                            onGenDone(out)
                            onStatus("✅ Scripts written to ${out.absolutePath}")
                        } catch (e: Exception) {
                            onGenError(e.message.toString())
                            onStatus("Generation error: ${e.message}")
                            onLog("Generation error: $e")
                        } finally {
                            isGenerating = false
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            AlphaInputText(
                backgroundColor = Subtle,
                value = outputDir,
                onValueChange = { outputDir = it },
                hint = "Output folder"
            )
        }
    }
}



