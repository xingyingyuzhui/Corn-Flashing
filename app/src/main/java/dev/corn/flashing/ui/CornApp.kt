package dev.corn.flashing.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.corn.flashing.core.FlashStage
import dev.corn.flashing.core.FirmwarePreflight
import dev.corn.flashing.core.isFirmwareConfigurationError
import dev.corn.flashing.core.targets.TargetCatalog
import dev.corn.flashing.core.targets.TargetSupport
import dev.corn.flashing.core.TargetInfo
import dev.corn.flashing.core.DemoFlashBackend
import dev.corn.flashing.core.ProductProfiles
import dev.corn.flashing.core.ProductProfile
import dev.corn.flashing.core.hexAddress
import dev.corn.flashing.core.regions
import dev.corn.flashing.storage.ImportedFirmware
import dev.corn.flashing.storage.JobEntity
import dev.corn.flashing.storage.SessionState
import dev.corn.flashing.usb.UsbProbe
import java.util.Calendar

/** Overlay pages pushed above the tabs. */
enum class Page { NONE, SETTINGS, FIRMWARE_DETAIL, NEW_BATCH, FLOW }

/** Per-job UI log + stage timings for screens 02/03/20. */
class RunLog {
    var jobId by mutableStateOf<String?>(null)
    val lines = mutableStateListOf<String>()
    val stageStart = mutableStateMapOf<FlashStage, Long>()
    var startedAt by mutableLongStateOf(0L)
    var endedAt by mutableLongStateOf(0L)
}

@Composable
fun CornApp(vm: FlashViewModel) {
    val state by vm.session.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val devices by vm.devices.collectAsStateWithLifecycle()
    val reservedSettings by vm.reservedSettings.collectAsStateWithLifecycle()
    val experimentalEnabled by vm.experimentalAlgorithmsEnabled.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val batch by vm.batch.collectAsStateWithLifecycle()
    val offer by vm.resumeOffer.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf(Page.NONE) }
    var batchMode by rememberSaveable { mutableStateOf(false) }
    var binAddress by rememberSaveable { mutableStateOf("08000000") }
    val log = remember { RunLog() }
    val prefs = vm.prefs
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri, binAddress)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri)
    }
    LaunchedEffect(batch != null) { if (batch != null) batchMode = true }
    // Keep a readable log of the current job (screen 02 實時日志).
    LaunchedEffect(state.job?.id, state.flashStage, state.message, state.running) {
        val job = state.job ?: return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (log.jobId != job.id) {
            log.jobId = job.id; log.lines.clear(); log.stageStart.clear(); log.startedAt = now; log.endedAt = 0L
        }
        state.flashStage?.let { stage ->
            // Fast stages can be conflated away; mark skipped earlier steps as started now.
            FlashStage.entries.filter { it.ordinal <= stage.ordinal && it !in log.stageStart }.forEach { log.stageStart[it] = now }
        }
        val line = "${formatTime(now)}  ${state.message}"
        if (log.lines.lastOrNull()?.substringAfter("  ") != state.message) log.lines.add(line)
        if (!state.running && log.endedAt == 0L && job.finishedAt != null) log.endedAt = now
    }
    val view = LocalView.current
    DisposableEffect(prefs.keepScreenOn) { view.keepScreenOn = prefs.keepScreenOn; onDispose { } }
    FinishFeedback(vm, state)
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val resultPageEligible = page == Page.FLOW && report == null && batch == null &&
        !state.running && state.job?.status == "SUCCEEDED" && state.job?.finishedAt != null &&
        prefs.autoNextBoard && !prefs.simulator
    val resultVisible = resultPageEligible && lifecycleState == Lifecycle.State.RESUMED
    val activeResult by rememberUpdatedState(if (resultPageEligible) state.job else null)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> vm.stopWatchingNextBoard()
                Lifecycle.Event.ON_RESUME -> activeResult?.let { vm.watchNextBoard(it.id, it.targetUid) }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); vm.stopWatchingNextBoard() }
    }
    DisposableEffect(resultVisible, state.job?.id) {
        if (resultVisible) state.job?.let { vm.watchNextBoard(it.id, it.targetUid) }
        onDispose { vm.stopWatchingNextBoard() }
    }
    val leaveFlow: () -> Unit = { vm.stopWatchingNextBoard(); page = Page.NONE }

    val pendingStart = remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        vm.onNotificationPermissionResult(granted)
        val next = pendingStart.value
        pendingStart.value = null
        // Proceed even when denied so flashing still works; the notice explains the trade-off.
        next?.invoke()
    }
    val withNotifications: (() -> Unit) -> Unit = { block ->
        vm.ensureNotificationsThen(
            request = {
                pendingStart.value = block
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else block()
            },
            proceed = block,
        )
    }

    CornTheme {
      key(experimentalEnabled) {
        val sim = prefs.simulator
        Box(Modifier.fillMaxSize().background(CornColors.Background)) {
            val reportId = report
            when {
                reportId != null -> {
                    BackHandler { vm.openReport(null) }
                    BatchReportScreen(vm, reportId, onBack = { vm.openReport(null) })
                }
                page == Page.FLOW && batch == null -> {
                    BackHandler(enabled = !state.running) { leaveFlow() }
                    FlowScreen(vm, state, log, history, onBack = { if (!state.running) leaveFlow() })
                }
                page == Page.SETTINGS -> {
                    BackHandler { page = Page.NONE }
                    SettingsScreen(vm, onBack = { page = Page.NONE }, onExport = { exporter.launch("corn-flashing-records.json") })
                }
                page == Page.FIRMWARE_DETAIL && state.firmware != null -> {
                    BackHandler { page = Page.NONE }
                    FirmwareDetailScreen(state.firmware!!, effectiveProfile(state, vm), effectiveTarget(state, vm), onBack = { page = Page.NONE }, registry = vm.targetRegistry)
                }
                page == Page.NEW_BATCH && batch == null -> {
                    BackHandler { page = Page.NONE }
                    NewBatchScreen(vm, state, devices, onBack = { page = Page.NONE },
                        onGoFirmware = { page = Page.NONE; tab = 1 },
                        onStarted = { page = Page.NONE; tab = 0; batchMode = true },
                        withNotifications = withNotifications)
                }
                else -> Box(Modifier.fillMaxSize()) {
                  // Draw the scrolling page through the gesture area. Only interactive dock content
                  // avoids it; trailing scroll clearance accounts for that inset exactly once.
                  val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                  Scaffold(
                    containerColor = CornColors.Background,
                    contentWindowInsets = WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                ) { padding ->
                  CompositionLocalProvider(LocalDockClearance provides DockClearance + navInset) {
                    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        when (tab) {
                            0 -> Workbench(vm, state, history, devices, batchMode, sim,
                                onMode = { wanted ->
                                    if (!wanted && batch != null) vm.notify("批次未结束：结束批次后才能切回单路") else batchMode = wanted
                                },
                                onSettings = { page = Page.SETTINGS }, onGoFirmware = { tab = 1 },
                                onStart = { withNotifications { vm.startSingle(); page = Page.FLOW } },
                                onNewBatch = { page = Page.NEW_BATCH })
                            1 -> FirmwareTab(vm, state, binAddress, { binAddress = it },
                                onPick = { picker.launch(arrayOf("*/*")) }, onDetail = { page = Page.FIRMWARE_DETAIL })
                            2 -> ConfigTab(vm)
                            3 -> DevicesTab(vm, devices)
                            4 -> RecordsTab(vm, history, onExport = { exporter.launch("corn-flashing-records.json") })
                        }
                    }
                  }
                  }
                  Box(Modifier.align(Alignment.BottomCenter)) { BottomNav(tab) { tab = it } }
                }
            }
            if (notice != null) {
                MessageDialog("消息提醒", notice!!, onDismiss = vm::clearNotice)
            } else state.importError?.let { error ->
                val (title, advice) = importErrorAdvice(error)
                MessageDialog(title, "$error\n\n$advice", onDismiss = vm::clearImportError)
            }
            offer?.let { ResumeSheet(it, vm) }
        }
      }
    }
}

