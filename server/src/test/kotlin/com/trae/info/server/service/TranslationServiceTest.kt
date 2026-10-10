package com.trae.info.server.service

import com.trae.info.core.model.ContentItem
import com.trae.info.server.config.AppConfig
import com.trae.info.server.config.ConfigStore
import io.ktor.server.config.MapApplicationConfig
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TranslationService in-flight 去重（B04）回归测试：
 * 覆盖审核要求四场景——首次请求、并发重复、失败重试、超过批上限（BATCH_SIZE=20），另附锁内复查。
 * 修复背景：旧实现 `.filterNot { inFlight.add(it.id) }` 语义反转——首次条目被排除（补翻不启动），
 * 重复条目被保留（并发请求重复入队重复计费），与设计目标完全相反。
 */
class TranslationServiceTest {

    /** data/translations.json 相对模块工作目录；测试前清空保证各用例从空缓存开始。 */
    @BeforeTest fun cleanPersistedCache() {
        File("data/translations.json").delete()
    }

    /** 测试用 AppConfig（空 hocon + 临时 ConfigStore；dataDir 为相对 data/，由 @BeforeTest 清理隔离）。 */
    private fun testCfg() = AppConfig(MapApplicationConfig(), ConfigStore(File("build/tmp-test-config").apply { mkdirs() }))

    private fun foreignItem(prefix: String, i: Int) = ContentItem(
        id = "$prefix-$i",
        categoryId = "ai-news",
        source = "hackernews",
        title = "English headline number $i about AI models",
        url = "https://example.com/$prefix/$i"
    )

    /** 伪 chat：解析 prompt「标题列表：」之后的编号行，返回等长 JSON 字符串数组（模拟 LLM 契约）。
     *  注意不能全 prompt 数编号行——提示词前部「要求：1. … 2. …」也是编号行，会污染计数。 */
    private fun fakeChat(onCall: (Int) -> Unit = {}, failFirst: Boolean = false): suspend (String, String) -> String {
        val attempt = AtomicInteger(0)
        return { _, prompt ->
            // 单次递增；onCall 在 throw 前记录（含失败尝试，供失败场景观察第一轮已执行）
            val n = attempt.incrementAndGet()
            onCall(n)
            if (failFirst && n == 1) throw IllegalStateException("模拟 LLM 超时")
            val titles = prompt.substringAfter("标题列表：\n").trim().lines().filter { it.isNotBlank() }
            check(titles.isNotEmpty()) { "fakeChat 未解析到标题行，prompt 片段: ${prompt.takeLast(80)}" }
            titles.map { t -> "\"译文$t\"" }.joinToString(",", "[", "]")
        }
    }

    /** 等待异步补翻完成：轮询直至谓词成立（上限 10s）。 */
    private fun await(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
    }

    private fun translatedCount(service: TranslationService, prefix: String) =
        service.decorate((1..50).map { foreignItem(prefix, it) })
            .count { it.translatedTitle != null }

    // ---- 场景 1：首次请求应启动补翻并写入缓存 ----
    @Test fun firstRequestTranslatesAndCaches() {
        val items = (1..5).map { foreignItem("first", it) }
        var calls = 0
        val service = TranslationService(testCfg(), chat = fakeChat(onCall = { calls = it }))
        service.translateMissingAsync(items)
        await { translatedCount(service, "first") == 5 }
        assertEquals(5, translatedCount(service, "first"), "首次补翻应把 5 条全部写入缓存（修复前 filterNot 反转导致 0 条入队）")
        assertEquals(1, calls, "5 条一批应只调用一次模型")
    }

    // ---- 场景 2：并发重复请求只触发一次翻译批次 ----
    @Test fun concurrentDuplicatesTriggerSingleTranslation() {
        val items = (1..8).map { foreignItem("dup", it) }
        var calls = 0
        val service = TranslationService(testCfg(), chat = fakeChat(onCall = { calls = it }))
        val barrier = java.util.concurrent.CyclicBarrier(4)
        val threads = (1..4).map {
            Thread {
                barrier.await()
                service.translateMissingAsync(items)
            }.apply { start() }
        }
        threads.forEach { it.join() }
        await { translatedCount(service, "dup") == 8 }
        assertEquals(8, translatedCount(service, "dup"), "4 个并发 feed 的同一批条目应全部有译文")
        assertEquals(1, calls, "并发重复请求应合并为单次 LLM 批调用（修复前重复条目反而重复入队）")
    }

    // ---- 场景 3：失败重试——首轮失败后在途标记必须释放，下轮可重试成功 ----
    @Test fun failureReleasesInFlightAndRetrySucceeds() {
        val items = (1..3).map { foreignItem("retry", it) }
        var calls = 0
        val service = TranslationService(testCfg(), chat = fakeChat({ calls = it }, failFirst = true))
        service.translateMissingAsync(items) // 第一轮：失败
        await { calls == 1 }
        Thread.sleep(300) // 给 finally 释放 in-flight 标记留时间
        assertEquals(0, translatedCount(service, "retry"), "第一轮失败不应有译文")
        service.translateMissingAsync(items) // 第二轮：应可重试（若 in-flight 未释放，pending 为空不会重试）
        await { calls == 2 && translatedCount(service, "retry") == 3 }
        assertEquals(3, translatedCount(service, "retry"), "失败释放后同批条目应可重试成功")
        assertEquals(2, calls, "共两次模型调用（1 失败 + 1 成功）")
    }

    // ---- 场景 4：超过 BATCH_SIZE(20) 自动分批翻完，无需额外 feed 推动 ----
    @Test fun overBatchSizeAutoChunks() {
        val items = (1..45).map { foreignItem("chunk", it) }
        val batchSizes = mutableListOf<Int>()
        val lock = Any()
        val service = TranslationService(testCfg(), chat = { _, prompt ->
            val titles = prompt.substringAfter("标题列表：\n").trim().lines().filter { it.isNotBlank() }
            synchronized(lock) { batchSizes.add(titles.size) }
            titles.map { n -> "\"译文$n\"" }.joinToString(",", "[", "]")
        })
        service.translateMissingAsync(items)
        await { translatedCount(service, "chunk") == 45 }
        assertEquals(45, translatedCount(service, "chunk"), "45 条应全部翻译完成")
        synchronized(lock) { assertEquals(listOf(20, 20, 5), batchSizes, "45 条应自动分为 20+20+5 三批（单一闸门许可内串行）") }
    }

    // ---- 场景 5（附加）：锁内复查——闸门等待期间已缓存的条目被跳过 ----
    @Test fun lockRecheckSkipsAlreadyCached() {
        val items = (1..3).map { foreignItem("recheck", it) }
        var calls = 0
        val service = TranslationService(testCfg(), chat = { _, prompt ->
            calls++
            val titles = prompt.substringAfter("标题列表：\n").trim().lines().filter { it.isNotBlank() }
            titles.map { n -> "\"译文$n\"" }.joinToString(",", "[", "]")
        })
        kotlinx.coroutines.runBlocking { service.translateAll(items) }
        assertEquals(3, translatedCount(service, "recheck"), "同步首轮翻完 3 条")
        assertEquals(1, calls)
        // 同实例同批重入：锁内复查应全部跳过，不产生第二次模型调用
        kotlinx.coroutines.runBlocking { service.translateAll(items) }
        assertEquals(1, calls, "同批重入时已缓存条目不应再调用模型（锁内复查生效）")
    }
}
