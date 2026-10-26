package dev.corn.flashing.core.engine

import dev.corn.flashing.core.TargetInfo
import dev.corn.flashing.core.hexAddress
import dev.corn.flashing.core.targets.TargetCatalog
import dev.corn.flashing.core.targets.TargetRegistry

/** Read-only identification selected by CPU part and registered chip-specific addresses. */
class TargetIdentifier(private val registry: TargetRegistry = TargetCatalog.registry) {
    fun identify(probe: DebugProbe): TargetInfo {
        val cpuid = probe.read32(CPUID_ADDRESS)
        val corePart = ((cpuid ushr 4) and 0xFFF).toInt()
        val candidates = registry.descriptors.filter { it.corePart == corePart }
        require(candidates.isNotEmpty()) {
            "未登记的核心类型 0x${corePart.toString(16)}（CPUID=${cpuid.hexAddress()}），未执行擦除"
        }
        val observed = mutableListOf<String>()
        for (address in candidates.map { it.dbgmcuAddress }.distinct()) {
            val word = probe.read32(address)
            val deviceId = (word and 0xFFF).toInt()
            val descriptor = candidates.firstOrNull { it.deviceId == deviceId && it.dbgmcuAddress == address }
            observed += "${address.hexAddress()}=0x${deviceId.toString(16)}"
            if (descriptor == null) continue

            // Some size registers (for example STM32F4) are halfword-aligned, not word-aligned.
            val size = probe.readMemory(descriptor.flashSizeAddress, 2)
            check(size.size == 2) { "芯片容量寄存器读回长度不符" }
            val kib = (size[0].toInt() and 255) or ((size[1].toInt() and 255) shl 8)
            val target = TargetInfo(deviceId, ((word ushr 16) and 0xFFFF).toInt(),
                kib.toLong() * 1024, null, descriptor.family)
            descriptor.validate(target)

            val uid = probe.readMemory(descriptor.uidAddress, 12)
            check(uid.size == 12) { "芯片 UID 读回长度不符" }
            return target.copy(uid = uid.asList().chunked(4).joinToString("-") { part ->
                part.joinToString("") { "%02X".format(it.toInt() and 255) }
            })
        }
        throw IllegalArgumentException(
            "未登记的芯片设备组（核心 0x${corePart.toString(16)}；${observed.joinToString()}），未执行擦除",
        )
    }

    companion object { const val CPUID_ADDRESS = 0xE000ED00L }
}
