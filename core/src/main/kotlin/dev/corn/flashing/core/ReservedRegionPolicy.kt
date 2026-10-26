package dev.corn.flashing.core

import dev.corn.flashing.core.targets.TargetDescriptor

/** User-defined preservation is opt-in. Disabled ranges remain stored but have no planning effect. */
data class ReservedRegionPolicy(
    val enabled: Boolean = false,
    val ranges: List<AddressRange> = emptyList(),
) {
    fun apply(profile: ProductProfile, target: TargetInfo, descriptor: TargetDescriptor): ProductProfile {
        if (!enabled) return profile
        require(ranges.isNotEmpty()) { "请先添加参数保留区，再开启保留" }
        val combined = profile.reservedRanges + ranges
        validateRanges(combined, target, descriptor)
        return profile.copy(reservedRanges = combined.sortedBy { it.start })
    }

    companion object {
        /** Both user inputs are inclusive addresses; internal ends remain exclusive and use Long. */
        fun parseRange(startText: String, endInclusiveText: String): AddressRange {
            fun address(text: String, label: String): Long {
                val digits = text.trim().removePrefix("0x").removePrefix("0X")
                val value = digits.takeIf { it.length in 1..8 && it.all { c -> c.digitToIntOrNull(16) != null } }
                    ?.toLongOrNull(16)
                return requireNotNull(value) { "$label 不是有效的32位十六进制地址" }
            }
            val start = address(startText, "保留区起始地址")
            val end = address(endInclusiveText, "保留区结束地址")
            require(end >= start) { "保留区结束地址不能小于起始地址" }
            return AddressRange(start, end + 1)
        }

        /** Complete physical erase sectors are the only preservable units; no implicit rounding. */
        fun validateRanges(ranges: List<AddressRange>, target: TargetInfo, descriptor: TargetDescriptor) {
            descriptor.validate(target)
            val flashStart = descriptor.flashStart
            val flashEnd = flashStart + target.flashSizeBytes
            val sectors = descriptor.geometry.sectors(flashStart, target.flashSizeBytes)
            val boundaries = sectors.mapTo(mutableSetOf()) { it.address }.apply { add(flashEnd) }
            val sorted = ranges.sortedBy { it.start }
            sorted.forEach { reserved ->
                require(reserved.start >= flashStart && reserved.endExclusive <= flashEnd) { "保留区超出 Flash 范围" }
                require(reserved.start in boundaries && reserved.endExclusive in boundaries) {
                    "保留区必须按实际擦除页/扇区边界对齐；不会自动扩大地址范围"
                }
            }
            sorted.zipWithNext().forEach { (left, right) ->
                require(!left.overlaps(right)) { "保留区地址重叠或重复" }
            }
        }
    }
}
