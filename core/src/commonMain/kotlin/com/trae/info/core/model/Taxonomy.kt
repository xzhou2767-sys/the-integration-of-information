package com.trae.info.core.model

import kotlinx.serialization.Serializable

/**
 * 信息域：实用性导向信息 与 Ne 类信息 两大顶层分区。
 */
@Serializable
enum class Domain(val displayName: String) {
    PRACTICAL("实用性信息"),
    NE("Ne 类信息")
}

@Serializable
enum class Region(val displayName: String) {
    DOMESTIC("国内"),
    OVERSEAS("海外")
}

/**
 * 指向性信息平台。[requiresProxy] = true 的平台仅由云端通过 3xui 代理访问，
 * 客户端永远直连云 API、自身不配置任何代理。
 */
@Serializable
enum class SourcePlatform(
    val id: String,
    val displayName: String,
    val region: Region,
    val requiresProxy: Boolean,
    val hasOfficialApi: Boolean
) {
    // ---- 国内平台 ----
    BILIBILI("bilibili", "哔哩哔哩", Region.DOMESTIC, requiresProxy = false, hasOfficialApi = true),
    ICUOOC("icourse163", "中国大学MOOC", Region.DOMESTIC, requiresProxy = false, hasOfficialApi = true),
    ZHIHU("zhihu", "知乎", Region.DOMESTIC, requiresProxy = false, hasOfficialApi = false),
    XIAOHONGSHU("xiaohongshu", "小红书", Region.DOMESTIC, requiresProxy = false, hasOfficialApi = false),
    DOUYIN("douyin", "抖音", Region.DOMESTIC, requiresProxy = false, hasOfficialApi = true),
    WECHAT("wechat", "微信搜一搜", Region.DOMESTIC, requiresProxy = false, hasOfficialApi = false),
    DOUBAN("douban", "豆瓣", Region.DOMESTIC, requiresProxy = false, hasOfficialApi = true),
    CNKI("cnki", "CNKI/万方", Region.DOMESTIC, requiresProxy = false, hasOfficialApi = false),
    NSSD("nssd", "国家哲学社科文献中心", Region.DOMESTIC, requiresProxy = false, hasOfficialApi = false),
    CAIXIN_PENGPAI("caixin", "财新/澎湃", Region.DOMESTIC, requiresProxy = false, hasOfficialApi = false),

    // ---- 海外平台 ----
    REDDIT("reddit", "Reddit", Region.OVERSEAS, requiresProxy = true, hasOfficialApi = true),
    YOUTUBE("youtube", "YouTube", Region.OVERSEAS, requiresProxy = true, hasOfficialApi = true),
    TWITTER("twitter", "X / Twitter", Region.OVERSEAS, requiresProxy = true, hasOfficialApi = true),
    QUORA("quora", "Quora", Region.OVERSEAS, requiresProxy = true, hasOfficialApi = false),
    GOOGLE_SCHOLAR("google-scholar", "Google Scholar", Region.OVERSEAS, requiresProxy = true, hasOfficialApi = false),
    WIKIPEDIA("wikipedia", "Wikipedia", Region.OVERSEAS, requiresProxy = true, hasOfficialApi = true),
    STACKEXCHANGE("stackexchange", "Stack Exchange", Region.OVERSEAS, requiresProxy = true, hasOfficialApi = true),
    ARXIV("arxiv", "arXiv", Region.OVERSEAS, requiresProxy = true, hasOfficialApi = true),
    HACKERNEWS("hackernews", "Hacker News", Region.OVERSEAS, requiresProxy = true, hasOfficialApi = true),
    JSTOR_ACM("jstor-acm", "JSTOR/ACM", Region.OVERSEAS, requiresProxy = true, hasOfficialApi = false),
    SUBSTACK("substack", "Substack", Region.OVERSEAS, requiresProxy = true, hasOfficialApi = false);

    companion object {
        fun byId(id: String): SourcePlatform? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 信息细分类目。客户端按此树渲染可勾选项，云端按订阅结果执行定向收集。
 */
@Serializable
data class InfoCategory(
    val id: String,
    val name: String,
    val domain: Domain,
    val group: String,
    val description: String,
    val keywords: List<String>,
    val sources: List<SourcePlatform>,
    val defaultEnabled: Boolean = true
)

/**
 * 全量信息分类体系（单一事实源：客户端订阅界面与云端采集调度共用）。
 */
object Taxonomy {

    val platforms: List<SourcePlatform> = SourcePlatform.entries

    val categories: List<InfoCategory> = buildList {
        // ================= 实用性导向信息 =================
        val eduSources = listOf(
            SourcePlatform.BILIBILI, SourcePlatform.ICUOOC, SourcePlatform.ZHIHU,
            SourcePlatform.YOUTUBE, SourcePlatform.QUORA,
            SourcePlatform.WIKIPEDIA, SourcePlatform.ARXIV, SourcePlatform.NSSD
        )
        val techSources = listOf(
            SourcePlatform.ZHIHU, SourcePlatform.BILIBILI, SourcePlatform.XIAOHONGSHU,
            SourcePlatform.REDDIT, SourcePlatform.YOUTUBE, SourcePlatform.TWITTER, SourcePlatform.QUORA,
            SourcePlatform.HACKERNEWS, SourcePlatform.ARXIV, SourcePlatform.STACKEXCHANGE
        )
        val academicSources = listOf(
            SourcePlatform.ARXIV, SourcePlatform.NSSD, SourcePlatform.CNKI,
            SourcePlatform.JSTOR_ACM, SourcePlatform.GOOGLE_SCHOLAR,
            SourcePlatform.WIKIPEDIA, SourcePlatform.STACKEXCHANGE
        )

        fun edu(id: String, subject: String, vararg keywords: String) = add(
            InfoCategory(
                id = id,
                name = "高等教育 · $subject",
                domain = Domain.PRACTICAL,
                group = "高等教育学科",
                description = "覆盖 $subject 方向的知识点梳理、学习资源（公开课/慕课）与研究进展",
                keywords = keywords.toList(),
                sources = eduSources
            )
        )
        edu("edu-math", "数学", "高等数学", "数学分析", "线性代数")
        edu("edu-physics", "物理学", "大学物理", "理论力学", "量子力学")
        edu("edu-chemistry", "化学", "有机化学", "物理化学", "分析化学")
        edu("edu-biology", "生命科学", "分子生物学", "遗传学", "神经科学")
        edu("edu-cs", "计算机科学", "数据结构", "操作系统", "机器学习")
        edu("edu-ee", "电子信息", "电路", "信号与系统", "通信原理")
        edu("edu-medicine", "医学药学", "临床医学", "药理学", "公共卫生")
        edu("edu-engineering", "工程技术", "机械工程", "土木工程", "材料工程")
        edu("edu-economics", "经济管理", "宏观经济学", "管理学", "金融学")
        edu("edu-humanities", "人文社科", "历史学", "文学", "法学")

        fun ai(id: String, name: String, desc: String, vararg keywords: String) = add(
            InfoCategory(id, "人工智能 · $name", Domain.PRACTICAL, "人工智能", desc,
                keywords.toList(), techSources)
        )
        ai("ai-news", "技术动态", "大模型与 AI 领域最新发布、技术动态追踪", "大模型发布", "AI 新闻")
        ai("ai-algorithm", "算法原理", "Transformer、扩散模型、强化学习等算法原理深度解析",
            "算法原理", "论文解读", "Transformer")
        ai("ai-cases", "应用案例", "AI 在各行业的落地应用案例与工程实践", "AI 应用", "行业落地")
        ai("ai-industry", "行业趋势", "人工智能产业格局、投融资与政策趋势分析", "AI 行业", "产业趋势")

        fun frontier(id: String, name: String, desc: String, vararg keywords: String) = add(
            InfoCategory(id, "前沿物理 · $name", Domain.PRACTICAL, "前沿物理研究", desc,
                keywords.toList(), techSources + academicSources)
        )
        frontier("phys-discovery", "最新发现", "凝聚态、高能天体等领域的最新物理发现",
            "物理发现", "Nature Physics")
        frontier("phys-theory", "理论研究", "量子引力、弦论、多体理论等理论进展", "理论物理", "arXiv")
        frontier("phys-experiment", "实验成果", "对撞机、探测器、量子计算实验等实验成果",
            "物理实验", "LHC", "量子计算实验")

        add(InfoCategory(
            "intl-news", "国际时事热点", Domain.PRACTICAL, "国际时事热点",
            "及时、准确的全球重要新闻事件梳理与分析",
            listOf("国际新闻", "全球事件", "时事分析"),
            listOf(SourcePlatform.REDDIT, SourcePlatform.TWITTER, SourcePlatform.YOUTUBE, SourcePlatform.QUORA,
                   SourcePlatform.ZHIHU, SourcePlatform.CAIXIN_PENGPAI, SourcePlatform.WECHAT,
                   SourcePlatform.HACKERNEWS, SourcePlatform.SUBSTACK)
        ))
        add(InfoCategory(
            "policy-employment", "国家就业与行业政策", Domain.PRACTICAL, "国家就业与行业政策",
            "国家就业政策、行业发展规划的专业解读与影响分析",
            listOf("就业政策", "行业规划", "政策解读"),
            listOf(SourcePlatform.ZHIHU, SourcePlatform.BILIBILI, SourcePlatform.XIAOHONGSHU,
                   SourcePlatform.REDDIT, SourcePlatform.CAIXIN_PENGPAI, SourcePlatform.WECHAT)
        ))

        // ================= Ne 类信息 =================
        val neGeneral = listOf(
            SourcePlatform.BILIBILI, SourcePlatform.ZHIHU, SourcePlatform.XIAOHONGSHU,
            SourcePlatform.DOUYIN, SourcePlatform.REDDIT, SourcePlatform.YOUTUBE, SourcePlatform.QUORA,
            SourcePlatform.HACKERNEWS
        )
        val neHumanitiesSources = neGeneral + listOf(SourcePlatform.DOUBAN, SourcePlatform.SUBSTACK, SourcePlatform.WIKIPEDIA)
        val neBeautySources = neGeneral + listOf(SourcePlatform.WIKIPEDIA, SourcePlatform.DOUBAN)
        fun ne(id: String, group: String, name: String, desc: String, vararg keywords: String) = add(
            InfoCategory(id, name, Domain.NE, group, desc, keywords.toList(), neGeneral)
        )
        fun neH(id: String, group: String, name: String, desc: String, vararg keywords: String) = add(
            InfoCategory(id, name, Domain.NE, group, desc, keywords.toList(), neHumanitiesSources)
        )
        fun neB(id: String, group: String, name: String, desc: String, vararg keywords: String) = add(
            InfoCategory(id, name, Domain.NE, group, desc, keywords.toList(), neBeautySources)
        )

        ne("ne-digital-news", "电子数码", "数码硬件与公司产品新闻",
            "各大科技公司产品发布、发布会与产品新闻速报", "新品发布", "发布会", "数码新闻")
        ne("ne-digital-review", "电子数码", "硬件评测",
            "手机、PC、显卡、外设等硬件深度评测与对比", "硬件评测", "跑分", "开箱")
        ne("ne-elec-explain", "电子信息解读", "电子信息专业解读",
            "芯片制程工艺、通信标准（5G/6G）、电子设备工作原理的专业解读",
            "芯片技术", "通信标准", "半导体")
        neH("ne-sociology", "人文思想", "社会学",
            "社会现象分析、社会学理论与文化研究", "社会学理论", "社会现象", "文化研究")
        neH("ne-philosophy", "人文思想", "哲学",
            "哲学流派、经典著作与思想理论导读", "哲学流派", "经典哲学", "思想史")

        neB("ne-beauty-math", "学科之美", "学科之美 · 数学",
            "展示数学证明的逻辑严密性与方法的巧妙性", "数学证明", "数学之美", "解题艺术")
        neB("ne-beauty-physics", "学科之美", "学科之美 · 物理",
            "呈现物理定律的简洁性与普适性", "物理定律", "对称性", "物理之美")
        neB("ne-beauty-chemistry", "学科之美", "学科之美 · 化学",
            "展示分子结构的美感与化学反应的奇妙", "分子结构", "化学反应", "化学之美")

        ne("ne-psy-jung", "心理学与类型学", "荣格心理学",
            "荣格分析心理学理论体系（原型、集体无意识、心理类型）与应用",
            "荣格心理学", "分析心理学", "原型")
        ne("ne-psy-socionics", "心理学与类型学", "Socionics 类型学",
            "Socionics 人格类型系统：IM 元素、类型间关系与模型 A",
            "Socionics", "人格类型", "IM elements")

        ne("ne-ty-astrology", "泛类型学", "占星学",
            "占星学理论体系、解释框架及扩展性内容（行星、宫位、相位）",
            "占星学", "星盘", "星座")
        ne("ne-ty-bazi", "泛类型学", "四柱八字",
            "四柱八字基本原理、十神体系、解读方法与应用案例",
            "四柱八字", "命理", "干支")
        ne("ne-ty-ziwei", "泛类型学", "紫微斗数",
            "紫微斗数理论基础、命盘排布与解释性内容",
            "紫微斗数", "命盘", "十二宫")
    }

    /** 客户端分组渲染使用：域 -> 分组 -> 类目 */
    fun grouped(): Map<Domain, Map<String, List<InfoCategory>>> =
        categories.groupBy { it.domain }.mapValues { (_, list) ->
            list.groupBy { it.group }
        }

    fun byId(id: String): InfoCategory? = categories.firstOrNull { it.id == id }
}
