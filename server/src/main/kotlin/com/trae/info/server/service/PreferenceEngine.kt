package com.trae.info.server.service

import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户信息偏好引擎：
 * 从用户在豆包 / Gemini / GPT 对话框获得的「信息获取倾向与习惯」总结文本中，
 * 推导正向/负向兴趣关键词、信息流展示模式，并对聚合内容做个性化排序。
 *
 * 纯规则实现（无额外 AI 调用）：
 * - 否定窗口：识别「减少/不喜欢/少看/避免/不要…」等表达，窗口内词汇归为负向关键词（屏蔽词），
 *   不再被误当正向兴趣加权；
 * - Unicode 脚本边界分词：拉丁/数字段用全局正则提取（保 gpt-4o / react18 / c++），
 *   英文匹配用词边界（`\b`）避免 java⊂javascript 误匹配；CJK 段按标点切片并做长词优先去重；
 * - 展示模式识别支持否定（「不喜欢深度长文」不投 detail），且 compact/detail 需领先 ≥2 票才生效。
 */
object PreferenceEngine {

    /** 解析结果：正向兴趣词、负向屏蔽词、展示模式。 */
    data class ParsedPref(
        val positiveKeywords: List<String>,
        val negativeKeywords: List<String>,
        val displayMode: String
    )

    /** 兴趣关键词提取时过滤的无意义泛词/停用词。 */
    private val STOPWORDS = setOf(
        "我们", "你们", "他们", "自己", "用户", "信息", "内容", "偏好", "习惯", "倾向",
        "获取", "关注", "喜欢", "希望", "需要", "主要", "以及", "例如", "包括", "领域",
        "方面", "类型", "平台", "来源", "方式", "时间", "频率", "建议", "总结", "分析",
        "特点", "特征", "优先", "优先级", "关键词", "中文", "输出", "结构化",
        "帖子", "新闻", "资讯", "社区", "媒体", "社交",
        "学习", "了解", "掌握", "日常", "每日", "每周",
        "时候", "进行", "相关", "一定", "一般", "较多", "较少", "强烈", "明显", "典型",
        // 否定窗口内高频但非屏蔽词本身的动词/量词
        "娱乐", "内容"
    )

    /** 展示模式：快讯/碎片/高效 → compact（密集卡片）。 */
    private val COMPACT_MARKERS = listOf(
        "快讯", "速览", "简报", "速递", "碎片", "高效", "标题", "快速浏览", "短内容",
        "短平快", "实时", "即时", "概览", "一览"
    )

    /** 展示模式：深度/长文/精读 → detail（展开摘要的大卡片）。 */
    private val DETAIL_MARKERS = listOf(
        "深度", "长文", "精读", "论文", "系统性", "系统学习", "详细", "全文", "研究报告",
        "书籍", "专著", "长篇", "硬核", "原理", "机制", "入门到精通", "体系化"
    )

    /** 媒介倾向词：命中越多，视频类平台权重越高（用于抓取配额分配）。 */
    private val VIDEO_TENDENCY_WORDS = listOf(
        "视频", "短视频", "弹幕", "直播", "b站", "bilibili", "抖音", "youtube"
    )

    /** 阅读倾向词：命中越多，文本类平台权重越高（用于抓取配额分配）。 */
    private val READING_TENDENCY_WORDS = listOf(
        "文章", "长文", "深度", "精读", "论文", "教程", "图文"
    )

    /** 否定引导词：其后到句末标点之间的内容视为「不感兴趣/屏蔽」。 */
    private val NEG_MARKERS = listOf(
        "减少", "不喜欢", "不想看", "不想", "不爱", "少看", "少说", "少推送", "避免",
        "不要", "别再", "别给", "别推", "拒绝", "屏蔽", "过滤", "不感兴趣", "无需",
        "不需要", "讨厌", "不想收到"
    )

    /** 拉丁/数字词（含 + # . ），全局匹配而非锚定行首，保 gpt-4o / react18 / c++ / c#。 */
    private val LATIN_TOKEN = Regex("[A-Za-z][A-Za-z0-9+#.]{1,}")

    /** 纯字母数字拉丁词（用于词边界匹配）。 */
    private val ALNUM_TOKEN = Regex("[a-z0-9]+")

    /** 否定窗口句末标点。 */
    private val WINDOW_END = Regex("[。！？!?；;\n\r，,]")

