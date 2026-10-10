package com.trae.info.server.browser

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.ElementHandle
import com.microsoft.playwright.Page
import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import java.net.URI

/**
 * 平台浏览器刮板契约（通道 A：云端真实浏览器）。
 * 签名/风控参数由站点自身 JS 在真实浏览器中生成，无需逆向。
 * 选择器均为 best-effort 多候选取值，站点改版时只需更新此处。
 */
interface BrowserScraper {
    val platform: SourcePlatform
    val loginRequired: Boolean
    val locale: String get() = "zh-CN"
    val loginUrl: String
    /** 搜索结果页首屏卡片选择器，用于条件等待替代盲等；null 表示回退固定延时。 */
    val readySelector: String? get() = null
    fun searchUrl(encodedKeyword: String): String
    fun loggedIn(ctx: BrowserContext, page: Page): Boolean
    fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem>

    fun absolutize(href: String?, base: String): String? = href?.let {
        runCatching { URI(base).resolve(it).toString() }.getOrNull()
    }

    fun ElementHandle.firstText(vararg selectors: String): String? = selectors
        .asSequence()
        .mapNotNull { querySelector(it)?.textContent()?.trim()?.takeIf { t -> t.isNotBlank() } }
        .firstOrNull()

    fun buildItem(
        category: InfoCategory,
        url: String,
        title: String,
        summary: String = "",
        author: String = "",
        tags: List<String> = listOf("浏览器采集")
    ): ContentItem = ContentItem(
        id = "br-${platform.id}-${url.hashCode().toUInt().toString(16)}",
        categoryId = category.id,
        source = platform.id,
        title = title.take(160),
        summary = summary.take(300),
        url = url,
        author = author,
        tags = tags
    )
}

object BrowserScrapers {

    val all: Map<SourcePlatform, BrowserScraper> = listOf(
        MoocScraper, BilibiliScraper, ZhihuScraper, XiaohongshuScraper,
        DouyinScraper, RedditScraper, QuoraScraper,
        // 0.5.3 新增 · 无公开 API 平台浏览器通道（直连不可用时的模拟行为）
        WechatScraper, DoubanScraper, GoogleScholarScraper, CaixinPengpaiScraper,
        JstorAcmScraper, CnkiScraper, SubstackScraper
    ).associateBy { it.platform }

    fun byId(platformId: String): BrowserScraper? =
        all[SourcePlatform.byId(platformId)]
}

// ================= 中国大学MOOC（无需登录，作为 RPC 的稳健替代） =================

object MoocScraper : BrowserScraper {
    override val platform = SourcePlatform.ICUOOC
    override val loginRequired = false
    override val loginUrl = "https://www.icourse163.org/"
    override val readySelector = ".moco-course-card, div[class*='course-card']"

    override fun searchUrl(encodedKeyword: String) =
        "https://www.icourse163.org/search.htm?search=$encodedKeyword"

    override fun loggedIn(ctx: BrowserContext, page: Page) =
        ctx.cookies().any { it.name == "NTESSTUDYSI" }

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val base = "https://www.icourse163.org/"
        val cards = page.querySelectorAll(
            ".moco-course-card, .u-cls__item, div[class*='course-card'], div[class*='CourseCard']"
        )
        val items = cards.mapNotNull { el ->
            val link = el.querySelector("a[href*='/course/']") ?: return@mapNotNull null
            val url = absolutize(link.getAttribute("href"), base) ?: return@mapNotNull null
            val title = el.firstText(".u-cls-cname", "h3", "[class*='title']")
                ?: link.textContent()?.trim()
                ?: return@mapNotNull null
            val school = el.firstText(".u-cls-pschool", "[class*='school']", "[class*='School']")
                .orEmpty()
            buildItem(category, url, title, summary = school, author = school,
                tags = listOf("慕课", "课程"))
        }
        return items.distinctBy { it.url }.take(limit)
    }
}

// ================= 知乎（登录态网页搜索） =================

object ZhihuScraper : BrowserScraper {
    override val platform = SourcePlatform.ZHIHU
    override val loginRequired = true
    override val loginUrl = "https://www.zhihu.com/signin?next=%2F"

