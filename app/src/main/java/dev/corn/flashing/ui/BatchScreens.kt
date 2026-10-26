package dev.corn.flashing.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.corn.flashing.core.*
import dev.corn.flashing.storage.*

// ---------------------------------------------------------------- batch tab

fun LazyListScope.batchItems(vm: FlashViewModel, state: SessionState, view: BatchView?, onGoFirmware: () -> Unit) {
    if (view == null) {
        item { NewBatchForm(vm, state, onGoFirmware) }
    } else {
        item { BatchRun(vm, state, view) }
    }
}

/** Screen 12. */
@Composable
private fun NewBatchForm(vm: FlashViewModel, state: SessionState, onGoFirmware: () -> Unit) {
    var workOrder by rememberSaveable { mutableStateOf("") }
    var planned by rememberSaveable { mutableIntStateOf(20) }
    var skipDup by rememberSaveable { mutableStateOf(true) }
    var pauseAfter by rememberSaveable { mutableIntStateOf(3) }
    var serialOn by rememberSaveable { mutableStateOf(true) }
    var prefix by rememberSaveable { mutableStateOf("CF-") }
    var injectFaults by rememberSaveable { mutableStateOf(true) }
    val firmware = state.firmware
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("单工位连续", ChipTone.WARN)
            Chip("多工位并行 · 待开放")
        }
        CornCard {
            Label("新建批次")
            OutlinedTextField(workOrder, { workOrder = it }, singleLine = true, label = { Text("工单号") },
                placeholder = { Text("如 WO-1026-A") }, modifier = Modifier.fillMaxWidth())
            DividerLine()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("固件", color = CornColors.Dim, fontSize = 13.sp)
                    Text(firmware?.name ?: "未选择", color = if (firmware == null) CornColors.Red else CornColors.Text, fontWeight = FontWeight.SemiBold)
                }
                if (firmware == null) TextButton(onClick = onGoFirmware) { Text("去选择", color = CornColors.Corn) }
                else Text(shortSha(firmware.image.sourceSha256), color = CornColors.Dim, style = numericStyle, fontSize = 12.sp)
            }
            KeyValue("产品配置", vm.profile.name)
            DividerLine()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("计划数量", color = CornColors.Text, fontWeight = FontWeight.SemiBold)
                    Text("成功数到达后自动结束批次", color = CornColors.Dim, fontSize = 11.5.sp)
                }
                Stepper(planned, { planned = it })
            }
        }
        CornCard {
            Label("工位")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("工位 1 · 模拟烧录器", color = CornColors.Text, fontWeight = FontWeight.SemiBold)
                    Text("真实 ST-LINK 接入后可选择探针", color = CornColors.Dim, fontSize = 11.5.sp)
                }
                Chip("已就绪", ChipTone.OK, dot = true)
            }
        }
        CornCard {
            Label("规则")
            RuleRow("同一 UID 再次接入", "跳过并提醒，防止重复烧录、计数虚高") { CornToggle(skipDup) { skipDup = it } }
            RuleRow("连续失败自动暂停", "0 表示不暂停") { Stepper(pauseAfter, { pauseAfter = it }, min = 0, max = 99) }
            RuleRow("写入序列号", "仅烧录成功后分配，中断/失败不占号") { CornToggle(serialOn) { serialOn = it } }
            if (serialOn) {
                OutlinedTextField(prefix, { prefix = it.take(12) }, singleLine = true, label = { Text("序列号前缀") },
                    supportingText = { Text("示例：${SerialFormat(prefix).format(1)}") }, modifier = Modifier.fillMaxWidth())
            }
            RuleRow("模拟故障注入", "随机产生校验失败、目标无响应、重复 UID，用于演练") { CornToggle(injectFaults) { injectFaults = it } }
        }
        CornButton("开始批次 · 1 个工位", primary = true, enabled = firmware != null && !state.running && workOrder.isNotBlank(),
            subtitle = if (workOrder.isBlank()) "请填写工单号" else "计划 $planned 块",
            onClick = {
                val config = runCatching {
                    BatchConfig(workOrder, planned,
                        if (skipDup) DuplicatePolicy.SKIP_AND_WARN else DuplicatePolicy.ALLOW,
                        pauseAfter, if (serialOn) SerialFormat(prefix) else null)
                }
                config.onSuccess { vm.startBatch(it, injectFaults) {} }.onFailure { vm.notify(it.message ?: "参数无效") }
            })
    }
}

