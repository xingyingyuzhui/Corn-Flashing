package dev.corn.flashing.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.corn.flashing.pack.InstalledFlm

@Composable
fun AlgorithmLibraryCard(vm: FlashViewModel) {
    val items by vm.installedAlgorithms.collectAsStateWithLifecycle()
    val bindings by vm.algorithmBindings.collectAsStateWithLifecycle()
    val candidates by vm.algorithmCandidates.collectAsStateWithLifecycle()
    val busy by vm.algorithmBusy.collectAsStateWithLifecycle()
    val state by vm.session.collectAsStateWithLifecycle()
    var bindingRequest by remember { mutableStateOf<InstalledFlm?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importAlgorithm) }
    val locked = busy || state.running || state.probing || vm.batch.collectAsStateWithLifecycle().value != null
    CornCard(spacing = 10.dp) {
        Label("算法包 · CMSIS-Pack / FLM")
        T("导入器件包或独立 FLM，检查布局后绑定到已识别的芯片。导入不会自动启用。", CornColors.Dim, 12.sp)
        CornButton(if (busy) "正在解析…" else "导入 .pack / .flm", { picker.launch(arrayOf("*/*")) },
            Modifier.fillMaxWidth(), enabled = !locked, icon = Lucide.Download)
        if (items.isEmpty()) T("尚无导入算法，当前使用内置支持。", CornColors.Mute, 12.sp)
        items.forEach { item ->
            DividerLine()
            T(item.name, size = 13.sp, weight = FontWeight.SemiBold)
            T("${item.model.flash.start.toString(16)} · ${item.model.flash.size / 1024} KB · 页 ${item.model.flash.pageSize} B", CornColors.Dim, 11.5.sp, mono = true)
            T("SHA ${item.hash.take(12)} · ${item.origin}", CornColors.Mute, 11.sp)
            val active = bindings.any { it.deviceId == state.target?.deviceId && it.hash == item.hash }
            val incompatibility = state.target?.let { vm.algorithmLibrary.compatibility(item, it) }
            if (incompatibility != null) T(incompatibility, CornColors.Corn, 11.5.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CornButton(if (active) "当前绑定" else "用于当前芯片", { bindingRequest = item }, Modifier.weight(1f),
                    enabled = !locked && state.target != null && incompatibility == null && !active, primary = !active, height = 42)
                CornButton("移除", { vm.removeAlgorithm(item.hash) }, Modifier.weight(1f), enabled = !locked, height = 42)
            }
        }
        if (bindings.any { it.deviceId == state.target?.deviceId }) {
            CornButton("恢复内置算法", vm::restoreBuiltInAlgorithm, Modifier.fillMaxWidth(), enabled = !locked, height = 42)
        }
        T("外部算法会在芯片 RAM 中执行。请使用你信任的厂商算法；地址和扇区匹配不能证明算法适用于每个具体料号。", CornColors.Mute, 11.5.sp)
    }
    if (candidates.isNotEmpty()) AlertDialog(
        onDismissRequest = vm::dismissAlgorithmCandidates,
        title = { T("选择要导入的算法", size = 18.sp, weight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                candidates.forEach { candidate ->
                    CornButton(candidate.title, { vm.installAlgorithm(candidate) }, Modifier.fillMaxWidth(), enabled = !busy, height = 64)
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::dismissAlgorithmCandidates) { T("取消", CornColors.Corn) } },
    )
    bindingRequest?.let { item -> AlertDialog(
        onDismissRequest = { bindingRequest = null },
        title = { T("绑定到当前芯片", size = 18.sp, weight = FontWeight.Bold) },
        text = {
            T("${item.name}\n设备组 0x${state.target?.deviceId?.toString(16)} · ${state.target?.flashSizeBytes?.div(1024)} KB\n\n启用后，下次烧录会执行此 FLM。当前仅完成格式、RAM 与 Flash 布局检查，尚无该算法的实板验证记录。", CornColors.Dim, 13.sp)
        },
        dismissButton = { TextButton(onClick = { bindingRequest = null }) { T("取消", CornColors.Dim) } },
        confirmButton = { TextButton(onClick = { bindingRequest = null; vm.bindAlgorithm(item.hash) }) { T("确认绑定", CornColors.Corn) } },
    ) }
}