    override fun searchUrl(encodedKeyword: String) =
        "https://www.zhihu.com/search?type=content&q=$encodedKeyword"

    override fun loggedIn(ctx: BrowserContext, page: Page) =
        ctx.cookies().any { it.name == "z_c0" }

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val cards = page.querySelectorAll(
            "div.SearchResult-Card, div.Card.List-item, div.List-item, div[class*='SearchResult']"
        )
        val items = cards.mapNotNull { el ->
            val titleLink = el.querySelector(
                "h2 a, a[data-za-detail-view-element_name='Title'], a[href*='/question/']"
            ) ?: return@mapNotNull null
            val href = titleLink.getAttribute("href") ?: return@mapNotNull null
            if (!href.contains("/question/")) return@mapNotNull null
            val url = absolutize(href, "https://www.zhihu.com/") ?: return@mapNotNull null
            val title = titleLink.textContent()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val excerpt = el.firstText(
                "span.RichText", ".CopyrightRichText-richText", "[class*='ContentItem-excerpt']", "p"
            ).orEmpty()
            buildItem(category, url, title, summary = excerpt, tags = listOf("知乎"))
        }
        return items.distinctBy { it.url }.take(limit)
    }
}

// ================= 小红书（登录态，高风控：慢频、单会话） =================

object XiaohongshuScraper : BrowserScraper {
    override val platform = SourcePlatform.XIAOHONGSHU
    override val loginRequired = true
    override val loginUrl = "https://www.xiaohongshu.com/explore"

    override fun searchUrl(encodedKeyword: String) =
        "https://www.xiaohongshu.com/search_result?keyword=$encodedKeyword"

    override fun loggedIn(ctx: BrowserContext, page: Page) =
        ctx.cookies().any { it.name == "web_session" }

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val cards = page.querySelectorAll("section.note-item, div[class*='note-item']")
        val items = cards.mapNotNull { el ->
            val link = el.querySelector(
                "a.cover, a[href*='/explore/'], a[href*='/search_result/'], a[href*='/discovery/item/']"
            ) ?: return@mapNotNull null
            val url = absolutize(link.getAttribute("href"), "https://www.xiaohongshu.com/")
                ?: return@mapNotNull null
            val title = el.firstText(".title", ".footer .title", "a.title span", "[class*='title']")
                ?: return@mapNotNull null
            val author = el.firstText(".name", ".author .name", "[class*='author'] [class*='name']")
                .orEmpty()
            buildItem(category, url, title, author = author, tags = listOf("小红书", "笔记"))
        }
        return items.distinctBy { it.url }.take(limit)
    }
}

// ================= 抖音（登录态，高风控） =================

object DouyinScraper : BrowserScraper {
    override val platform = SourcePlatform.DOUYIN
    override val loginRequired = true
    override val loginUrl = "https://www.douyin.com/"

    override fun searchUrl(encodedKeyword: String) =
        "https://www.douyin.com/search/$encodedKeyword"

    override fun loggedIn(ctx: BrowserContext, page: Page) =
        ctx.cookies().any { it.name == "sessionid" || it.name == "sessionid_ss" }

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val cards = page.querySelectorAll(
            "li[data-e2e='search-result-item'], ul[data-e2e='scroll-list'] > li, div[class*='search-result'] li"
        )
        val items = cards.mapNotNull { el ->
            val link = el.querySelector("a[href*='/video/']") ?: return@mapNotNull null
            val url = absolutize(link.getAttribute("href"), "https://www.douyin.com/")
                ?: return@mapNotNull null
            val title = el.firstText(
                "a[href*='/video/'] p", "[data-e2e='search-result-title']", "[class*='title']"
            ) ?: link.textContent()?.trim()?.takeIf { it.isNotBlank() }
              ?: return@mapNotNull null
            val author = el.firstText(
                "[data-e2e='search-result-author'] span", "[class*='author']"
            ).orEmpty()
            buildItem(category, url, title, author = author, tags = listOf("抖音", "视频"))
        }
        return items.distinctBy { it.url }.take(limit)
    }
}

