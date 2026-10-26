package dev.corn.flashing.pack

import android.content.Context
import dev.corn.flashing.core.TargetInfo
import dev.corn.flashing.core.engine.FlashAlgorithm
import dev.corn.flashing.core.pack.*
import dev.corn.flashing.core.targets.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Files are local and content-addressed. Importing never selects or runs an algorithm. */
data class InstalledFlm(val hash: String, val name: String, val model: FlmImage, val origin: String)
data class AlgorithmCandidate(val title: String, val source: String, val bytes: ByteArray)
data class AlgorithmBinding(val deviceId: Int, val capacityBytes: Long, val hash: String)
data class AlgorithmSnapshot(val targets: TargetRegistry, val algorithms: List<FlashAlgorithm>)

class AlgorithmLibrary(context: Context) {
    private val directory = File(context.filesDir, "algorithms").apply { mkdirs() }
    private val index = File(directory, "index.json")
    private val _installed = MutableStateFlow<List<InstalledFlm>>(emptyList())
    val installed = _installed.asStateFlow()
    private val _bindings = MutableStateFlow<List<AlgorithmBinding>>(emptyList())
    val bindings = _bindings.asStateFlow()
    private val executionGate = ExperimentalAlgorithmGate()
    val experimentalEnabled = executionGate.enabled
    init { restore() }

    @Synchronized fun setExperimentalEnabled(enabled: Boolean) { executionGate.setEnabled(enabled) }

    fun candidates(name: String, bytes: ByteArray): List<AlgorithmCandidate> {
        if (name.endsWith(".pack", true)) {
            val archive = CmsisPackReader.read(bytes)
            val paths = archive.devices.flatMap { device -> device.algorithms.map { device.name to it.path } }
                .groupBy({ it.second }, { it.first })
            require(paths.isNotEmpty()) { "包中没有设备 Flash 算法" }
            return paths.map { (path, names) ->
                AlgorithmCandidate("${names.distinct().take(3).joinToString()} · $path",
                    "${archive.metadata.vendor}.${archive.metadata.name}@${archive.metadata.version}:$path", archive.algorithmBytes(path))
            }
        }
        require(name.endsWith(".flm", true)) { "请选择 .pack 或 .flm 算法文件" }
        return listOf(AlgorithmCandidate(name, name, bytes.copyOf()))
    }

    @Synchronized fun install(candidate: AlgorithmCandidate): InstalledFlm {
        val model = FlmParser.parse(candidate.bytes)
        val hash = digest(candidate.bytes)
        val file = File(directory, "$hash.flm")
        if (!file.exists()) {
            val temp = File(directory, "$hash.tmp")
            temp.writeBytes(candidate.bytes)
            check(temp.renameTo(file)) { "无法保存算法" }
        }
        val item = InstalledFlm(hash, model.flash.name, model, candidate.source)
        publish(_installed.value.filterNot { it.hash == hash } + item, _bindings.value)
        return item
    }

    fun compatibility(item: InstalledFlm, target: TargetInfo): String? = try {
        val base = requireNotNull(TargetCatalog.find(target.deviceId)) { "目标未登记识别规则，不能仅凭算法猜测芯片" }
        base.validate(target)
        require(item.model.flash.start == base.flashStart && item.model.flash.size == target.flashSizeBytes) {
            "算法 Flash 地址/容量与实际芯片不一致"
        }
        val expected = base.geometry.sectors(base.flashStart, target.flashSizeBytes)
        require(item.model.flash.geometry().sectors(base.flashStart, item.model.flash.size) == expected) { "算法扇区布局与芯片描述不一致" }
        // Constructing validates RAM/code/stack/buffer without touching USB or Flash.
        FlmFlashAlgorithm.validateForDescriptor(item.model, base, target)
        null
    } catch (error: Exception) { error.message ?: "算法不匹配" }

    @Synchronized fun bind(hash: String, target: TargetInfo) {
        executionGate.requireEnabled()
        val item = requireNotNull(_installed.value.find { it.hash == hash }) { "算法不存在" }
        require(compatibility(item, target) == null) { compatibility(item, target) ?: "算法不匹配" }
        publish(_installed.value, _bindings.value.filterNot { it.deviceId == target.deviceId || it.hash == hash } + AlgorithmBinding(target.deviceId, target.flashSizeBytes, hash))
    }
    @Synchronized fun useBuiltIn(deviceId: Int) {
        publish(_installed.value, _bindings.value.filterNot { it.deviceId == deviceId })
    }
    @Synchronized fun remove(hash: String) {
        publish(_installed.value.filterNot { it.hash == hash }, _bindings.value.filterNot { it.hash == hash })
        File(directory, "$hash.flm").delete()
    }

