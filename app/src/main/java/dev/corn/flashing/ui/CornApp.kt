package dev.corn.flashing.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.corn.flashing.core.FlashStage
import dev.corn.flashing.core.hexAddress
import dev.corn.flashing.storage.ImportedFirmware
import dev.corn.flashing.storage.JobEntity
import dev.corn.flashing.storage.SessionState
import dev.corn.flashing.usb.UsbProbe

@Composable
fun CornApp(vm: FlashViewModel) {
    val state by vm.session.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val devices by vm.devices.collectAsStateWithLifecycle()
    val usbMessage by vm.usbMessage.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val batch by vm.batch.collectAsStateWithLifecycle()
    val offer by vm.resumeOffer.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val batchList by vm.batchList.collectAsStateWithLifecycle(initialValue = emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var batchMode by rememberSaveable { mutableStateOf(false) }
    var binAddress by rememberSaveable { mutableStateOf("08000000") }
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri, binAddress)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri)
    }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.clearNotice() } }
    LaunchedEffect(batch != null) { if (batch != null) batchMode = true }

    CornTheme {
        val reportId = report
        if (reportId != null) {
            BackHandler { vm.openReport(null) }
            BatchReportScreen(vm, reportId, onBack = { vm.openReport(null) })
            return@CornTheme
        }
        Scaffold(
            containerColor = CornColors.Background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = CornColors.NavBackground) {
                    listOf("⚡" to "工作台", "▤" to "固件", "⌁" to "设备", "≡" to "记录").forEachIndexed { index, (icon, label) ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Glyph(icon, if (tab == index) CornColors.Corn else CornColors.Dim, 16) },
                            label = { Text(label) })
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Header(tab, batch != null) }
                    when (tab) {
                        0 -> {
                            item {
                                ModeSwitch(batchMode, onChange = { wanted ->
                                    if (!wanted && batch != null) vm.notify("批次未结束：结束批次后才能切回单路")
                                    else batchMode = wanted
                                })
                            }
                            if (batchMode) batchItems(vm, state, batch, onGoFirmware = { tab = 1 })
                            else singleItems(vm, state, devices, onGoDevices = { tab = 2 }, onGoFirmware = { tab = 1 })
                        }
                        1 -> firmwareItems(vm, state, binAddress, { binAddress = it }, onPick = { picker.launch(arrayOf("*/*")) })
                        2 -> deviceItems(vm, devices, usbMessage)
                        3 -> recordItems(history, batchList, onExport = { exporter.launch("corn-flashing-records.json") },
                            onReport = vm::openReport)
                    }
                    item {
                        Text("Corn Flashing · by xingyingyuzhui · 模拟模式", color = CornColors.Mute, fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
        offer?.let { ResumeSheet(it, vm) }
    }
}

@Composable
private fun Header(tab: Int, batchOpen: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RoundIcon("◆", CornColors.CornOn, CornColors.Corn, size = 38, glyphSize = 16)
        Column(Modifier.weight(1f)) {
            Text(listOf("Corn Flashing", "固件库", "设备", "记录")[tab], color = CornColors.Text,
                fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(listOf("离线烧录 · 全程留痕", "先检查，再烧录", "从 USB 授权开始", "每块板都有结果")[tab],
                color = CornColors.Dim, fontSize = 12.sp)
        }
        if (batchOpen) Chip("批次进行中", ChipTone.WARN, dot = true) else Chip("模拟", ChipTone.INFO, dot = true)
    }
}

/** Phone → ST-LINK → chip chain (screens 01/17). In simulation the chain is always bypassed. */
@Composable
private fun LinkChain(probeReady: Boolean, targetReady: Boolean) {
    CornCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Chip("手机", ChipTone.OK, dot = true)
            Text("—", color = CornColors.Mute)
            Chip("ST-LINK", if (probeReady) ChipTone.OK else ChipTone.BAD, dot = true)
            Text("—", color = CornColors.Mute)
            Chip("目标板", if (targetReady) ChipTone.OK else ChipTone.BAD, dot = true)
        }
    }
}

// ---------------------------------------------------------------- single mode