// ================= Quora（登录态，走云端代理，建议英文关键词） =================

object QuoraScraper : BrowserScraper {
    override val platform = SourcePlatform.QUORA
    override val loginRequired = true
    override val locale = "en-US"
    override val loginUrl = "https://www.quora.com/"
    override val readySelector = "a[href*='/answer/']"

    override fun searchUrl(encodedKeyword: String) =
        "https://www.quora.com/search?q=$encodedKeyword"

    override fun loggedIn(ctx: BrowserContext, page: Page) =
        page.querySelector("a[href*='/profile/']") != null

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val links = page.querySelectorAll("a[href*='/answer/']")
        val items = links.mapNotNull { a ->
            val href = a.getAttribute("href") ?: return@mapNotNull null
            val url = absolutize(href, "https://www.quora.com/") ?: return@mapNotNull null
            val title = a.textContent()?.trim()?.takeIf { it.length > 8 }
                // 向上取卡片正文作为标题
                ?: a.evaluate("el => el.closest('div[class*=\"q-box\"]')?.innerText?.slice(0, 160)")
                    ?.toString()
                ?: return@mapNotNull null
            buildItem(category, url, title, tags = listOf("Quora", "问答"))
        }
        // 同一问题的多个 answer 链接去重到问题页
        return items.distinctBy {
            it.url.substringBefore("/answer/")
        }.take(limit)
    }
}

// ================= 哔哩哔哩（扫码登录态网页搜索；API 被 412 风控时的浏览器通道） =================

object BilibiliScraper : BrowserScraper {
    override val platform = SourcePlatform.BILIBILI
    override val loginRequired = true
    override val loginUrl = "https://passport.bilibili.com/login"

    override fun searchUrl(encodedKeyword: String) =
        "https://search.bilibili.com/all?keyword=$encodedKeyword"

    override fun loggedIn(ctx: BrowserContext, page: Page) =
        ctx.cookies().any { it.name == "SESSDATA" }

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val cards = page.querySelectorAll(
            "div.bili-video-card, div.video-list-item, div[class*='video-list-item']"
        )
        val items = cards.mapNotNull { el ->
            val link = el.querySelector("a[href*='/video/BV'], a[href*='/video/av']")
                ?: return@mapNotNull null
            val href = link.getAttribute("href") ?: return@mapNotNull null
            val url = absolutize(href, "https://www.bilibili.com/") ?: return@mapNotNull null
            val titleEl = el.querySelector(
                ".bili-video-card__info--tit, h3[title], .bili-video-card__info--tit a"
            )
            val title = (titleEl?.getAttribute("title") ?: titleEl?.textContent())
                ?.trim()?.takeIf { it.isNotBlank() }
                ?: link.getAttribute("title")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val author = el.firstText(
                ".bili-video-card__info--author", "span.up-name", "[class*='author']"
            ).orEmpty()
            buildItem(category, url, title, author = author, tags = listOf("哔哩哔哩", "视频"))
        }
        return items.distinctBy { it.url }.take(limit)
    }
}

// ================= Reddit（登录态网页搜索，走云端代理；公开 .json 被拦时的浏览器通道） =================

object RedditScraper : BrowserScraper {
    override val platform = SourcePlatform.REDDIT
    override val loginRequired = true
    override val locale = "en-US"
    override val loginUrl = "https://www.reddit.com/login/"
    override val readySelector = "shreddit-post, article[data-testid='post-container']"

    override fun searchUrl(encodedKeyword: String) =
        "https://www.reddit.com/search/?q=$encodedKeyword&type=link"

