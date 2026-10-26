package dev.corn.flashing.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.corn.flashing.core.FlashStage
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
    val notice by vm.notice.collectAsStateWithLifecycle()
    val batch by vm.batch.collectAsStateWithLifecycle()
    val offer by vm.resumeOffer.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf(Page.NONE) }
    var batchMode by rememberSaveable { mutableStateOf(false) }
    var binAddress by rememberSaveable { mutableStateOf("08000000") }
    val snackbar = remember { SnackbarHostState() }
    val log = remember { RunLog() }
    val prefs = vm.prefs
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri, binAddress)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri)
    }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.clearNotice() } }
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

    CornTheme {
        val sim = prefs.simulator
        Box(Modifier.fillMaxSize().background(CornColors.Background)) {
            val reportId = report
            when {
                reportId != null -> {
                    BackHandler { vm.openReport(null) }
                    BatchReportScreen(vm, reportId, onBack = { vm.openReport(null) })
                }
                page == Page.FLOW && batch == null -> {
                    BackHandler(enabled = !state.running) { page = Page.NONE }
                    FlowScreen(vm, state, log, history, onBack = { if (!state.running) page = Page.NONE })
                }
                page == Page.SETTINGS -> {
                    BackHandler { page = Page.NONE }
                    SettingsScreen(vm, onBack = { page = Page.NONE }, onExport = { exporter.launch("corn-flashing-records.json") })
                }
                page == Page.FIRMWARE_DETAIL && state.firmware != null -> {
                    BackHandler { page = Page.NONE }
                    FirmwareDetailScreen(state.firmware!!, vm.profile, onBack = { page = Page.NONE })
                }
                page == Page.NEW_BATCH && batch == null -> {
                    BackHandler { page = Page.NONE }
                    NewBatchScreen(vm, state, devices, onBack = { page = Page.NONE },
                        onGoFirmware = { page = Page.NONE; tab = 1 }, onStarted = { page = Page.NONE; tab = 0; batchMode = true })
                }
                else -> Scaffold(
                    containerColor = CornColors.Background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = { BottomNav(tab) { tab = it } },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
                        when (tab) {
                            0 -> Workbench(vm, state, history, devices, batchMode, sim,
                                onMode = { wanted ->
                                    if (!wanted && batch != null) vm.notify("批次未结束：结束批次后才能切回单路") else batchMode = wanted
                                },
                                onSettings = { page = Page.SETTINGS }, onGoFirmware = { tab = 1 },
                                onStart = { vm.startSingle(); page = Page.FLOW },
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
        }
        offer?.let { ResumeSheet(it, vm) }
    }
}

/** Tone on success, vibration on failure (settings 10). */
@Composable
private fun FinishFeedback(vm: FlashViewModel, state: SessionState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var lastId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.job?.id, state.running) {
        val job = state.job ?: return@LaunchedEffect
        if (state.running || job.finishedAt == null || job.id == lastId) return@LaunchedEffect
        if (lastId == null) { lastId = job.id; return@LaunchedEffect } // do not beep for a restored job
        lastId = job.id
        if (job.status in listOf("SIMULATED", "SUCCEEDED") && vm.prefs.successTone) runCatching {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80)
                .startTone(android.media.ToneGenerator.TONE_PROP_ACK, 200)
        }
        if (job.status == "FAILED" && vm.prefs.vibrateOnFail) runCatching {
            val vibrator = context.getSystemService(android.os.Vibrator::class.java)
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(300, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}

// ---------------------------------------------------------------- nav

@Composable
private fun BottomNav(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf(Lucide.Bolt to "工作台", Lucide.File to "固件", Lucide.Sliders to "配置", Lucide.Usb to "设备", Lucide.List to "记录")
    Column(Modifier.fillMaxWidth().background(CornColors.NavBackground).navigationBarsPadding()) {
        DividerLine()
        Row(Modifier.fillMaxWidth().height(76.dp).padding(top = 8.dp, bottom = 14.dp)) {
            items.forEachIndexed { index, (icon, label) ->
                val active = index == tab
                Column(Modifier.weight(1f).fillMaxHeight().clickable { onTab(index) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                    Box(Modifier.size(52.dp, 28.dp).clip(RoundedCornerShape(99.dp))
                        .background(if (active) CornColors.CornContainer else Color.Transparent), contentAlignment = Alignment.Center) {
                        Ico(icon, if (active) CornColors.Corn else CornColors.Mute, 20.dp)
                    }
                    T(label, if (active) CornColors.Text else CornColors.Mute, 11.sp, if (active) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun ScrollBody(modifier: Modifier = Modifier, spacing: Int = 12, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.dp), content = content)
}

// ---------------------------------------------------------------- workbench (01 / 17 / 13)

@Composable
private fun Workbench(
    vm: FlashViewModel, state: SessionState, history: List<JobEntity>, devices: List<UsbProbe>,
    batchMode: Boolean, sim: Boolean, onMode: (Boolean) -> Unit, onSettings: () -> Unit, onGoFirmware: () -> Unit,
    onStart: () -> Unit, onNewBatch: () -> Unit,
) {
    val batch by vm.batch.collectAsStateWithLifecycle()
    val probe = devices.firstOrNull { it.candidate }
    val probeOk = sim || probe?.permissionGranted == true
    if (batchMode) {
        BatchWorkbench(vm, state, batch, onMode, onSettings, onNewBatch)
        return
    }
    TopBar("Corn Flashing", "离线烧录 · STM32F103") {
        if (probeOk) Chip("工位 1", ChipTone.OK, dot = true) else Chip("未就绪", ChipTone.BAD, dot = true)
        IconBtn(Lucide.Gear, onSettings)
    }
    ScrollBody {
        Seg(listOf("单路", "批量"), 0, { onMode(it == 1) })
        Chain(
            ChainNode(Lucide.Phone, "Android", if (vm.hostSupported) (if (probe?.permissionGranted == true) "OTG 已授权" else "OTG 可用") else "无 USB Host",
                if (vm.hostSupported) CornColors.Leaf else CornColors.Red),
            ChainNode(Lucide.Usb, "ST-LINK/V2", when { sim -> "模拟器"; probe == null -> "未连接"; probe.permissionGranted -> probe.serial?.take(8) ?: "已授权"; else -> "待授权" },
                if (probeOk) CornColors.Leaf else if (probe != null) CornColors.Corn else CornColors.Red),
            ChainNode(Lucide.Chip, if (sim) "F103C8" else "目标板", if (sim) "3.30 V · 模拟" else "—",
                if (sim) CornColors.Leaf else CornColors.Red),
        )
        if (!probeOk) {
            NoProbeCard(probe, devices.isNotEmpty(), vm)
            CornButton("开始烧录", {}, Modifier.fillMaxWidth(), enabled = false, height = 64, radius = 20, icon = Lucide.Bolt,
                subtitle = if (probe != null && probe.permissionGranted) "真实烧录尚未接入" else "先接上 ST-LINK")
            T("没有硬件？用模拟烧录器演练流程", CornColors.Corn, 13.sp, FontWeight.SemiBold, align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { vm.prefs.simulator = true }.padding(8.dp))
            return@ScrollBody
        }
        FirmwareCard(state.firmware, vm.profile, onGoFirmware, vm)
        val firmware = state.firmware
        val pages = firmware?.let { erasePages(it, vm.profile) }
        CornButton("开始烧录", onStart, Modifier.fillMaxWidth(), primary = true, height = 64, radius = 20, icon = Lucide.Bolt,
            enabled = state.ready && !state.importing && firmware != null && !state.running,
            subtitle = if (firmware == null) "先选择固件" else "擦除 $pages 页 · 写入 ${kb(firmware.image.totalBytes)} · 回读校验")
        CornCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                SRow("自动换板", "检测到新目标板后自动开始") { CornToggle(vm.prefs.autoNextBoard) { vm.prefs.autoNextBoard = it } }
                SRow("完成后复位运行", "烧录后让目标板直接跑起来", last = true) { CornToggle(vm.prefs.resetAfter) { vm.prefs.resetAfter = it } }
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

fun erasePages(firmware: ImportedFirmware, profile: ProductProfile): Int =
    firmware.image.segments.flatMap { s ->
        val first = (s.address - profile.flashStart) / profile.pageSize
        val last = (s.endAddressExclusive - 1 - profile.flashStart) / profile.pageSize
        (first..last).toList()
    }.toSet().size

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
            T("F103 · ${profile.flashSizeBytes / 1024} KB", CornColors.Mute, 10.sp, mono = true)
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
        Label("今日 · 工位 1")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatCell("${ok.size}", "成功", CornColors.Leaf, modifier = Modifier.weight(1f))
            VDivider()
            StatCell("$bad", "失败", CornColors.Red, modifier = Modifier.weight(1f))
            VDivider()
            StatCell(percent((ok.size + bad).takeIf { it > 0 }?.let { ok.size.toFloat() / it }), "良率", modifier = Modifier.weight(1f))
            VDivider()
            StatCell(avg?.let { "%.1f".format(it / 1000f) + "s" } ?: "—", "平均", modifier = Modifier.weight(1f))
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
                    if (i < SIX_STEPS.lastIndex) Box(Modifier.width(2.dp).height(14.dp).background(if (done) CornColors.Leaf.copy(alpha = .5f) else CornColors.Line))
                }
                Spacer(Modifier.width(10.dp))
                T(stepLabel(stage, pages), when { bad -> CornColors.Red; now -> CornColors.Corn; done -> CornColors.Text; else -> CornColors.Mute },
                    13.5.sp, if (now) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f).padding(top = 2.dp))
                val t = log?.let { stepTime(it, i) }
                T(when { bad -> stopText; t != null -> t; now -> "…"; else -> "" },
                    if (bad) CornColors.Red else CornColors.Dim, 11.5.sp, mono = true, modifier = Modifier.padding(top = 3.dp))
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
    BackTop("烧录完成", "工位 1 · ${job.firmwareName}", onBack = onBack) { Chip("可拔线", ChipTone.OK, dot = true) }
    ScrollBody(Modifier.weight(1f)) {
        ResultHeader(Lucide.Check, CornColors.Leaf, CornColors.LeafContainer, "烧录成功",
            "#$seq · 用时 ${secs((job.finishedAt ?: job.startedAt) - job.startedAt)} · ${formatTime(job.finishedAt ?: job.startedAt)}")
        CornCard(padding = 14.dp) {
            KeyValue("芯片", state.target?.let { "STM32F103 · ${it.flashSizeBytes / 1024} KB" } ?: "STM32F103")
            KeyValue("UID", shortUid(job.targetUid), mono = true)
            KeyValue("写入", fw?.let { "${kb(it.image.totalBytes)} · ${erasePages(it, vm.profile)} 页" } ?: "—", mono = true)
            KeyValue("回读校验", "通过 · 模拟", valueColor = CornColors.Leaf)
            KeyValue("复位", if (vm.prefs.resetAfter) "已运行 · 模拟" else "未复位", last = true)
        }
        if (showLog) LiveLog(log, expandedDefault = true)
        DashedNote {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Ico(Lucide.Repeat, CornColors.Corn, 16.dp)
                T("等待下一块板…", CornColors.Corn, 14.sp, FontWeight.SemiBold)
            }
            T(if (vm.prefs.autoNextBoard) "换上新板后自动开始（模拟：点「立即再烧一块」）" else "自动换板已关闭", CornColors.Dim, 11.5.sp)
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

private val noTargetWords = listOf("电压", "未响应")

@Composable
private fun ColumnScope.FailedScreen(vm: FlashViewModel, state: SessionState, log: RunLog, seq: Int, onBack: () -> Unit) {
    val job = state.job!!
    val stage = state.failedStage
    val noTarget = stage == FlashStage.PROBING || noTargetWords.any { job.message.contains(it) }
    var showLog by remember { mutableStateOf(false) }
    if (noTarget) {
        NoTargetScreen(vm, job, onBack)
        return
    }
    val addr = Regex("0x[0-9A-Fa-f]{8}").find(job.message)?.value
    val hex = Regex("0x[0-9A-Fa-f]{2}\\b").findAll(job.message).map { it.value }.toList()
    BackTop("烧录失败", "工位 1 · ${job.firmwareName}", onBack = onBack) { Chip("该板未通过", ChipTone.BAD, dot = true) }
    ScrollBody(Modifier.weight(1f)) {
        ResultHeader(Lucide.X, CornColors.Red, CornColors.RedContainer,
            stage?.let { if (it == FlashStage.VERIFYING) "校验失败" else "${stageLabel(it)}失败" } ?: "烧录失败",
            "#$seq · 停在「${stage?.let { stepLabel(it, null).trim() } ?: job.stage}」")
        CornCard {
            KeyValue("出错地址", addr ?: "—", mono = true, valueColor = CornColors.Red)
            KeyValue("期望", hex.getOrNull(0) ?: "—", mono = true)
            KeyValue("实际", hex.getOrNull(1) ?: "—", mono = true, valueColor = CornColors.Red)
            KeyValue("已完成", stage?.let { s -> SIX_STEPS.takeWhile { it != s }.joinToString(" · ") { stageLabel(it) } } ?: "—")
            KeyValue("目标电压", "3.30 V · 模拟", mono = true, last = true)
        }
        CornCard(color = CornColors.RedContainer, border = CornColors.Red.copy(alpha = .25f), spacing = 4.dp) {
            T("可能原因", CornColors.Red, 13.5.sp, FontWeight.Bold)
            T("· 这块板的 Flash 有坏页，写入后读回不一致", CornColors.Dim, 12.sp)
            T("· SWD 线过长或接触不良，写入时数据出错", CornColors.Dim, 12.sp)
            T("· 目标板供电不稳，擦写时电压跌落", CornColors.Dim, 12.sp)
        }
        CornCard(spacing = 4.dp) {
            T("建议", size = 13.5.sp, weight = FontWeight.Bold)
            T("先重试一次；同一块板连续失败就标为不良换板，不同板都失败请检查接线和供电。", CornColors.Dim, 12.sp)
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
private fun ColumnScope.NoTargetScreen(vm: FlashViewModel, job: JobEntity, onBack: () -> Unit) {
    BackTop("目标板没有响应", "工位 1 · ${job.firmwareName}", onBack = onBack) { Chip("目标未连接", ChipTone.BAD, dot = true) }
    ScrollBody(Modifier.weight(1f)) {
        Chain(
            ChainNode(Lucide.Phone, "Android", "OTG 可用", CornColors.Leaf),
            ChainNode(Lucide.Usb, "ST-LINK/V2", "模拟器", CornColors.Leaf),
            ChainNode(Lucide.Chip, "目标板", "0.00 V", CornColors.Red),
        )
        Banner("目标板没有响应", "探针正常，但读不到目标电压，也连不上 SWD。（${job.message}）")
        CornCard(spacing = 0.dp) {
            Label("SWD 接线检查", Modifier.padding(bottom = 4.dp))
            PinRow("3V3", "目标 VDD", "0.00 V", CornColors.Red)
            PinRow("SWDIO", "PA13", "—", CornColors.Dim)
            PinRow("SWCLK", "PA14", "—", CornColors.Dim)
            PinRow("GND", "GND", "共地", CornColors.Leaf, last = true)
            T("ST-LINK/V2 的 3V3 脚只检测电压，不给目标板供电。", CornColors.Mute, 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        CornCard(padding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ico(Lucide.Refresh, CornColors.Corn, 16.dp)
                T("点「复位后连接」再试一次（模拟器不会自动重试）", CornColors.Dim, 12.sp, modifier = Modifier.weight(1f))
            }
        }
    }
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CornButton("降速重试 · 模拟", vm::startSingle, Modifier.weight(1f))
        CornButton("复位后连接", vm::startSingle, Modifier.weight(1f), primary = true)
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
