package dev.corn.flashing.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.corn.flashing.core.targets.TargetCatalog
import dev.corn.flashing.core.targets.TargetSupport
import dev.corn.flashing.core.ProductProfile
import dev.corn.flashing.core.TargetInfo
import dev.corn.flashing.core.FirmwarePreflight
import dev.corn.flashing.core.hexAddress
import dev.corn.flashing.core.regions
import dev.corn.flashing.storage.ImportedFirmware
import dev.corn.flashing.storage.JobEntity
import dev.corn.flashing.storage.SessionState
import dev.corn.flashing.usb.UsbProbe
import java.util.Calendar

// ---------------------------------------------------------------- firmware library (05 / 23)

@Composable
fun FirmwareTab(vm: FlashViewModel, state: SessionState, binAddress: String, onAddress: (String) -> Unit,
                onPick: () -> Unit, onDetail: () -> Unit) {
    val fw = state.firmware
    TopBar("固件库", "本机 · ${if (fw == null) 0 else 1} 个版本") { IconBtn(Lucide.Download, onPick) }
    Box(Modifier.fillMaxSize()) {
        ScrollBody(spacing = 12) {
            if (fw == null) EmptyLibrary(vm, state, onPick)
            else {
                var query by rememberSaveable { mutableStateOf("") }
                var filter by rememberSaveable { mutableIntStateOf(0) }
                SearchField(query) { query = it }
                val format = fw.image.format.name
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部" to 1, "HEX" to (if (format == "HEX") 1 else 0), "BIN" to (if (format == "BIN") 1 else 0), "ELF" to 0)
                        .forEachIndexed { i, (label, n) ->
                            Chip("$label $n", if (filter == i) ChipTone.WARN else ChipTone.NEUTRAL, onClick = { filter = i })
                        }
                }
                val visible = (filter == 0 || listOf("", "HEX", "BIN", "ELF")[filter] == format) &&
                    (query.isBlank() || fw.name.contains(query, true) || fw.image.sourceSha256.contains(query, true))
                if (visible) FirmwareRow(fw, onDetail) else T("没有匹配的固件", CornColors.Dim, 13.sp, modifier = Modifier.padding(8.dp))
                T("固件只存本机；导入时锁定 SHA-256，改动源文件不影响已导入版本", CornColors.Mute, 11.5.sp,
                    align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                BinAddressCard(binAddress, onAddress)
                Spacer(Modifier.height(64.dp))
            }
        }
        if (fw != null) Row(Modifier.align(Alignment.BottomEnd)
            .padding(end = 18.dp, bottom = 18.dp + LocalDockClearance.current).height(54.dp)
            .clip(RoundedCornerShape(18.dp)).background(CornColors.Corn).clickable(enabled = !state.importing, onClick = onPick)
            .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Ico(Lucide.Plus, CornColors.CornOn, 20.dp)
            T(if (state.importing) "正在解析…" else "导入固件", CornColors.CornOn, 15.sp, FontWeight.Bold)
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(13.dp)).background(CornColors.Surface2)
        .border(1.dp, CornColors.Line, RoundedCornerShape(13.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ico(Lucide.Search, CornColors.Mute, 17.dp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) T("搜索固件名称或 SHA", CornColors.Mute, 13.5.sp)
            BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(CornColors.Corn),
                textStyle = TextStyle(color = CornColors.Text, fontSize = 13.5.sp), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FirmwareRow(fw: ImportedFirmware, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CornColors.Surface1)
        .border(1.dp, CornColors.Corn.copy(alpha = .55f), RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FormatBadge(fw.image.format.name, width = 38.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T(fw.name, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Chip("使用中", ChipTone.OK)
            }
            T("${kb(fw.image.totalBytes)} · ${fw.image.regions().size} 段", CornColors.Dim, 12.sp)
            T("SHA ${shortSha(fw.image.sourceSha256)}", CornColors.Mute, 11.sp, mono = true)
        }
        Chev()
    }
}

@Composable
private fun BinAddressCard(binAddress: String, onAddress: (String) -> Unit) {
    CornCard(spacing = 6.dp) {
        Label("BIN 起始地址")
        Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(CornColors.Surface2).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            T("0x", CornColors.Mute, 13.5.sp, mono = true)
            BasicTextField(binAddress, { onAddress(it.take(8)) }, singleLine = true, cursorBrush = SolidColor(CornColors.Corn),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                textStyle = TextStyle(color = CornColors.Text, fontSize = 13.5.sp, fontFamily = Mono), modifier = Modifier.weight(1f))
        }
        T("导入 BIN 时使用；HEX 自带地址", CornColors.Mute, 11.sp)
    }
}

/** Screen 23. */
@Composable
private fun EmptyLibrary(vm: FlashViewModel, state: SessionState, onPick: () -> Unit) {
    CornCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Illo(Lucide.File)
            T("还没有导入固件", size = 18.sp, weight = FontWeight.Bold)
            T("支持 Intel HEX、BIN；ELF / AXF 即将支持\n固件只保存在本机，不会上传", CornColors.Dim, 12.5.sp, align = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            CornButton(if (state.importing) "正在解析…" else "选择固件文件", onPick, Modifier.fillMaxWidth(), primary = true,
                icon = Lucide.Plus, enabled = state.ready && !state.importing)
            T("先用演示 HEX 试一下", CornColors.Corn, 13.sp, FontWeight.SemiBold,
                modifier = Modifier.clickable(enabled = state.ready && !state.importing) { vm.loadDemo() }.padding(6.dp))
        }
    }
    Label("导入失败示例")
    Banner("HEX 校验和错误", "第 37 行校验和不对，文件可能在传输时损坏。请重新从电脑拷贝。")
    Banner("BIN 需要起始地址", "BIN 文件不带地址信息，请先填写起始地址（常见 0x08000000）。", tone = ChipTone.WARN)
    Banner("超出 Flash 范围", "固件地址超过目标芯片的允许范围，请检查固件与芯片容量。")
}

fun importErrorAdvice(error: String): Pair<String, String> = when {
    error.contains("校验") -> "HEX 校验和错误" to "文件可能在传输时损坏，请重新从电脑拷贝。"
    error.contains("BIN") || error.contains("起始地址") -> "BIN 需要起始地址" to "填写正确的起始地址后重新选择文件。"
    error.contains("超出") || error.contains("范围") || error.contains("Flash") -> "超出 Flash 范围" to "可能选错了产品配置或起始地址。"
    error.contains("ELF") -> "暂不支持 ELF" to "请在编译工具里同时导出 HEX 或 BIN。"
    else -> "导入失败" to "请确认文件格式为 HEX 或 BIN。"
}

// ---------------------------------------------------------------- firmware detail (06)

@Composable
fun FirmwareDetailScreen(fw: ImportedFirmware, profile: ProductProfile, target: TargetInfo?, onBack: () -> Unit, registry: dev.corn.flashing.core.targets.TargetRegistry = TargetCatalog.registry) {
    val regions = fw.image.regions()
    val preflight = FirmwarePreflight.inspect(fw.image, profile, target, registry)
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        BackTop("固件详情", when {
            !preflight.targetKnown -> "目标容量待识别 · 尚未确认适配"
            !preflight.canStart -> "当前目标配置检查未通过"
            else -> "地址检查通过 · ${profile.flashSizeBytes / 1024} KB"
        }, onBack = onBack)
        ScrollBody(Modifier.weight(1f)) {
            CornCard(spacing = 8.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormatBadge(fw.image.format.name)
                    T(fw.name, size = 18.sp, weight = FontWeight.Bold)
                }
                T("SHA-256  ${fw.image.sourceSha256.take(16)}…", CornColors.Dim, 11.sp, mono = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(kb(fw.image.totalBytes)); Chip("${regions.size} 个区段"); Chip("已锁定 SHA", ChipTone.OK)
                }
            }
            CornCard(spacing = 10.dp) {
                Row { Label("Flash 布局", Modifier.weight(1f)); T("每格 ${profile.flashSizeBytes / 64 / 1024} KB", CornColors.Mute, 11.sp) }
                FlashGrid(fw, profile)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(CornColors.Corn, "固件"); LegendDot(CornColors.Surface3, "空闲"); LegendDot(CornColors.Red, "保留", hatch = true)
                }
            }
            CornCard {
                Label("区段")
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    T("起始", CornColors.Mute, 11.sp, modifier = Modifier.weight(1f)); T("结束", CornColors.Mute, 11.sp, modifier = Modifier.weight(1f))
                    T("大小", CornColors.Mute, 11.sp)
                }
                regions.forEachIndexed { i, r ->
                    DividerLine()
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        T(r.start.hexAddress(), size = 12.sp, mono = true, modifier = Modifier.weight(1f))
                        T((r.endExclusive - 1).hexAddress(), size = 12.sp, mono = true, modifier = Modifier.weight(1f))
                        T(kb(r.endExclusive - r.start), CornColors.Dim, 12.sp, mono = true)
                    }
                }
            }
            val inFlash = fw.image.startAddress >= profile.flashStart && fw.image.endAddressExclusive <= profile.flashStart + profile.flashSizeBytes
            val overlap = profile.reservedRanges.any { res -> regions.any { it.overlaps(res) } }
            CornCard {
                Label("导入检查")
                CheckLine(true, "格式解析通过（${fw.image.format.name}${if (fw.image.format.name == "HEX") " · 每行校验和正确" else ""}）")
                if (preflight.targetKnown) {
                    CheckLine(inFlash, if (inFlash) "地址都在 Flash 范围内" else "有数据超出 Flash 范围")
                    CheckLine(preflight.canStart, preflight.error ?: "擦除页与目标配置检查通过")
                    CheckLine(!overlap, if (overlap) "与保留区重叠" else "不碰保留区")
                } else {
                    T("当前布局按占位配置展示；请先识别芯片，实际容量与擦除范围尚未确认。", CornColors.Mute, 12.sp)
                }
                CheckLine(regions.size <= 1, if (regions.size <= 1) "连续区段" else "${regions.size} 个区段，中间有空洞（空洞不写）", last = true)
            }
            CornButton("当前固件 ✓", {}, Modifier.fillMaxWidth(), primary = true, enabled = false)
        }
    }
}

