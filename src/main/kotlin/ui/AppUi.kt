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
import agent.memory.ComponentMemory
import agent.memory.ComponentMemoryAdapter
import agent.memory.MemActivity
import agent.memory.MemApp
import agent.memory.MemEntry
import agent.memory.MemIndex
import agent.memory.MemSelector
import agent.vision.DekiYoloClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
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
import androidx.compose.ui.geometry.Offset
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
import ui.component.*
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

// ----------------------- Memory browser model + loader -----------------------

// --- keep your data classes as-is ---

private fun flattenMemIndex(index: MemIndex): List<MemEntry> =
    index.apps.flatMap { app ->
        app.activities.flatMap { act ->
            act.entries.map {
                it.copy(
                    appPkg = app.name,
                    activity = act.name
                )
            }
        }
    }
        .sortedWith(compareBy({ it.appPkg }, { it.activity }, { it.op }, { it.hint.lowercase() }))

private fun loadSelectorMemoryIndex(root: File = File("memory")): MemIndex {
    val absRoot = root.absoluteFile
    println("mem: root=${absRoot} exists=${absRoot.exists()} isDir=${absRoot.isDirectory}")

    val appsByPkg = linkedMapOf<String, MutableMap<String, MutableList<MemEntry>>>() // pkg -> (activity -> entries)
    var entryCount = 0
    var selectorCount = 0

    fun mergeEntry(e: MemEntry) {
        val acts = appsByPkg.getOrPut(e.appPkg) { linkedMapOf() }
        val list = acts.getOrPut(e.activity) { mutableListOf() }
        val existing = list.firstOrNull { it.op == e.op && it.hint == e.hint }
        if (existing == null) {
            list += e
            entryCount++
            selectorCount += e.selectors.size
        } else {
            val merged = (existing.selectors + e.selectors)
                .groupBy { it.strategy to it.value }
                .map { (_, ss) ->
                    val ok = ss.sumOf { s -> s.ok }
                    val fail = ss.sumOf { s -> s.fail }
                    val last = ss.maxOfOrNull { s -> s.last } ?: 0L
                    MemSelector(ss.first().strategy, ss.first().value, ok, fail, last)
                }
            val idx = list.indexOf(existing)
            list[idx] = existing.copy(selectors = merged)
            selectorCount += (merged.size - existing.selectors.size).coerceAtLeast(0)
        }
    }

    // ---------- JSON: components.json (preferred) + component.json (legacy) ----------
    val jsonCandidates = listOf("components.json", "component.json")
        .map { File(absRoot, it) }
        .filter { it.isFile }

    if (jsonCandidates.isNotEmpty()) {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerKotlinModule()

        jsonCandidates.forEach { jf ->
            println("mem: reading ${jf.absolutePath} size=${jf.length()} bytes")
            runCatching {
                val rootNode = mapper.readTree(jf)
                val fields = rootNode.fields()
                var count = 0
                while (fields.hasNext()) {
                    val (key, node) = fields.next()
                    val pkg = node.get("pkg")?.asText().orEmpty()
                    val act = node.get("activity")?.asText().orEmpty()
                    val op = node.get("op")?.asText().orEmpty()
                    val hint = node.get("hint")?.asText().orEmpty()
                    val sels = node.get("selectors")?.mapNotNull { s ->
                        val strat = s.get("strategy")?.asText() ?: return@mapNotNull null
                        val valStr = s.get("value")?.asText() ?: return@mapNotNull null
                        val ok = s.get("successes")?.asInt() ?: 0
                        val fail = s.get("failures")?.asInt() ?: 0
                        val last = s.get("lastSeen")?.asLong() ?: 0L
                        MemSelector(strat, valStr, ok, fail, last)
                    } ?: emptyList()
                    mergeEntry(MemEntry(pkg, act, op, hint, jf, sels))
                    count++
                }
                println("mem: loaded ${count} entries from ${jf.name}")
            }.onFailure { e ->
                println("mem: ERROR reading ${jf.absolutePath}: ${e.message}")
            }
        }
    } else {
        println("mem: no components.json/component.json in ${absRoot}")
    }

    // ---------- LEGACY TSV fallback (memory/<pkg>/<act>/*.tsv) ----------
    absRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { appDir ->
        appDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { actDir ->
            actDir.listFiles()
                ?.filter { it.isFile && it.extension.equals("tsv", true) }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    val raw = file.nameWithoutExtension
                    val us = raw.indexOf('_')
                    val op = if (us > 0) raw.substring(0, us) else raw
                    val hint = if (us > 0) raw.substring(us + 1).replace('-', ' ') else raw
                    val sels = runCatching {
                        file.readLines().mapNotNull { line ->
                            val p = line.split('\t')
                            if (p.size < 5) null else MemSelector(
                                strategy = p[0], value = p[1],
                                ok = p[2].toIntOrNull() ?: 0,
                                fail = p[3].toIntOrNull() ?: 0,
                                last = p[4].toLongOrNull() ?: 0L
                            )
                        }
                    }.getOrElse { emptyList() }
                    mergeEntry(MemEntry(appDir.name, actDir.name, op, hint, file, sels))
                }
        }
    }

    // ---------- Build final tree ----------
    val apps = appsByPkg.map { (pkg, actsMap) ->
        val activities = actsMap.map { (act, entries) ->
            val sorted = entries.sortedWith(compareBy({ it.op }, { it.hint.lowercase() }))
            MemActivity(act, sorted)
        }.sortedBy { it.name }
        MemApp(pkg, activities)
    }.sortedBy { it.name }

    println("mem: result apps=${apps.size} entries=$entryCount selectors=$selectorCount")
    return MemIndex(apps, entryCount, selectorCount)
}


