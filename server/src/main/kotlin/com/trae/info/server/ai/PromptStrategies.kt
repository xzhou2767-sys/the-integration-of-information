package com.trae.info.server.ai

/**
 * 提示词策略：围绕「信息问询领域」与「用户信息获取倾向」做系统性总结分析。
 */
object PromptStrategies {

    private const val SYSTEM_PROMPT = """
        你是 Trae 跨平台信息整合系统的分析引擎。你的任务：
        基于系统从国内与海外多个信息平台采集到的内容，对指定领域进行
        信息问询领域总结 与 用户信息获取倾向分析。
        要求：客观、结构化、标注不确定性；不编造来源；中文输出。
    """

    /**
     * @param mode "domain" 问询领域总结；"tendency" 用户信息获取倾向分析
     * @return (systemPrompt, userPrompt)
     */
    fun build(mode: String, categoryName: String?, extraText: String?): Pair<String, String> {
        val target = categoryName ?: "用户订阅的全部领域"
        val extra = extraText?.takeIf { it.isNotBlank() }?.let { "\n用户补充说明：$it" }.orEmpty()

        val user = when (mode) {
            "tendency" -> """
                请针对「$target」完成用户信息获取倾向分析：
                1. 该领域用户最典型的问询主题分布（列出 Top 5 问询方向）；
                2. 内容形态偏好：深度解读 / 资讯速览 / 学习资源链接 / 短视频 的倾向特征；
                3. 信息源偏好：社区讨论、公开课、论文、新闻各占的权重判断；
                4. 基于以上倾向给出内容运营与采集侧重建议。
                请以结构化要点输出。$extra
            """.trimIndent()

            else -> """
                请针对「$target」完成问询领域系统性总结：
                1. 当前该领域用户最关心的核心问题与问询热点；
                2. 高价值信息源类型（公开课 / 慕课 / 论文 / 社区讨论 / 新闻资讯）；
                3. 近期值得关注的进展、事件或研究方向；
                4. 面向不同深度需求（入门 / 进阶 / 专业）的信息获取路径建议。
                请以结构化要点输出。$extra
            """.trimIndent()
        }
        return SYSTEM_PROMPT.trimIndent() to user
    }

    /**
     * AI 驱动自定对象搜索：理解用户收集目的 → 提取检索关键词与检索策略。
     * 用于在多个信息平台（知乎/B站/Reddit/YouTube 等）执行定向检索。
     */
    fun buildKeywordExtraction(goal: String): Pair<String, String> {
        val system = """
            你是 Trae 跨平台信息整合系统的搜索策略引擎。
            用户会输入一个信息收集目的，你需要：
            1. 理解意图，提取 3-6 个在多平台检索时最有效的关键词（中英文混合，覆盖核心概念）；
            2. 用一句话说明检索策略。
            严格按以下格式输出，不要额外解释：
            关键词：词1, 词2, 词3
            策略：一句话策略说明
        """.trimIndent()
        val user = "信息收集目的：$goal"
        return system to user
    }

    /**
     * 信息流条目中文总结（供后台抓取进程的 AI 摘要阶段使用）。
     * @param mode 总结级别：brief（模糊·≤25字速览）/ default（默认·≤40字精要）/ detailed（详细·80-120字要点）
     */
    fun buildSummarize(title: String, excerpt: String, mode: String = "default"): Pair<String, String> {
        val system = when (mode) {
            "brief" -> "你是信息流摘要助手。用中文一句话（不超过25字）高度概括该信息最核心的结论，" +
                "直接输出正文，不要任何前缀、引号或解释。"
            "detailed" -> "你是信息流摘要助手。用中文2-3句话（80-120字）详细概括该信息：" +
                "先点明主题背景，再列出1-2个关键信息点，最后说明其价值或影响。" +
                "直接输出正文，不要任何前缀、引号或解释。"
            else -> "你是信息流摘要助手。用中文一句话（不超过40字）概括该信息的核心内容，" +
                "直接输出正文，不要任何前缀、引号或解释。"
        }
        // 详细级别给模型更多原文上下文
        val excerptLimit = if (mode == "detailed") 600 else 300
        val user = "${title.trim()}\n${excerpt.trim().take(excerptLimit)}"
        return system to user
    }
}
