package com.trae.info.server.service

import com.trae.info.core.model.ContentItem
import com.trae.info.server.ai.AiProviders
import com.trae.info.server.config.AppConfig
import com.trae.info.server.util.AtomicFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 外文条目概要自动翻译（翻译出片方向 P0，借鉴 VideoLingo 三步翻译的分级思想，
 * 信息流场景只跑第一步直译——成本导向）：
 * - feed 响应前同步填充已缓存的 [ContentItem.translatedTitle]（零延迟零成本）；
 * - 未命中缓存的条目 fire-and-forget 批量补翻（单次 LLM 调用译一批，下次请求生效）；
 * - 翻译结果持久化 data/translations.json（原子写，AtomicFiles 备份恢复），
 *   容量 [MAX_ENTRIES]，超出按写入时间淘汰最旧；
 * - 翻译失败静默降级：不影响 feed 主链路，仅记日志。
 */
class TranslationService(
    cfg: AppConfig,
    private val ai: AiProviders? = null,
    /** 可注入的翻译调用点（测试注入时 ai 可为空）：system prompt + user prompt → 模型输出文本。 */
    private val chat: suspend (String, String) -> String = { system, prompt ->
        requireNotNull(ai) { "ai 与 chat 必须提供其一" }.chat(system, prompt).content
    }
) {
    private val log = LoggerFactory.getLogger(TranslationService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(cfg.dataDir, "translations.json")
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    /** 翻译缓存：itemId -> 中文标题（跨会话持久，summaries.json 同款模式）。 */
    private val cache: ConcurrentHashMap<String, String> = ConcurrentHashMap(load())

    /** 补翻并发闸门：同类目并发 feed 触发多次补翻时收敛为串行批次。 */
    private val gate = Semaphore(1)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** in-flight 任务键（P0 修复：同一批条目在补翻进行中被并发 feed 重复排队 → 重复调用 AI 重复计费）。 */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 响应组装：同步填充已有翻译（无 LLM 调用，feed 延迟零增加）。 */
    fun decorate(items: List<ContentItem>): List<ContentItem> =
        items.map { item ->
            val translated = cache[item.id]
            if (translated != null && translated != item.title) item.copy(translatedTitle = translated)
            else item
        }

    /** 异步补翻缺失条目（fire-and-forget，下次 feed 请求生效）。 */
    fun translateMissingAsync(items: List<ContentItem>) {
        // in-flight 去重（活动任务合并）：add() 首次加入返回 true → 取得在途标记并保留；
        // 已在途返回 false → 排除，防止并发 feed 重复排队 AI 调用重复计费。
        val pending = items.asSequence()
            .filter { it.translatedTitle == null && cache[it.id] == null && isForeign(it.title) }
            .filter { inFlight.add(it.id) }
            .toList()
        if (pending.isEmpty()) return
        scope.launch {
            try {
                translateAll(pending)
            } catch (t: Throwable) {
                log.debug("外文标题补翻失败（不影响信息流）: {}", t.message)
            } finally {
                // 批次结束（无论成败）释放任务键：失败条目允许下轮重试
                pending.forEach { inFlight.remove(it.id) }
            }
        }
    }

    /** 单一闸门许可内自动分批翻完（超过 BATCH_SIZE 无需额外 feed 请求推动）；internal 供测试直调。 */
    internal suspend fun translateAll(pending: List<ContentItem>) {
        gate.withPermit { pending.chunked(BATCH_SIZE).forEach { translateBatch(it) } }
    }

    /** 单批 ≤20 条标题一次调用翻译（批量摊薄成本：约 0.0005 元/批）。 */
    private suspend fun translateBatch(items: List<ContentItem>) {
        // 锁内复查：等待闸门/分批排队期间可能已被并发批次写入缓存，跳过已完成条目
        val batch = items.filter { cache[it.id] == null }
        if (batch.isEmpty()) return
        val listing = batch.withIndex().joinToString("\n") { (i, item) -> "${i + 1}. ${item.title}" }
        val prompt = """
            把下列标题翻译成简体中文。要求：
            1. 保留专有名词与技术术语的通用译法；
            2. 只输出 JSON 字符串数组（与输入等长、顺序一致），不要任何解释。
            示例：["标题一", "标题二"]

            标题列表：
            $listing
        """.trimIndent()

        val resp = chat("你是专业的标题翻译。", prompt)
        val translations = runCatching { json.decodeFromString<List<String>>(resp) }.getOrNull()
        if (translations == null || translations.size != batch.size) {
            log.debug("翻译返回格式不符（{} 条期望 {} 条），本批跳过", translations?.size, batch.size)
            return
        }
        batch.forEachIndexed { i, item ->
            val zh = translations[i].trim()
            log.debug("译题[{}]: 原文={} 译文={}", i, item.title.take(50), zh.take(50))
            // 译文==原文（LLM 判定无需翻译）也写入缓存：作为负缓存防止每次 feed 重复调用
            if (zh.isNotBlank()) cache[item.id] = zh
        }
        persist()
        log.info("外文标题翻译完成：{} 条（缓存总量 {}）", batch.size, cache.size)
    }

    /** 中文判定：标题含 ≥6 个汉字，或汉字占非空字符 ≥10%，均视为中文（专有名词混杂的中文标题不误判）；
     *  仅汉字 <6 且占比 <10%（纯外文标题）才需要翻译。 */
    private fun isForeign(title: String): Boolean {
        if (title.isBlank()) return false
        val cjk = title.count { it.code in 0x4E00..0x9FFF }
        return cjk < 6 && cjk.toDouble() / title.length < 0.1
    }

    @Synchronized
    private fun persist() {
        // 容量控制：超量按条目插入顺序近似淘汰（ConcurrentHashMap 无序，改为全量重写时截断）
        val entries = if (cache.size > MAX_ENTRIES) {
            cache.entries.associate { it.key to it.value }.entries.take(MAX_ENTRIES)
                .associate { it.key to it.value }
        } else cache.toMap()
        runCatching { AtomicFiles.writeTextAtomically(file, json.encodeToString(serializer, entries)) }
            .onFailure { log.warn("翻译缓存落盘失败: {}", it.message) }
    }

    private fun load(): Map<String, String> = runCatching {
        val raw = AtomicFiles.readTextOrBackup(file) ?: return emptyMap()
        if (raw.isBlank()) emptyMap() else json.decodeFromString<Map<String, String>>(raw)
    }.getOrElse {
        log.warn("翻译缓存读取失败，从空开始: {}", it.message)
        emptyMap()
    }

    /** metrics：缓存条目数。 */
    fun cacheSize(): Int = cache.size

    companion object {
        private const val MAX_ENTRIES = 2000
        private const val BATCH_SIZE = 20
    }
}