private fun LazyListScope.singleItems(
    vm: FlashViewModel, state: SessionState, devices: List<UsbProbe>,
    onGoDevices: () -> Unit, onGoFirmware: () -> Unit,
) {
    val probe = devices.firstOrNull { it.candidate }
    val authorized = probe?.permissionGranted == true
    item { LinkChain(authorized, false) }
    if (!authorized) item { NoProbeCard(probe, devices.isNotEmpty(), vm, onGoDevices) }
    item { FirmwareCard(state.firmware, onGoFirmware, vm) }
    item { SingleRunCard(vm, state) }
}

/** Screen 17: no probe. */
@Composable
private fun NoProbeCard(probe: UsbProbe?, anyUsb: Boolean, vm: FlashViewModel, onGoDevices: () -> Unit) {
    CornCard {
        Label("没有可用的烧录器")
        Text("先接上 ST-LINK", color = CornColors.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        HintRow("1", "插上 OTG 转接头", "手机 Type-C → USB-A 母口", done = anyUsb)
        HintRow("2", "接上 ST-LINK/V2", "接好后列表自动更新", done = probe != null)
        HintRow("3", "允许 USB 访问", "系统会弹出授权窗口", done = probe?.permissionGranted == true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (probe != null && !probe.permissionGranted) {
                CornButton("授权", { vm.authorize(probe.connectionId) }, Modifier.weight(1f), primary = true, height = 44)
            }
            CornButton("排查连接", onGoDevices, Modifier.weight(1f), height = 44)
        }
        Text("真实 ST-LINK 烧录尚未接入；下面可以用模拟烧录器演练流程。", color = CornColors.Dim, fontSize = 12.sp)
    }
}

@Composable
private fun FirmwareCard(firmware: ImportedFirmware?, onGoFirmware: () -> Unit, vm: FlashViewModel) {
    CornCard {
        Label("固件")
        if (firmware == null) {
            Text("尚未选择固件", color = CornColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CornButton("去固件库", onGoFirmware, Modifier.weight(1f), height = 44)
                CornButton("演示 HEX", vm::loadDemo, Modifier.weight(1f), height = 44)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(firmware.image.format.toString(), ChipTone.WARN)
                Text(firmware.name, color = CornColors.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            KeyValue("大小", "${firmware.image.totalBytes} 字节", mono = true)
            KeyValue("SHA-256", shortSha(firmware.image.sourceSha256), mono = true)
            KeyValue("产品配置", vm.profile.name)
        }
    }
}

@Composable
private fun SingleRunCard(vm: FlashViewModel, state: SessionState) {
    val job = state.job
    CornCard {
        if (state.running) {
            Label("烧录中 · 模拟")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.stage, color = CornColors.Text, fontWeight = FontWeight.SemiBold)
                Text("${(state.progress * 100).toInt()}%", color = CornColors.Corn, style = numericStyle)
            }
            CornBar(state.progress)
            Text(state.message, color = CornColors.Dim, fontSize = 12.sp)
            CornButton("中止", vm::cancel, danger = true, height = 44)
            return@CornCard
        }
        when (job?.status) {
            "SIMULATED", "SUCCEEDED" -> Banner("烧录成功（模拟）", "UID ${job.targetUid ?: "—"} · ${job.message}", bad = false, icon = "✓")
            "FAILED" -> FailureDetail(job.message, state.failedStage)
            "CANCELLED", "INTERRUPTED" -> UnpluggedCard(job)
        }
        CornButton(
            "开始烧录（模拟）", vm::startSingle, primary = true,
            enabled = state.ready && !state.importing && state.firmware != null,
            subtitle = state.plan?.let { "擦 ${it.erasePageAddresses.size} 页 · 写 ${it.writeBytes} 字节 · 读回校验" }
                ?: if (state.firmware == null) "先选择固件" else "模拟烧录器 · 不写入真实芯片",
        )
    }
}

/** Failure explanation; screen 19 when the target did not respond. */
@Composable
fun FailureDetail(message: String, stage: FlashStage?) {
    val noTarget = stage == FlashStage.PROBING || message.contains("电压") || message.contains("未响应")
    if (!noTarget) {
        Banner("烧录失败${stage?.let { " · 停在${stageLabel(it)}" } ?: ""}", message)
        return
    }
    Banner("目标板没响应", "$message。探针正常，但读不到目标电压。")
    CornCard(color = CornColors.Surface2) {
        Label("SWD 接线检查")
        HintRow("1", "3V3 → 目标板 3.3V", "ST-LINK/V2 的 3V3 脚只检测电压，不给目标板供电")
        HintRow("2", "SWDIO → PA13", "线尽量短，不超过 20 cm")
        HintRow("3", "SWCLK → PA14", "接触不良时可降低 SWD 速率重试")
        HintRow("4", "GND → GND", "必须共地")
    }
}

/** Screen 20: unplugged / aborted mid-flash. */
@Composable
fun UnpluggedCard(job: JobEntity) {
    Banner("这块板现在不能用",
        "烧录在「${job.stage}」中断：可能已经擦除，只写了一部分，程序不完整。接回后核对 UID，同一块板需要从头重烧。")
}

fun stageLabel(stage: FlashStage) = when (stage) {
    FlashStage.CONNECTING -> "连接"
    FlashStage.PROBING -> "识别芯片"
    FlashStage.ERASING -> "擦除"
    FlashStage.WRITING -> "写入"
    FlashStage.VERIFYING -> "校验"
    FlashStage.RESETTING -> "复位"
    FlashStage.SUCCEEDED -> "完成"
}

// ---------------------------------------------------------------- firmware (23)

private fun LazyListScope.firmwareItems(
    vm: FlashViewModel, state: SessionState, binAddress: String, onAddress: (String) -> Unit, onPick: () -> Unit,
) {
    state.importError?.let { error -> item { ImportErrorCard(error, vm::clearImportError) } }
    if (state.firmware == null) item {
        DashedNote {
            RoundIcon("▤", CornColors.Corn, CornColors.CornContainer, size = 56, glyphSize = 22)
            Text("固件库是空的", color = CornColors.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("支持 HEX、BIN（需填写起始地址）。ELF 解析待接入。", color = CornColors.Dim, fontSize = 12.5.sp)
            CornButton(if (state.importing) "正在解析…" else "选择固件文件", onPick, primary = true,
                enabled = state.ready && !state.importing)
            CornButton("先用演示 HEX 试一下", vm::loadDemo, enabled = state.ready && !state.importing, height = 44)
        }
    } else {
        item {
            val firmware = state.firmware
            CornCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(firmware.image.format.toString(), ChipTone.WARN)
                    Text(firmware.name, color = CornColors.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    Chip("使用中", ChipTone.OK, dot = true)
                }
                KeyValue("地址范围", "${firmware.image.startAddress.hexAddress()} – ${firmware.image.endAddressExclusive.hexAddress()}", mono = true)
                KeyValue("区段", "${firmware.image.segments.size}", mono = true)
                KeyValue("大小", "${firmware.image.totalBytes} 字节", mono = true)
                KeyValue("SHA-256", shortSha(firmware.image.sourceSha256), mono = true)
                CornButton(if (state.importing) "正在解析…" else "导入其他固件", onPick,
                    enabled = state.ready && !state.importing && !state.running, height = 44)
            }
        }
    }
    item {
        CornCard {
            Label("BIN 起始地址")
            OutlinedTextField(value = binAddress, onValueChange = onAddress, singleLine = true, prefix = { Text("0x") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
            Text("常见值：0x08000000（无 Bootloader）、0x08004000 / 0x08008000（带 Bootloader）", color = CornColors.Dim, fontSize = 12.sp)
        }
    }
    item {
        CornCard {
            Label("产品配置")
            KeyValue("预设", vm.profile.name)
            KeyValue("Flash", "${vm.profile.flashStart.hexAddress()} · ${vm.profile.flashSizeBytes / 1024} KB", mono = true)
            KeyValue("擦除", "仅计划涉及的页")
            KeyValue("读保护", "不修改")
        }
    }
}

/** Screen 23: turn a parser message into cause + next step. */
@Composable
private fun ImportErrorCard(error: String, onDismiss: () -> Unit) {
    val (title, advice) = importErrorAdvice(error)
    Banner(title, "$error\n$advice")
    TextButton(onClick = onDismiss) { Text("知道了", color = CornColors.Corn) }
}

fun importErrorAdvice(error: String): Pair<String, String> = when {
    error.contains("校验") -> "HEX 校验和错误" to "文件可能在传输时损坏，请重新从电脑拷贝。"
    error.contains("BIN") || error.contains("起始地址") -> "BIN 需要起始地址" to "填写正确的起始地址后重新选择文件。"
    error.contains("超出") || error.contains("范围") || error.contains("Flash") -> "超出 Flash 范围" to "可能选错了产品配置或起始地址。"
    error.contains("ELF") -> "暂不支持 ELF" to "请在编译工具里同时导出 HEX 或 BIN。"
    else -> "导入失败" to "请确认文件格式为 HEX 或 BIN。"
}

// ---------------------------------------------------------------- devices (18)

private fun LazyListScope.deviceItems(vm: FlashViewModel, devices: List<UsbProbe>, usbMessage: String) {
    item {
        CornCard {
            KeyValue("USB Host", if (vm.hostSupported) "✓ 系统支持" else "✗ 系统未声明支持",
                valueColor = if (vm.hostSupported) CornColors.Leaf else CornColors.Red)
            Text(usbMessage, color = CornColors.Dim, fontSize = 12.sp)
            CornButton("刷新设备列表", vm::refreshUsb, height = 44)
        }
    }
    if (devices.isEmpty()) item {
        CornCard {
            Label("插上了但没看到设备？")
            Banner("先检查 OTG 开关", "很多手机默认关闭 OTG，部分机型 10 分钟无操作后会自动关闭。", bad = false)
            HintRow("1", "系统支持 USB Host", if (vm.hostSupported) "已自动检测通过" else "本机不支持，无法连接烧录器", done = vm.hostSupported)
            HintRow("2", "打开 OTG 开关", "设置 → 更多连接 → OTG")
            HintRow("3", "换一根线", "只能充电的线没有数据线芯")
            HintRow("4", "供电是否足够", "多个设备时用带供电的 USB Hub")
        }
    }
    items(devices, key = { it.connectionId }) { device ->
        CornCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.name, color = if (device.candidate) CornColors.Text else CornColors.Mute,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                when {
                    !device.candidate -> Chip("非烧录器 · 已忽略")
                    device.permissionGranted -> Chip("已授权", ChipTone.OK, dot = true)
                    else -> Chip("未授权", ChipTone.WARN, dot = true)
                }
            }
            KeyValue("VID / PID", "%04X / %04X".format(device.vendorId, device.productId), mono = true)
            device.serial?.let { KeyValue("序列号", it, mono = true) }
            if (device.candidate && !device.permissionGranted) {
                Text("之前拒绝过授权时，可以在这里重新授权。", color = CornColors.Dim, fontSize = 12.sp)
                CornButton("授权", { vm.authorize(device.connectionId) }, primary = true, height = 44)
            }
        }
    }
}

// ---------------------------------------------------------------- records

private fun LazyListScope.recordItems(
    history: List<JobEntity>, batches: List<dev.corn.flashing.storage.BatchEntity>,
    onExport: () -> Unit, onReport: (String) -> Unit,
) {
    item { CornButton("导出 JSON 记录", onExport, height = 44) }
    if (batches.isNotEmpty()) {
        item { Label("批次") }
        items(batches, key = { "b" + it.id }) { b ->
            CornCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.workOrder, color = CornColors.Text, fontWeight = FontWeight.Bold)
                        Text("${formatDateTime(b.startedAt)} · 计划 ${b.planned} · ${b.firmwareName}", color = CornColors.Dim, fontSize = 12.sp)
                    }
                    TextButton(onClick = { onReport(b.id) }) { Text("报告", color = CornColors.Corn) }
                }
            }
        }
    }
    item { Label("最近任务") }
    if (history.isEmpty()) item {
        DashedNote { Text("还没有任务记录", color = CornColors.Dim) }
    }
    items(history, key = { it.id }) { record ->
        CornCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.seq?.let { "#$it " }.orEmpty() + record.firmwareName, color = CornColors.Text,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip(statusLabel(record.status), statusTone(record.status))
            }
            Text("${formatDateTime(record.startedAt)} · UID ${shortUid(record.targetUid)}" +
                (record.serial?.let { " · $it" } ?: ""), color = CornColors.Dim, fontSize = 12.sp)
            Text(record.message, color = CornColors.Dim, fontSize = 12.sp)
        }
    }
}
