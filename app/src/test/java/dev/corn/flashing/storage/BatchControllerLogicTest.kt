package dev.corn.flashing.storage

import dev.corn.flashing.core.BatchFingerprint
import dev.corn.flashing.core.BatchResumeBlock
import dev.corn.flashing.core.BatchRules
import dev.corn.flashing.core.ProductProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * App-layer resume/end rules that BatchController wires to Room.
 * Full cancelRunning wiring is covered by CornApplication + service smoke;
 * fingerprint comparison (config content SHA) is asserted here.
 */
class BatchControllerLogicTest {
    @Test fun resumeUsesConfigContentShaNotOnlyProfileId() {
        val base = ProductProfile(id = "f103-64k")
        val changed = base.copy(pageSize = 2048)
        assertEquals(base.id, changed.id)
        val frozen = BatchFingerprint("fw", base.configSha256)
        assertNull(BatchRules.resumeBlock(frozen, BatchFingerprint("fw", base.configSha256)))
        assertEquals(
            BatchResumeBlock.PROFILE_CHANGED,
            BatchRules.resumeBlock(frozen, BatchFingerprint("fw", changed.configSha256)),
        )
    }

    @Test fun statusLabelsCoverBatchExportPath() {
        assertEquals("模拟成功", BatchController.statusLabel("SIMULATED"))
        assertEquals("跳过重复", BatchController.statusLabel("SKIPPED"))
        assertEquals("已中止", BatchController.statusLabel("CANCELLED"))
    }
}
