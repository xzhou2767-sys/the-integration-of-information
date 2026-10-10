package com.trae.info.core.api

import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import com.trae.info.core.model.SubscriptionPrefs
import kotlinx.serialization.Serializable

@Serializable
data class CategoriesResponse(
    val categories: List<InfoCategory>,
    val platforms: List<SourcePlatform>
)

@Serializable
data class FeedResponse(
    val categoryId: String,
    val items: List<ContentItem>,
    /** 本次聚合是否经过云端代理（海外平台） */
    val proxied: Boolean,
    /** 是否已按用户信息偏好总结做个性化排序 */
    val personalized: Boolean = false,
    /** 个性化展示模式：default / compact（快讯密集） / detail（深度展开） */
    val displayMode: String = "default"
)

@Serializable
data class ProxyStatusResponse(
    val state: String,               // READY / REFRESHING / NO_XRAY / EMPTY / NO_CONFIG / ERROR
    val xrayAvailable: Boolean,
    val nodeCount: Int = 0,
    val activeNode: String? = null,
    val activeProtocol: String? = null,
    val activeLatencyMs: Long? = null,
    val lastRefreshEpochMs: Long? = null,
    val message: String = ""
)

@Serializable
data class AnalyzeRequest(
    /** 针对某一类目做领域问询/用户倾向分析；为空时做综合分析 */
    val categoryId: String? = null,
    /** 用户自定义补充文本，可空 */
    val text: String? = null,
    /** domain = 问询领域总结；tendency = 用户信息获取倾向分析 */
    val mode: String = "domain"
)

@Serializable
data class AnalyzeResponse(
    val provider: String,
    val content: String
)

@Serializable
data class ErrorResponse(val error: String)

/**
 * Agent 智能登录请求：客户端输入账号密码，云端 Agent 自动填充表单完成登录。
 * 凭据仅内存使用，不落盘、不写日志。
 */
@Serializable
data class AgentLoginRequest(val username: String, val password: String)

/**
 * Agent 智能登录结果。
 * [state]：QR_ONLY（页面仅二维码，提示扫码）｜NEEDS_MANUAL（需人工验证，切手动控制）｜
 *          LOGGED_IN（登录成功）｜FAILED（执行失败，见 message）。
 */
@Serializable
data class AgentLoginResponse(
    val state: String,
    val message: String,
    val pngBase64: String,
    val loggedIn: Boolean = false
)

@Serializable
data class OkResponse(val ok: Boolean = true, val message: String = "")

// ---- 用户信息获取偏好总结（用于信息流个性化）----

@Serializable
data class PreferenceDto(
    /** 用户从豆包/Gemini/GPT 对话框获得的信息获取倾向与习惯总结原文 */
    val summary: String = "",
    /** 展示模式：default / compact / detail（服务端依据总结推导） */
    val displayMode: String = "default",
    /** 从总结中提取的兴趣关键词（服务端推导） */
    val keywords: List<String> = emptyList(),
    /** 从「减少/不喜欢/少看…」否定表达中提取的屏蔽词（服务端推导，默认空以兼容旧客户端） */
    val negativeKeywords: List<String> = emptyList(),
    val updatedAtEpochMs: Long? = null
)

// ---- 通道 A：云端浏览器登录会话 ----

@Serializable
data class SessionInfo(
    val platform: String,
    val name: String,
    val loginRequired: Boolean,
    val loggedIn: Boolean
)

@Serializable
data class LoginScreenshotResponse(
    val pngBase64: String,
    val state: String
)

@Serializable
data class LoginCompleteResponse(val loggedIn: Boolean)

// ---- 通道 A 扩展：远程浏览器控制（虚拟键鼠，用于无法扫码的平台手动登录）----

@Serializable
data class RemoteClickRequest(val x: Double, val y: Double)

@Serializable
data class RemoteTypeRequest(val text: String)

@Serializable
data class RemoteKeyRequest(val key: String)

@Serializable
data class RemoteNavigateRequest(val url: String)

@Serializable
data class RemoteScrollRequest(val deltaX: Double = 0.0, val deltaY: Double = 0.0)

@Serializable
data class RemoteUrlResponse(val url: String)

// ---- 云端管理：AI / 爬虫 / 代理 配置 ----

