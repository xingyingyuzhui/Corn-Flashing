package dev.corn.flashing.core.targets

import dev.corn.flashing.core.AddressRange
import dev.corn.flashing.core.ProductProfile
import dev.corn.flashing.core.TargetInfo
import java.io.Reader
import java.util.Properties

/** Algorithm availability is separate from evidence for any particular phone/probe/board combination. */
enum class TargetSupport { FLASH_ALGORITHM, IDENTIFY_ONLY }

data class FlashSector(val address: Long, val sizeBytes: Int) {
    val range: AddressRange get() = AddressRange(address, address + sizeBytes)
}

/** A zero count extends this final region to the reported Flash capacity. */
data class SectorRegion(val offsetBytes: Long, val sizeBytes: Int, val count: Int)

data class FlashGeometry(val regions: List<SectorRegion>) {
    init {
        require(regions.isNotEmpty()) { "芯片描述缺少扇区布局" }
        var expectedOffset = 0L
        regions.forEachIndexed { index, region ->
            require(region.offsetBytes >= 0 && region.sizeBytes > 0 && region.count >= 0) { "芯片扇区布局无效" }
            require(region.offsetBytes == expectedOffset) { "芯片扇区布局重叠或有空洞" }
            expectedOffset += region.sizeBytes.toLong() * region.count
            require(region.count != 0 || index == regions.lastIndex) { "扩展扇区只能是最后一个区域" }
        }
    }
    val minimumSectorSize: Int get() = regions.minOf { it.sizeBytes }

    /** Exact sector boundaries, including chips with different sector sizes in one bank. */
    fun sectors(flashStart: Long, capacityBytes: Long): List<FlashSector> {
        require(flashStart >= 0 && capacityBytes > 0 && capacityBytes <= 0x1_0000_0000L - flashStart) { "Flash 地址/容量无效" }
        val result = mutableListOf<FlashSector>()
        var offset = 0L
        for (region in regions) {
            if (offset == capacityBytes) break
            require(region.offsetBytes == offset) { "芯片扇区布局重叠或有空洞" }
            val available = capacityBytes - offset
            val count = if (region.count == 0) {
                require(available % region.sizeBytes == 0L) { "容量不能完整覆盖末尾扇区" }
                available / region.sizeBytes
            } else minOf(region.count.toLong(), available / region.sizeBytes)
            require(count > 0 && count <= 65536 && result.size + count <= 65536) { "芯片扇区数量无效" }
            repeat(count.toInt()) {
                result += FlashSector(flashStart + offset, region.sizeBytes)
                offset += region.sizeBytes
            }
            if (region.count > 0 && count < region.count) {
                require(offset == capacityBytes) { "容量截断了芯片扇区" }
            }
        }
        require(offset == capacityBytes) { "芯片扇区布局未覆盖 Flash 容量" }
        return result
    }
}

data class TargetDescriptor(
    val key: String,
    val family: String,
    val deviceId: Int,
    val corePart: Int,
    val ramStart: Long,
    val ramMinimumBytes: Long,
    val dbgmcuAddress: Long,
    val flashSizeAddress: Long,
    val uidAddress: Long,
    val flashStart: Long,
    val allowedCapacityKiB: Set<Long>,
    val geometry: FlashGeometry,
    val algorithmId: String?,
    val support: TargetSupport,
) {
    init {
        require(key.isNotBlank() && family.isNotBlank() && deviceId in 1..0xFFF && corePart in 1..0xFFF)
        require(ramStart >= 0 && ramMinimumBytes > 0 && ramMinimumBytes <= 0x1_0000_0000L - ramStart)
        require(allowedCapacityKiB.isNotEmpty() && allowedCapacityKiB.all { it in 1..4096 })
        listOf(dbgmcuAddress, flashSizeAddress, uidAddress, flashStart).forEach { require(it in 0..0xFFFF_FFFFL) }
        require(algorithmId == null || algorithmId.isNotBlank())
        require(dbgmcuAddress % 4 == 0L && flashSizeAddress % 2 == 0L && uidAddress % 4 == 0L && ramStart % 4 == 0L)
        require((support == TargetSupport.FLASH_ALGORITHM) == (algorithmId != null)) { "算法可用状态与算法标识不一致" }
        allowedCapacityKiB.forEach { geometry.sectors(flashStart, it * 1024) }
    }

    fun validate(target: TargetInfo) {
        require(target.deviceId == deviceId) { "芯片设备组与描述不匹配" }
        require(target.flashSizeBytes % 1024L == 0L && target.flashSizeBytes / 1024 in allowedCapacityKiB) {
            "芯片报告的 Flash 容量异常：${target.flashSizeBytes / 1024} KiB（设备组 0x${deviceId.toString(16)}）"
        }
    }

    /** Alignment itself is also useful for a read-only identification view. */
    fun profileFor(target: TargetInfo, template: ProductProfile): ProductProfile {
        validate(target)
        require(template.id == "auto-detect" || template.flashStart == flashStart) { "产品 Flash 起始地址与芯片描述不符" }
        val legacyF1 = (template.id.startsWith("f103-") || template.id == "auto-detect") && key.startsWith("stm32f1-")
        return template.copy(
            id = if (legacyF1) "f103-0x${deviceId.toString(16)}-${target.flashSizeBytes / 1024}k" else "$key-${target.flashSizeBytes / 1024}k",
            name = "$family · ${target.flashSizeBytes / 1024} KB",
            flashStart = flashStart,
            flashSizeBytes = target.flashSizeBytes,
            pageSize = geometry.minimumSectorSize,
            expectedDeviceIds = setOf(deviceId),
        )
    }

    fun requireProgrammable(target: TargetInfo): TargetDescriptor {
        validate(target)
        require(support == TargetSupport.FLASH_ALGORITHM) { "$family 仅支持识别，烧录算法尚未接入；未执行擦除" }
        return this
    }
}