@Composable
private fun RuleRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = CornColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = CornColors.Dim, fontSize = 11.5.sp)
        }
        control()
    }
}

/** Screen 13 (+ paused / finished / interrupted variants). */
@Composable
private fun BatchRun(vm: FlashViewModel, state: SessionState, view: BatchView) {
    val tally = view.tally
    val planned = view.batch.planned
    val outcomes = view.rows.sortedBy { it.seq ?: 0 }.mapNotNull { it.outcome() }
    val durations = view.rows.filter { it.outcome() == BoardOutcome.SUCCEEDED }.mapNotNull { r -> r.finishedAt?.let { it - r.startedAt } }
    val avg = durations.takeIf { it.isNotEmpty() }?.average()?.toLong()
    val remaining = avg?.let { (planned - tally.succeeded).coerceAtLeast(0) * (it + 1800) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("单工位连续", ChipTone.WARN)
            Spacer(Modifier.weight(1f))
            Text(view.batch.workOrder, color = CornColors.Dim, style = numericStyle, fontSize = 12.sp)
        }
        CornCard {
            Text("本批次进度", color = CornColors.Dim, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
                Text("${tally.succeeded}", color = CornColors.Text, style = numericStyle, fontSize = 56.sp)
                Text(" / $planned", color = CornColors.Dim, style = numericStyle, fontSize = 22.sp, modifier = Modifier.padding(bottom = 10.dp))
            }
            CornBar(tally.succeeded.toFloat() / planned, height = 8)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Counter("${tally.succeeded}", "成功", CornColors.Leaf)
                Counter("${tally.failed}", "失败", CornColors.Red)
                Counter("${tally.skippedDuplicates}", "跳过重复", CornColors.Corn)
                Counter(remaining?.let { "≈${(it / 60000).coerceAtLeast(1)}分" } ?: "—", "剩余", CornColors.Text)
            }
        }
        CornCard {
            Row(Modifier.fillMaxWidth()) {
                Text("最近 30 块", color = CornColors.Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("良率 ${percent(tally.yield)}", color = CornColors.Dim, fontSize = 12.sp)
            }
            OutcomeStrip(outcomes)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Legend(CornColors.Leaf, "成功"); Legend(CornColors.Red, "失败"); Legend(CornColors.Corn, "重复 UID"); Legend(CornColors.Surface3, "中断")
            }
        }
        view.notice?.let { Banner("重复 UID", it, bad = false) }
        when (view.phase) {
            BatchPhase.FLASHING -> CornCard {
                Row {
                    Text("#${tally.lastSeq + 1} ${state.stage}", color = CornColors.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${(state.progress * 100).toInt()}%", color = CornColors.Corn, style = numericStyle)
                }
                CornBar(state.progress)
                Text("UID ${shortUid(state.target?.uid)} · ${state.message}", color = CornColors.Dim, fontSize = 12.sp)
                if (view.batch.status == BatchStatus.PAUSED) Text("这块烧完后暂停", color = CornColors.Corn, fontSize = 12.sp)
            }
            BatchPhase.WAITING_BOARD -> DashedNote {
                Glyph("⇄", CornColors.Corn, 22)
                Text("等待下一块板", color = CornColors.Text, fontWeight = FontWeight.Bold)
                val last = view.rows.filter { it.outcome() == BoardOutcome.SUCCEEDED }.maxByOrNull { it.seq ?: 0 }
                Text(last?.let { "#${it.seq} 成功${it.serial?.let { s -> " · $s" } ?: ""} · 换上新板后自动开始" } ?: "接上新板后自动开始（模拟）",
                    color = CornColors.Dim, fontSize = 12.sp)
            }
            BatchPhase.PAUSED -> PausedPanel(view)
            BatchPhase.FINISHED -> Banner("批次完成", "已成功 ${tally.succeeded} 块，达到计划数量。", bad = false, icon = "✓")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (view.phase) {
                BatchPhase.PAUSED -> CornButton("继续批次", vm::resumeBatch, Modifier.weight(1f), primary = true)
                BatchPhase.FINISHED -> Unit
                else -> CornButton("暂停批次", { vm.pauseBatch() }, Modifier.weight(1f))
            }
            CornButton(if (view.phase == BatchPhase.FINISHED) "查看批次报告" else "结束并出报告", vm::endBatch,
                Modifier.weight(1f), primary = view.phase == BatchPhase.FINISHED, danger = view.phase != BatchPhase.FINISHED)
        }
    }
}

