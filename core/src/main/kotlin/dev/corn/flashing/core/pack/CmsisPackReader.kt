package dev.corn.flashing.core.pack

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/** Pack metadata is an association hint, never a hardware ID or permission to execute an algorithm. */
data class PackMetadata(val vendor: String, val name: String, val version: String)
data class PackProcessor(val name: String, val core: String)
data class PackMemory(val name: String, val start: Long, val size: Long, val access: String, val isDefault: Boolean, val processorName: String? = null)
data class PackAlgorithm(
    val path: String, val start: Long, val size: Long,
    val ramStart: Long?, val ramSize: Long?, val isDefault: Boolean, val processorName: String?,
)
data class PackDevice(
    val name: String, val vendor: String, val processors: List<PackProcessor>,
    val memories: List<PackMemory>, val algorithms: List<PackAlgorithm>,
)

class PackArchive internal constructor(
    val metadata: PackMetadata,
    val devices: List<PackDevice>,
    val entryNames: Set<String>,
    private val algorithmFiles: Map<String, ByteArray>,
) {
    fun algorithmBytes(path: String): ByteArray = requireNotNull(algorithmFiles[path]) {
        "Pack 中找不到 FLM 算法文件：$path"
    }.copyOf()
}

/** Bounded, in-memory CMSIS-Pack reader. It never extracts archive paths onto the filesystem. */
object CmsisPackReader {
    internal data class Limits(
        val input: Int = 64 * 1024 * 1024,
        val entries: Int = 4096,
        val total: Long = 128L * 1024 * 1024,
        val algorithm: Int = 2 * 1024 * 1024,
        val pdsc: Int = 4 * 1024 * 1024,
        val other: Int = 32 * 1024 * 1024,
        val xmlNodes: Int = 100_000,
        val xmlDepth: Int = 64,
    )
    private val DEFAULT = Limits()
    fun read(bytes: ByteArray): PackArchive = read(bytes, DEFAULT)

    internal fun read(bytes: ByteArray, limits: Limits): PackArchive {
        require(bytes.isNotEmpty() && bytes.size <= limits.input) { "Pack 文件为空或超过大小限制" }
        require(bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) { "Pack 必须是 ZIP 文件" }
        val names = linkedSetOf<String>()
        val retained = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= limits.entries) { "Pack ZIP 条目过多" }
                val rawName = entry.name
                val directory = entry.isDirectory
                val name = checkedPath(if (directory) rawName.removeSuffix("/") else rawName)
                require(names.add(name)) { "Pack 含重复 ZIP 路径：$name" }
                if (directory) {
                    require(zip.read() == -1) { "Pack ZIP 目录不能包含文件数据：$name" }
                    zip.closeEntry()
                    continue
                }
                val suffix = name.substringAfterLast('.', "").lowercase()
                val maximum = when (suffix) { "flm" -> limits.algorithm; "pdsc" -> limits.pdsc; else -> limits.other }
                require(entry.size <= maximum) { "Pack 条目过大：$name" }
                val output = if (suffix == "flm" || suffix == "pdsc") ByteArrayOutputStream() else null
                val buffer = ByteArray(8192)
                var size = 0L
                while (true) {
                    val read = zip.read(buffer)
                    if (read == -1) break
                    size += read
                    total += read
                    require(size <= maximum && total <= limits.total) { "Pack 解压内容超过限制：$name" }
                    output?.write(buffer, 0, read)
                }
                if (output != null) retained[name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        val pdsc = retained.filterKeys { it.endsWith(".pdsc", ignoreCase = true) }
        require(pdsc.size == 1) { "Pack 必须且只能包含一个 PDSC 描述文件" }
        val (pdscPath, description) = pdsc.entries.single()
        val base = pdscPath.substringBeforeLast('/', "")
        val root = parseXml(description, limits)
        require(root.name == "package") { "PDSC 根元素必须是 package" }
        fun text(name: String) = root.children.firstOrNull { it.name == name }?.text?.toString()?.trim().orEmpty()
        val vendor = text("vendor")
        val packName = text("name")
        require(vendor.isNotBlank() && packName.isNotBlank()) { "PDSC 缺少包厂商或名称" }
        // PDSC release order is newest first; preserve the author's version instead of guessing semver.
        val version = root.children.firstOrNull { it.name == "releases" }?.children
            ?.firstOrNull { it.name == "release" }?.attrs?.get("version").orEmpty()
        val devices = mutableListOf<PackDevice>()
        val group = root.children.firstOrNull { it.name == "devices" }
            ?: throw IllegalArgumentException("PDSC 缺少 devices" )
        val defaults = Inherited(vendor)
        group.children.filter { it.name == "family" }.forEach { collectDevices(it, defaults, base, devices) }
        require(devices.isNotEmpty()) { "Pack 没有可选择的设备" }
        require(devices.map { it.name }.distinct().size == devices.size) { "Pack 设备名称重复，无法明确选择" }
        val files = retained.filterKeys { it.endsWith(".flm", ignoreCase = true) }
        devices.flatMap { it.algorithms }.forEach { algorithm ->
            require(files.containsKey(algorithm.path)) { "Pack 算法引用的文件不存在：${algorithm.path}" }
        }
        return PackArchive(PackMetadata(vendor, packName, version), devices.toList(), names.toSet(), files)
    }