// ----------------------- Graph builder for memory entry -----------------------

private data class GraphOut(val nodes: List<Node>, val connections: List<Connection>)

private fun buildGraphFor(entry: MemEntry?): GraphOut {
    if (entry == null) return GraphOut(emptyList(), emptyList())

    // Limit selectors for a compact display
    val sorted = entry.selectors.sortedByDescending { (it.ok - it.fail) }.take(8)

    // Colors aligned with your screenshot
    val colApp = Color(0xFFF9A825)     // orange
    val colAct = Color(0xFFD6C6E1)     // soft purple
    val colOp = Color(0xFFC5CAE9)     // light blue
    val colHint = Color(0xFFC8E6C9)     // light green
    val colSel = Color(0xFFEEEEEE)     // card-ish

    fun portIn(id: String, nodeId: String) = Port(id, nodeId, PortType.INPUT)
    fun portOut(id: String, nodeId: String) = Port(id, nodeId, PortType.OUTPUT)

    val nodes = mutableListOf<Node>()
    val conns = mutableListOf<Connection>()

    // positions (world space)
    val yBase = 120f
    val x0 = 60f;
    val x1 = 360f;
    val x2 = 660f;
    val x3 = 960f;
    val x4 = 1280f

    val app = Node(
        id = "app",
        position = Offset(x0, yBase),
        title = "App: ${entry.appPkg}",
        color = colApp,
        inputs = emptyList(),
        outputs = listOf(portOut("out", "app"))
    ); nodes += app

    val act = Node(
        id = "act",
        position = Offset(x1, yBase),
        title = "Activity",
        color = colAct,
        inputs = listOf(portIn("in", "act")),
        outputs = listOf(portOut("out", "act"))
    ); nodes += act
    conns += Connection("app", "out", "act", "in")

    val op = Node(
        id = "op",
        position = Offset(x2, yBase),
        title = "Op: ${entry.op}",
        color = colOp,
        inputs = listOf(portIn("in", "op")),
        outputs = listOf(portOut("out", "op"))
    ); nodes += op
    conns += Connection("act", "out", "op", "in")

    val hint = Node(
        id = "hint",
        position = Offset(x3, yBase),
        title = "Hint: ${entry.hint}",
        color = colHint,
        inputs = listOf(portIn("in", "hint")),
        outputs = sorted.mapIndexed { i, _ -> portOut("out_${i + 1}", "hint") }
    ); nodes += hint
    conns += Connection("op", "out", "hint", "in")

    // selector nodes in a vertical column
    val vGap = 120f
    sorted.forEachIndexed { idx, sel ->
        val id = "sel_$idx"
        val score = sel.ok - sel.fail
        val labelShort = sel.value.take(38).ifBlank { "(empty)" }
        val n = Node(
            id = id,
            position = Offset(x4, yBase + (idx * vGap)),
            title = "${sel.strategy}: $labelShort  [${sel.ok}/${sel.ok + sel.fail}]  s=$score",
            color = colSel,
            inputs = listOf(portIn("in", id)),
            outputs = emptyList()
        )
        nodes += n
        conns += Connection("hint", "out_${idx + 1}", id, "in")
    }

    return GraphOut(nodes, conns)
}