    @Synchronized fun snapshot(): AlgorithmSnapshot {
        if (!experimentalEnabled.value) return AlgorithmSnapshot(TargetCatalog.registry, emptyList())
        val drivers = mutableListOf<FlashAlgorithm>()
        val descriptors = TargetCatalog.descriptors.map { base ->
            val binding = _bindings.value.find { it.deviceId == base.deviceId } ?: return@map base
            val item = _installed.value.find { it.hash == binding.hash } ?: return@map base
            val target = TargetInfo(base.deviceId, 0, binding.capacityBytes, null, base.family)
            require(compatibility(item, target) == null) { "已绑定算法与芯片描述不兼容，请恢复内置算法" }
            val descriptor = base.copy(key = "pack-${base.deviceId.toString(16)}-${item.hash}",
                allowedCapacityKiB = setOf(binding.capacityBytes / 1024),
                algorithmId = "flm-${item.hash}", support = TargetSupport.FLASH_ALGORITHM)
            drivers += executionGate.guard(FlmFlashAlgorithm(item.model, descriptor))
            descriptor
        }
        return AlgorithmSnapshot(TargetRegistry(descriptors), drivers)
    }

    private fun restore() {
        if (!index.exists()) return
        runCatching {
            val root = JSONObject(index.readText())
            require(root.getInt("schemaVersion") == 1) { "算法索引格式不支持" }
            val items = root.getJSONArray("items")
            require(items.length() <= 128)
            val loaded = mutableListOf<InstalledFlm>()
            for (i in 0 until items.length()) {
                val row = items.getJSONObject(i)
                val hash = row.getString("hash")
                require(hash.matches(Regex("[0-9a-f]{64}")))
                val file = File(directory, "$hash.flm")
                require(file.length() in 1..FlmParser.MAX_FILE_BYTES.toLong())
                val bytes = file.readBytes()
                require(digest(bytes) == hash) { "已存算法哈希不匹配" }
                val model = FlmParser.parse(bytes)
                loaded += InstalledFlm(hash, model.flash.name, model, row.getString("origin"))
            }
            require(loaded.map { it.hash }.distinct().size == loaded.size)
            val bindings = root.getJSONArray("bindings")
            require(bindings.length() <= 128)
            val restoredBindings = (0 until bindings.length()).map { i -> bindings.getJSONObject(i).let {
                AlgorithmBinding(it.getInt("deviceId"), it.getLong("capacity"), it.getString("hash"))
            } }
            require(restoredBindings.map { it.deviceId }.distinct().size == restoredBindings.size)
            require(restoredBindings.map { it.hash }.distinct().size == restoredBindings.size)
            restoredBindings.forEach { b ->
                val item = requireNotNull(loaded.find { it.hash == b.hash })
                val base = requireNotNull(TargetCatalog.find(b.deviceId))
                require(compatibility(item, TargetInfo(b.deviceId, 0, b.capacityBytes, null, base.family)) == null)
            }
            _installed.value = loaded
            _bindings.value = restoredBindings
        }.onFailure {
            // Corrupt local resources never silently authorise a writer.
            _installed.value = emptyList(); _bindings.value = emptyList()
        }
    }
    private fun publish(nextItems: List<InstalledFlm>, nextBindings: List<AlgorithmBinding>) {
        require(nextItems.size <= 128) { "最多保存128个算法" }
        val items = JSONArray()
        nextItems.forEach { items.put(JSONObject().put("hash", it.hash).put("origin", it.origin)) }
        val bindings = JSONArray()
        nextBindings.forEach { bindings.put(JSONObject().put("deviceId", it.deviceId).put("capacity", it.capacityBytes).put("hash", it.hash)) }
        val temp = File(directory, "index.tmp")
        temp.writeText(JSONObject().put("schemaVersion", 1).put("items", items).put("bindings", bindings).toString())
        check(temp.renameTo(index)) { "算法配置保存失败" }
        _installed.value = nextItems
        _bindings.value = nextBindings
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