/** Tone on success, vibration on failure (settings 10). */
@Composable
private fun FinishFeedback(vm: FlashViewModel, state: SessionState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Seed with the job already in memory so a restored row does not beep; the first new finish still does.
    var lastId by remember { mutableStateOf(state.job?.id) }
    LaunchedEffect(state.job?.id, state.running) {
        val job = state.job ?: return@LaunchedEffect
        if (state.running || job.finishedAt == null || job.id == lastId) return@LaunchedEffect
        lastId = job.id
        if (job.status in listOf("SIMULATED", "SUCCEEDED") && vm.prefs.successTone) runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80)
            try {
                tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 200)
                kotlinx.coroutines.delay(220)
            } finally { tone.release() }
        }
        if (job.status == "FAILED" && vm.prefs.vibrateOnFail) runCatching {
            val vibrator = context.getSystemService(android.os.Vibrator::class.java)
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(300, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}

// ---------------------------------------------------------------- nav

// Only tab pages reserve scrollable trailing space for the overlay; detail pages have no dock.
val LocalDockClearance = staticCompositionLocalOf { 0.dp }
private val DockClearance = 88.dp // Island height (68) plus its outer vertical spacing (20).

@Composable
private fun BottomNav(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf(Lucide.Bolt to "工作台", Lucide.File to "固件", Lucide.Sliders to "配置", Lucide.Usb to "设备", Lucide.List to "记录")
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 32.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = CornColors.Surface2,
            border = BorderStroke(1.dp, CornColors.Line),
            shadowElevation = 12.dp,
        ) {
            Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 6.dp, vertical = 6.dp)) {
                items.forEachIndexed { index, (icon, label) ->
                    val active = index == tab
                    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(22.dp))
                        .background(if (active) CornColors.CornContainer else Color.Transparent)
                        .clickable { onTab(index) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                        Ico(icon, if (active) CornColors.Corn else CornColors.Dim, 21.dp)
                        T(label, if (active) CornColors.Corn else CornColors.Dim, 11.sp,
                            if (active) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun ScrollBody(modifier: Modifier = Modifier, spacing: Int = 12, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(start = 16.dp, end = 16.dp, bottom = 20.dp + LocalDockClearance.current),
        verticalArrangement = Arrangement.spacedBy(spacing.dp), content = content)
}

// ---------------------------------------------------------------- workbench (01 / 17 / 13)

@Composable
private fun Workbench(
    vm: FlashViewModel, state: SessionState, history: List<JobEntity>, devices: List<UsbProbe>,
    batchMode: Boolean, sim: Boolean, onMode: (Boolean) -> Unit, onSettings: () -> Unit, onGoFirmware: () -> Unit,
    onStart: () -> Unit, onNewBatch: () -> Unit,
) {
    val reservedSettings by vm.reservedSettings.collectAsStateWithLifecycle()
    val bindings by vm.algorithmBindings.collectAsStateWithLifecycle()
    val experimentalEnabled by vm.experimentalAlgorithmsEnabled.collectAsStateWithLifecycle()
    val targetRegistry = remember(bindings, experimentalEnabled) { vm.targetRegistry }
    val batch by vm.batch.collectAsStateWithLifecycle()
    val probe = devices.firstOrNull { it.candidate }
    val probeOk = sim || probe?.permissionGranted == true
    if (batchMode) {
        BatchWorkbench(vm, state, batch, onMode, onSettings, onNewBatch)
        return
    }
    TopBar("Corn Flashing", when {
        sim -> "模拟烧录 · 演示板"
        state.target != null -> "离线烧录 · ${state.target.family}"
        else -> "离线烧录 · 自动识别目标"
    }) {
        if (probeOk) Chip("工位 1", ChipTone.OK, dot = true) else Chip("未就绪", ChipTone.BAD, dot = true)
        IconBtn(Lucide.Gear, onSettings)
    }
    ScrollBody {
        Seg(listOf("单路", "批量"), 0, { onMode(it == 1) })
        Chain(
            ChainNode(Lucide.Phone, "Android", if (vm.hostSupported) (if (probe?.permissionGranted == true) "OTG 已授权" else "OTG 可用") else "无 USB Host",
                if (vm.hostSupported) CornColors.Leaf else CornColors.Red),
            ChainNode(Lucide.Usb, state.probeInfo?.label ?: "烧录探针", when { sim -> "模拟器"; probe == null -> "未连接"; probe.permissionGranted -> state.probeInfo?.firmware ?: "已授权"; else -> "待授权" },
                if (probeOk) CornColors.Leaf else if (probe != null) CornColors.Corn else CornColors.Red),
            ChainNode(
                Lucide.Chip,
                when {
                    sim -> "F103C8"
                    state.target != null -> state.target.family.take(10)
                    else -> "目标板"
                },
                when {
                    sim -> "3.30 V · 模拟"
                    state.target != null -> "${state.target.flashSizeBytes / 1024} KB" +
                        (state.target.uid?.let { " · ${it.take(8)}" } ?: "")
                    else -> "未识别"
                },
                when {
                    sim -> CornColors.Leaf
                    state.target != null && targetRegistry.find(state.target.deviceId)?.support == TargetSupport.FLASH_ALGORITHM -> CornColors.Leaf
                    state.target != null -> CornColors.Corn
                    else -> CornColors.Red
                },
            ),
        )
        if (!probeOk) {
            NoProbeCard(probe, devices.isNotEmpty(), vm)
            CornButton("开始烧录", {}, Modifier.fillMaxWidth(), enabled = false, height = 64, radius = 20, icon = Lucide.Bolt,
                subtitle = "先接上并授权 ST-LINK")
            T("没有硬件？用模拟烧录器演练流程", CornColors.Corn, 13.sp, FontWeight.SemiBold, align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { vm.prefs.simulator = true }.padding(8.dp))
            return@ScrollBody
        }
        if (!sim && probe?.permissionGranted == true) {
            CornButton(
                when {
                    state.probing -> "识别中…"
                    state.target == null -> "识别芯片"
                    else -> "重新识别"
                },
                vm::probeChip,
                Modifier.fillMaxWidth(),
                height = 48,
                radius = 16,
                icon = Lucide.Chip,
                enabled = !state.running && !state.probing,
                subtitle = state.target?.let { "${it.family} · ${it.flashSizeBytes / 1024} KB" } ?: "读 DBGMCU / Flash 容量 / UID",
            )
        }
        if (!sim) state.target?.let { target ->
            val descriptor = vm.targetRegistry.find(target.deviceId)
            val programmable = descriptor?.support == TargetSupport.FLASH_ALGORITHM
            val external = descriptor?.algorithmId?.startsWith("flm-") == true
            CornCard(spacing = 6.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("当前目标兼容性", Modifier.weight(1f))
                    Chip(if (external) "实验 · 未验证" else if (programmable) "算法已接入" else if (descriptor == null) "未登记" else "仅识别",
                        if (programmable && !external) ChipTone.OK else ChipTone.WARN)
                }
                T(if (external) "已启用外部 FLM 实验算法，尚无对应料号实板验证；不能保证其自身不访问保留区或 Option Bytes/RDP。"
                    else if (programmable) "已匹配内置烧录算法；烧录前仍会检查探针能力、容量和固件范围。"
                    else "已识别目标信息，当前没有匹配的烧录算法，暂不开放擦写。", CornColors.Dim, 12.sp)
            }
        }
        FirmwareCard(state.firmware, effectiveProfile(state, vm), onGoFirmware, vm)
        val firmware = state.firmware
        val preflight = firmware?.let { FirmwarePreflight.inspect(it.image, effectiveProfile(state, vm), effectiveTarget(state, vm), if (sim) TargetCatalog.registry else vm.targetRegistry) }
        val reservedError = reservedSettings.error
        val pages = firmware?.takeIf { preflight?.targetKnown == true && preflight.canStart }?.let { erasePages(it, effectiveProfile(state, vm)) }
        CornButton("开始烧录", onStart, Modifier.fillMaxWidth(), primary = true, height = 64, radius = 20, icon = Lucide.Bolt,
            enabled = reservedError == null && state.ready && !state.importing && firmware != null && !state.running && !state.probing && preflight?.canStart == true,
            subtitle = when {
                reservedError != null -> "请先修复参数保留区配置"
                state.probing -> "识别中，请等待 USB 通信完成"
                firmware == null -> "先选择固件"
                preflight?.error != null -> "当前目标或固件未通过烧录预检"
                preflight?.targetKnown != true -> "先识别目标容量，再检查并烧录"
                sim -> "模拟 · 擦除 $pages 页 · 写入 ${kb(firmware.image.totalBytes)}"
                else -> "真实擦写 · ${pages ?: "?"} 页 · 回读校验"
            })
        preflight?.error?.let { message ->
            T(message + if (sim) "；当前模拟芯片固定为 64 KB" else "；请检查固件与目标配置", CornColors.Red, 12.sp)
        }
        CornCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                SRow("自动换板", if (sim) "模拟：成功后点「立即再烧一块」" else "成功后探测到新 UID 自动开始") {
                    CornToggle(vm.prefs.autoNextBoard) { vm.prefs.autoNextBoard = it }
                }
                SRow("完成后复位运行", "真实路径：烧录后复位并运行目标；模拟仅演示", last = true) {
                    CornToggle(vm.prefs.resetAfter) { vm.prefs.resetAfter = it }
                }
            }
        }
        TodayCard(history)
    }
}

