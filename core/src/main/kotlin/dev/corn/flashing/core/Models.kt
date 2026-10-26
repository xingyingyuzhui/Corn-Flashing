package dev.corn.flashing.core

import java.security.MessageDigest

enum class FirmwareFormat { HEX, BIN, ELF, AXF }

data class FirmwareSegment(val address: Long, val data: ByteArray) {
    val endAddressExclusive: Long get() = address + data.size
}

/** Addresses are unsigned 32-bit values represented as Long; ends are exclusive. */
data class AddressRange(val start: Long, val endExclusive: Long) {
    init { require(start >= 0 && endExclusive > start && endExclusive <= 0x1_0000_0000L) }
    fun overlaps(other: AddressRange): Boolean = start < other.endExclusive && other.start < endExclusive
}

data class FirmwareImage(
    val format: FirmwareFormat,
    val segments: List<FirmwareSegment>,
    val sourceSha256: String,
) {
    val totalBytes: Long get() = segments.sumOf { it.data.size.toLong() }
    val startAddress: Long get() = segments.minOf { it.address }
    val endAddressExclusive: Long get() = segments.maxOf { it.endAddressExclusive }
}

data class ProductProfile(
    val id: String = "f103-open",
    val name: String = "STM32F1 · 探测后自动匹配",
    val flashStart: Long = 0x08000000L,
    /** Placeholder until a chip is probed; planning uses [TargetInfo.flashSizeBytes]. */
    val flashSizeBytes: Long = 64L * 1024,
    val pageSize: Int = 1024,
    val reservedRanges: List<AddressRange> = emptyList(),
    /** Default: every known F1 group; [ProductProfiles.alignedTo] pins to the probed id. */
    val expectedDeviceIds: Set<Int> = setOf(0x412, 0x410, 0x414, 0x430),
) {
    /**
     * Stable hash of every field that affects erase/write decisions.
     * Used to block batch resume when the profile id is unchanged but its content is not.
     */
    val configSha256: String get() {
        val canonical = buildString {
            append("id=").append(id).append('\n')
            append("name=").append(name).append('\n')
            append("flashStart=").append(flashStart).append('\n')
            append("flashSizeBytes=").append(flashSizeBytes).append('\n')
            append("pageSize=").append(pageSize).append('\n')
            append("reserved=")
            reservedRanges.sortedWith(compareBy({ it.start }, { it.endExclusive })).forEach {
                append(it.start).append('-').append(it.endExclusive).append(';')
            }
            append('\n')
            append("devices=")
            expectedDeviceIds.sorted().joinTo(this, ",")
            append('\n')
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

data class TargetInfo(
    val deviceId: Int,
    val revisionId: Int,
    val flashSizeBytes: Long,
    val uid: String?,
    val family: String,
)

data class FlashPlan(
    val image: FirmwareImage,
    val profile: ProductProfile,
    val target: TargetInfo,
    val erasePageAddresses: List<Long>,
    /** Actual per-sector sizes, including non-uniform devices; empty only on legacy hand-built plans. */
    val eraseSectorSizes: Map<Long, Int> = emptyMap(),
) {
    val writeBytes: Long get() = image.totalBytes
}

enum class FlashStage { CONNECTING, PROBING, ERASING, WRITING, VERIFYING, RESETTING, SUCCEEDED }

data class FlashProgress(val stage: FlashStage, val fraction: Float, val message: String)

fun Long.hexAddress(): String = "0x" + toString(16).uppercase().padStart(8, '0')