    private val EMPTY = ParsedPref(emptyList(), emptyList(), "default")

    /** 解析结果缓存：偏好总结文本不变则不重复切分（ContentService 每请求都会用到）。 */
    private val parseCache = ConcurrentHashMap<String, ParsedPref>()

    fun parse(summary: String): ParsedPref {
        if (summary.isBlank()) return EMPTY
        parseCache[summary]?.let { return it }
        val parsed = parseInternal(summary)
        parseCache[summary] = parsed
        if (parseCache.size > 64) parseCache.clear()
        return parsed
    }

    private fun parseInternal(summary: String): ParsedPref {
        // ---- 1. 否定窗口：抽取屏蔽词，并把窗口从正向文本中抠除 ----
        val negWindows = mutableListOf<IntRange>()
        val negTerms = LinkedHashSet<String>()
        for (marker in NEG_MARKERS) {
            var from = 0
            while (true) {
                val idx = summary.indexOf(marker, from)
                if (idx < 0) break
                val start = idx + marker.length
                val endMatch = WINDOW_END.find(summary, start)
                val end = endMatch?.range?.first ?: (start + 24).coerceAtMost(summary.length)
                negWindows.add(idx..end)
                val window = summary.substring(start, end)
                extractTerms(window, neg = true).forEach { negTerms.add(it) }
                from = end
            }
        }
        val positiveText = buildString {
            var last = 0
            for (w in negWindows.sortedBy { it.first }) {
                if (w.first > last) append(summary, last, w.first)
                last = maxOf(last, w.last + 1)
            }
            if (last < summary.length) append(summary, last, summary.length)
        }

        // ---- 2. 正向词：「」核心词 + 正文切分 ----
        val bracketed = Regex("「([^」]{2,24})」").findAll(positiveText)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val positiveTerms = LinkedHashSet<String>()
        bracketed.forEach { positiveTerms.add(it) }
        extractTerms(positiveText, neg = false).forEach { positiveTerms.add(it) }

        // 负向词若同时出现在正向，以负向为准（用户明确表达减少）
        positiveTerms.removeAll(negTerms)

        val positive = rankTerms(positiveTerms).take(24)
        val negative = rankTerms(negTerms).take(16)

        return ParsedPref(positive, negative, displayModeOfInternal(summary))
    }

    /** 从一段文本抽取候选词：拉丁全局正则 + CJK 标点切片。 */
    private fun extractTerms(text: String, neg: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        // 拉丁/数字词
        LATIN_TOKEN.findAll(text).forEach { m ->
            val t = m.value
            if (t.length in 3..24) out.add(t)
        }
        // CJK：按标点/空白/序号/拉丁片段边界切片
        val cjkRuns = text.split(
            "\n", "\r", "\t", " ", "，", "。", "；", "、", "：", ":", "（", "）", "(", ")",
            "【", "】", "[", "]", "\"", "“", "”", "‘", "’", "「", "」", "《", "》", "-", "—",
            "·", "/", "|", "!", "！", "?", "？", ".", "0", "1", "2", "3", "4", "5",
            "6", "7", "8", "9", "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
            "和", "与", "及", "或"
        )
        for (run in cjkRuns) {
            val t = run.trim()
            val isCjk = t.isNotEmpty() && t.all { it.code in 0x4E00..0x9FFF }
            if (isCjk && t.length in 2..(if (neg) 10 else 8) && t !in STOPWORDS) out.add(t)
        }
        return out.toList()
    }

    /**
     * 长词优先去重：短词被长词包含且长词频次不低于短词时，丢弃短词
     * （避免高频短噪声词「人工」与低频长词「人工智能」共存导致的误加权）。
     * 频次信息此处无法获得（窗口级抽取），故按「长词包含短词即抑制短词」处理，
     * 长度差 ≥1 且短词长度 ≥2。
     */
    private fun rankTerms(terms: Set<String>): List<String> {
        val list = terms.toList()
        val result = mutableListOf<String>()
        for (t in list.sortedByDescending { it.length }) {
            val suppressed = result.any { longer ->
                longer != t && longer.contains(t) && t.length >= 2
            }
            if (!suppressed) result.add(t)
        }
        return result
    }

    /**
     * 从总结文本推导展示模式（否定感知 + 差值阈值）。
     * @return "compact" / "detail" / "default"
     */
    fun displayModeOf(summary: String): String = parse(summary).displayMode