data class ChainNode(val icon: ImageVector, val name: String, val status: String, val color: Color)

/** Phone › ST-LINK › chip chain card (01 / 17 / 19). */
@Composable
fun Chain(vararg nodes: ChainNode) {
    CornCard(padding = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            nodes.forEachIndexed { index, node ->
                if (index > 0) Ico(Lucide.Chev, CornColors.Mute, 14.dp, Modifier.padding(horizontal = 2.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(CornColors.Surface2), contentAlignment = Alignment.Center) {
                        Ico(node.icon, node.color, 15.dp)
                    }
                    Column {
                        T(node.name, size = 11.5.sp, weight = FontWeight.SemiBold, maxLines = 1)
                        T(node.status, node.color, 10.5.sp, mono = true, maxLines = 1)
                    }
                }
            }
        }
    }
}

fun effectiveTarget(state: SessionState, vm: FlashViewModel): TargetInfo? =
    if (vm.prefs.simulator) DemoFlashBackend.DEFAULT_TARGET
    else state.target?.takeUnless { it.uid?.startsWith("DEMO-") == true }

fun effectiveProfile(state: SessionState, vm: FlashViewModel): ProductProfile {
    val target = effectiveTarget(state, vm) ?: return ProductProfiles.AUTO_DETECT
    return runCatching { vm.profileFor(target) }
        .getOrDefault(ProductProfiles.AUTO_DETECT)
}