@Composable
private fun CheckLine(ok: Boolean, text: String, last: Boolean = false) {
    Column {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Ico(if (ok) Lucide.Check else Lucide.Alert, if (ok) CornColors.Leaf else CornColors.Corn, 16.dp)
            T(text, size = 13.sp)
        }
        if (!last) DividerLine()
    }
}

/** 16×4 grid; each cell is 1/64 of the flash. */
@Composable
fun FlashGrid(fw: ImportedFirmware, profile: ProductProfile) {
    val cell = profile.flashSizeBytes / 64
    val regions = fw.image.regions()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        (0 until 4).forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                (0 until 16).forEach { c ->
                    val start = profile.flashStart + (r * 16 + c) * cell
                    val range = dev.corn.flashing.core.AddressRange(start, start + cell)
                    val reserved = profile.reservedRanges.any { it.overlaps(range) }
                    val used = regions.any { it.overlaps(range) }
                    Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(3.dp)).let {
                        when { reserved -> it.hatch(); used -> it.background(CornColors.Corn); else -> it.background(CornColors.Surface3) }
                    })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- product config (07)

@Composable
fun ConfigTab(vm: FlashViewModel) {
    val reserved by vm.reservedSettings.collectAsStateWithLifecycle()
    val bindings by vm.algorithmBindings.collectAsStateWithLifecycle()
    val experimentalEnabled by vm.experimentalAlgorithmsEnabled.collectAsStateWithLifecycle()
    val registry = remember(bindings, experimentalEnabled) { vm.targetRegistry }
    val state by vm.session.collectAsStateWithLifecycle()
    val p = vm.profile
    val prefs = vm.prefs
    val probed = state.target
    TopBar("产品配置", "设备组与容量按探测自动匹配") {
        when {
            prefs.simulator -> SimMark()
            probed != null && registry.find(probed.deviceId)?.algorithmId?.startsWith("flm-") == true -> Chip("实验 · 未验证", ChipTone.WARN)
            probed != null && registry.find(probed.deviceId)?.support == TargetSupport.FLASH_ALGORITHM -> Chip("算法已匹配", ChipTone.OK, dot = true)
            probed != null -> Chip("仅识别", ChipTone.WARN, dot = true)
            else -> Chip("未探测", ChipTone.WARN)
        }
    }
    ScrollBody {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(p.name.take(22), ChipTone.WARN, dot = true)
            Chip(if (state.probing) "识别中…" else "重新识别", onClick = if (state.probing || state.running) null else ({
                if (prefs.simulator) vm.notify("模拟模式：演示板固定 0x410 · 64 KB")
                else vm.probeChip()
            }))
        }
        CornCard {
            Label("目标芯片（自动匹配）")
            KeyValue("设备组", probed?.let { "0x${it.deviceId.toString(16)} · ${it.family}" } ?: "探测后写入", mono = true)
            KeyValue("Flash 容量", probed?.takeIf { it.flashSizeBytes > 0 }?.let {
                "${it.flashSizeBytes / 1024} KB · 芯片报告"
            } ?: "待探测", mono = true)
            val descriptor = probed?.let { registry.find(it.deviceId) }
            val sectorSizes = descriptor?.let {
                runCatching { it.geometry.sectors(p.flashStart, p.flashSizeBytes).map { sector -> sector.sizeBytes }.distinct() }.getOrNull()
            }
            KeyValue("擦除布局", sectorSizes?.joinToString(" / ") { "$it B" } ?: "待识别", mono = true)
            KeyValue("UID", probed?.uid ?: "—", mono = true, last = true)
            T("按芯片描述匹配内存布局；只有同时匹配烧录算法和探针能力的目标才开放擦写。",
                CornColors.Dim, 12.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                T("固件超出容量时", CornColors.Dim, 13.sp, modifier = Modifier.weight(1f)); Chip("拒绝烧录", ChipTone.BAD)
            }
        }
        CornCard(spacing = 10.dp) {
            Label("内置芯片兼容性")
            registry.descriptors.forEach { descriptor ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        T(descriptor.family, size = 13.sp, weight = FontWeight.SemiBold)
                        T("设备组 0x${descriptor.deviceId.toString(16)}", CornColors.Mute, 11.sp, mono = true)
                    }
                    val programmable = descriptor.support == TargetSupport.FLASH_ALGORITHM
                    val external = descriptor.algorithmId?.startsWith("flm-") == true
                    Chip(if (external) "实验 · 未验证" else if (programmable) "算法已接入" else "仅识别",
                        if (programmable && !external) ChipTone.OK else ChipTone.WARN)
                }
            }
            DividerLine()
            T("实板验证记录：STM32F1 大容量 256 KB + ST-LINK/V2 J23 + Xiaomi 25060RK16C / Android 17，已通过擦写、回读和复位。",
                CornColors.Dim, 11.5.sp)
            T("这条验证记录不代表整个系列均已实测；“仅识别”目标不会执行擦除或写入。", CornColors.Mute, 11.5.sp)
        }
        AlgorithmLibraryCard(vm)
        ReservedRegionCard(vm)
        CornCard(spacing = 10.dp) {
            Row { Label("擦除", Modifier.weight(1f)); T("按下方偏好执行", CornColors.Mute, 11.sp) }
            Seg(listOf("只擦用到的页", "全部非保留区"), prefs.eraseMode, { prefs.eraseMode = it })
            T("全部非保留区：逐扇区擦除，跳过已生效的保留区。", CornColors.Mute, 11.5.sp)
            Label("校验")
            Seg(listOf("回读全部", "不校验"), VerificationPreferences.uiIndex(prefs.verifyMode),
                { prefs.verifyMode = VerificationPreferences.storedValue(it) })
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                T("SWD 速率", CornColors.Dim, 13.sp, modifier = Modifier.weight(1f))
                T("探针默认速率", size = 13.sp, weight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CornColors.Surface1)
            .border(1.dp, CornColors.Line, RoundedCornerShape(16.dp)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Ico(Lucide.Shield, CornColors.Leaf, 18.dp)
            T("内置烧录路径不修改读保护与 Option Bytes；外部 FLM 在芯片上执行，其行为不受此保证约束。", CornColors.Dim, 12.sp)
        }
    }
}

