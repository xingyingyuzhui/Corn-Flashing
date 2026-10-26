package dev.corn.flashing.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.corn.flashing.core.*
import dev.corn.flashing.storage.*
import dev.corn.flashing.usb.UsbProbe

// ---------------------------------------------------------------- batch workbench (13)

@Composable
fun BatchWorkbench(vm: FlashViewModel, state: SessionState, view: BatchView?, onMode: (Boolean) -> Unit,
                   onSettings: () -> Unit, onNewBatch: () -> Unit) {
    val fwName = view?.batch?.firmwareName ?: state.firmware?.name ?: "未选固件"
    TopBar("Corn Flashing", "批量 · 工位 1 · $fwName") {
        when {
            view == null -> Chip("未开始")
            view.phase == BatchPhase.PAUSED -> Chip("已暂停", ChipTone.WARN, dot = true)
            view.phase == BatchPhase.FINISHED -> Chip("已完成", ChipTone.OK, dot = true)
            else -> Chip("运行中", ChipTone.OK, dot = true)
        }
        IconBtn(Lucide.Gear, onSettings)
    }
    ScrollBody {
        Seg(listOf("单路", "批量"), 1, { onMode(it == 1) })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("单工位连续", ChipTone.WARN)
            Chip("多工位并行 · 未开放")
            Spacer(Modifier.weight(1f))
            view?.let { T(it.batch.workOrder, CornColors.Dim, 12.sp, mono = true) }
        }
        if (view == null) {
            CornCard {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Illo(Lucide.Repeat)
                    T("还没有进行中的批次", size = 17.sp, weight = FontWeight.Bold)
                    T("填好工单号、计划数量和规则后开始，每块板都会留记录", CornColors.Dim, 12.5.sp, align = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    CornButton("新建批次", onNewBatch, Modifier.fillMaxWidth(), primary = true, icon = Lucide.Plus)
                }
            }
            return@ScrollBody
        }
        BatchRun(vm, state, view)
    }
}

@Composable
private fun BatchRun(vm: FlashViewModel, state: SessionState, view: BatchView) {
    val tally = view.tally
    val planned = view.batch.planned
    val outcomes = view.rows.sortedBy { it.seq ?: 0 }.mapNotNull { it.outcome() }
    val durations = view.rows.filter { it.outcome() == BoardOutcome.SUCCEEDED }.mapNotNull { r -> r.finishedAt?.let { it - r.startedAt } }
    val avg = durations.takeIf { it.isNotEmpty() }?.average()?.toLong()
    val remaining = avg?.let { (planned - tally.succeeded).coerceAtLeast(0) * (it + 1800) }
    CornCard(spacing = 8.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            T("本批次进度", CornColors.Dim, 11.5.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                T("${tally.succeeded}", size = 54.sp, weight = FontWeight.Bold, mono = true, lineHeight = 58.sp)
                T("/ $planned", CornColors.Dim, 24.sp, FontWeight.Bold, mono = true, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
            }
        }
        CornBar(tally.succeeded.toFloat() / planned, height = 8)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            StatCell("${tally.succeeded}", "成功", CornColors.Leaf, 18.sp, Modifier.weight(1f)); VDivider()
            StatCell("${tally.failed}", "失败", CornColors.Red, 18.sp, Modifier.weight(1f)); VDivider()
            StatCell("${tally.skippedDuplicates}", "跳过重复", CornColors.Corn, 18.sp, Modifier.weight(1f)); VDivider()
            StatCell(remaining?.let { "≈${(it / 60000).coerceAtLeast(1)}′" } ?: "—", "剩余", CornColors.Text, 18.sp, Modifier.weight(1f))
        }
    }
    CornCard(spacing = 8.dp) {
        Row(Modifier.fillMaxWidth()) {
            Label("最近 30 块", Modifier.weight(1f))
            T("最新在右", CornColors.Mute, 11.sp)
        }
        OutcomeStrip(outcomes)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDot(CornColors.Leaf, "成功"); LegendDot(CornColors.Red, "失败"); LegendDot(CornColors.Corn, "重复 UID")
        }
    }
    view.notice?.let { Banner("重复 UID", it, tone = ChipTone.WARN) }
    when (view.phase) {
        BatchPhase.FLASHING -> CornCard(spacing = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("#${tally.lastSeq + 1}  ${state.stage}", size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                T("${(state.progress * 100).toInt()}%", CornColors.Corn, 14.sp, FontWeight.Bold, mono = true)
            }
            CornBar(state.progress)
            T("UID ${shortUid(state.target?.uid)} · ${state.message}", CornColors.Dim, 11.5.sp)
            if (view.batch.status == BatchStatus.PAUSED) T("这块烧完后暂停", CornColors.Corn, 12.sp)
        }
        BatchPhase.WAITING_BOARD -> DashedNote(border = CornColors.Corn.copy(alpha = .45f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Ico(Lucide.Repeat, CornColors.Corn, 16.dp)
                T("等待下一块板", CornColors.Corn, 16.sp, FontWeight.Bold)
            }
            val last = view.rows.filter { it.outcome() == BoardOutcome.SUCCEEDED }.maxByOrNull { it.seq ?: 0 }
            T(last?.let { "#${it.seq} 成功${it.serial?.let { s -> " · $s" } ?: ""} · 换上新板后自动开始" } ?: "接上新板后自动开始（模拟）",
                CornColors.Dim, 11.5.sp)
        }
        BatchPhase.PAUSED -> PausedPanel(view)
        BatchPhase.FINISHED -> Banner("批次完成", "已成功 ${tally.succeeded} 块，达到计划数量。", tone = ChipTone.OK, icon = Lucide.Check)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (view.phase) {
            BatchPhase.PAUSED -> CornButton("继续批次", vm::resumeBatch, Modifier.weight(1f), primary = true, icon = Lucide.Play)
            BatchPhase.FINISHED -> Unit
            else -> CornButton("暂停批次", { vm.pauseBatch() }, Modifier.weight(1f))
        }
        if (view.phase == BatchPhase.FINISHED) CornButton("查看批次报告", vm::endBatch, Modifier.weight(1f), primary = true)
        else CornButton("结束并出报告", vm::endBatch, Modifier.weight(1f), textColor = CornColors.Red)
    }
    SimNote()
}