fun erasePages(firmware: ImportedFirmware, profile: ProductProfile): Int {
    val descriptor = profile.expectedDeviceIds.singleOrNull()?.let(TargetCatalog::find)
    if (descriptor != null) {
        return descriptor.geometry.sectors(profile.flashStart, profile.flashSizeBytes).count { sector ->
            firmware.image.segments.any { segment ->
                segment.address < sector.address + sector.sizeBytes && segment.endAddressExclusive > sector.address
            }
        }
    }
    return firmware.image.segments.flatMap { segment ->
        val first = (segment.address - profile.flashStart) / profile.pageSize
        val last = (segment.endAddressExclusive - 1 - profile.flashStart) / profile.pageSize
        (first..last).toList()
    }.toSet().size
}

@Composable
private fun FirmwareCard(firmware: ImportedFirmware?, profile: ProductProfile, onGoFirmware: () -> Unit, vm: FlashViewModel) {
    CornCard(spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("当前固件", Modifier.weight(1f))
            Chip(if (firmware == null) "选择 ›" else "更换 ›", onClick = onGoFirmware)
        }
        if (firmware == null) {
            T("还没有选择固件", size = 17.sp, weight = FontWeight.Bold)
            T("先用演示 HEX 试一下", CornColors.Corn, 13.sp, FontWeight.SemiBold, modifier = Modifier.clickable { vm.loadDemo() })
            return@CornCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormatBadge(firmware.image.format.name)
            T(firmware.name, size = 17.sp, weight = FontWeight.Bold, maxLines = 1)
        }
        T("SHA ${shortSha(firmware.image.sourceSha256)} · ${kb(firmware.image.totalBytes)} · ${firmware.image.regions().size} 段",
            CornColors.Dim, 11.5.sp, mono = true)
        MemMap(firmware, profile)
    }
}

/** 26dp flash usage bar with address scale and legend. */
@Composable
fun MemMap(firmware: ImportedFirmware, profile: ProductProfile) {
    val total = profile.flashSizeBytes.toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(8.dp)).background(CornColors.Surface3)) {
            val w = maxWidth
            profile.reservedRanges.forEach { r ->
                Box(Modifier.offset(x = w * ((r.start - profile.flashStart) / total)).width(w * ((r.endExclusive - r.start) / total)).fillMaxHeight().hatch())
            }
            firmware.image.regions().forEach { r ->
                Box(Modifier.offset(x = w * ((r.start - profile.flashStart) / total)).width(w * ((r.endExclusive - r.start) / total))
                    .fillMaxHeight().background(CornColors.Corn))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            T(profile.flashStart.hexAddress(), CornColors.Mute, 10.sp, mono = true)
            Spacer(Modifier.weight(1f))
            T(if (profile.expectedDeviceIds.size == 1) "Flash · ${profile.flashSizeBytes / 1024} KB" else "Flash · 待探测", CornColors.Mute, 10.sp, mono = true)
            Spacer(Modifier.weight(1f))
            T((profile.flashStart + profile.flashSizeBytes - 1).hexAddress(), CornColors.Mute, 10.sp, mono = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(CornColors.Corn, "写入")
            LegendDot(CornColors.Red, "保留区（不擦写）", hatch = true)
        }
    }
}