// ---------------------------------------------------------------- devices (08 / 18)

@Composable
fun DevicesTab(vm: FlashViewModel, devices: List<UsbProbe>) {
    val bindings by vm.algorithmBindings.collectAsStateWithLifecycle()
    val registry = remember(bindings) { vm.targetRegistry }
    val usbMessage by vm.usbMessage.collectAsStateWithLifecycle()
    val state by vm.session.collectAsStateWithLifecycle()
    TopBar("设备", if (vm.hostSupported) "USB Host 可用 · 插拔自动刷新" else "本机不支持 USB Host")
    ScrollBody {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("已连接 · ${devices.size}", Modifier.weight(1f))
            Chip("刷新", icon = Lucide.Refresh, onClick = vm::refreshUsb)
        }
        if (devices.isEmpty()) {
            Banner("插上了但没看到设备？", "很多手机默认关闭 OTG，部分机型 10 分钟无操作后会自动关闭。", tone = ChipTone.WARN)
            CornCard {
                Label("逐项排查")
                HintRow("1", "系统支持 USB Host", if (vm.hostSupported) "已自动检测通过" else "本机不支持，无法连接烧录器", done = vm.hostSupported)
                HintRow("2", "打开 OTG 开关", "设置 → 更多连接 → OTG")
                HintRow("3", "换一根线", "只能充电的线没有数据线芯")
                HintRow("4", "供电是否足够", "多个设备时用带供电的 USB Hub", last = true)
            }
            T(usbMessage, CornColors.Mute, 11.sp)
        }
        devices.forEach { DeviceCard(it, vm) }
        state.probeInfo?.let { probeInfo ->
            CornCard(spacing = 8.dp) {
                Label("探针能力 · 最近一次识别")
                KeyValue("探针", probeInfo.label)
                KeyValue("固件", probeInfo.firmware, mono = true)
                KeyValue("编程路径", probeInfo.programmingPath)
                KeyValue("USB 通道", probeInfo.transport)
                val descriptor = state.target?.let { registry.find(it.deviceId) }
                KeyValue("当前目标", when {
                    state.target == null -> "尚未识别"
                    descriptor == null -> "未登记 · 不开放擦写"
                    descriptor.support == TargetSupport.IDENTIFY_ONLY -> "仅识别 · 不开放擦写"
                    else -> "算法已接入 · 烧录前检查探针能力"
                }, last = true)
                T(probeInfo.capabilities.joinToString(" · ") { it.label }.ifBlank { "暂无可用编程能力" }, CornColors.Dim, 11.5.sp)
                T("USB 授权只代表允许访问探针，不代表已支持目标芯片的擦写。", CornColors.Mute, 11.5.sp)
            }
        }
        state.usbDiagnostic?.takeIf { it.isNotBlank() }?.let { diagnostic ->
            UsbDiagnosticCard(diagnostic, vm)
        }
        val denied by vm.usbDenied.collectAsStateWithLifecycle()
        val hasUnauthorizedCandidate = devices.any { it.candidate && !it.permissionGranted }
        if (denied && hasUnauthorizedCandidate) {
            CornCard(spacing = 8.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(CornColors.Surface2), contentAlignment = Alignment.Center) {
                        Ico(Lucide.Lock, CornColors.Corn, 18.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        T("上次拒绝了 USB 授权", size = 14.sp, weight = FontWeight.SemiBold)
                        T("点重新授权，系统会再次询问", CornColors.Dim, 11.5.sp)
                    }
                    Chip("重新授权", ChipTone.WARN, onClick = vm::authorizeDenied)
                }
            }
        }
        WiringCard()
    }
}