    private fun displayModeOfInternal(summary: String): String {
        val text = summary.lowercase()
        var compact = COMPACT_MARKERS.count { m -> occursAffirmative(text, m) }
        var detail = DETAIL_MARKERS.count { m -> occursAffirmative(text, m) }
        // 差值阈值 ≥2 才采纳，避免一两个词的偶然命中翻转模式
        return when {
            compact - detail >= 2 -> "compact"
            detail - compact >= 2 -> "detail"
            else -> "default"
        }
    }

    /** 关键词在文本中出现、但不在任何否定窗口内。 */
    private fun occursAffirmative(text: String, marker: String): Boolean {
        var from = 0
        while (true) {
            val idx = text.indexOf(marker, from)
            if (idx < 0) return false
            val inNeg = NEG_MARKERS.any { m ->
                val mi = text.lastIndexOf(m, idx)
                if (mi < 0) false
                else {
                    val end = WINDOW_END.find(text, mi + m.length)?.range?.first ?: text.length
                    idx in mi..end
                }
            }
            if (!inNeg) return true
            from = idx + marker.length
        }
    }

    /** 正向兴趣关键词（兼容旧调用，等价于 parse().positiveKeywords）。 */
    fun keywordsOf(summary: String, limit: Int = 20): List<String> =
        parse(summary).positiveKeywords.take(limit)

    /** 负向屏蔽关键词。 */
    fun negativeKeywordsOf(summary: String): List<String> = parse(summary).negativeKeywords

    /**
     * 平台抓取权重 w_p（供时间预算规划分配配额）：
     * - compact → 文本 1.3 / 图文 1.0 / 视频 0.8；
     *   detail  → 文本 1.3 / 图文 1.0 / 视频 1.1（长文阅读为主，视频适度保留）；
     *   default → 1.0；
     * - 再叠加媒介倾向词乘法（视频倾向 ×1.15/词封顶 1.5；阅读倾向 ×1.1/词封顶 1.4）。
     */
    fun platformWeight(displayMode: String, summary: String, platform: SourcePlatform): Double {
        val kind = BudgetPlanner.mediaKindOf(platform)
        val negative = parse(summary).negativeKeywords.map { it.lowercase() }
        val explicitlyAvoided = when (kind) {
            MediaKind.VIDEO -> negative.any { n -> VIDEO_TENDENCY_WORDS.any { w -> n.contains(w) || w.contains(n) } }
            MediaKind.TEXT -> negative.any { n -> READING_TENDENCY_WORDS.any { w -> n.contains(w) || w.contains(n) } }
            MediaKind.IMAGE -> negative.any { it.contains("图文") || it.contains("图片") || it.contains("小红书") }
        }
        if (explicitlyAvoided) return 0.1
        val base = when (displayMode) {
            "compact" -> when (kind) {
                MediaKind.TEXT -> 1.3
                MediaKind.IMAGE -> 1.0
                MediaKind.VIDEO -> 0.8
            }
            "detail" -> when (kind) {
                MediaKind.TEXT -> 1.3
                MediaKind.IMAGE -> 1.0
                MediaKind.VIDEO -> 1.1
            }
            else -> 1.0
        }
        val text = summary.lowercase()
        var weight = base
        if (kind == MediaKind.VIDEO) {
            val hits = VIDEO_TENDENCY_WORDS.count { text.contains(it) }
            if (hits > 0) weight *= (1.15 * hits).coerceAtMost(1.5)
        }
        if (kind == MediaKind.TEXT) {
            val hits = READING_TENDENCY_WORDS.count { text.contains(it) }
            if (hits > 0) weight *= (1.1 * hits).coerceAtMost(1.4)
        }
        return weight
    }

    /**
     * 类目与偏好的相关度（供抓取优先级与浏览器 top-N 类目圈定）：
     * 正向词命中类目名/分组/描述/关键词 +2，负向词命中 -3。
     */
    fun categoryRelevance(
        category: InfoCategory,
        positive: List<String>,
        negative: List<String>
    ): Int {
        val hay = (listOf(category.name, category.group, category.description) +
                category.keywords).joinToString(" ").lowercase()
        var score = 0
        for (kw in positive) if (containsToken(hay, kw)) score += 2
        for (kw in negative) if (containsToken(hay, kw)) score -= 3
        return score
    }