    private data class Inherited(
        val vendor: String,
        val processors: Map<String, PackProcessor> = emptyMap(),
        val memories: Map<String, PackMemory> = emptyMap(),
        val algorithms: Map<String, PackAlgorithm> = emptyMap(),
    )

    private fun collectDevices(node: Node, inherited: Inherited, base: String, output: MutableList<PackDevice>) {
        val vendor = node.attrs["Dvendor"]?.substringBefore(':')?.takeIf { it.isNotBlank() } ?: inherited.vendor
        val processors = inherited.processors.toMutableMap()
        val memories = inherited.memories.toMutableMap()
        val algorithms = inherited.algorithms.toMutableMap()
        val ownProcessors = mutableSetOf<String>()
        val ownMemories = mutableSetOf<String>()
        val ownAlgorithms = mutableSetOf<String>()
        node.children.forEach { child ->
            val a = child.attrs
            when (child.name) {
                "processor" -> {
                    val name = a["Pname"].orEmpty()
                    require(ownProcessors.add(name)) { "PDSC 同层处理器定义重复：$name" }
                    val core = a["Dcore"] ?: processors[name]?.core.orEmpty()
                    require(core.isNotBlank()) { "PDSC 处理器缺少 Dcore" }
                    processors[name] = PackProcessor(name, core)
                }
                "memory" -> {
                    val name = a["name"] ?: a["id"] ?: error("PDSC memory 缺少 name/id")
                    val key = a["Pname"].orEmpty() + ":" + name
                    require(ownMemories.add(key)) { "PDSC 同层内存定义重复：$name" }
                    val old = memories[key]
                    val start = a["start"]?.let(::number) ?: old?.start ?: error("PDSC memory 缺少 start")
                    val size = a["size"]?.let(::number) ?: old?.size ?: error("PDSC memory 缺少 size")
                    checkedRange(start, size)
                    memories[key] = PackMemory(name, start, size, a["access"] ?: old?.access.orEmpty(), bool(a["default"], old?.isDefault ?: false), a["Pname"] ?: old?.processorName)
                }
                "algorithm" -> {
                    val path = resolvePath(base, a["name"] ?: error("PDSC algorithm 缺少 name"))
                    require(path.endsWith(".flm", ignoreCase = true)) { "暂仅支持 FLM 算法：$path" }
                    val pname = a["Pname"]
                    // Pack algorithms inherit by path and processor; child entries override attributes.
                    val key = pname.orEmpty() + ":" + path
                    require(ownAlgorithms.add(key)) { "PDSC 同层算法定义重复：$path" }
                    val old = algorithms[key]
                    val start = a["start"]?.let(::number) ?: old?.start ?: error("PDSC algorithm 缺少 start")
                    val size = a["size"]?.let(::number) ?: old?.size ?: error("PDSC algorithm 缺少 size")
                    checkedRange(start, size)
                    val ramStart = a["RAMstart"]?.let(::number) ?: old?.ramStart
                    val ramSize = a["RAMsize"]?.let(::number) ?: old?.ramSize
                    require((ramStart == null) == (ramSize == null)) { "PDSC 算法 RAMstart/RAMsize 必须成对提供" }
                    if (ramStart != null && ramSize != null) checkedRange(ramStart, ramSize)
                    algorithms[key] = PackAlgorithm(path, start, size, ramStart, ramSize, bool(a["default"], old?.isDefault ?: false), pname)
                }
            }
        }
        val merged = Inherited(vendor, processors, memories, algorithms)
        val descendants = node.children.filter { it.name in setOf("family", "subFamily", "device", "variant") }
        if (node.name == "device" && descendants.none { it.name == "variant" } || node.name == "variant") {
            val name = node.attrs[if (node.name == "variant") "Dvariant" else "Dname"]
                ?.takeIf { it.isNotBlank() } ?: error("PDSC 设备缺少名称")
            output += PackDevice(name, vendor, processors.values.toList(), memories.values.toList(), algorithms.values.toList())
        }
        descendants.forEach { collectDevices(it, merged, base, output) }
    }