/** Paused: reason + the failures that caused it (screen 15 adapted to one station, 20 for an aborted board). */
@Composable
private fun PausedPanel(view: BatchView) {
    val reason = view.batch.pauseReason ?: "已暂停"
    val lastRow = view.rows.maxByOrNull { it.seq ?: 0 }
    if (lastRow != null && lastRow.status in listOf("CANCELLED", "INTERRUPTED")) UnpluggedCard(lastRow)
    Banner("批次已暂停", reason, bad = reason.contains("失败") || reason.contains("中止"))
    val failures = view.rows.filter { it.status == "FAILED" }.sortedByDescending { it.seq ?: 0 }.take(view.batch.pauseAfterFailures.coerceAtLeast(3))
    if (reason.contains("连续失败") && failures.isNotEmpty()) {
        CornCard {
            Label("导致暂停的失败")
            failures.forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    Text("#${row.seq}", color = CornColors.Dim, style = numericStyle, fontSize = 12.sp, modifier = Modifier.width(48.dp))
                    Text(shortUid(row.targetUid), color = CornColors.Text, style = numericStyle, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(row.stage, color = CornColors.Red, fontSize = 12.sp)
                }
            }
            val sameUid = failures.mapNotNull { it.targetUid }.distinct().size == 1
            Banner("判断", if (sameUid) "几次都是同一块板（UID 相同），更像是这块板有问题，请标为不良并换板。"
                else "不同的板都失败了，更像是接线、探针或供电问题，请检查后继续。", bad = false, icon = "◇")
            failures.firstOrNull()?.let { FailureDetail(it.message, if (it.message.contains("电压")) FlashStage.PROBING else null) }
        }
    }
}

@Composable
private fun Counter(value: String, label: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, style = numericStyle, fontSize = 20.sp)
        Text(label, color = CornColors.Dim, fontSize = 11.5.sp)
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, color = CornColors.Dim, fontSize = 11.sp)
    }
}

