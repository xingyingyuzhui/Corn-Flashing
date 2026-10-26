package dev.corn.flashing.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.corn.flashing.core.hexAddress
import dev.corn.flashing.storage.ImportedFirmware
import dev.corn.flashing.storage.JobEntity
import dev.corn.flashing.storage.SessionState
import dev.corn.flashing.usb.UsbProbe
import java.text.SimpleDateFormat
import java.util.Date

private val Pine = Color(0xFF006B54)
private val Canvas = Color(0xFFF5F6F1)
private val Ink = Color(0xFF182B26)
private val Muted = Color(0xFF596963)
private val Amber = Color(0xFFF9EDC9)
private val CornScheme = lightColorScheme(
    primary = Pine, onPrimary = Color.White, primaryContainer = Color(0xFFD4EDE2),
    onPrimaryContainer = Ink, secondaryContainer = Amber, onSecondaryContainer = Ink,
    surface = Color.White, background = Canvas, onSurface = Ink, onBackground = Ink,
)

@Composable
fun CornApp(vm: FlashViewModel) {
    val state by vm.session.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val devices by vm.devices.collectAsStateWithLifecycle()
    val usbMessage by vm.usbMessage.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var binAddress by rememberSaveable { mutableStateOf("08000000") }
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri, binAddress)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri)
    }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.clearNotice() } }
    MaterialTheme(colorScheme = CornScheme, shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp),
    )) {
        Scaffold(
            containerColor = Canvas,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    listOf("工作台", "固件", "设备", "记录").forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Text("0${index + 1}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                            label = { Text(label) })
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 880.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { Header(tab) }
                    when (tab) {
                        0 -> {
                            item { ModeBanner() }
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Metric("设备发现", devices.count { it.candidate }.toString(), "ST-LINK 候选", Modifier.weight(1f))
                                    Metric("模拟完成", history.count { it.status == "SIMULATED" }.toString(), "最近 100 条", Modifier.weight(1f))
                                    Metric("真实烧录", "未接入", "等待硬件验证", Modifier.weight(1f))
                                }
                            }
                            item {
                                Panel("当前任务", "01 / FLASH SESSION") {
                                    FirmwareSummary(state.firmware)
                                    HorizontalDivider(color = Canvas)
                                    KeyValue("目标配置", vm.profile.name + "（预设）")
                                    KeyValue("探针模式", "模拟烧录器 · 无 USB 写入")
                                    state.target?.let { target ->
                                        KeyValue("模拟目标", "${target.flashSizeBytes / 1024} KB · ID 0x${target.deviceId.toString(16)}")
                                    }
                                    state.plan?.let { plan ->
                                        KeyValue("模拟计划", "${plan.erasePageAddresses.size} 页 / ${plan.writeBytes} 字节")
                                    }
                                    if (!state.ready || state.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(state.stage, fontWeight = FontWeight.SemiBold)
                                        Text("${(state.progress * 100).toInt()}%", fontFamily = FontFamily.Monospace)
                                    }
                                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = Muted)
                                    if (state.running) {
                                        OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) { Text("取消模拟任务") }
                                    } else {
                                        Button(onClick = vm::startDemo, enabled = state.ready && !state.importing && state.firmware != null,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("开始模拟流程") }
                                    }
                                    if (state.firmware == null) {
                                        TextButton(onClick = vm::loadDemo, enabled = state.ready && !state.importing,
                                            modifier = Modifier.fillMaxWidth()) { Text("加载演示 HEX") }
                                    }
                                }
                            }
                            item {
                                Panel("连续批量", "02 / REPEAT") {
                                    Text("同一固件，重复执行", style = MaterialTheme.typography.titleMedium)
                                    Text("完成后可再次开始模拟。每次任务独立记录；当前模拟 UID 固定，不代表真实板卡数量。", color = Muted)
                                    Text("多路并行待原生会话隔离验证后开放。", style = MaterialTheme.typography.bodySmall, color = Muted)
                                }
                            }
                        }
                        1 -> {
                            item {
                                Panel("导入固件", "LOCAL / OFFLINE") {
                                    Text("固件保存在设备本地", style = MaterialTheme.typography.titleLarge)
                                    Text("当前已实现 HEX 与 BIN 解析。ELF / AXF 已预留格式入口，解析器待接入。", color = Muted)
                                    OutlinedTextField(value = binAddress, onValueChange = { binAddress = it },
                                        enabled = !state.running && !state.importing,
                                        label = { Text("BIN 起始地址（十六进制）") }, prefix = { Text("0x") },
                                        supportingText = { Text("仅 BIN 使用；请确认是否包含 Bootloader") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                        singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Button(onClick = { picker.launch(arrayOf("*/*")) },
                                        enabled = state.ready && !state.running && !state.importing,
                                        modifier = Modifier.fillMaxWidth()) { Text(if (state.importing) "正在解析…" else "选择固件文件") }
                                    OutlinedButton(onClick = vm::loadDemo,
                                        enabled = state.ready && !state.running && !state.importing,
                                        modifier = Modifier.fillMaxWidth()) { Text("加载演示 HEX") }
                                    Text("演示文件只用于验证解析与模拟流程，不能作为可运行固件。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            item {
                                Panel("当前文件", "FIRMWARE IMAGE") {
                                    FirmwareSummary(state.firmware, detailed = true)
                                    Text(state.message, color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            item {
                                Panel("产品配置", "F103 / INTERNAL FLASH") {
                                    KeyValue("容量预设", "64 KB（尚未连接真实芯片）")
                                    KeyValue("Flash 地址", "0x08000000 – 0x0800FFFF")
                                    KeyValue("擦除策略", "仅计划涉及的页 · 每页 1 KB")
                                    Text("该预设只适用于匹配的设备组和容量。Bootloader、保留参数区与其他容量配置需在后续配置编辑器中建立。", color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        2 -> {
                            item {
                                Panel("USB 设备", "USB HOST / OTG") {
                                    KeyValue("USB Host", if (vm.hostSupported) "系统声明支持" else "系统未声明支持")
                                    Text(usbMessage, color = Muted)
                                    OutlinedButton(onClick = vm::refreshUsb, modifier = Modifier.fillMaxWidth()) { Text("刷新设备列表") }
                                }
                            }
                            if (devices.isEmpty()) item {
                                Panel("尚未发现 USB 设备", "NO DEVICE") {
                                    Text("通过 OTG 连接 ST-LINK/V2 后刷新。模拟器可验证界面流程，不能验证真实 USB 与 SWD。", color = Muted)
                                }
                            }
                            items(devices, key = { it.connectionId }) { device -> DeviceCard(device, vm::authorize) }
                            item {
                                Panel("真实后端接入点", "ST-LINK / P0") {
                                    Text("当前仅发现设备和请求权限，不读取目标芯片，不发送擦写命令。", color = Muted)
                                    Text("下一步：授权 USB FD → 原生 ST-LINK 会话 → F103 探测 → 擦写校验。", style = MaterialTheme.typography.bodySmall, color = Muted)
                                }
                            }
                        }
                        3 -> {
                            item {
                                Panel("任务记录", "HISTORY / LOCAL") {
                                    Text("显示最近 100 条，导出包含全部记录。模拟完成与真实烧录结果分开存储。", color = Muted)
                                    OutlinedButton(onClick = { exporter.launch("corn-flashing-records.json") }, modifier = Modifier.fillMaxWidth()) { Text("导出 JSON 记录") }
                                }
                            }
                            if (history.isEmpty()) item {
                                Panel("还没有任务记录", "READY WHEN YOU ARE") {
                                    Text("加载演示固件并运行一次模拟，检查整个任务与归档流程。", color = Muted)
                                }
                            }
                            items(history, key = { it.id }) { record -> HistoryCard(record) }
                        }
                    }
                    item { Text("CORN FLASHING  /  0.1.0 FRAMEWORK", color = Muted,
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Header(tab: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("CORN / FLASHING", color = Pine, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
            Surface(color = Color(0xFFE3EAE4), shape = RoundedCornerShape(8.dp)) {
                Text("基础框架", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(listOf("烧录工作台", "固件与配置", "设备连接", "执行记录")[tab],
            style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(listOf("离线操作 · 独立任务 · 全程留痕", "统一解析，先检查再执行", "从 USB 授权开始建立连接", "每次执行都有明确的结果")[tab], color = Muted)
    }
}

@Composable
private fun ModeBanner() {
    Surface(color = Amber, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("模拟模式已启用", fontWeight = FontWeight.Bold)
            Text("用于验证软件流程。当前版本不能烧录真实芯片。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Panel(title: String, eyebrow: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(eyebrow, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Pine)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Metric(title: String, value: String, subtitle: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = Color(0xFFE8EEE7)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Muted)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FirmwareSummary(firmware: ImportedFirmware?, detailed: Boolean = false) {
    if (firmware == null) {
        Text("尚未选择固件", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("从固件页面导入，或先加载演示 HEX。", color = Muted)
        return
    }
    Text(firmware.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text("${firmware.image.format}  /  ${firmware.image.totalBytes} 字节  /  ${firmware.image.segments.size} 个区段", color = Muted)
    if (detailed) {
        KeyValue("地址范围（结束地址不含）", "${firmware.image.startAddress.hexAddress()} – ${firmware.image.endAddressExclusive.hexAddress()}")
        KeyValue("源文件 SHA-256", firmware.image.sourceSha256)
        Text("文件解析成功不代表适配当前电路板。", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DeviceCard(device: UsbProbe, authorize: (Int) -> Unit) {
    Panel(device.name, if (device.candidate) "ST-LINK CANDIDATE" else "USB DEVICE") {
        KeyValue("VID / PID", "%04X / %04X".format(device.vendorId, device.productId))
        KeyValue("权限", if (device.permissionGranted) "已授权" else "尚未授权")
        device.serial?.let { KeyValue("探针序列号", it) }
        Text("USB ID 匹配只表示候选设备，兼容性需实际验证。", color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { authorize(device.connectionId) }, enabled = !device.permissionGranted,
            modifier = Modifier.fillMaxWidth()) { Text(if (device.permissionGranted) "已获得 USB 权限" else "请求 USB 访问权限") }
    }
}

@Composable
private fun HistoryCard(record: JobEntity) {
    val locale = LocalConfiguration.current.locales[0]
    val label = when (record.status) {
        "SIMULATED" -> "模拟完成"
        "CANCELLED" -> "已取消"
        "FAILED" -> "失败"
        "INTERRUPTED" -> "中断 · 结果未知"
        "RUNNING" -> "执行中"
        else -> "已排队"
    }
    Panel(record.firmwareName, "DEMO / $label") {
        Text(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).format(Date(record.startedAt)), color = Muted)
        Text(record.message, style = MaterialTheme.typography.bodyMedium)
        KeyValue("固件哈希", record.sha256.take(20) + "…")
        record.targetUid?.let { KeyValue("模拟 UID", it) }
    }
}