fun isToday(millis: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = millis }
    val b = Calendar.getInstance()
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun TodayCard(history: List<JobEntity>) {
    val today = history.filter { isToday(it.startedAt) }
    val ok = today.filter { it.status in listOf("SIMULATED", "SUCCEEDED") }
    val bad = today.count { it.status == "FAILED" }
    val avg = ok.mapNotNull { r -> r.finishedAt?.let { it - r.startedAt } }.takeIf { it.isNotEmpty() }?.average()?.toLong()
    CornCard(spacing = 10.dp) {
        Row {
            Label("今日 · 工位 1", Modifier.weight(1f))
            today.minOfOrNull { it.startedAt }?.let { T("${formatHm(it)} 起", CornColors.Mute, 11.sp, mono = true) }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf(
                Triple("${ok.size}", "成功", CornColors.Leaf), Triple("$bad", "失败", CornColors.Red),
                Triple(percent((ok.size + bad).takeIf { it > 0 }?.let { ok.size.toFloat() / it }), "良率", CornColors.Text),
                Triple(avg?.let { "%.1f".format(it / 1000f) + "s" } ?: "—", "平均", CornColors.Text),
            ).forEach { (v, l, c) ->
                Column(Modifier.weight(1f)) { T(v, c, 22.sp, FontWeight.Bold, mono = true); T(l, CornColors.Dim, 11.5.sp) }
            }
        }
    }
}

/** Screen 17: no probe. */
@Composable
private fun NoProbeCard(probe: UsbProbe?, anyUsb: Boolean, vm: FlashViewModel) {
    CornCard(spacing = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Illo(Lucide.Usb, CornColors.Dim)
            T("还没有连接烧录器", size = 17.sp, weight = FontWeight.Bold)
            T("按顺序接好，接上后这里会自动更新", CornColors.Dim, 12.5.sp)
        }
        HintRow("1", "OTG 转接头插到手机", "Type-C → USB-A 母口", done = vm.hostSupported && (anyUsb || true))
        HintRow("2", "接上 ST-LINK", "ST-LINK/V2 插到转接头上", done = probe != null)
        HintRow("3", "允许 Corn Flashing 访问", "系统会弹出 USB 授权窗口", done = probe?.permissionGranted == true, last = true)
        if (probe != null && !probe.permissionGranted) {
            CornButton("授权", { vm.authorize(probe.connectionId) }, Modifier.fillMaxWidth(), primary = true, height = 44)
        }
    }
}

// ---------------------------------------------------------------- single flow (02 / 03 / 04 / 19 / 20)

val SIX_STEPS = listOf(FlashStage.CONNECTING, FlashStage.PROBING, FlashStage.ERASING, FlashStage.WRITING, FlashStage.VERIFYING, FlashStage.RESETTING)

fun stepLabel(stage: FlashStage, pages: Int?) = when (stage) {
    FlashStage.CONNECTING -> "连接探针"
    FlashStage.PROBING -> "识别芯片"
    FlashStage.ERASING -> if (pages == null) "擦除" else "擦除 $pages 页"
    FlashStage.WRITING -> "写入"
    FlashStage.VERIFYING -> "回读校验"
    FlashStage.RESETTING -> "复位运行"
    FlashStage.SUCCEEDED -> "完成"
}

fun stageLabel(stage: FlashStage) = stepLabel(stage, null)

@Composable
private fun FlowScreen(vm: FlashViewModel, state: SessionState, log: RunLog, history: List<JobEntity>, onBack: () -> Unit) {
    val job = state.job
    val fw = state.firmware
    val seq = history.count { isToday(it.startedAt) && it.batchId == null }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        when {
            state.running || job == null || job.finishedAt == null -> RunningScreen(vm, state, log, seq, fw)
            job.status in listOf("SIMULATED", "SUCCEEDED") -> SuccessScreen(vm, state, log, seq, onBack)
            job.status == "FAILED" -> FailedScreen(vm, state, log, seq, onBack)
            else -> AbortedScreen(vm, state, log, onBack)
        }
    }
}

@Composable
private fun ColumnScope.RunningScreen(vm: FlashViewModel, state: SessionState, log: RunLog, seq: Int, fw: ImportedFirmware?) {
    val now by produceState(System.currentTimeMillis(), state.running) { while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(200) } }
    val elapsed = (now - log.startedAt).coerceAtLeast(1)
    val p = state.progress.coerceIn(0f, 1f)
    val bytes = (fw?.image?.totalBytes ?: 0) * p
    BackTop("烧录中", "工位 1 · ${fw?.name ?: ""}", onBack = {}) { Chip("请勿拔线", ChipTone.WARN, dot = true) }
    ScrollBody(Modifier.weight(1f)) {
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Ring(p) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    T("${(p * 100).toInt()}%", size = 40.sp, weight = FontWeight.Bold, mono = true)
                    T("正在${state.flashStage?.let { stageLabel(it) } ?: "准备"}", CornColors.Corn, 13.sp, FontWeight.SemiBold)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatCell(if (bytes > 0) "%.1f KB/s".format(bytes / 1024f / (elapsed / 1000f)) else "—", "速度", size = 16.sp, modifier = Modifier.weight(1f))
            VDivider()
            StatCell(if (p > 0.05f) secs(((elapsed * (1 - p)) / p).toLong()) else "—", "剩余", size = 16.sp, modifier = Modifier.weight(1f))
            VDivider()
            StatCell("#$seq", "本次序号", size = 16.sp, modifier = Modifier.weight(1f))
        }
        StepList(state.flashStage, null, log, fw?.let { erasePages(it, vm.profile) }, running = true)
        LiveLog(log)
        SimNote()
    }
    Box(Modifier.padding(16.dp)) { HoldToAbort(vm::cancel) }
}

@Composable
fun SimNote() = T("模拟烧录器：流程与计时为演示，不操作真实芯片", CornColors.Mute, 11.sp, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())