/** Immutable registry; duplicate IDs are rejected rather than selecting an arbitrary driver. */
class TargetRegistry(descriptors: List<TargetDescriptor>) {
    val descriptors = descriptors.toList()
    init {
        require(descriptors.isNotEmpty())
        require(descriptors.map { it.key }.distinct().size == descriptors.size) { "芯片描述 key 重复" }
        require(descriptors.map { it.deviceId }.distinct().size == descriptors.size) { "芯片设备 ID 重复" }
    }
    private val byId = descriptors.associateBy { it.deviceId }
    val identificationAddresses: Set<Long> get() = descriptors.map { it.dbgmcuAddress }.toSet()
    fun find(deviceId: Int): TargetDescriptor? = byId[deviceId]
    fun match(deviceId: Int, dbgmcuAddress: Long): TargetDescriptor? = find(deviceId)?.takeIf { it.dbgmcuAddress == dbgmcuAddress }
    fun requireProgrammable(target: TargetInfo): TargetDescriptor = requireNotNull(find(target.deviceId)) {
        "未知或尚未支持的芯片设备组，禁止擦写"
    }.requireProgrammable(target)

    companion object {
        /** Required values and complete sector geometry are validated before registering shipped UTF-8 properties. */
        fun read(reader: Reader): TargetRegistry {
            val p = Properties().apply { load(reader) }
            require(p.getProperty("schemaVersion") == "1") { "不支持的芯片描述格式版本" }
            val keys = p.getProperty("targets")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: error("芯片描述缺少 targets")
            fun value(key: String, field: String) = requireNotNull(p.getProperty("$key.$field")) { "芯片描述缺少 $key.$field" }.trim()
            fun number(text: String): Long = if (text.startsWith("0x", true)) text.substring(2).toLong(16) else text.toLong()
            fun integer(text: String): Int = number(text).also { require(it in 0..Int.MAX_VALUE.toLong()) { "芯片描述整数越界" } }.toInt()
            return TargetRegistry(keys.map { key ->
                val algorithm = value(key, "algorithm").takeUnless { it == "none" }
                TargetDescriptor(
                    key, value(key, "family"), integer(value(key, "deviceId")),
                    integer(value(key, "corePart")), number(value(key, "ramStart")), number(value(key, "ramMinimumBytes")),
                    number(value(key, "dbgmcuAddress")), number(value(key, "flashSizeAddress")), number(value(key, "uidAddress")),
                    number(value(key, "flashStart")), value(key, "capacityKiB").split(',').map { number(it.trim()) }.toSet(),
                    FlashGeometry(value(key, "sectors").split(',').map { definition ->
                        val fields = definition.trim().split(':')
                        require(fields.size == 3) { "芯片扇区定义无效" }
                        SectorRegion(number(fields[0]), integer(fields[1]), if (fields[2] == "*") 0 else integer(fields[2]))
                    }), algorithm, TargetSupport.valueOf(value(key, "support")),
                )
            })
        }
    }
}

object TargetCatalog {
    val registry: TargetRegistry = requireNotNull(
        javaClass.getResourceAsStream("/dev/corn/flashing/targets/builtin.properties"),
    ) { "内置芯片描述库缺失" }.bufferedReader(Charsets.UTF_8).use(TargetRegistry::read)
    val descriptors: List<TargetDescriptor> get() = registry.descriptors
    val identificationAddresses: Set<Long> get() = registry.identificationAddresses
    fun find(deviceId: Int): TargetDescriptor? = registry.find(deviceId)
    fun match(deviceId: Int, dbgmcuAddress: Long): TargetDescriptor? = registry.match(deviceId, dbgmcuAddress)
    fun requireProgrammable(target: TargetInfo): TargetDescriptor = registry.requireProgrammable(target)
}