@Composable
private fun UsbDiagnosticCard(diagnostic: String, vm: FlashViewModel) {
    val context = LocalContext.current
    val preview = remember(diagnostic) {
        diagnostic.lineSequence().take(12).joinToString("\n").take(1600)
    }
    CornCard(spacing = 10.dp) {
        Label("USB 诊断")
        T("当前信息用于排查 USB 通信，不能据此认定控制板损坏。", CornColors.Dim, 12.sp)
        T(preview, CornColors.Mute, 11.sp, mono = true, maxLines = 12)
        if (preview.length < diagnostic.length) {
            T("这里只展示部分内容，复制可获取完整诊断。", CornColors.Mute, 11.sp)
        }
        CornButton("复制 USB 诊断", {
            try {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Corn Flashing USB 诊断", diagnostic))
                vm.notify("已复制完整 USB 诊断")
            } catch (_: Exception) {
                vm.notify("复制失败，请重试")
            }
        }, Modifier.fillMaxWidth(), height = 44)
    }
}

@Composable
private fun DeviceCard(d: UsbProbe, vm: FlashViewModel) {
    val state by vm.session.collectAsStateWithLifecycle()
    val prefs = vm.prefs
    val target = state.target
    val border = if (d.candidate && d.permissionGranted) CornColors.Leaf.copy(alpha = .5f) else CornColors.Line
    val canProbe = d.candidate && d.permissionGranted && !prefs.simulator && !state.running && !state.probing
    CornCard(Modifier.alpha(if (d.candidate) 1f else .55f), border = border, spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(CornColors.Surface2), contentAlignment = Alignment.Center) {
                Ico(Lucide.Usb, if (d.candidate) CornColors.Corn else CornColors.Mute, 20.dp)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    T(d.name, size = 14.5.sp, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    when {
                        !d.candidate -> Chip("非烧录器")
                        d.permissionGranted -> Chip("已授权", ChipTone.OK)
                        else -> Chip("未授权", ChipTone.WARN)
                    }
                }
                T("%04X:%04X".format(d.vendorId, d.productId) + (d.serial?.let { " · SN $it" } ?: ""), CornColors.Dim, 11.sp, mono = true)
            }
            if (d.candidate && !d.permissionGranted) {
                Box(Modifier.height(34.dp).clip(RoundedCornerShape(11.dp)).background(CornColors.Corn).clickable { vm.authorize(d.connectionId) }
                    .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) { T("授权", CornColors.CornOn, 13.sp, FontWeight.Bold) }
            }
        }
        if (d.candidate && d.permissionGranted) {
            val chipText = target?.let { "${it.family} · ${it.flashSizeBytes / 1024} KB" } ?: "—"
            val uidText = target?.uid ?: "—"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(CornColors.Surface2).padding(10.dp)) {
                    T("目标电压", CornColors.Mute, 10.5.sp); T("—", size = 13.sp, weight = FontWeight.Bold, mono = true)
                }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(CornColors.Surface2).padding(10.dp)) {
                    T("目标芯片", CornColors.Mute, 10.5.sp)
                    T(chipText, size = 12.sp, weight = FontWeight.Bold, maxLines = 2)
                    if (target?.uid != null) T(uidText, CornColors.Dim, 10.sp, mono = true, maxLines = 1)
                }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(CornColors.Surface2).padding(10.dp)) {
                    T("绑定", CornColors.Mute, 10.5.sp); T("工位 1", size = 13.sp, weight = FontWeight.Bold, mono = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CornButton(
                    when {
                        state.probing -> "识别中…"
                        target == null -> "识别芯片"
                        else -> "重新识别"
                    },
                    {
                        when {
                            prefs.simulator -> vm.notify("模拟模式：请到工作台使用演示板，或关闭模拟后再识别真芯片")
                            !d.permissionGranted -> vm.notify("请先授权 ST-LINK")
                            state.running -> vm.notify("烧录进行中，稍后再识别")
                            state.probing -> vm.notify("识别中，请稍候")
                            else -> vm.probeChip()
                        }
                    },
                    Modifier.weight(1f), height = 40, icon = Lucide.Chip, enabled = canProbe,
                )
                CornButton("闪灯定位 · 未开放", {}, Modifier.weight(1f), height = 40, enabled = false)
            }
        }
    }
}