/** 6-step stepper. [stoppedAt] marks a failed / interrupted step in red. */
@Composable
fun StepList(current: FlashStage?, stoppedAt: FlashStage?, log: RunLog?, pages: Int?, running: Boolean, stopText: String = "失败") {
    val curIndex = current?.let { SIX_STEPS.indexOf(it).let { i -> if (it == FlashStage.SUCCEEDED) 6 else i } } ?: -1
    val stopIndex = stoppedAt?.let { SIX_STEPS.indexOf(it) } ?: -1
    CornCard(spacing = 0.dp) {
        SIX_STEPS.forEachIndexed { i, stage ->
            val done = if (stopIndex >= 0) i < stopIndex else i < curIndex
            val now = if (stopIndex >= 0) i == stopIndex else i == curIndex && running
            val bad = stopIndex >= 0 && i == stopIndex
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(when { bad -> CornColors.Red; done -> CornColors.Leaf; now -> CornColors.Corn; else -> Color.Transparent })
                        .let { if (!done && !now) it.border(1.5.dp, CornColors.Mute, CircleShape) else it }, contentAlignment = Alignment.Center) {
                        when {
                            bad -> Ico(Lucide.X, CornColors.CornOn, 12.dp)
                            done -> Ico(Lucide.Check, CornColors.CornOn, 12.dp)
                            now -> Box(Modifier.size(7.dp).clip(CircleShape).background(CornColors.CornOn))
                        }
                    }
                    if (i < SIX_STEPS.lastIndex) Box(Modifier.width(2.dp).height(22.dp).background(if (done) CornColors.Leaf.copy(alpha = .5f) else CornColors.Line))
                }
                Spacer(Modifier.width(10.dp))
                T(stepLabel(stage, pages), when { bad -> CornColors.Red; now -> CornColors.Corn; done -> CornColors.Text; else -> CornColors.Mute },
                    13.5.sp, if (now) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f).padding(top = 2.dp))
                val t = log?.let { stepTime(it, i) }
                T(when { bad -> stopText; t != null -> t; now -> "…"; else -> "" },
                    when { bad -> CornColors.Red; now -> CornColors.Corn; else -> CornColors.Leaf }, 11.5.sp, mono = true, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

private fun stepTime(log: RunLog, index: Int): String? {
    val start = log.stageStart[SIX_STEPS[index]] ?: return null
    val next = SIX_STEPS.drop(index + 1).firstNotNullOfOrNull { log.stageStart[it] }
        ?: log.stageStart[FlashStage.SUCCEEDED] ?: log.endedAt.takeIf { it > 0 } ?: return null
    return secs(next - start)
}

@Composable
fun LiveLog(log: RunLog, expandedDefault: Boolean = false) {
    var expanded by remember { mutableStateOf(expandedDefault) }
    CornCard(color = Color(0xFF0D0D0D), spacing = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("实时日志", Modifier.weight(1f))
            T(if (expanded) "收起 ‹" else "展开 ›", CornColors.Corn, 11.5.sp, FontWeight.SemiBold, modifier = Modifier.clickable { expanded = !expanded })
        }
        val lines = if (expanded) log.lines else log.lines.takeLast(4)
        lines.forEach { T(it, Color(0xFF8F8A7E), 10.5.sp, mono = true, maxLines = if (expanded) 3 else 1) }
        if (log.lines.isEmpty()) T("等待开始…", Color(0xFF8F8A7E), 10.5.sp, mono = true)
    }
}

/** Danger button that only aborts after a 1 s hold. */
@Composable
private fun HoldToAbort(onAbort: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    val progress by androidx.compose.animation.core.animateFloatAsState(if (holding) 1f else 0f,
        androidx.compose.animation.core.tween(if (holding) 1000 else 150, easing = androidx.compose.animation.core.LinearEasing), label = "hold") {
        if (it >= 1f && holding) { holding = false; onAbort() }
    }
    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
        .border(1.5.dp, CornColors.Red.copy(alpha = .55f), RoundedCornerShape(16.dp))
        .pointerInput(Unit) {
            detectTapGestures(onPress = {
                holding = true
                tryAwaitRelease()
                holding = false
            })
        }, contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(CornColors.RedContainer))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Ico(Lucide.X, CornColors.Red, 18.dp)
            Spacer(Modifier.width(8.dp))
            T("长按 1 秒中止", CornColors.Red, 15.sp, FontWeight.Bold)
        }
    }
}