/** Paused: reason + the failures that caused it. */
@Composable
private fun PausedPanel(view: BatchView) {
    val reason = view.batch.pauseReason ?: "已暂停"
    val lastRow = view.rows.maxByOrNull { it.seq ?: 0 }
    if (lastRow != null && lastRow.status in listOf("CANCELLED", "INTERRUPTED")) {
        Banner("这块板现在不能用", "#${lastRow.seq} 在「${lastRow.stage}」中断：程序可能不完整，同一块板需要从头重烧。")
    }
    Banner("批次已暂停", reason, tone = if (reason.contains("失败") || reason.contains("中止")) ChipTone.BAD else ChipTone.WARN)
    val failures = view.rows.sortedByDescending { it.seq ?: 0 }
        .filter { it.outcome() == BoardOutcome.FAILED || it.outcome() == BoardOutcome.SUCCEEDED }
        .takeWhile { it.outcome() == BoardOutcome.FAILED }
    if (reason.contains("连续失败") && failures.isNotEmpty()) {
        CornCard {
            Label("导致暂停的失败")
            failures.forEachIndexed { i, row ->
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        T("#${row.seq}", CornColors.Dim, 12.sp, mono = true, modifier = Modifier.width(48.dp))
                        T(shortUid(row.targetUid), size = 12.sp, mono = true, modifier = Modifier.weight(1f))
                        T(row.stage, CornColors.Red, 12.sp)
                    }
                    if (i < failures.lastIndex) DividerLine()
                }
            }
            if (failures.size >= 2) {
                val sameUid = failures.mapNotNull { it.targetUid }.distinct().size == 1
                Spacer(Modifier.height(8.dp))
                Banner("判断", if (sameUid) "几次都是同一块板（UID 相同），更像是这块板有问题，请标为不良并换板。"
                    else "不同的板都失败了，更像是接线、探针或供电问题，请检查后继续。", tone = ChipTone.INFO)
            }
            failures.firstOrNull()?.takeIf { it.message.contains("电压") }?.let {
                Spacer(Modifier.height(8.dp))
                Label("SWD 接线检查")
                PinRow("3V3", "目标 VDD", "0.00 V", CornColors.Red)
                PinRow("SWDIO", "PA13", "—", CornColors.Dim)
                PinRow("SWCLK", "PA14", "—", CornColors.Dim)
                PinRow("GND", "GND", "共地", CornColors.Leaf, last = true)
            }
        }
    }
}