@Composable
private fun WiringCard() {
    CornCard(spacing = 10.dp) {
        Label("接线")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf(Lucide.Phone to "手机", Lucide.Usb to "OTG Hub", Lucide.Usb to "ST-LINK", Lucide.Chip to "目标板").forEachIndexed { i, (icon, label) ->
                if (i > 0) T("→", CornColors.Mute, 13.sp)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(CornColors.Surface2), contentAlignment = Alignment.Center) {
                        Ico(icon, CornColors.Dim, 17.dp)
                    }
                    T(label, CornColors.Dim, 11.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ico(Lucide.Alert, CornColors.Corn, 15.dp)
            T("ST-LINK/V2 的 3V3 只检测电压不供电；目标板要单独上电并共地。", CornColors.Dim, 11.5.sp)
        }
    }
}

// ---------------------------------------------------------------- records (09)

@Composable
fun RecordsTab(vm: FlashViewModel, history: List<JobEntity>, onExport: () -> Unit) {
    val batches by vm.batchList.collectAsStateWithLifecycle(initialValue = emptyList())
    var range by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    TopBar("记录", "本机保存 · 可导出 CSV / JSON") { IconBtn(Lucide.Download, onExport) }
    ScrollBody {
        val today = history.filter { isToday(it.startedAt) }
        val scope = if (range == 0) today else history.filter { it.startedAt > System.currentTimeMillis() - 7L * 86_400_000 }
        val ok = scope.count { it.status in listOf("SIMULATED", "SUCCEEDED") }
        val bad = scope.count { it.status == "FAILED" }
        CornCard(spacing = 10.dp) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Label(if (range == 0) "今天" else "7 天")
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        T("${scope.size}", size = 26.sp, weight = FontWeight.Bold, mono = true)
                        T("块 · 良率 ${percent((ok + bad).takeIf { it > 0 }?.let { ok.toFloat() / it })}", CornColors.Leaf, 12.5.sp, FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                Seg(listOf("今天", "7 天"), range, { range = it }, Modifier.width(120.dp), height = 32.dp)
            }
            Bars(scope, range == 0)
        }
        val filters = listOf(
            Triple("全部", ChipTone.WARN) { _: JobEntity -> true },
            Triple("成功", ChipTone.OK) { j: JobEntity -> j.status in listOf("SIMULATED", "SUCCEEDED") },
            Triple("失败", ChipTone.BAD) { j: JobEntity -> j.status == "FAILED" },
            Triple("模拟", ChipTone.INFO) { j: JobEntity -> j.mode == "DEMO" },
            Triple("中止", ChipTone.NEUTRAL) { j: JobEntity -> j.status in listOf("CANCELLED", "INTERRUPTED") },
        )
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            filters.forEachIndexed { i, (label, tone, pred) ->
                Chip("$label ${history.count(pred)}", if (filter == i) tone else ChipTone.NEUTRAL, onClick = { filter = i })
            }
        }
        if (batches.isNotEmpty() && filter == 0) {
            CornCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 14.dp)) {
                    batches.take(5).forEachIndexed { i, b ->
                        SRow(b.workOrder, "${formatDateTime(b.startedAt)} · 计划 ${b.planned} · ${b.firmwareName}", last = i == minOf(4, batches.lastIndex),
                            onClick = { vm.openReport(b.id) }, leading = { Ico(Lucide.List, CornColors.Corn, 16.dp) }) {
                            T("报告", CornColors.Corn, 12.sp, FontWeight.SemiBold); Chev()
                        }
                    }
                }
            }
        }
        val rows = history.filter(filters[filter].third)
        if (rows.isEmpty()) DashedNote { T("还没有记录", CornColors.Dim, 13.sp) }
        else CornCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                rows.take(200).forEachIndexed { i, r -> RecordRow(r, history.size - history.indexOf(r), i == minOf(199, rows.lastIndex)) }
            }
        }
    }
}

