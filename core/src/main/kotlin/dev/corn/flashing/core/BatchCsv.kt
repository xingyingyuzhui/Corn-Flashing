package dev.corn.flashing.core

/** One board row of a batch report. Kept free of Android/storage types so CSV output is unit-tested. */
data class BatchCsvRow(
    val seq: Int?,
    val station: String,
    val uid: String?,
    val serial: String?,
    val result: String,
    val durationMillis: Long?,
    val stage: String,
    val message: String,
    val startedAt: Long,
)

object BatchCsv {
    /**
     * One line per board. Time is supplied by the caller so tests do not depend on the host time zone.
     * The BOM keeps Excel from mis-decoding the Chinese headings.
     * Cells that look like spreadsheet formulas are neutralized with a leading apostrophe.
     */
    fun render(rows: List<BatchCsvRow>, timestamp: (Long) -> String): String = buildString {
        append('\uFEFF')
        append("序号,工位,UID,序列号,结果,用时(ms),阶段,说明,开始时间\n")
        rows.forEach { row ->
            listOf(
                row.seq?.toString().orEmpty(),
                row.station,
                row.uid.orEmpty(),
                row.serial.orEmpty(),
                row.result,
                row.durationMillis?.toString().orEmpty(),
                row.stage,
                row.message,
                timestamp(row.startedAt),
            ).joinTo(this, ",") { escape(it) }
            append('\n')
        }
    }

    internal fun escape(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in FORMULA_PREFIX) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }

    private val FORMULA_PREFIX = charArrayOf('=', '+', '-', '@', '\t')
}
