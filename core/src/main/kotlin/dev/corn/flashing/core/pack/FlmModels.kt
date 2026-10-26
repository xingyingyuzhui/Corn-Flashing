package dev.corn.flashing.core.pack

import dev.corn.flashing.core.targets.FlashGeometry
import dev.corn.flashing.core.targets.SectorRegion

/** FlashDevice sector entries are offsets relative to the algorithm's declared Flash base. */
data class FlmSectorRegion(val offset: Long, val size: Int)

data class FlmFlashDevice(
    val version: Int,
    val name: String,
    val type: Int,
    val start: Long,
    val size: Long,
    val pageSize: Int,
    val erasedByte: Int,
    val programTimeoutMs: Long,
    val eraseTimeoutMs: Long,
    val sectors: List<FlmSectorRegion>,
) {
    fun geometry(): FlashGeometry = FlashGeometry(sectors.mapIndexed { index, region ->
        val end = sectors.getOrNull(index + 1)?.offset ?: size
        require(end > region.offset && (end - region.offset) % region.size == 0L) { "FLM 扇区布局与 Flash 容量不符" }
        SectorRegion(region.offset, region.size, ((end - region.offset) / region.size).toInt())
    })
}

/** Parsed zero-based, position-independent FLM blob. Entrypoint offsets retain the Thumb bit. */
data class FlmImage(
    val image: ByteArray,
    val imageBase: Long,
    val codeOffset: Int,
    val codeSize: Int,
    val rwOffset: Int,
    val rwSize: Int,
    val ziOffset: Int,
    val ziSize: Int,
    val initOffset: Int,
    val unInitOffset: Int,
    val eraseSectorOffset: Int,
    val programPageOffset: Int,
    val staticBaseOffset: Int,
    val flash: FlmFlashDevice,
    val sha256: String,
)
