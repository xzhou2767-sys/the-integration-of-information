package com.trae.info.server.store

import com.trae.info.core.model.SourcePlatform
import com.trae.info.server.decision.SourceObservation
import com.trae.info.server.decision.SourceScheduler
import com.trae.info.server.util.AtomicFiles
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 决策数据存储（方案 §3 S/store/DecisionStore.kt）：
 * 持久「来源观测」与策略版本（首期 JSON 原子写，复用项目既有存储惯例；
 * SQLite 迁移为后续独立项）。
 * - 无论灰度开关状态，抓取单元完成后都写入观测（影子收集，供 V2 开启时使用）；
 * - 单写入器（@Synchronized），跨轮累积。
 */
class DecisionStore(dataDir: File) {

    private val log = LoggerFactory.getLogger(DecisionStore::class.java)
    private val file = File(dataDir, "decision-store.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = MapSerializer(String.serializer(), SourceObservation.serializer())

    private val observations = ConcurrentHashMap<String, SourceObservation>()
    @Volatile
    private var dirty = false

    init { runCatching { load() } }

    private fun load() {
        val raw = AtomicFiles.readTextOrBackup(file) ?: return
        if (raw.isBlank()) return
        val loaded = runCatching {
            json.decodeFromString<Map<String, SourceObservation>>(raw)
        }.getOrElse {
            log.warn("DecisionStore 观测解析失败，从空开始: {}", it.message)
            emptyMap()
        }
        loaded.forEach { (k, v) -> observations[k] = v }
        log.info("DecisionStore 载入 {} 条来源观测", observations.size)
    }

    fun observationOf(platform: SourcePlatform): SourceObservation? = observations[platform.id]

    /** 记录一次单元观测（方案 §5.2：新增唯一数才是奖励，非原始抓到数量）。 */
    @Synchronized
    fun recordUnit(
        platform: SourcePlatform,
        success: Boolean,
        uniqueNew: Int,
        elapsedMs: Long,
        paidCost: Double = 0.0,
        transferBytes: Long = 0
    ) {
        val current = observations[platform.id]
        observations[platform.id] = SourceScheduler.observe(
            current, platform.id, success, uniqueNew, elapsedMs, paidCost, transferBytes
        )
        dirty = true
    }

    /** 标记探索调度命中（lastExploredAt 用于轮换，非固定抽同一批）。 */
    @Synchronized
    fun markExplored(platform: SourcePlatform) {
        observations[platform.id]?.let {
            observations[platform.id] = it.copy(lastExploredAtEpochMs = System.currentTimeMillis())
            dirty = true
        }
    }

    /** 落盘（原子写；每轮结束后调用一次）。 */
    @Synchronized
    fun flush() {
        if (!dirty) return
        runCatching {
            AtomicFiles.writeTextAtomically(file, json.encodeToString(serializer, observations.toMap()))
            dirty = false
        }.onFailure { log.warn("DecisionStore 落盘失败: {}", it.message) }
    }

    /** metrics：观测条数与调试快照。 */
    fun size(): Int = observations.size
    fun snapshot(): Map<String, SourceObservation> = observations.toMap()
}