// ---------------------------------------------------------------- new batch (12)

@Composable
fun NewBatchScreen(vm: FlashViewModel, state: SessionState, devices: List<UsbProbe>, onBack: () -> Unit,
                   onGoFirmware: () -> Unit, onStarted: () -> Unit,
                   withNotifications: (() -> Unit) -> Unit = { it() }) {
    var workOrder by rememberSaveable { mutableStateOf("WO-" + java.text.SimpleDateFormat("MMdd", java.util.Locale.getDefault()).format(java.util.Date()) + "-A") }
    var planned by rememberSaveable { mutableIntStateOf(20) }
    var skipDup by rememberSaveable { mutableStateOf(true) }
    var pauseAfter by rememberSaveable { mutableIntStateOf(3) }
    var serialOn by rememberSaveable { mutableStateOf(true) }
    val firmware = state.firmware
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        BackTop("新建批次", "批量烧录 · 单工位连续", onBack = onBack)
        ScrollBody(Modifier.weight(1f)) {
            CornCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    Label("批次", Modifier.padding(top = 12.dp))
                    Column {
                        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            T("工单号", CornColors.Dim, 13.sp, modifier = Modifier.width(76.dp))
                            BasicTextField(workOrder, { workOrder = it.take(32) }, singleLine = true, cursorBrush = SolidColor(CornColors.Corn),
                                textStyle = TextStyle(color = CornColors.Text, fontSize = 14.sp, fontFamily = Mono, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End),
                                modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(6.dp)); Chev()
                        }
                        DividerLine()
                    }
                    SRow("固件", firmware?.let { "${shortSha(it.image.sourceSha256)} · ${kb(it.image.totalBytes)}" } ?: "未选择 · 点此去固件库",
                        onClick = if (firmware == null) onGoFirmware else null) {
                        if (firmware != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FormatBadge(firmware.image.format.name); T(firmware.name, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
                        } else Chev()
                    }
                    SRow("产品配置", vm.profile.name) { Chev() }
                    SRow("计划数量", "成功数到达后自动结束", last = true) { Stepper(planned, { planned = it }) }
                }
            }
            CornCard(spacing = 10.dp) {
                Row { Label("工位", Modifier.weight(1f)); T("已选 1", CornColors.Corn, 11.5.sp, FontWeight.SemiBold) }
                val probe = devices.firstOrNull { it.candidate }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StationTile(
                        "工位 1",
                        if (vm.prefs.simulator) "模拟烧录器"
                        else (probe?.name ?: "请先授权 ST-LINK"),
                        true,
                        Modifier.weight(1f),
                    )
                    StationTile("工位 2", "未连接", false, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StationTile("工位 3", "未连接", false, Modifier.weight(1f))
                    StationTile("工位 4", "未连接", false, Modifier.weight(1f))
                }
                T(
                    if (vm.prefs.simulator) "模拟批次 · 单工位连续"
                    else "真实批次 · 单工位连续（每块板探测后烧录）",
                    CornColors.Mute, 11.sp,
                )
            }
            CornCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    Label("规则", Modifier.padding(top = 12.dp))
                    SRow("同一 UID", "已烧过的板再次接入") {
                        Chip(if (skipDup) "跳过并提醒" else "允许重烧", if (skipDup) ChipTone.WARN else ChipTone.NEUTRAL, onClick = { skipDup = !skipDup })
                    }
                    SRow("连续失败自动暂停", "0 表示不暂停") { Stepper(pauseAfter, { pauseAfter = it }, min = 0, max = 99) }
                    SRow("写入序列号", "CF-000001 起 · 只记录在报告里（模拟，不写芯片）", last = true) { CornToggle(serialOn) { serialOn = it } }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(CornColors.NavBackground)) {
            DividerLine()
            CornButton("开始批次 · 1 个工位", primary = true, height = 56, icon = Lucide.Play,
                enabled = firmware != null && !state.running && workOrder.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                onClick = {
                    runCatching {
                        BatchConfig(workOrder, planned, if (skipDup) DuplicatePolicy.SKIP_AND_WARN else DuplicatePolicy.ALLOW,
                            pauseAfter, if (serialOn) SerialFormat() else null)
                    }.onSuccess { cfg -> withNotifications { vm.startBatch(cfg, vm.prefs.injectFaults, onStarted) } }.onFailure { vm.notify(it.message ?: "参数无效") }
                })
        }
    }
}