@Composable
private fun RecordRow(r: JobEntity, index: Int, last: Boolean) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor(r.status)))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T("#${r.seq ?: index}", size = 13.5.sp, weight = FontWeight.Bold, mono = true)
                    T(shortUid(r.targetUid), CornColors.Dim, 12.sp, mono = true, maxLines = 1)
                }
                T("${r.firmwareName} · ${formatHm(r.startedAt)}" + (r.serial?.let { " · $it" } ?: ""), CornColors.Mute, 11.5.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                T(if (r.status == "FAILED") r.stage.removePrefix("停在").trim('「', '」') + "失败" else statusLabel(r.status),
                    statusColor(r.status), 12.5.sp, FontWeight.SemiBold)
                T(r.finishedAt?.let { secs(it - r.startedAt) } ?: "—", CornColors.Mute, 11.sp, mono = true)
            }
        }
        if (!last) DividerLine()
    }
}

/** Hourly (today) or daily (7 days) bars; failures stacked as a red cap. */
@Composable
private fun Bars(rows: List<JobEntity>, hourly: Boolean) {
    val cal = Calendar.getInstance()
    val buckets: List<Pair<String, List<JobEntity>>> = if (hourly) {
        val end = maxOf(19, cal.get(Calendar.HOUR_OF_DAY)); val start = end - 11
        (start..end).map { h -> "%02d:00".format(h) to rows.filter { Calendar.getInstance().apply { timeInMillis = it.startedAt }.get(Calendar.HOUR_OF_DAY) == h } }
    } else {
        (6 downTo 0).map { d ->
            val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -d) }
            formatDate(day.timeInMillis) to rows.filter {
                val c = Calendar.getInstance().apply { timeInMillis = it.startedAt }
                c.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR) && c.get(Calendar.YEAR) == day.get(Calendar.YEAR)
            }
        }
    }
    val max = buckets.maxOf { it.second.size }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
            buckets.forEach { (_, items) ->
                val fail = items.count { it.status == "FAILED" }
                val good = items.size - fail
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (fail > 0) Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(2.dp)).background(CornColors.Red))
                    Box(Modifier.fillMaxWidth().height((60f * good / max).dp.coerceAtLeast(if (good > 0) 3.dp else 2.dp))
                        .clip(RoundedCornerShape(3.dp)).background(if (good > 0) CornColors.Leaf.copy(alpha = .85f) else CornColors.Surface3))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val labels = if (hourly) listOf(0, 4, 8, buckets.lastIndex) else listOf(0, 3, buckets.lastIndex)
            labels.forEachIndexed { i, idx ->
                if (i > 0) Spacer(Modifier.weight(1f))
                T(buckets[idx].first, CornColors.Mute, 10.sp, mono = true)
            }
        }
    }
}