    /** 按偏好对信息流条目个性化排序（含负向降权；偏好同分按平台内质量分，再按时间）。 */
    fun rank(items: List<ContentItem>, pref: ParsedPref): List<ContentItem> {
        if (items.isEmpty()) return items
        if (pref.positiveKeywords.isEmpty() && pref.negativeKeywords.isEmpty()) return items
        return items.sortedWith(
            compareByDescending<ContentItem> {
                relevanceScore(it, pref.positiveKeywords, pref.negativeKeywords)
            }.thenByDescending { qualityScoreOf(it) }
            .thenByDescending { it.publishedAt }
        )
    }

    // ---- 平台内质量信号（完全公开排序机制的平台） ----

    private val hotTagRegex = Regex("hot=([0-9.]+)")
    private val wilsonRegex = Regex("Wilson：([0-9.]+)")
    private val doubanRatingRegex = Regex("评分：([0-9.]+)")

    /**
     * 平台内质量分（0.0-1.0）tie-breaker：仅用于偏好相关度同分时的次级排序，
     * 不改变「信息偏好优先」的总原则。
     * - Hacker News：官方 Hot 分 hot=(P-1)/(T+2)^1.8（tags 元数据），log1p 归一化到 0-1（300 分饱和）；
     * - Stack Exchange：Wilson 区间下界（天然 0-1，summary 元数据）；
     * - 豆瓣：评分 /10（summary 元数据）；
     * - 其余平台（含排序黑箱平台）：0.5 中性值，不产生偏置。
     */
    fun qualityScoreOf(item: ContentItem): Double {
        // Hacker News：hot= 分写入 tags 末位
        val hot = item.tags.lastOrNull()
            ?.let { hotTagRegex.find(it)?.groupValues?.get(1) }?.toDoubleOrNull()
        if (hot != null) return (Math.log1p(hot.coerceAtLeast(0.0)) / Math.log(301.0)).coerceIn(0.0, 1.0)
        // Stack Exchange：Wilson 下界写入 summary
        wilsonRegex.find(item.summary)?.groupValues?.get(1)?.toDoubleOrNull()?.let { w ->
            return w.coerceIn(0.0, 1.0)
        }
        // 豆瓣：评分 /10
        doubanRatingRegex.find(item.summary)?.groupValues?.get(1)?.toDoubleOrNull()?.let { r ->
            return (r / 10.0).coerceIn(0.0, 1.0)
        }
        return 0.5
    }

    /** 兼容旧签名：仅正向词。 */
    fun rank(items: List<ContentItem>, keywords: List<String>): List<ContentItem> =
        rank(items, ParsedPref(keywords, emptyList(), "default"))

    /**
     * 条目与偏好关键词的相关度打分：
     * 正向词标题 +3 / 标签 +2 / 摘要 +1 / 作者 +1；负向词同权重扣分（屏蔽词命中沉底）。
     * 英文纯字母数字词用词边界匹配，避免 java⊂javascript；其余用子串。
     */
    fun relevanceScore(
        item: ContentItem,
        positive: List<String>,
        negative: List<String> = emptyList()
    ): Int {
        var score = 0
        val title = item.title.lowercase()
        val tags = item.tags.joinToString(" ").lowercase()
        val summary = item.summary.lowercase()
        val author = item.author.lowercase()
        for (kw in positive) {
            if (containsToken(title, kw)) score += 3
            if (containsToken(tags, kw)) score += 2
            if (containsToken(summary, kw)) score += 1
            if (containsToken(author, kw)) score += 1
        }
        for (kw in negative) {
            if (containsToken(title, kw)) score -= 3
            if (containsToken(tags, kw)) score -= 2
            if (containsToken(summary, kw)) score -= 1
            if (containsToken(author, kw)) score -= 1
        }
        return score
    }

    /**  token 命中判断：纯字母数字用词边界，否则子串（大小写不敏感）。 */
    private fun containsToken(haystackLower: String, keyword: String): Boolean {
        val k = keyword.lowercase()
        if (k.isBlank()) return false
        return if (ALNUM_TOKEN.matches(k)) {
            Regex("\\b" + Regex.escape(k) + "\\b").containsMatchIn(haystackLower)
        } else {
            haystackLower.contains(k)
        }
    }
}
