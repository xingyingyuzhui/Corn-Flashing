package dev.corn.flashing.core.engine

import dev.corn.flashing.core.stlink.StLinkVersion

/** Conservative firmware capability negotiation, independent of Android's USB APIs. */
object StLinkProbeCapabilities {
    fun describe(version: StLinkVersion, transport: String): ProbeInfo {
        val capabilities = mutableSetOf<ProbeCapability>()
        if (version.stlink == 2 && version.jtag >= 13) {
            capabilities += setOf(ProbeCapability.MEMORY_32, ProbeCapability.CORE_REGISTERS,
                ProbeCapability.HALT_RESUME, ProbeCapability.SOFTWARE_RESET)
            if (version.supportsHalfWordWrite) capabilities += ProbeCapability.NATIVE_HALFWORD_WRITE
        }
        val kind = when (version.stlink) { 2 -> ProbeKind.ST_LINK_V2; 3 -> ProbeKind.ST_LINK_V3; else -> ProbeKind.UNKNOWN }
        return ProbeInfo(kind, "V${version.stlink}J${version.jtag}S${version.swim}", capabilities, transport)
    }
}
