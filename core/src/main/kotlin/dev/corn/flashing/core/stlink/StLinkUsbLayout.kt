package dev.corn.flashing.core.stlink

/**
 * Preferred ST-LINK bulk endpoint addresses and interface selection.
 * Pure Kotlin — unit-tested without Android USB APIs.
 */
object StLinkUsbLayout {
    data class BulkEp(val address: Int, val directionOut: Boolean)

    data class Candidate(
        val interfaceIndex: Int,
        val outAddr: Int,
        val inAddr: Int,
    ) {
        fun describe(): String =
            "iface=$interfaceIndex OUT=0x${outAddr.toString(16)} IN=0x${inAddr.toString(16)}"
    }

    /** A known active configuration must not be reset on every open. */
    fun needsConfigurationSwitch(current: Int?, desired: Int, tryIfUnknown: Boolean = false): Boolean {
        require(desired in 1..255)
        require(current == null || current in 0..255)
        return current?.let { it != desired } ?: tryIfUnknown
    }

    /** Classic V2 clones use 0x02/0x81; V2-1 / many Nucleo boards use 0x01/0x81. */
    fun preferredOutIn(productId: Int): Pair<Int, Int> = when (productId and 0xFFFF) {
        0x3748 -> 0x02 to 0x81
        0x374B, 0x374A, 0x3752 -> 0x01 to 0x81
        else -> 0x02 to 0x81
    }

    /**
     * @param ifaces list of (interfaceIndex, bulk endpoints on that interface)
     */
    fun pick(productId: Int, ifaces: List<Pair<Int, List<BulkEp>>>): Candidate {
        val (prefOut, prefIn) = preferredOutIn(productId)
        val candidates = ifaces.mapNotNull { (idx, eps) ->
            val outs = eps.filter { it.directionOut }.map { it.address and 0xFF }
            val inns = eps.filter { !it.directionOut }.map { it.address and 0xFF }
            if (outs.isEmpty() || inns.isEmpty()) return@mapNotNull null
            val out = outs.firstOrNull { it == prefOut } ?: outs.first()
            val inn = inns.firstOrNull { it == prefIn }
                ?: inns.firstOrNull { it == 0x81 }
                ?: inns.first()
            Candidate(idx, out, inn)
        }
        require(candidates.isNotEmpty()) {
            "找不到带 bulk OUT+IN 的 ST-LINK 接口（pid=0x${(productId and 0xFFFF).toString(16)}）"
        }
        return candidates.firstOrNull { it.outAddr == prefOut && it.inAddr == prefIn }
            ?: candidates.firstOrNull { it.outAddr == prefOut }
            ?: candidates.firstOrNull { it.inAddr == prefIn }
            ?: candidates.first()
    }
}