    override fun loggedIn(ctx: BrowserContext, page: Page) =
        ctx.cookies().any { it.name == "token_v2" || it.name == "reddit_session" }

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val posts = page.querySelectorAll(
            "shreddit-post, article[data-testid='post-container'], div[data-testid='post-container']"
        )
        val items = posts.mapNotNull { el ->
            // shreddit-post 自带 content-href；回退到 /comments/ 链接
            val href = el.getAttribute("content-href")
                ?: el.querySelector("a[href*='/comments/']")?.getAttribute("href")
                ?: return@mapNotNull null
            val url = absolutize(href, "https://www.reddit.com/") ?: return@mapNotNull null
            if (!url.contains("/comments/")) return@mapNotNull null
            val title = el.firstText(
                "a[slot='title']", "h3", "a[id$='post-title-text']", "div[id$='title']"
            ) ?: return@mapNotNull null
            val author = el.firstText(
                "[data-testid='post_author_name']", "a[href^='/user/']"
            ).orEmpty()
            buildItem(category, url, title, author = author, tags = listOf("Reddit", "讨论"))
        }
        return items.distinctBy { it.url }.take(limit)
    }
}

// ===================== 0.5.3 新增 · 12 平台浏览器刮板（直连不可用时的模拟行为） =====================

// ================= 微信搜一搜（搜狗微信搜索，无登录即可用） =================

object WechatScraper : BrowserScraper {
    override val platform = SourcePlatform.WECHAT
    override val loginRequired = false
    override val loginUrl = "https://weixin.sogou.com/"
    override val readySelector = ".news-box, div[class*='news-list']"

    override fun searchUrl(encodedKeyword: String) =
        "https://weixin.sogou.com/weixin?type=2&query=$encodedKeyword"

    override fun loggedIn(ctx: BrowserContext, page: Page) = true

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val base = "https://weixin.sogou.com"
        val cards = page.querySelectorAll(".news-box li, div[class*='news-list'] .news-list li, .results > div")
        val items = cards.mapNotNull { el ->
            val link = el.querySelector("a[d-share], a[href*='/mp.weixin.qq.com/'], a[href*='/link?url=']")
                ?: el.querySelector("h3 a, h4 a")
                ?: return@mapNotNull null
            val href = link.getAttribute("href") ?: return@mapNotNull null
            val url = absolutize(href, base) ?: return@mapNotNull null
            val title = el.firstText("h3 a", "h4 a", ".txt-box h3", "a[d-share]")
                ?: link.textContent()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val account = el.firstText(".account", ".s-p .account", "[class*='account']")
                .orEmpty()
            val excerpt = el.firstText(".txt-info", ".s-p p", "p.txt-info").orEmpty()
            buildItem(category, url, title, summary = excerpt, author = account,
                tags = listOf("微信搜一搜", "公众号"))
        }
        return items.distinctBy { it.url }.take(limit)
    }
}

// ================= 豆瓣（搜索无需登录，登录态可看更多） =================

object DoubanScraper : BrowserScraper {
    override val platform = SourcePlatform.DOUBAN
    override val loginRequired = false
    override val loginUrl = "https://www.douban.com/"
    override val readySelector = ".result-list .result, div.result, .search-result"

    override fun searchUrl(encodedKeyword: String) =
        "https://www.douban.com/search?cat=1005&q=$encodedKeyword"

    override fun loggedIn(ctx: BrowserContext, page: Page) =
        ctx.cookies().any { it.name == "dbcl2" }

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val base = "https://www.douban.com"
        val cards = page.querySelectorAll(".result-list .result, div.result, .search-result .item")
        val items = cards.mapNotNull { el ->
            val link = el.querySelector("h3 a, .title a, a[href*='douban.com']")
                ?: return@mapNotNull null
            val href = link.getAttribute("href") ?: return@mapNotNull null
            val url = absolutize(href, base) ?: return@mapNotNull null
            val title = el.firstText("h3 a", ".title a", "h3")
                ?: link.textContent()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val rating = el.firstText(".rating_nums", "[class*='rating']").orEmpty()
            val excerpt = el.firstText(".abstract", ".content p", "p").orEmpty()
            val summary = buildString {
                if (rating.isNotBlank()) append("评分：$rating")
                if (excerpt.isNotBlank()) append(if (isEmpty()) excerpt else "｜$excerpt")
            }
            buildItem(category, url, title, summary = summary, tags = listOf("豆瓣"))
        }
        return items.distinctBy { it.url }.take(limit)
    }
}