@Composable
private fun ResultHeader(icon: ImageVector, color: Color, bg: Color, title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Box(Modifier.size(62.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Ico(icon, CornColors.CornOn, 30.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
        T(title, size = 24.sp, weight = FontWeight.Bold)
        T(sub, CornColors.Dim, 12.5.sp, mono = true)
    }
}

@Composable
private fun ColumnScope.SuccessScreen(vm: FlashViewModel, state: SessionState, log: RunLog, seq: Int, onBack: () -> Unit) {
    val job = state.job!!
    val fw = state.firmware
    var showLog by remember { mutableStateOf(false) }
    val simJob = job.mode == "DEMO"
    val verified = !job.message.contains("未校验")
    BackTop("烧录完成", "工位 1 · ${job.firmwareName}", onBack = onBack) { Chip("可拔线", ChipTone.OK, dot = true) }
    ScrollBody(Modifier.weight(1f)) {
        ResultHeader(Lucide.Check, CornColors.Leaf, CornColors.LeafContainer, if (verified) "烧录成功" else "写入完成（未校验）",
            "#$seq · 用时 ${secs((job.finishedAt ?: job.startedAt) - job.startedAt)} · ${formatTime(job.finishedAt ?: job.startedAt)}")
        CornCard(padding = 14.dp) {
            KeyValue(
                "芯片",
                state.target?.let { "${it.family} · ${it.flashSizeBytes / 1024} KB" }
                    ?: if (simJob) "STM32F103 · 模拟" else "—",
            )
            KeyValue("UID", shortUid(job.targetUid), mono = true)
            KeyValue("写入", fw?.let { "${kb(it.image.totalBytes)} · ${erasePages(it, vm.profile)} 页" } ?: "—", mono = true)
            KeyValue("回读校验", when {
                !verified -> "未执行"
                simJob -> "通过 · 模拟"
                else -> "回读通过"
            }, valueColor = if (verified) CornColors.Leaf else CornColors.Corn)
            KeyValue(
                "复位",
                when {
                    simJob && job.message.contains("模拟复位") -> "已复位 · 模拟"
                    simJob -> "未复位 · 模拟"
                    job.message.contains("已复位") -> "已复位并解除停核"
                    else -> "未复位"
                },
            )
            KeyValue("目标电压", voltageLabel(vm, state), mono = true, last = true)
        }
        if (showLog) LiveLog(log, expandedDefault = true)
        DashedNote {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Ico(Lucide.Repeat, CornColors.Corn, 16.dp)
                T("等待下一块板…", CornColors.Corn, 14.sp, FontWeight.SemiBold)
            }
            T(
                when {
                    !vm.prefs.autoNextBoard -> "自动换板已关闭"
                    job.mode == "DEMO" -> "模拟：点「立即再烧一块」继续"
                    else -> "正在等待下一块板（新 UID）…也可手动点「立即再烧一块」"
                },
                CornColors.Dim, 11.5.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(1f, .6f, .3f).forEach { Box(Modifier.size(6.dp).clip(CircleShape).background(CornColors.Corn.copy(alpha = it))) }
            }
        }
    }
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CornButton(if (showLog) "收起日志" else "查看日志", { showLog = !showLog }, Modifier.weight(1f))
        CornButton("立即再烧一块", vm::startSingle, Modifier.weight(1.4f), primary = true, icon = Lucide.Play)
    }
}

private val noTargetWords = listOf("电压", "未响应", "DBGMCU", "进入 SWD")

@Composable
private fun ColumnScope.FailedScreen(vm: FlashViewModel, state: SessionState, log: RunLog, seq: Int, onBack: () -> Unit) {
    val job = state.job!!
    val stage = state.failedStage
    val configurationError = isFirmwareConfigurationError(job.message)
    val noTarget = !configurationError && (stage == FlashStage.PROBING || noTargetWords.any { job.message.contains(it) })
    var showLog by remember { mutableStateOf(false) }
    if (noTarget) {
        NoTargetScreen(vm, state, job, onBack)
        return
    }
    val usbOrProbe = !configurationError && (job.message.contains("ST-LINK") || stage in setOf(FlashStage.CONNECTING, FlashStage.PROBING))
    val addr = Regex("0x[0-9A-Fa-f]{8}").find(job.message)?.value
    val hex = Regex("0x[0-9A-Fa-f]{2}\\b").findAll(job.message).map { it.value }.toList()
    BackTop("烧录失败", "工位 1 · ${job.firmwareName}", onBack = onBack) {
        Chip(if (configurationError) "配置未通过" else "该板未通过", ChipTone.BAD, dot = true)
    }
    ScrollBody(Modifier.weight(1f)) {
        ResultHeader(Lucide.X, CornColors.Red, CornColors.RedContainer,
            when {
                configurationError -> "固件 / 配置不匹配"
                usbOrProbe -> "连接 / 识别失败"
                stage == FlashStage.VERIFYING -> "校验失败"
                stage != null -> "${stageLabel(stage)}失败"
                else -> "烧录失败"
            },
            if (configurationError) "#$seq · 目标配置检查未通过" else "#$seq · 停在「${stage?.let { stepLabel(it, null).trim() } ?: job.stage}」")
        CornCard {
            if (configurationError) {
                KeyValue("检查结果", job.message, valueColor = CornColors.Red)
            } else if (!usbOrProbe) {
                KeyValue("出错地址", addr ?: "—", mono = true, valueColor = CornColors.Red)
                KeyValue("期望", hex.getOrNull(0) ?: "—", mono = true)
                KeyValue("实际", hex.getOrNull(1) ?: "—", mono = true, valueColor = CornColors.Red)
            }
            KeyValue("已完成", if (configurationError) "输入与目标配置检查" else stage?.let { s -> SIX_STEPS.takeWhile { it != s }.joinToString(" · ") { stageLabel(it) } } ?: "—")
            KeyValue("目标电压", voltageLabel(vm, state), mono = true, last = true)
        }
        CornCard(color = CornColors.RedContainer, border = CornColors.Red.copy(alpha = .25f), spacing = 4.dp) {
            T("可能原因", CornColors.Red, 13.5.sp, FontWeight.Bold)
            if (configurationError) {
                T("· 固件地址范围超过目标实际容量，或不符合产品配置", CornColors.Dim, 12.sp)
                T("· 写入或擦除页与需要保留的数据冲突", CornColors.Dim, 12.sp)
            } else if (usbOrProbe) {
                T("· USB 未正确配置接口/端点，或 ST-LINK 被电脑端占用", CornColors.Dim, 12.sp)
                T("· OTG 供电不稳；请拔插 ST-LINK 后重试", CornColors.Dim, 12.sp)
                T("· 目标未上电或 SWD 未接好（若已过通信阶段）", CornColors.Dim, 12.sp)
            } else {
                T("· 这块板的 Flash 有坏页，写入后读回不一致", CornColors.Dim, 12.sp)
                T("· SWD 线过长或接触不良，写入时数据出错", CornColors.Dim, 12.sp)
                T("· 目标板供电不稳，擦写时电压跌落", CornColors.Dim, 12.sp)
            }
        }
        CornCard(spacing = 4.dp) {
            T("建议", size = 13.5.sp, weight = FontWeight.Bold)
            T(if (configurationError) "核对固件与芯片容量；必要时重新识别目标、选择匹配固件。配置检查失败不代表控制板损坏。"
                else "检查完整错误日志、接线和供电；不要仅凭一次烧录失败判定控制板损坏。", CornColors.Dim, 12.sp)
        }
        if (showLog) LiveLog(log, expandedDefault = true)
        T(job.message, CornColors.Mute, 11.sp, mono = true)
    }
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CornButton(if (showLog) "收起日志" else "完整日志", { showLog = !showLog }, Modifier.weight(1f))
        CornButton("重试此板", vm::startSingle, Modifier.weight(1.4f), primary = true, icon = Lucide.Refresh)
    }
}

