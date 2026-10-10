package com.trae.info.server.crawler

import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import com.trae.info.server.proxy.ProxyPool
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

/**
 * 平台抓取适配器统一接口。
 * 合规约定：优先官方开放 API；无头浏览器渲染仅用于无 API 的平台；
 * 遵守各平台 robots 协议与服务条款，控制请求频率。
 */
interface PlatformAdapter {
    val platform: SourcePlatform

    /**
     * 抓取一个平台×类目单元。
     * @param allowBrowser 是否允许走云端浏览器冷启动/常驻通道。
     *   后台抓取进程传 true；用户请求实时聚合（/api/feed、AI 搜索）传 false，
     *   避免在请求线程触发昂贵的浏览器渲染。
     */
    suspend fun fetch(
        category: InfoCategory,
        limit: Int = 20,
        allowBrowser: Boolean = true
    ): List<ContentItem>
}

/**
 * 提供按平台区域自动选择直连/云端代理的 HTTP GET 能力。
 * 纯 HTTP 适配器无浏览器通道，[allowBrowser] 对其无影响。
 */
abstract class HttpAdapter(
    override val platform: SourcePlatform,
    protected val pool: ProxyPool
) : PlatformAdapter {

    /** 接口方法桥接：HTTP 适配器忽略 allowBrowser。 */
    override suspend fun fetch(
        category: InfoCategory,
        limit: Int,
        allowBrowser: Boolean
    ): List<ContentItem> = fetch(category, limit)

    /** 子类实现的纯 HTTP 抓取。 */
    protected abstract suspend fun fetch(category: InfoCategory, limit: Int): List<ContentItem>

    protected suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
        val client = pool.clientFor(platform.requiresProxy)
        return client.get(url) {
            headers.forEach { (k, v) -> header(k, v) }
        }.bodyAsText()
    }

    protected fun queryOf(category: InfoCategory): String =
        category.keywords.firstOrNull() ?: category.name

    /** 类目主检索词；取前两个关键词提升召回率 */
    protected fun searchQuery(category: InfoCategory): String =
        category.keywords.take(2).joinToString(" ").ifBlank { category.name }
}
