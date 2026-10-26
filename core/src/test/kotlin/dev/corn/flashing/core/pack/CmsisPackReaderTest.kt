package dev.corn.flashing.core.pack

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CmsisPackReaderTest {
    private val metadata = "<vendor>Acme</vendor><name>Example</name><releases><release version='2.1.0'/><release version='2.0.0'/></releases>"
    private val family = """<family Dfamily="Family" Dvendor="DeviceVendor:1">
        <processor Dcore="Cortex-M3"/>
        <memory id="IRAM1" start="0x20000000" size="0x1000" access="rwx" default="true"/>
        <algorithm name="Flash/a.FLM" start="0x08000000" size="0x10000" RAMstart="0x20000000" RAMsize="0x1000" default="true"/>
        <device Dname="Chip"><memory id="IRAM1" size="0x2000"/><variant Dvariant="ChipA"/><variant Dvariant="ChipB"><processor Dcore="Cortex-M4"/></variant></device>
        </family>"""
    private fun xml(families: String = family) = "<?xml version='1.0' encoding='UTF-8'?><package>$metadata<devices>$families</devices></package>".toByteArray()
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() } }
    }.toByteArray()
    private fun pack(description: ByteArray = xml()) = zip("Acme.Example.pdsc" to description, "Flash/a.FLM" to byteArrayOf(1, 2, 3))

    @Test fun parsesMetadataInheritanceAndVariants() {
        val result = CmsisPackReader.read(pack())
        assertEquals(PackMetadata("Acme", "Example", "2.1.0"), result.metadata)
        assertEquals(listOf("ChipA", "ChipB"), result.devices.map { it.name })
        val device = result.devices.first()
        assertEquals("DeviceVendor", device.vendor)
        assertEquals("Cortex-M3", device.processors.single().core)
        assertEquals("Cortex-M4", result.devices.last().processors.single().core)
        assertEquals(0x2000L, device.memories.single().size)
        assertEquals(0x20000000L, device.memories.single().start)
        assertTrue(device.memories.single().isDefault)
        assertEquals(0x1000L, device.algorithms.single().ramSize)
        assertArrayEquals(byteArrayOf(1,2,3), result.algorithmBytes("Flash/a.FLM"))
        val copy = result.algorithmBytes("Flash/a.FLM"); copy[0] = 9
        assertEquals(1.toByte(), result.algorithmBytes("Flash/a.FLM")[0])
    }
    @Test fun childAlgorithmOverridesAndMultipleAlgorithmsRemainSelectable() {
        val family = family.replace("<variant Dvariant=\"ChipA\"/>", """<variant Dvariant="ChipA">
            <algorithm name="Flash/a.FLM" start="0x08000000" size="0x20000" default="false"/>
            <algorithm name="Flash/b.FLM" start="0x90000000" size="0x100000" default="true" Pname="M3"/>
            </variant>""")
        val archive = CmsisPackReader.read(zip("a.pdsc" to xml(family), "Flash/a.FLM" to byteArrayOf(1), "Flash/b.FLM" to byteArrayOf(2)))
        val choices = archive.devices.first().algorithms
        assertEquals(2, choices.size)
        assertEquals(0x20000L, choices.first().size)
        assertFalse(choices.first().isDefault)
        assertEquals("M3", choices.last().processorName)
    }
    @Test fun resolvesNestedPdscPathsWithoutExtracting() {
        val archive = CmsisPackReader.read(zip("nested/a.pdsc" to xml(), "nested/Flash/a.FLM" to byteArrayOf(1)))
        assertEquals("nested/Flash/a.FLM", archive.devices.first().algorithms.single().path)
    }
    @Test fun rejectsUnsafeAndDuplicateZipPaths() {
        for (name in listOf("../a.FLM", "/a.FLM", "C:/a.FLM", "Flash\\a.FLM", "Flash/./a.FLM", "Flash//a.FLM")) {
            assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(zip("a.pdsc" to xml(), name to byteArrayOf(1))) }
        }
        // ZipOutputStream refuses duplicate names. Change an equally-sized entry name in both headers.
        val duplicated = zip("a.pdsc" to xml(), "Flash/a.FLM" to byteArrayOf(1), "Flash/b.FLM" to byteArrayOf(2))
        val from = "Flash/b.FLM".toByteArray(); val to = "Flash/a.FLM".toByteArray()
        for (index in 0..duplicated.size - from.size) if (duplicated.copyOfRange(index,index+from.size).contentEquals(from)) to.copyInto(duplicated,index)
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(duplicated) }
    }
    @Test fun directoriesCannotBypassDecompressionBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            CmsisPackReader.read(zip("a.pdsc" to xml(), "payload/" to ByteArray(4096)))
        }
    }
    @Test fun rejectsMissingReferencesAndMultipleDescriptions() {
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(zip("a.pdsc" to xml())) }
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(zip("a.pdsc" to xml(), "b.pdsc" to xml())) }
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(zip("Flash/a.FLM" to byteArrayOf(1))) }
    }
    @Test fun rejectsDoctypeEntitiesAndUtf16() {
        val entity = "<!DOCTYPE package [<!ENTITY e SYSTEM 'file:///etc/passwd'>]>" + xml().toString(Charsets.UTF_8).substringAfter("?>")
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(pack(entity.toByteArray())) }
        assertThrows(Exception::class.java) { CmsisPackReader.read(pack(xml().toString(Charsets.UTF_8).toByteArray(Charsets.UTF_16))) }
    }
    @Test fun boundsEntriesExpansionAndXmlDepth() {
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(pack(), CmsisPackReader.Limits(entries=1)) }
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(pack(), CmsisPackReader.Limits(total=10)) }
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(pack(), CmsisPackReader.Limits(pdsc=10)) }
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(pack(), CmsisPackReader.Limits(xmlDepth=2)) }
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(pack(), CmsisPackReader.Limits(xmlNodes=2)) }
        assertThrows(IllegalArgumentException::class.java) {
            CmsisPackReader.read(zip("a.pdsc" to xml(), "Flash/a.FLM" to ByteArray(1024)), CmsisPackReader.Limits(algorithm=512))
        }
    }
    @Test fun rejectsConflictingDefinitionsAndInvalidRanges() {
        val duplicate = family.replace("<device Dname=\"Chip\">", "<algorithm name='Flash/a.FLM' start='0x08000000' size='10'/><device Dname=\"Chip\">")
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(pack(xml(duplicate))) }
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(pack(xml(family.replace("size=\"0x1000\"", "size=\"0xffffffff\"")))) }
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(pack(xml(family.replace("Flash/a.FLM", "../a.FLM")))) }
    }
    @Test fun sameDeviceNameMustNotSilentlySelectOne() {
        assertThrows(IllegalArgumentException::class.java) { CmsisPackReader.read(pack(xml(family.replace("Dvariant=\"ChipB\"", "Dvariant=\"ChipA\"")))) }
    }
    @Test fun deviceWithoutAlgorithmRemainsMetadataOnly() {
        val archive = CmsisPackReader.read(zip("a.pdsc" to xml("<family Dfamily='X'><device Dname='NoFlash'/></family>")))
        assertTrue(archive.devices.single().algorithms.isEmpty())
    }
}
