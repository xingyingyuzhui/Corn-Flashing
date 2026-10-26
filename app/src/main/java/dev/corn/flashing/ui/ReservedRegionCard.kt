package dev.corn.flashing.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.corn.flashing.core.*
import dev.corn.flashing.storage.ReservedRegionStore

@Composable
fun ReservedRegionCard(vm: FlashViewModel) {
    val settings by vm.reservedSettings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val batch by vm.batch.collectAsStateWithLifecycle()
    val busy by vm.algorithmBusy.collectAsStateWithLifecycle()
    val target = session.target
    val policy = target?.let { settings.policies[ReservedRegionStore.key(it)] } ?: ReservedRegionPolicy()
    val locked = session.running || session.probing || batch != null || busy
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingIndex by rememberSaveable { mutableIntStateOf(-1) }
    var start by rememberSaveable { mutableStateOf("") }
    var end by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var resetConfirm by remember { mutableStateOf(false) }
    CornCard(spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("参数保留区")
                T(if (policy.enabled) "已开启 · 烧录时跳过" else "已关闭 · 默认不保留", if (policy.enabled) CornColors.Leaf else CornColors.Dim, 12.sp)
            }
            Switch(checked = policy.enabled, onCheckedChange = { vm.setReservedPolicy(it, policy.ranges) },
                enabled = !locked && target != null && policy.ranges.isNotEmpty() && settings.error == null)
        }
        settings.error?.let {
            T(it, CornColors.Red, 12.sp)
            CornButton("重置保留区配置", { resetConfirm = true }, Modifier.fillMaxWidth(), enabled = !locked)
        }
        T(if (target == null) "先识别芯片，再添加要保留的地址。" else
            "仅用于设备组 0x${target.deviceId.toString(16)} · ${target.flashSizeBytes / 1024} KB。地址必须覆盖完整擦除页或扇区。", CornColors.Dim, 12.sp)
        policy.ranges.forEachIndexed { index, range ->
            DividerLine()
            T("${range.start.hexAddress()} – ${(range.endExclusive - 1).hexAddress()}", size = 12.sp, mono = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CornButton("编辑", {
                    editingIndex = index; start = range.start.toString(16).uppercase(); end = (range.endExclusive - 1).toString(16).uppercase()
                    error = null; editing = true
                }, Modifier.weight(1f), enabled = !locked, height = 40)
                CornButton("删除", {
                    val next = policy.ranges.filterIndexed { i, _ -> i != index }
                    vm.setReservedPolicy(policy.enabled && next.isNotEmpty(), next)
                }, Modifier.weight(1f), enabled = !locked, height = 40)
            }
        }
        CornButton("添加地址范围", {
            editingIndex = -1; start = ""; end = ""; error = null; editing = true
        }, Modifier.fillMaxWidth(), enabled = !locked && target != null && settings.error == null && policy.ranges.size < 32, height = 44)
        T("起始、结束地址均包含在范围内；不会自动扩大到整页。固件覆盖保留区时拒绝烧录，“全部非保留区”会跳过保留页。外部 FLM 自身的访问行为无法由此限制。", CornColors.Mute, 11.5.sp)
        T("关闭后已保存地址不生效；本功能不阻止新固件启动后主动清空参数，也不管理外置 EEPROM。", CornColors.Mute, 11.5.sp)
    }
    if (editing) {
        val editingTargetKey = remember { target?.let(ReservedRegionStore::key) }
        val descriptor = target?.let { vm.targetRegistry.find(it.deviceId) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { T(if (editingIndex < 0) "添加参数保留区" else "编辑参数保留区", size = 18.sp, weight = FontWeight.Bold) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(start, { start = it; error = null }, label = { Text("起始地址（十六进制）") }, singleLine = true)
                OutlinedTextField(end, { end = it; error = null }, label = { Text("结束地址（包含此地址）") }, singleLine = true)
                T("按芯片实际擦除边界填写，保存后仍需开启保留开关。", CornColors.Dim, 12.sp)
                error?.let { T(it, CornColors.Red, 12.sp) }
            } },
            dismissButton = { TextButton(onClick = { editing = false }) { T("取消", CornColors.Dim) } },
            confirmButton = { TextButton(enabled = !locked && target != null, onClick = {
                try {
                    check(target != null && descriptor != null && ReservedRegionStore.key(target) == editingTargetKey) { "目标已变化，请重新添加" }
                    val range = ReservedRegionPolicy.parseRange(start, end)
                    val next = policy.ranges.toMutableList().apply { if (editingIndex < 0) add(range) else set(editingIndex, range) }
                    ReservedRegionPolicy.validateRanges(next, target, descriptor)
                    vm.setReservedPolicy(policy.enabled, next)
                    editing = false
                } catch (failure: Exception) { error = failure.message ?: "地址范围无效" }
            }) { T("保存", CornColors.Corn) } },
        )
    }
    if (resetConfirm) AlertDialog(onDismissRequest = { resetConfirm = false },
        title = { T("重置保留区配置", size = 18.sp) },
        text = { T("将移除所有设备组保存的保留地址，并关闭参数保留。", CornColors.Dim) },
        dismissButton = { TextButton(onClick = { resetConfirm = false }) { T("取消", CornColors.Dim) } },
        confirmButton = { TextButton(onClick = { resetConfirm = false; vm.resetReservedSettings() }) { T("确认重置", CornColors.Corn) } })
}