@Serializable
data class AiConfigDto(
    val doubaoKey: String = "",
    val doubaoModel: String = "doubao-pro-32k",
    val doubaoBaseUrl: String = "https://ark.cn-beijing.volces.com/api/v3",
    val doubaoVisionModel: String = "",
    val openaiKey: String = "",
    val openaiModel: String = "gpt-4o-mini",
    val openaiBaseUrl: String = "https://api.openai.com/v1",
    val geminiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    /** LAS 数据湖 API Key（视觉理解备用通道），为空则不启用 LAS */
    val lasApiKey: String = ""
)

@Serializable
data class CrawlerConfigDto(
    val youtubeApiKey: String = "",
    val xBearerToken: String = "",
    val serpApiKey: String = "",
    val companionToken: String = "trae-companion"
)

@Serializable
data class ProxyConfigDto(
    val proxySubUrl: String = "",
    val xrayPath: String = "xray",
    val refreshIntervalMin: Long = 30L
)

// ---- 动态信息分类管理 ----

@Serializable
data class CategoryCreateRequest(
    val name: String,
    val group: String = "自定义",
    val description: String = "",
    val keywords: List<String> = emptyList(),
    val platformIds: List<String> = emptyList(),
    val domain: String = "PRACTICAL"
)

@Serializable
data class CategoriesManagementResponse(
    val defaults: List<InfoCategory>,
    val customs: List<InfoCategory>
)

// ---- AI 驱动自定对象搜索 ----

@Serializable
data class AiSearchRequest(
    val goal: String
)

@Serializable
data class AiSearchResponse(
    val keywords: List<String>,
    val reasoning: String,
    val items: List<ContentItem>,
    val proxied: Boolean
)

// ---- 后台抓取进程控制 ----

@Serializable
data class ScrapingControlRequest(
    /** 抓取循环间隔（秒），<=0 使用服务端默认（12 小时） */
    val intervalSec: Long = 0L,
    /** 单次全量抓取的预计总耗时（分钟）；服务端约束下限 5 分钟、不设上限，<=0 使用服务端默认（15 分钟） */
    val expectedMinutes: Int = 0,
    /** AI 总结级别：brief（模糊）/ default（默认）/ detailed（详细），非法值服务端归一为 default */
    val summaryMode: String = "default",
    /** 一键总结所有条目：true=本轮抓到的全部条目均生成 AI 总结；false=仅高相关条目（预算内） */
    val summarizeAll: Boolean = false
)

@Serializable
data class ScrapingStatusDto(
    val running: Boolean,
    val intervalSec: Long,
    val startedAtEpochMs: Long? = null,
    val stoppedAtEpochMs: Long? = null,
    val lastRunStartedAtEpochMs: Long? = null,
    val lastRunFinishedAtEpochMs: Long? = null,
    val nextAtEpochMs: Long? = null,
    val lastItemsCount: Int = 0,
    val lastFailedCategories: Int = 0,
    val totalRuns: Long = 0L,
    val lastError: String? = null,
    val message: String = "",
    // ---- 进度上报（百分比 + 处理数量/目标数量）----
    /** 当前阶段：IDLE / PLANNING（规划中）/ FETCHING（抓取中）/ SUMMARIZING（AI总结中）/ DONE（本轮完成） */
    val phase: String = "IDLE",
    /** 进度百分比 0–100（按已完成工作单元数计算） */
    val progressPercent: Int = 0,
    /** 已完成工作单元数（平台×领域） */
    val completedUnits: Int = 0,
    /** 总工作单元数（平台×领域） */
    val totalUnits: Int = 0,
    /** 已抓取条目数 */
    val fetchedItems: Int = 0,
    /** 目标条目数（按时间预算推算的计划抓取总量） */
    val targetItems: Int = 0,
    /** 已生成 AI 总结的条目数 */
    val summarizedItems: Int = 0,
    /** 本轮预计总耗时（分钟），0 表示未设置 */
    val expectedMinutes: Int = 0,
    /** 当前正在处理的任务描述，如 "bilibili × 人工智能" */
    val currentTask: String = "",
    /** 本轮 AI 总结级别：brief / default / detailed */
    val summaryMode: String = "default",
    /** 本轮是否一键总结所有条目 */
    val summarizeAll: Boolean = false
)

