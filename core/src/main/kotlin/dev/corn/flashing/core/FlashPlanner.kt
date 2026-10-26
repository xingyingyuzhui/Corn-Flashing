package dev.corn.flashing.core

import dev.corn.flashing.core.targets.TargetCatalog
import dev.corn.flashing.core.targets.TargetRegistry

object FlashPlanner {
    /** Chip descriptions provide boundaries; product policies provide permitted devices/reserved data. */
    fun plan(
        image: FirmwareImage,
        profile: ProductProfile,
        target: TargetInfo,
        registry: TargetRegistry = TargetCatalog.registry,
    ): FlashPlan {
        val descriptor = requireNotNull(registry.find(target.deviceId)) {
            "未知或尚未支持的芯片设备组，禁止擦写"
        }
        require(target.deviceId in profile.expectedDeviceIds) { "芯片设备组不符合产品配置" }
        descriptor.requireProgrammable(target)
        val effective = descriptor.profileFor(target, profile)
        val flash = AddressRange(effective.flashStart, effective.flashStart + effective.flashSizeBytes)
        val sectors = descriptor.geometry.sectors(effective.flashStart, effective.flashSizeBytes)
        ReservedRegionPolicy.validateRanges(effective.reservedRanges, target, descriptor)
        require(image.segments.isNotEmpty()) { "固件没有可烧录数据" }
        val touched = sortedSetOf<Long>()
        var previousEnd = flash.start
        image.segments.sortedBy { it.address }.forEach { segment ->
            require(segment.data.isNotEmpty()) { "固件含空数据区段" }
            require(segment.address >= flash.start && segment.endAddressExclusive <= flash.endExclusive) {
                "固件地址超出允许范围：${segment.address.hexAddress()}"
            }
            require(segment.address >= previousEnd) { "固件数据区段重叠" }
            previousEnd = segment.endAddressExclusive
            val writeRange = AddressRange(segment.address, segment.endAddressExclusive)
            require(effective.reservedRanges.none { it.overlaps(writeRange) }) { "固件覆盖产品保留区" }
            sectors.filter { it.range.overlaps(writeRange) }.forEach { touched += it.address }
        }
        val eraseSectors = sectors.filter { it.address in touched }
        eraseSectors.forEach { sector ->
            require(effective.reservedRanges.none { it.overlaps(sector.range) }) {
                "擦除页 ${sector.address.hexAddress()} 包含保留数据，当前不支持自动读改写"
            }
        }
        return FlashPlan(image, effective, target, touched.toList(), eraseSectors.associate { it.address to it.sizeBytes })
    }
}
