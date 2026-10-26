package dev.corn.flashing.storage

import android.content.Context
import dev.corn.flashing.core.*
import dev.corn.flashing.core.targets.TargetDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ReservedRegionSettings(val policies: Map<String, ReservedRegionPolicy> = emptyMap(), val error: String? = null)

/** Opt-in, persisted per device group AND capacity. Never silently round or reuse another chip's addresses. */
class ReservedRegionStore(context: Context) {
    private val prefs = context.getSharedPreferences("reserved-regions", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state = _state.asStateFlow()
    fun snapshot(): Map<String, ReservedRegionPolicy> {
        require(_state.value.error == null) { _state.value.error ?: "保留区配置无效" }
        return _state.value.policies.toMap()
    }
    fun policy(target: TargetInfo?) = target?.let { _state.value.policies[key(it)] } ?: ReservedRegionPolicy()

    @Synchronized fun update(target: TargetInfo, descriptor: TargetDescriptor, policy: ReservedRegionPolicy) {
        require(_state.value.error == null) { "保留区配置损坏，请先重置配置" }
        // Saved ranges are validated even when disabled; enabling cannot revive unchecked addresses.
        ReservedRegionPolicy.validateRanges(policy.ranges, target, descriptor)
        if (policy.enabled) require(policy.ranges.isNotEmpty()) { "请先添加至少一个保留区" }
        val next = _state.value.policies + (key(target) to policy)
        val root = JSONObject().put("schemaVersion", 1)
        val rows = JSONObject()
        next.forEach { (key, value) ->
            val ranges = JSONArray()
            value.ranges.forEach { ranges.put(JSONObject().put("start", it.start).put("end", it.endExclusive)) }
            rows.put(key, JSONObject().put("enabled", value.enabled).put("ranges", ranges))
        }
        root.put("policies", rows)
        check(prefs.edit().putString("config", root.toString()).commit()) { "保存保留区失败，配置未改变" }
        _state.value = ReservedRegionSettings(next)
    }
    @Synchronized fun reset() {
        check(prefs.edit().remove("config").commit()) { "无法重置保留区配置" }
        _state.value = ReservedRegionSettings()
    }
    private fun load(): ReservedRegionSettings {
        val raw = prefs.getString("config", null) ?: return ReservedRegionSettings()
        return try {
            require(raw.length <= 65536)
            val root = JSONObject(raw)
            require(root.getInt("schemaVersion") == 1)
            val rows = root.getJSONObject("policies")
            require(rows.length() <= 128)
            val policies = mutableMapOf<String, ReservedRegionPolicy>()
            rows.keys().forEach { key ->
                require(key.matches(Regex("[0-9a-f]{1,3}:[0-9]+")))
                val row = rows.getJSONObject(key)
                val ranges = row.getJSONArray("ranges")
                require(ranges.length() <= 32)
                policies[key] = ReservedRegionPolicy(row.getBoolean("enabled"), (0 until ranges.length()).map {
                    val r = ranges.getJSONObject(it)
                    AddressRange(r.getLong("start"), r.getLong("end"))
                })
            }
            policies.forEach { (key, policy) ->
                val parts = key.split(':')
                val descriptor = requireNotNull(dev.corn.flashing.core.targets.TargetCatalog.find(parts[0].toInt(16)))
                val target = TargetInfo(descriptor.deviceId, 0, parts[1].toLong(), null, descriptor.family)
                ReservedRegionPolicy.validateRanges(policy.ranges, target, descriptor)
                if (policy.enabled) require(policy.ranges.isNotEmpty())
            }
            ReservedRegionSettings(policies)
        } catch (error: Exception) {
            ReservedRegionSettings(error = "保留区配置读取失败，已阻止烧录，请检查或重置：${error.message}")
        }
    }
    companion object { fun key(target: TargetInfo) = "${target.deviceId.toString(16)}:${target.flashSizeBytes}" }
}