// ---------------------------------------------------------------- resume (22)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumeSheet(offer: ResumeOffer, vm: FlashViewModel) {
    ModalBottomSheet(onDismissRequest = vm::dismissResumeOffer, containerColor = CornColors.Surface1) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("批次 ${offer.batch.workOrder} 没有结束", color = CornColors.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("应用上次被关闭时批次还在进行。", color = CornColors.Dim, fontSize = 12.5.sp)
            KeyValue("进度", "${offer.tally.succeeded} / ${offer.batch.planned}", mono = true)
            val config = vm.batchConfig(offer.batch)
            BatchRules.nextSerial(config, offer.tally)?.let { KeyValue("下一个序列号", it, mono = true) }
            if (offer.interrupted.isNotEmpty()) {
                Label("关闭时正在烧录")
                offer.interrupted.forEach { row ->
                    KeyValue("#${row.seq ?: "—"} ${shortUid(row.targetUid)}", "中断 · 需要重烧", valueColor = CornColors.Red)
                }
                Text("这些板不算成功，也不占序列号。", color = CornColors.Dim, fontSize = 12.sp)
            }
            if (offer.blocker != null) Banner("不能继续", offer.blocker)
            else KeyValue("固件 / 配置 SHA", "✓ 未变化", valueColor = CornColors.Leaf)
            CornButton("继续批次", vm::resumeBatch, primary = true, enabled = offer.blocker == null)
            CornButton("结束并出报告", vm::endBatch, danger = true)
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
    Surface(color = CornColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 返回", color = CornColors.Corn) }
                Text("批次报告", color = CornColors.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            val b = batch ?: run { Text("加载中…", color = CornColors.Dim); return@Column }
            val tally = BatchController.tally(rows)
            val config = vm.batchConfig(b)
            CornCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.workOrder, color = CornColors.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Chip(when (b.status) { BatchStatus.COMPLETED -> "已完成"; BatchStatus.ENDED -> "提前结束"; else -> "进行中" },
                        if (b.status == BatchStatus.COMPLETED) ChipTone.OK else ChipTone.WARN, dot = true)
                }
                Text("${formatDateTime(b.startedAt)}" + (b.endedAt?.let { " – ${formatDateTime(it)} · 用时 ${formatDuration(it - b.startedAt)}" } ?: ""),
                    color = CornColors.Dim, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Counter("${b.planned}", "计划", CornColors.Text)
                    Counter("${tally.succeeded}", "成功", CornColors.Leaf)
                    Counter("${tally.failed}", "失败", CornColors.Red)
                    Counter(percent(tally.yield), "良率", CornColors.Corn)
                }
                DividerLine()
                KeyValue("固件", "${b.firmwareName} · ${shortSha(b.sha256)}", mono = true)
                KeyValue("产品配置", b.profileName)
                KeyValue("序列号", BatchRules.serialRange(config, tally)?.let { "${it.first} – ${it.second}" } ?: "—", mono = true)
                KeyValue("跳过重复 UID", "${tally.skippedDuplicates} 次", mono = true)
                KeyValue("中断", "${tally.interrupted} 块", mono = true)
                if (b.mode == "DEMO") Chip("模拟数据 · 不计入真实良率", ChipTone.INFO)
            }
            val reasons = rows.filter { it.status == "FAILED" }.groupingBy { it.message.substringBefore("：") }.eachCount()
            if (reasons.isNotEmpty()) CornCard {
                Label("失败原因")
                val max = reasons.values.max().toFloat()
                reasons.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(reason, color = CornColors.Text, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                        CornBar(count / max, Modifier.weight(1f), color = CornColors.Red)
                        Text("$count", color = CornColors.Text, style = numericStyle, fontSize = 13.sp)
                    }
                }
            }
            CornCard {
                Label("明细（${rows.size}）")
                rows.sortedBy { it.seq ?: 0 }.takeLast(50).forEach { row ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("#${row.seq ?: "—"}", color = CornColors.Dim, style = numericStyle, fontSize = 12.sp, modifier = Modifier.width(48.dp))
                        Text(shortUid(row.targetUid), color = CornColors.Text, style = numericStyle, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(row.serial ?: "", color = CornColors.Dim, style = numericStyle, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                        Chip(statusLabel(row.status), statusTone(row.status))
                    }
                }
                if (rows.size > 50) Text("仅显示最近 50 块，完整明细请导出 CSV", color = CornColors.Dim, fontSize = 11.5.sp)
            }
            CornButton("导出 CSV", { exporter.launch("${b.workOrder}-report.csv") }, primary = true, icon = "↓")
            Text("CSV 每块板一行：序号、工位、UID、序列号、结果、用时、阶段、说明", color = CornColors.Dim, fontSize = 11.5.sp)
        }
    }
}