    private fun number(text: String): Long {
        val raw = text.trim()
        val value = if (raw.startsWith("0x", true)) raw.substring(2).toLongOrNull(16) else raw.toLongOrNull()
        return requireNotNull(value?.takeIf { it in 0..0xFFFF_FFFFL }) { "PDSC 数值无效：$text" }
    }
    private fun checkedRange(start: Long, size: Long) {
        require(size > 0 && start + size <= 0x1_0000_0000L) { "PDSC 地址范围无效" }
    }
    private fun bool(text: String?, default: Boolean): Boolean = when (text) {
        null -> default; "true", "1" -> true; "false", "0" -> false
        else -> throw IllegalArgumentException("PDSC 布尔值无效：$text")
    }
    private fun resolvePath(base: String, path: String): String = checkedPath(if (base.isBlank()) path else "$base/$path")
    private fun checkedPath(path: String): String {
        require(path.isNotBlank() && path.length <= 1024 && !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path) {
            "Pack 路径无效：$path"
        }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Pack 路径越界：$path" }
        return path
    }

    private class Node(val name: String, val attrs: Map<String, String>) {
        val children = mutableListOf<Node>()
        val text = StringBuilder()
    }
    private fun parseXml(bytes: ByteArray, limits: Limits): Node {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val xml = decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require('\u0000' !in xml && !xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) {
            "PDSC 禁止 DTD 和外部实体，且仅接受 UTF-8 XML"
        }
        val encoding = Regex("""<\?xml[^>]*encoding\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)
        require(encoding == null || encoding.equals("utf-8", true) || encoding.equals("us-ascii", true)) { "PDSC 仅接受 UTF-8 XML" }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
        // The lexical UTF-8 rejection above remains enforced on Android parsers lacking a Xerces feature.
        for ((feature, enabled) in listOf(XMLConstants.FEATURE_SECURE_PROCESSING to true,
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false)) {
            runCatching { factory.setFeature(feature, enabled) }
        }
        var root: Node? = null
        val stack = ArrayDeque<Node>()
        var count = 0
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("PDSC 外部实体被禁止")
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                require(++count <= limits.xmlNodes && stack.size < limits.xmlDepth) { "PDSC XML 层级或节点过多" }
                require(attributes.length <= 128) { "PDSC XML 属性过多" }
                val attrs = (0 until attributes.length).associate { attributes.getQName(it) to attributes.getValue(it) }
                val node = Node(localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty(), attrs)
                if (stack.isEmpty()) { require(root == null); root = node } else stack.last().children += node
                stack.addLast(node)
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { stack.lastOrNull()?.text?.append(ch, start, length) }
            override fun endElement(uri: String?, localName: String?, qName: String?) { stack.removeLast() }
        }
        factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        return requireNotNull(root) { "PDSC XML 为空" }
    }
}