// ============================= UI =============================

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

    // NEW — Memory browser state
    var showMemoryView by remember { mutableStateOf(false) }
    var memIndex by remember { mutableStateOf(loadSelectorMemoryIndex(File("memory"))) }
    var selectedEntry by remember { mutableStateOf<MemEntry?>(null) }

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

    val disambiguator = remember { null as LlmDisambiguator? }
    val reranker = remember { null as agent.semantic.SemanticReranker? }

    /* ===================== LAYOUT ===================== */
    Box(Modifier.fillMaxSize().background(SurfaceBG)) {
        Row(Modifier.fillMaxSize()) {

            /* -------- Left rail (controls) -------- */
            Column(
                modifier = Modifier.weight(0.20f)
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

                val memRoot = remember { File("memory").absoluteFile }

                // ---------------- Memory Browser Card ----------------
                SectionTitle("Store Memory")
                Spacer(Modifier.height(8.dp))
                CardBox {
                    Column {
                        Row {
                            AlphaButton(text = "Open memory browser") {
                                showMemoryView = true
                                showAgentView = false
                                showAnimation = false
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        Row {
                            AlphaButton(text = "Reload") {
                                memIndex = loadSelectorMemoryIndex(File("memory"))
                                if (selectedEntry != null &&
                                    memIndex.apps.none { app -> app.activities.any { act -> act.entries.any { it.file == selectedEntry!!.file } } }
                                ) selectedEntry = null
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Entries: ${memIndex.totalEntries} • Selectors: ${memIndex.totalSelectors}",
                            color = Color.DarkGray
                        )
                        Spacer(Modifier.height(8.dp))

                        // tiny preview list inside the card
                        val mini = memIndex.apps.take(1).flatMap { it.activities }.flatMap { it.entries }.take(3)
                        if (mini.isNotEmpty()) {
                            mini.forEach { e ->
                                Text(
                                    "• ${e.appPkg} → ${e.activity} → ${e.op} → ${e.hint}",
                                    fontSize = TextUnit(12f, TextUnitType.Sp),
                                    color = Color.Gray
                                )
                            }
                        } else {
                            Text("No memory yet. Run the agent to learn selectors.", color = Color.Gray)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                AgentComponent(
                    selectedDevice = selectedDevice,
                    packageName = packageName,
                    onShowAgentView = {
                        showAgentView = true
                        showAnimation = false
                        showMemoryView = false
                    },
                    onStatus = { msg ->
                        agentStatus = msg
                    },
                    onLog = { line ->
                        val ts = LocalDateTime.now().format(LOG_TIME_FMT)
                        agentLogs.add(0, "[$ts] $line")
                        if (agentLogs.size > 600) agentLogs.removeLast()
                    },
                    onTimelineUpdate = { list ->
                        agentTimeline = list
                        agentCompletedSteps = list.size
                    },
                    onRunState = { running, total ->
                        isAgentRunning = running
                        agentTotalSteps = total
                        if (running) {
                            showAgentView = true
                            showMemoryView = false
                            showAnimation = false
                        }
                    },
                    isParsingTask = { isParsing ->
                        playing = isParsing
                        showAnimation = isParsing
                    },
                    onGenReset = { genState = GenState() },
                    onGenStart = { total ->
                        genState = GenState(
                            visible = true,
                            isGenerating = true,
                            total = total,
                            message = "Starting…"
                        )
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
                        genState = genState.copy(
                            isGenerating = false,
                            step = genState.total,
                            message = "Done",
                            outDir = dir
                        )
                    },
                    onGenError = { msg ->
                        genState = genState.copy(
                            visible = true,
                            isGenerating = false,
                            error = msg,
                            message = "Failed"
                        )
                    },
                    llmDisambiguator = disambiguator,
                    semanticReranker = reranker
                )

            }

            VerticalDivider(modifier = Modifier.padding(vertical = 24.dp), color = Line)

            /* -------- Right content (workspace) -------- */
            Column(modifier = Modifier.weight(0.80f).padding(16.dp)) {
                // Decide which view to show
                val showAgent = showAgentView || isAgentRunning || agentTimeline.isNotEmpty()

                when {
                    showMemoryView -> {
                        MemoryBrowserView(
                            index = memIndex,
                            selected = selectedEntry,
                            onSelect = { selectedEntry = it }
                        )
                    }

                    !showAgent -> {
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
                    }

                    else -> {
                        // ==== AGENT VIEW ====
                        Text("Agent run", fontWeight = MaterialTheme.typography.h6.fontWeight)
                        Spacer(Modifier.height(8.dp))

                        // these are wired in the second AgentComponent below (kept from your code)
                        // the rest of this block remains unchanged from your working UI…

                        // progress
                        LinearProgressIndicator(
                            progress = if (agentTotalSteps > 0) agentCompletedSteps.toFloat() / agentTotalSteps else 0f,
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF6A7BFF),
                            backgroundColor = Color(0xFFE9ECFF)
                        )
                        Spacer(Modifier.height(12.dp))

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
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

                            Column(Modifier.weight(0.40f)) {
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

                        Box(
                            Modifier.fillMaxWidth().height(logHeight)
                                .background(Color(0xFF1E1E1E), RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                                .border(
                                    1.dp,
                                    Color(0xFF3A3A3A),
                                    RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                                )
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
}

// ---------------- Memory Browser View (right content) ----------------

@Composable
private fun MemoryBrowserView(
    index: MemIndex,
    selected: MemEntry?,
    onSelect: (MemEntry) -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    val memoryTabList = listOf("Tree", "All", "Plans")

    val allPlans = model.plan.PlanRegistry.plans            // SnapshotStateList<Plan>
    val plans = allPlans.filter { it.status == model.plan.PlanStatus.SUCCESS }
    var selectedPlanId by remember(allPlans.size) {         // reselect when list updates
        mutableStateOf(plans.firstOrNull()?.id?.value)
    }
    val selectedPlan = plans.firstOrNull { it.id.value == selectedPlanId }


    Text("Store Memory", fontWeight = MaterialTheme.typography.h6.fontWeight)
    Spacer(Modifier.height(8.dp))

    Row(Modifier.fillMaxWidth()) {

        RightCard(modifier = Modifier.weight(0.24f).height(680.dp), pad = 10.dp) {
            val flatItems = remember(index) { flattenMemIndex(index) }

            Column(Modifier.fillMaxSize()) {
                AlphaTabRow(selectedTabIndex = tab, tabs = memoryTabList, onTabSelected = { tab = it })
                Spacer(Modifier.height(8.dp))

                when (tab) {
                    0 -> {
                        val scroll = rememberScrollState()
                        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                            if (index.apps.isEmpty()) {
                                Text("No memory yet. Run the agent to learn selectors.", color = Color.Gray)
                            } else {
                                index.apps.forEach { app ->
                                    Text("📱 ${app.name}", fontWeight = MaterialTheme.typography.h6.fontWeight)
                                    Spacer(Modifier.height(6.dp))
                                    app.activities.forEach { act ->
                                        Text("  • ${act.name}", color = Color(0xFF444444))
                                        Spacer(Modifier.height(6.dp))
                                        act.entries.forEach { e ->
                                            val title = "     — ${e.op} → ${e.hint}   [${e.selectors.size} selectors]"
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp)
                                                    .background(
                                                        if (selected?.file == e.file) Color(0xFFE8F0FF) else Color.Transparent,
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .clickable { onSelect(e) }
                                                    .padding(6.dp)
                                            ) {
                                                Text(
                                                    title,
                                                    color = Color(0xFF555555),
                                                    fontSize = TextUnit(12f, TextUnitType.Sp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                    }
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
                        }
                    }

                    1 -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(
                                items = flatItems,
                                key = { e -> "${e.appPkg}|${e.activity}|${e.op}|${e.hint}" }
                            ) { e ->
                                MemoryEntryCard(
                                    e = e,
                                    selected = selected?.let { s ->
                                        s.appPkg == e.appPkg && s.activity == e.activity && s.op == e.op && s.hint == e.hint
                                    } ?: false,
                                    onSelect = onSelect
                                )
                            }
                        }
                    }

                    else -> {
                        PlanListPane(
                            plans = plans,
                            selectedId = selectedPlanId,
                            onSelect = { selectedPlanId = it.id.value },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(16.dp))

        RightCard(modifier = Modifier.weight(0.52f).height(680.dp), pad = 6.dp) {
            if (tab == 2 && selectedPlan != null) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val nodes = remember(selectedPlan) { ui.component.planToNodes(selectedPlan).toMutableStateList() }
                    val connections = remember(nodes) { ui.component.planToConnections(nodes).toMutableStateList() }
                    val screenshotMap = remember(selectedPlan) { ui.component.planScreenshotMap(selectedPlan) }

                    LaunchedEffect(selectedPlan, maxWidth) {
                        gridArrangeToFit(
                            nodes = nodes,
                            maxWidthDp = maxWidth.value,
                            startX = 140f,
                            startY = 160f,
                            colGap = 420f,
                            rowGap = 240f
                        )
                    }

                    LaunchedEffect(selectedPlan.id) {
                        autoArrangeNodes(
                            nodes = nodes,
                            connections = connections,
                            startX = 140f,
                            startY = 120f,
                            colGap = 420f,
                            rowGap = 220f,
                            diagStep = 150f
                        )
                    }


                    NodeGraphEditor(
                        nodes = nodes,
                        connections = connections,
                        onNodePositionChange = { id, drag ->
                            val i = nodes.indexOfFirst { it.id == id }
                            if (i != -1) {
                                val p = nodes[i].position + drag
                                nodes[i] = nodes[i].copy(
                                    position = androidx.compose.ui.geometry.Offset(
                                        p.x.coerceAtLeast(-100f),
                                        p.y.coerceAtLeast(-100f)
                                    )
                                )
                            }
                        },
                        onNewConnection = { newConn ->
                            if (connections.none {
                                    it.fromNodeId == newConn.fromNodeId && it.fromPortId == newConn.fromPortId &&
                                            it.toNodeId == newConn.toNodeId && it.toPortId == newConn.toPortId
                                }) connections.add(newConn)
                        },
                        onAutoArrange = {
                            ui.component.gridArrangeToFit(
                                nodes = nodes,
                                maxWidthDp = maxWidth.value,
                                startX = 140f,
                                startY = 160f,
                                colGap = 420f,
                                rowGap = 240f
                            )
                        },
                        graphKey = "plan_${selectedPlan.id.value}",
                        screenshotPathForNodeId = { id -> screenshotMap[id] }
                    )
                }
            } else {
                if (selected == null) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) { Text("Select a memory entry on the left", color = Color.Gray) }
                } else {
                    val nodes = remember(selected) {
                        val pkg = selected.appPkg
                        val activity = selected.activity
                        val op = selected.op
                        val hint = selected.hint
                        val selectorCount = selected.selectors.size
                        val screenTitle = guessScreenTitle(activity, hint)
                        val enteredValue: String = ""
                        val opBody = if (op.equals("INPUT", true)) {
                            val key = (hint ?: "value").removePrefix("TEXT_")
                            if (enteredValue.isNotBlank()) "$key: $enteredValue" else key
                        } else "…"
                        val startX = 100f
                        val y = 250f
                        val gap = 300f
                        mutableStateListOf(
                            Node(
                                id = "n_app",
                                position = Offset(startX + 0 * gap, y),
                                title = "App: $pkg",
                                color = Color(0xFFF9A825),
                                inputs = emptyList(),
                                outputs = listOf(Port("out", "n_app", PortType.OUTPUT)),
                                body = "…"
                            ),
                            Node(
                                id = "n_screen",
                                position = Offset(startX + 1 * gap, y),
                                title = "Screen: $screenTitle",
                                color = Color(0xFFD6C6E1),
                                inputs = listOf(Port("in", "n_screen", PortType.INPUT)),
                                outputs = listOf(Port("out", "n_screen", PortType.OUTPUT)),
                                body = activity.substringAfterLast('.')
                            ),
                            Node(
                                id = "n_op",
                                position = Offset(startX + 2 * gap, y),
                                title = "Op: $op",
                                color = Color(0xFFC5CAE9),
                                inputs = listOf(Port("in", "n_op", PortType.INPUT)),
                                outputs = listOf(Port("out", "n_op", PortType.OUTPUT)),
                                body = opBody
                            ),
                            Node(
                                id = "n_hint",
                                position = Offset(startX + 3 * gap, y),
                                title = "Hint: $hint",
                                color = Color(0xFFC8E6C9),
                                inputs = listOf(Port("in", "n_hint", PortType.INPUT)),
                                outputs = listOf(Port("out", "n_hint", PortType.OUTPUT)),
                                body = "…"
                            ),
                            Node(
                                id = "n_sel",
                                position = Offset(startX + 4 * gap, y),
                                title = "Selector ($selectorCount)",
                                color = Color(0xFFFFF3E0),
                                inputs = listOf(Port("in", "n_sel", PortType.INPUT)),
                                outputs = emptyList(),
                                body = "stored"
                            )
                        )
                    }
                    val connections =
                        remember(nodes) { mutableStateListOf<Connection>().also { it.addAll(autoConnect(nodes)) } }
                    val keyForGraph = selected.let { "${it.appPkg}|${it.activity}|${it.op}|${it.hint}" }
                    LaunchedEffect(selected) {
                        autoArrangeNodes(
                            nodes, connections,
                            startX = 140f,
                            startY = 160f,
                            colGap = 460f,
                            rowGap = 240f,
                            diagStep = 150f
                        )
                    }
                    NodeGraphEditor(
                        nodes = nodes,
                        connections = connections,
                        onNodePositionChange = { id, drag ->
                            val i = nodes.indexOfFirst { it.id == id }
                            if (i != -1) {
                                val p = nodes[i].position + drag
                                nodes[i] =
                                    nodes[i].copy(position = Offset(p.x.coerceAtLeast(-100f), p.y.coerceAtLeast(-100f)))
                            }
                        },
                        onNewConnection = { newConn ->
                            if (connections.none {
                                    it.fromNodeId == newConn.fromNodeId &&
                                            it.fromPortId == newConn.fromPortId &&
                                            it.toNodeId == newConn.toNodeId &&
                                            it.toPortId == newConn.toPortId
                                }) connections.add(newConn)
                        },
                        onAutoArrange = {
                            autoArrangeNodes(
                                nodes, connections,
                                startX = 140f,
                                startY = 160f,
                                colGap = 460f,
                                rowGap = 240f,
                                diagStep = 150f
                            )
                        },
                        graphKey = keyForGraph
                    )
                }
            }
        }
    }
}




/* ---------------- Small composables (unchanged pieces you had) ---------------- */

@Composable
private fun BrandCard() { /* ... SAME AS YOUR VERSION ... */
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
private fun CardBox(modifier: Modifier = Modifier, pad: Dp = 12.dp, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(CardBG, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(pad)
    ) { content() }
}

@Preview
@Composable
fun DeviceNotSelectedError(onClick: () -> Unit) { /* ... SAME AS YOUR VERSION ... */
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
fun DeviceSelected() { /* ... SAME AS YOUR VERSION ... */
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
fun DeviceItem(deviceName: String, onDeviceSelected: (String) -> Unit) { /* ... SAME AS YOUR VERSION ... */
    var isActive by remember { mutableStateOf(false) }
    var isSelected by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxWidth()
            .background(color = if (isSelected) Color(BLUE) else Subtle, shape = RoundedCornerShape(8.dp))
            .onPointerEvent(PointerEventType.Enter) { isActive = true }
            .onPointerEvent(PointerEventType.Exit) { isActive = false }
            .onPointerEvent(PointerEventType.Press) { isSelected = true; onDeviceSelected.invoke(deviceName) }
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
                            val memDir = File("memory").absoluteFile.apply { mkdirs() }

                            val compMem = ComponentMemory(memDir)
                            val selectorMem = ComponentMemoryAdapter(compMem)
                            onLog("memory:store=${compMem.filePath()} entries=${compMem.stats().entries} selectors=${compMem.stats().selectors}")

                            onLog("memory:store=${memDir.absolutePath} entries=${compMem.stats().entries} selectors=${compMem.stats().selectors}")

                            val result = AgentRunner(
                                driver = driver,
                                resolver = resolver,
                                store = store,
                                llmDisambiguator = gemini,
                                semanticReranker = reranker,
                                deki = dekiClient,
                                memory = selectorMem
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
                            onLog("memory:after-run entries=${compMem.stats().entries} selectors=${compMem.stats().selectors}")
                            onStatus("Done: ${result.count { it.success }}/${result.size} steps OK")
                        } catch (e: Exception) {
                            model.plan.PlanRecorder.recordFailure(plan!!)
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

private fun guessScreenTitle(activity: String?, hint: String?): String {
    val a = (activity ?: "").substringAfterLast('.').lowercase()
    val h = hint?.lowercase().orEmpty()

    fun niceCap(s: String) = s.split(Regex("[_\\s]"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }

    return when {
        listOf("login", "signin", "auth").any { it in a || it in h } -> "Login Page"
        listOf("transfer", "remit").any { it in a || it in h } -> "Transfer Page"
        listOf("pay", "payment", "bill").any { it in a || it in h } -> "Payments Page"
        listOf("home", "main", "dashboard").any { it in a || it in h } -> "Home"
        else -> niceCap(a.ifBlank { "Screen" })
    }
}