/** Screen 19: target did not answer. */
@Composable
private fun ColumnScope.NoTargetScreen(vm: FlashViewModel, state: SessionState, job: JobEntity, onBack: () -> Unit) {
    val sim = job.mode == "DEMO" || vm.prefs.simulator
    val vLabel = voltageLabel(vm, state)
    BackTop("目标板没有响应", "工位 1 · ${job.firmwareName}", onBack = onBack) { Chip("目标未连接", ChipTone.BAD, dot = true) }
    ScrollBody(Modifier.weight(1f)) {
        Chain(
            ChainNode(Lucide.Phone, "Android", "OTG 可用", CornColors.Leaf),
            ChainNode(Lucide.Usb, "ST-LINK/V2", if (sim) "模拟器" else "已授权", CornColors.Leaf),
            ChainNode(Lucide.Chip, "目标板", if (sim) "0.00 V" else vLabel, CornColors.Red),
        )
        Banner("目标板没有响应", "探针正常，但读不到目标电压，也连不上 SWD。（${job.message}）")
        CornCard(spacing = 0.dp) {
            Label("SWD 接线检查", Modifier.padding(bottom = 4.dp))
            PinRow("3V3", "目标 VDD", if (sim) "0.00 V" else vLabel, CornColors.Red)
            PinRow("SWDIO", "PA13", "—", CornColors.Dim)
            PinRow("SWCLK", "PA14", "—", CornColors.Dim)
            PinRow("GND", "GND", "共地", CornColors.Leaf, last = true)
            T("ST-LINK/V2 的 3V3 脚只检测电压，不给目标板供电。", CornColors.Mute, 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        CornCard(padding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ico(Lucide.Refresh, CornColors.Corn, 16.dp)
                T(
                    if (sim) "点「复位后连接」再试一次（模拟器不会自动重试）"
                    else "点「复位后连接」再试一次；仍失败请检查目标供电与 SWD 线",
                    CornColors.Dim, 12.sp, modifier = Modifier.weight(1f),
                )
            }
        }
    }
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CornButton(if (sim) "降速重试 · 模拟" else "降速重试", vm::startSingle, Modifier.weight(1f))
        CornButton("复位后连接", vm::startSingle, Modifier.weight(1f), primary = true)
    }
}

private fun voltageLabel(vm: FlashViewModel, state: SessionState): String {
    val sim = state.job?.mode == "DEMO" || vm.prefs.simulator
    return when {
        sim -> "3.30 V · 模拟"
        state.voltageMv > 0 -> "%.2f V".format(state.voltageMv / 1000.0)
        else -> "未知（未读取）"
    }
}

@Composable
fun PinRow(pin: String, target: String, value: String, color: Color, last: Boolean = false) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            T(pin, size = 13.sp, weight = FontWeight.Bold, mono = true, modifier = Modifier.width(64.dp))
            T("→", CornColors.Mute, 13.sp, modifier = Modifier.width(24.dp))
            T(target, CornColors.Dim, 13.sp, mono = true, modifier = Modifier.weight(1f))
            T(value, color, 12.5.sp, FontWeight.SemiBold, mono = true)
        }
        if (!last) DividerLine()
    }
}

/** Screen 20: aborted / interrupted mid-flash. */
@Composable
private fun ColumnScope.AbortedScreen(vm: FlashViewModel, state: SessionState, log: RunLog, onBack: () -> Unit) {
    val job = state.job!!
    val fw = state.firmware
    val stopped = log.stageStart.keys.filter { it != FlashStage.SUCCEEDED }.maxByOrNull { it.ordinal } ?: FlashStage.CONNECTING
    BackTop("烧录中断", "工位 1 · ${job.firmwareName}", onBack = onBack) { Chip("未完成", ChipTone.BAD, dot = true) }
    ScrollBody(Modifier.weight(1f)) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(CornColors.RedContainer), contentAlignment = Alignment.Center) {
                Ico(Lucide.Usb, CornColors.Red, 36.dp)
            }
            T(if (job.status == "INTERRUPTED") "烧录被打断" else "已手动中止", size = 22.sp, weight = FontWeight.Bold)
            T("停在「${stepLabel(stopped, null).trim()}」· ${formatTime(job.finishedAt ?: job.startedAt)}", CornColors.Dim, 12.5.sp)
        }
        Banner("这块板现在不能用", "可能已经擦除，只写了一部分，程序不完整。同一块板需要从头重烧。")
        StepList(null, stopped, log, fw?.let { erasePages(it, vm.profile) }, running = false, stopText = "中断")
        DashedNote {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Ico(Lucide.Repeat, CornColors.Corn, 16.dp)
                T("等待重新开始…", CornColors.Corn, 14.sp, FontWeight.SemiBold)
            }
            T("接回后会核对 UID，同一块板从头重烧", CornColors.Dim, 11.5.sp)
        }
    }
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CornButton("标为不良", onBack, Modifier.weight(1f), textColor = CornColors.Red)
        CornButton("从头重烧", vm::startSingle, Modifier.weight(1f), primary = true, icon = Lucide.Refresh)
    }
}