// ---------------------------------------------------------------- settings (10)

@Composable
fun SettingsScreen(vm: FlashViewModel, onBack: () -> Unit, onExport: () -> Unit) {
    val prefs = vm.prefs
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        BackTop("设置", onBack = onBack)
        ScrollBody {
            Label("烧录")
            Group {
                SRow("同一 UID 重复烧录", "批次新建时可改；此处仅说明默认「先提醒」") { Chip("先提醒", ChipTone.WARN) }
                SRow("成功提示音") { CornToggle(prefs.successTone) { prefs.successTone = it } }
                SRow("失败时震动") { CornToggle(prefs.vibrateOnFail) { prefs.vibrateOnFail = it } }
                SRow("保持屏幕常亮", "产线上不用反复解锁", last = true) { CornToggle(prefs.keepScreenOn) { prefs.keepScreenOn = it } }
            }
            val needNotif by vm.needNotificationPermission.collectAsStateWithLifecycle()
            if (needNotif) {
                Banner("通知权限未开启", "烧录时通知栏看不到进度和取消按钮；请到系统设置里允许通知。", tone = ChipTone.WARN)
            }
            Label("数据")
            Group {
                SRow("记录保留", "超出后最旧的先删除") { T("10 000 条", CornColors.Dim, 13.sp, mono = true) }
                SRow("导出全部记录", "JSON，每块板一条", onClick = onExport) { Chev() }
                SRow("备份配置与固件", "未开放", last = true) { Chip("未开放") }
            }
            Label("模式")
            Group {
                SRow("模拟烧录器", "不接硬件也能演练完整流程，记录会标为「模拟」") { CornToggle(prefs.simulator) { prefs.simulator = it } }
                SRow("模拟故障注入", "随机出现校验失败、目标无响应、重复 UID", last = true) { CornToggle(prefs.injectFaults) { prefs.injectFaults = it } }
            }
            CornCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(44.dp)
                    Column {
                        T("Corn Flashing $version", size = 15.sp, weight = FontWeight.Bold)
                        T("非商业授权 · PolyForm NC 1.0.0", CornColors.Dim, 12.sp)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                T("Corn Flashing · by xingyingyuzhui", CornColors.Corn, 12.sp, FontWeight.SemiBold)
                T("github.com/xingyingyuzhui/Corn-Flashing", CornColors.Mute, 11.sp, mono = true)
            }
        }
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    CornCard(padding = 0.dp) { Column(Modifier.padding(horizontal = 14.dp), content = content) }
}