@Composable
private fun StationTile(name: String, sub: String, checked: Boolean, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(CornColors.Surface2)
        .border(1.dp, if (checked) CornColors.Corn.copy(alpha = .55f) else CornColors.Line, RoundedCornerShape(14.dp)).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        CheckBox(checked)
        Column {
            T(name, if (checked) CornColors.Text else CornColors.Mute, 13.5.sp, FontWeight.SemiBold)
            T(sub, CornColors.Mute, 11.sp, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- resume (22)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumeSheet(offer: ResumeOffer, vm: FlashViewModel) {
    ModalBottomSheet(onDismissRequest = vm::dismissResumeOffer, containerColor = CornColors.Surface1,
        scrimColor = Color.Black.copy(alpha = .65f), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(CornColors.CornContainer), contentAlignment = Alignment.Center) {
                    Ico(Lucide.Repeat, CornColors.Corn, 20.dp)
                }
                Column {
                    T("上次的批次没有结束", size = 17.sp, weight = FontWeight.Bold)
                    T("${offer.batch.workOrder} · ${formatDateTime(offer.batch.startedAt)} 开始 · 应用被关闭（系统回收或没电）", CornColors.Dim, 11.5.sp)
                }
            }
            CornCard(color = CornColors.Surface2, spacing = 8.dp) {
                Row(verticalAlignment = Alignment.Bottom) {
                    T("${offer.tally.succeeded}", size = 24.sp, weight = FontWeight.Bold, mono = true)
                    T(" / ${offer.batch.planned}", CornColors.Dim, 15.sp, FontWeight.Bold, mono = true, modifier = Modifier.padding(bottom = 3.dp))
                    Spacer(Modifier.weight(1f))
                    T("成功 ${offer.tally.succeeded} / 失败 ${offer.tally.failed}", CornColors.Dim, 12.sp, mono = true)
                }
                CornBar(offer.tally.succeeded.toFloat() / offer.batch.planned, height = 6)
            }
            if (offer.interrupted.isNotEmpty()) {
                Label("关闭时正在烧录的板")
                offer.interrupted.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("中断", ChipTone.BAD)
                        T("#${row.seq ?: "—"} · 工位 1 · ${shortUid(row.targetUid)}", size = 12.sp, mono = true, modifier = Modifier.weight(1f), maxLines = 1)
                        T("需要重烧", CornColors.Red, 12.sp, FontWeight.SemiBold)
                    }
                }
            }
            Column {
                val config = vm.batchConfig(offer.batch)
                BatchRules.nextSerial(config, offer.tally)?.let { KeyValue("序列号", "$it 起继续，不重复、不跳号", mono = true) }
                if (offer.blocker == null) KeyValue("固件 / 配置", "SHA 未变 ✓", valueColor = CornColors.Leaf, last = true)
            }
            if (offer.blocker != null) Banner("不能继续", offer.blocker)
            CornButton("继续批次", vm::resumeBatch, Modifier.fillMaxWidth(), primary = true, icon = Lucide.Play, enabled = offer.blocker == null)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CornButton("结束并出报告", vm::endBatch, Modifier.weight(1f))
                CornButton("稍后再说", vm::dismissResumeOffer, Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------- report (16)

@Composable
fun BatchReportScreen(vm: FlashViewModel, batchId: String, onBack: () -> Unit) {
    val batch by remember(batchId) { vm.batchById(batchId) }.collectAsStateWithLifecycle(initialValue = null)
    val rows by remember(batchId) { vm.batchRows(batchId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(batchId, uri)
    }
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(CornColors.Background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            BackTop("批次报告", "每块板一条，可追溯", onBack = onBack) { IconBtn(Lucide.List, { exporter.launch("${batch?.workOrder ?: "batch"}-report.csv") }) }
            val b = batch ?: run { T("加载中…", CornColors.Dim, modifier = Modifier.padding(16.dp)); return@Column }
            val tally = BatchController.tally(rows)
            val config = vm.batchConfig(b)
            val attempts = tally.succeeded + tally.failed
            val summary = "批次 ${b.workOrder}：烧录 $attempts 块，成功 ${tally.succeeded}，失败 ${tally.failed}，良率 ${percent(tally.yield)}（模拟数据）"
            ScrollBody(Modifier.weight(1f)) {
                CornCard(spacing = 10.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T(b.workOrder, size = 17.sp, weight = FontWeight.Bold, mono = true)
                            T(formatDateTime(b.startedAt) + (b.endedAt?.let { " – ${formatHm(it)} · ${formatDuration(it - b.startedAt)}" } ?: ""), CornColors.Dim, 11.5.sp)
                        }
                        Chip(when (b.status) { BatchStatus.COMPLETED -> "已完成"; BatchStatus.ENDED -> "提前结束"; else -> "进行中" },
                            if (b.status == BatchStatus.COMPLETED) ChipTone.OK else ChipTone.WARN, dot = true)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        StatCell("$attempts", "烧录", modifier = Modifier.weight(1f)); VDivider()
                        StatCell("${tally.succeeded}", "成功", CornColors.Leaf, modifier = Modifier.weight(1f)); VDivider()
                        StatCell("${tally.failed}", "失败", CornColors.Red, modifier = Modifier.weight(1f)); VDivider()
                        StatCell(percent(tally.yield), "良率", CornColors.Corn, 18.sp, Modifier.weight(1f))
                    }
                    Column {
                        KeyValue("固件", "${b.firmwareName} · ${shortSha(b.sha256)}", mono = true)
                        KeyValue("序列号", BatchRules.serialRange(config, tally)?.let { "${it.first} – ${it.second}" } ?: "—", mono = true)
                        KeyValue("跳过重复 UID", "${tally.skippedDuplicates} 次", mono = true, last = true)
                    }
                    if (b.mode == "DEMO") Chip("模拟数据 · 不计入真实良率", ChipTone.INFO)
                }
                CornCard {
                    Label("分工位")
                    val head = listOf("工位", "总数", "成功", "失败", "平均")
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) { head.forEach { T(it, CornColors.Mute, 11.sp, modifier = Modifier.weight(1f)) } }
                    DividerLine()
                    val avg = rows.filter { it.outcome() == BoardOutcome.SUCCEEDED }.mapNotNull { r -> r.finishedAt?.let { it - r.startedAt } }
                        .takeIf { it.isNotEmpty() }?.average()?.toLong()
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        T("工位 1", size = 12.5.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        T("${rows.size}", size = 12.5.sp, mono = true, modifier = Modifier.weight(1f))
                        T("${tally.succeeded}", CornColors.Leaf, 12.5.sp, mono = true, modifier = Modifier.weight(1f))
                        T("${tally.failed}", CornColors.Red, 12.5.sp, mono = true, modifier = Modifier.weight(1f))
                        T(avg?.let { secs(it) } ?: "—", CornColors.Dim, 12.5.sp, mono = true, modifier = Modifier.weight(1f))
                    }
                }
                val reasons = rows.filter { it.status == "FAILED" || it.status == "CANCELLED" || it.status == "INTERRUPTED" }
                    .groupingBy { if (it.status == "FAILED") it.stage.removePrefix("停在").trim('「', '」') + "失败" else "中断" }.eachCount()
                if (reasons.isNotEmpty()) CornCard(spacing = 8.dp) {
                    Label("失败原因")
                    val max = reasons.values.max().toFloat()
                    reasons.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            T(reason, size = 12.sp, modifier = Modifier.width(76.dp), maxLines = 1)
                            CornBar(count / max, Modifier.weight(1f), height = 8, color = if (reason == "中断") CornColors.Dim else CornColors.Red)
                            T("$count", size = 12.5.sp, weight = FontWeight.Bold, mono = true)
                        }
                    }
                }
                T("CSV 每块板一行：序号、工位、UID、序列号、结果、用时、阶段、说明", CornColors.Mute, 11.sp, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CornButton("导出 CSV", { exporter.launch("${b.workOrder}-report.csv") }, Modifier.weight(1f), icon = Lucide.Download)
                CornButton("分享报告", {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, summary)
                    context.startActivity(android.content.Intent.createChooser(send, "分享报告"))
                }, Modifier.weight(1f), primary = true)
            }
        }
    }
}