// ================= Google Scholar（无登录可基础检索，走云端代理） =================

object GoogleScholarScraper : BrowserScraper {
    override val platform = SourcePlatform.GOOGLE_SCHOLAR
    override val loginRequired = false
    override val locale = "en-US"
    override val loginUrl = "https://scholar.google.com/"
    override val readySelector = ".gs_r.gs_or_ggsm, .gs_ri, div.gs_r"

    override fun searchUrl(encodedKeyword: String) =
        "https://scholar.google.com/scholar?q=$encodedKeyword&hl=en"

    override fun loggedIn(ctx: BrowserContext, page: Page) = true

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val base = "https://scholar.google.com"
        val cards = page.querySelectorAll(".gs_r.gs_or_ggsm, div.gs_r, .gs_ri")
        val items = cards.mapNotNull { el ->
            val link = el.querySelector(".gs_rt a, h3 a, a[href*='/scholar']")
                ?: return@mapNotNull null
            val href = link.getAttribute("href") ?: return@mapNotNull null
            val url = absolutize(href, base) ?: return@mapNotNull null
            val title = el.firstText(".gs_rt a", "h3 a", ".gs_rt")
                ?: link.textContent()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val excerpt = el.firstText(".gs_rs", ".gs_a + div", "div.gs_rs").orEmpty()
            val meta = el.firstText(".gs_a", ".gs_acl").orEmpty()
            buildItem(category, url, title, summary = excerpt, author = meta,
                tags = listOf("Google Scholar", "论文"))
        }
        return items.distinctBy { it.url }.take(limit)
    }
}

// ================= 财新 / 澎湃新闻（深度报道，无登录可检索） =================

object CaixinPengpaiScraper : BrowserScraper {
    override val platform = SourcePlatform.CAIXIN_PENGPAI
    override val loginRequired = false
    override val loginUrl = "https://www.caixin.com/"
    override val readySelector = ".searchResult, .result-list, div.search_result"

    override fun searchUrl(encodedKeyword: String) =
        "https://search.caixin.com/search/zhkeyword=$encodedKeyword"

    override fun loggedIn(ctx: BrowserContext, page: Page) = true

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val base = "https://www.caixin.com"
        val cards = page.querySelectorAll(".searchResult, .result-list .result, div.search_result dl, .search-list li")
        val items = cards.mapNotNull { el ->
            val link = el.querySelector("a[href*='caixin.com'], a[href*='thepaper.cn'], h3 a, dt a")
                ?: return@mapNotNull null
            val href = link.getAttribute("href") ?: return@mapNotNull null
            val url = absolutize(href, base) ?: return@mapNotNull null
            val title = el.firstText("h3 a", "dt a", ".title a", "a")
                ?: link.textContent()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val excerpt = el.firstText("dd p", ".content p", ".summary", "p").orEmpty()
            val date = el.firstText(".date", ".time", "[class*='date']").orEmpty()
            buildItem(category, url, title, summary = excerpt, author = date,
                tags = listOf("财新/澎湃", "深度报道"))
        }
        return items.distinctBy { it.url }.take(limit)
    }
}

// ================= JSTOR / ACM Digital Library（学术文献，无登录可基础检索） =================

object JstorAcmScraper : BrowserScraper {
    override val platform = SourcePlatform.JSTOR_ACM
    override val loginRequired = false
    override val locale = "en-US"
    override val loginUrl = "https://www.jstor.org/"
    override val readySelector = ".search-results-list, .result, div[class*='result-item']"

    override fun searchUrl(encodedKeyword: String) =
        "https://www.jstor.org/action/doBasicSearch?Query=$encodedKeyword"

