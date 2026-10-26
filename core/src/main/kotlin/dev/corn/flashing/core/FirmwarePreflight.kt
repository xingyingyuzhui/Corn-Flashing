package dev.corn.flashing.core

/** UI preflight shares the actual erase planner; an unprobed capacity is never a promise. */
data class FirmwarePreflight(val targetKnown: Boolean, val error: String? = null) {
    val canStart: Boolean get() = error == null

    companion object {
        fun inspect(image: FirmwareImage, profile: ProductProfile, target: TargetInfo?, registry: dev.corn.flashing.core.targets.TargetRegistry = dev.corn.flashing.core.targets.TargetCatalog.registry): FirmwarePreflight {
            if (target == null) return FirmwarePreflight(targetKnown = false)
            return try {
                FlashPlanner.plan(image, profile, target, registry)
                FirmwarePreflight(targetKnown = true)
            } catch (error: IllegalArgumentException) {
                FirmwarePreflight(targetKnown = true, error = error.message ?: "固件不符合目标配置")
            }
        }
    }
}

/** Legacy job rows carry a message but no typed validation stage; classify those without blaming USB. */
fun isFirmwareConfigurationError(message: String): Boolean = listOf(
    "实际芯片、算法或保留区与任务冻结配置不一致", "保留区必须按实际擦除页", "保留区地址重叠或重复", "请先添加参数保留区", "超出允许范围", "芯片设备组不符合产品配置", "Flash 容量不符合产品配置", "容量异常",
    "当前仅支持 F103 内部 Flash", "产品 Flash 起始地址与芯片描述不符", "烧录算法尚未接入", "保留区超出 Flash 范围", "没有可烧录数据", "固件含空数据区段",
    "固件数据区段重叠", "固件覆盖产品保留区", "包含保留数据", "未知或尚未支持的芯片设备组",
).any(message::contains)
