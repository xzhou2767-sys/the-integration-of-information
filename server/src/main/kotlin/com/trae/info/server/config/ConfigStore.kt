package com.trae.info.server.config

import com.trae.info.core.model.Domain
import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import com.trae.info.core.model.Taxonomy
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 运行时可变配置存储。将云端管理界面修改的 AI Key、爬虫密钥、代理参数、
 * 自定义信息分类与收集目的持久化到 data/config.json，启动时载入。
 *
 * 环境变量 / hocon 作为兜底默认值；ConfigStore 中非空字段优先。
 */
class ConfigStore(private val dataDir: File) {

    private val log = LoggerFactory.getLogger(ConfigStore::class.java)
    private val file = File(dataDir, "config.json").also { it.parentFile?.mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var snap: Snapshot = load()

    /** 全量可变配置快照。 */
    @Serializable
    data class Snapshot(
        // ---- AI 平台 ----
        val doubaoKey: String = "",
        val doubaoModel: String = "doubao-pro-32k",
        val doubaoBaseUrl: String = "https://ark.cn-beijing.volces.com/api/v3",
        /** 视觉理解首选模型（留空则按性价比优先级自动尝试候选链，含 LAS 通道） */
        val doubaoVisionModel: String = "",
        val openaiKey: String = "",
        val openaiModel: String = "gpt-4o-mini",
        val openaiBaseUrl: String = "https://api.openai.com/v1",
        val geminiKey: String = "",
        val geminiModel: String = "gemini-2.5-flash",
        /** LAS 数据湖 API Key（视觉理解备用通道），为空则不启用 LAS */
        val lasApiKey: String = "",
        // ---- 抓取平台密钥 ----
        val youtubeApiKey: String = "",
        val xBearerToken: String = "",
        val serpApiKey: String = "",
        val companionToken: String = "trae-companion",
        // ---- 3xui 代理 ----
        val proxySubUrl: String = "",
        val xrayPath: String = "xray",
        val refreshIntervalMin: Long = 30L,
        // ---- 自定义信息分类（空列表时使用 Taxonomy 默认类目，当前 32 个）----
        val customCategories: List<InfoCategory> = emptyList(),
        // ---- AI 自定对象搜索的收集目的列表 ----
        val goals: List<String> = emptyList(),
        // ---- 用户信息获取偏好总结（由豆包/Gemini/GPT 生成后粘贴保存，用于信息流个性化排序/展示）----
        val preferenceSummary: String = "",
        val preferenceUpdatedAtEpochMs: Long? = null,
        // ---- 决策核心 V2 灰度开关（重写决策核心方案 §14：默认关闭，逐阶段开启）----
        /** feed 路径切换到新决策核心（统一准入 Decision + 去重 + 偏好排序评分解释）。 */
        val decisionCoreV2: Boolean = false,
        /** 抓取规划接入收益调度（基于来源观测的 expectedUseful/cost 优先级）。 */
        val sourceSchedulerV2: Boolean = false
    )

    fun get(): Snapshot = snap

    /** 非空覆盖，空值不覆盖（保留原值），便于增量更新。 */
    @Synchronized
    fun update(block: (Snapshot) -> Snapshot): Snapshot {
        snap = block(snap)
        runCatching {
            com.trae.info.server.util.AtomicFiles.writeTextAtomically(file, json.encodeToString(snap))
        }.onFailure { log.warn("配置持久化失败：{}", it.message) }
        return snap
    }

    // ---- 动态分类体系 ----

    /** 全量分类 = 自定义 + Taxonomy 默认；自定义优先（同 id 覆盖默认）。 */
    fun categories(): List<InfoCategory> {
        val customs = snap.customCategories
        if (customs.isEmpty()) return Taxonomy.categories
        val customIds = customs.map { it.id }.toSet()
        return customs + Taxonomy.categories.filter { it.id !in customIds }
    }

    fun categoryById(id: String): InfoCategory? = categories().firstOrNull { it.id == id }

    fun customCategories(): List<InfoCategory> = snap.customCategories

    fun goals(): List<String> = snap.goals

    // ---- 便捷的局部更新方法 ----

    fun updateCustomCategories(cats: List<InfoCategory>) = update { it.copy(customCategories = cats) }
    fun addCustomCategory(cat: InfoCategory) = update { it.copy(customCategories = it.customCategories + cat) }
    fun removeCustomCategory(id: String) = update { it.copy(customCategories = it.customCategories.filterNot { c -> c.id == id }) }
    fun updateGoals(goals: List<String>) = update { it.copy(goals = goals) }

    private fun load(): Snapshot = runCatching {
        if (file.exists()) json.decodeFromString<Snapshot>(file.readText()) else Snapshot()
    }.getOrElse {
        log.warn("配置文件加载失败，使用默认值：{}", it.message)
        com.trae.info.server.util.AtomicFiles.backupCorrupt(file)
        Snapshot()
    }
}