    override fun loggedIn(ctx: BrowserContext, page: Page) = true

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val base = "https://www.jstor.org"
        val cards = page.querySelectorAll(".search-results-list .item, .result, div[class*='result-item'], li[class*='result']")
        val items = cards.mapNotNull { el ->
            val link = el.querySelector("a[href*='/stable/'], a[href*='/doi/'], .title a, h3 a")
                ?: return@mapNotNull null
            val href = link.getAttribute("href") ?: return@mapNotNull null
            val url = absolutize(href, base) ?: return@mapNotNull null
            val title = el.firstText(".title a", "h3 a", ".title", "a")
                ?: link.textContent()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val excerpt = el.firstText(".abstract", ".snippet", "p").orEmpty()
            val meta = el.firstText(".meta", ".source", "[class*='author']").orEmpty()
            buildItem(category, url, title, summary = excerpt, author = meta,
                tags = listOf("JSTOR/ACM", "期刊"))
        }
        return items.distinctBy { it.url }.take(limit)
    }
}

// ================= CNKI / 万方（中文学术文献，无登录可基础检索） =================

object CnkiScraper : BrowserScraper {
    override val platform = SourcePlatform.CNKI
    override val loginRequired = false
    override val loginUrl = "https://kns.cnki.net/"
    override val readySelector = ".result-table-list, table.result-table, .result-list"

    override fun searchUrl(encodedKeyword: String) =
        "https://kns.cnki.net/kns8/defaultresult/index?dbcode=CFLS&kw=$encodedKeyword"

    override fun loggedIn(ctx: BrowserContext, page: Page) = true

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val base = "https://kns.cnki.net"
        // CNKI 用表格渲染结果，每行 tr 是一条文献
        val rows = page.querySelectorAll(".result-table-list tr, table.result-table tbody tr, .result-list .item")
        val items = rows.mapNotNull { el ->
            val link = el.querySelector("a[href*='/detail/'], a.fundressify, td.name a, td a")
                ?: return@mapNotNull null
            val href = link.getAttribute("href") ?: return@mapNotNull null
            val url = absolutize(href, base) ?: return@mapNotNull null
            val title = el.firstText("td.name a", "a.fundressify", "td a", "a")
                ?: link.textContent()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val author = el.firstText("td.author", "[class*='author']").orEmpty()
            val source = el.firstText("td.source", "[class*='source']").orEmpty()
            val date = el.firstText("td.date", "[class*='date']").orEmpty()
            val summary = buildString {
                if (author.isNotBlank()) append("作者：$author")
                if (source.isNotBlank()) append(if (isEmpty()) "来源：$source" else "｜来源：$source")
                if (date.isNotBlank()) append(if (isEmpty()) "日期：$date" else "｜日期：$date")
            }
            buildItem(category, url, title, summary = summary, tags = listOf("CNKI/万方", "文献"))
        }
        return items.distinctBy { it.url }.take(limit)
    }
}

// ================= Substack（发现页检索，无登录） =================

object SubstackScraper : BrowserScraper {
    override val platform = SourcePlatform.SUBSTACK
    override val loginRequired = false
    override val locale = "en-US"
    override val loginUrl = "https://substack.com/"
    override val readySelector = ".post-card, div[class*='post-card'], .post-preview"

    override fun searchUrl(encodedKeyword: String) =
        "https://substack.com/search/posts?query=$encodedKeyword"

    override fun loggedIn(ctx: BrowserContext, page: Page) = true

    override fun extract(page: Page, category: InfoCategory, limit: Int): List<ContentItem> {
        val base = "https://substack.com"
        val cards = page.querySelectorAll(".post-card, div[class*='post-card'], .post-preview, article")
        val items = cards.mapNotNull { el ->
            val link = el.querySelector("a[href*='.substack.com/p/'], a[href*='/p/'], h3 a, .post-card-title a")
                ?: return@mapNotNull null
            val href = link.getAttribute("href") ?: return@mapNotNull null
            val url = absolutize(href, base) ?: return@mapNotNull null
            if (!url.contains("/p/")) return@mapNotNull null
            val title = el.firstText("h3 a", ".post-card-title a", "h3", ".title a")
                ?: link.textContent()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val excerpt = el.firstText(".post-card-description", ".description", "p").orEmpty()
            val author = el.firstText(".author-name", "[class*='author']", ".byline").orEmpty()
            buildItem(category, url, title, summary = excerpt, author = author,
                tags = listOf("Substack", "专栏"))
        }
        return items.distinctBy { it.url }.take(limit)
    }
}
