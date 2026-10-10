package com.trae.info.server.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.WaitUntilState
import com.microsoft.playwright.options.Proxy as PwProxy
import com.trae.info.core.model.ContentItem
import com.trae.info.core.model.InfoCategory
import com.trae.info.server.config.AppConfig
import com.trae.info.server.proxy.ProxyPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** 待人工完成的登录会话（扫码/验证码），页面保持打开供轮询截图。 */
class LoginHandle(
    val platformId: String,
    private val browser: Browser,
    private val context: BrowserContext,
    private val page: Page
) {
    @Volatile
    var lastUsedAt: Long = System.currentTimeMillis()
        private set

    private fun touch() { lastUsedAt = System.currentTimeMillis() }

    fun screenshot(): ByteArray = page.screenshot().also { touch() }
    /** 二维码入口检测（轮询截图时同步返回，客户端可在延迟弹窗出现后自动浮现扫码提示）。 */
    fun detectQr(): Boolean =
        runCatching { AgentLoginEngine.detectQr(page) }.getOrDefault(false).also { touch() }
    fun isLoggedIn(scraper: BrowserScraper): Boolean =
        runCatching { scraper.loggedIn(context, page) }.getOrDefault(false).also { touch() }

    /** 登录确认后持久化 storageState */
    fun persist(statePath: java.io.File) {
        statePath.writeText(context.storageState())
    }

    // ---- 远程控制（虚拟键鼠）----
    fun click(x: Double, y: Double) = runCatching { page.mouse().click(x, y); touch() }.isSuccess
    fun type(text: String) = runCatching { page.keyboard().type(text); touch() }.isSuccess
    fun pressKey(key: String) = runCatching { page.keyboard().press(key); touch() }.isSuccess
    fun navigate(url: String) = runCatching { page.navigate(url); touch() }.isSuccess
    fun scroll(deltaX: Double, deltaY: Double) =
        runCatching { page.mouse().wheel(deltaX, deltaY); touch() }.isSuccess
    fun currentUrl(): String = runCatching { page.url() }.getOrDefault("")

    fun close() = runCatching {
        context.close()
        browser.close()
    }
}

/**
 * 云端浏览器自动化服务（通道 A）。
 *
 * - 真实 Chromium 驱动，站点 JS 自行生成签名；
 * - 海外平台经 ProxyPool 选中的 3xui SOCKS 节点出口；
 * - 抓取用常驻浏览器池（按是否走代理各 1 个实例，单元级仅新建 Context），
 *   并发 [FETCH_CONCURRENCY]=2、同平台串行（反爬）；浏览器每 ~100 单元/30 分钟重启控内存；
 * - 首次登录由人工扫码/过验证：REST 下发登录页截图，完成后保存 storageState；
 * - 登录会话 10 分钟空闲自动回收，防止 Chromium 进程泄漏 OOM；
 * - 不自动破解验证码，风控触发即记录并降级。
 */
class BrowserFetchService(
    private val cfg: AppConfig,
    private val pool: ProxyPool
) {
    private val log = LoggerFactory.getLogger(BrowserFetchService::class.java)

    @Volatile
    private var playwright: Playwright? = null

    /** 常驻抓取浏览器（key = requiresProxy）。 */
    private class Resident(val browser: Browser, @Volatile var launchedAt: Long, @Volatile var unitCount: Int)
    private val residents = ConcurrentHashMap<Boolean, Resident>()
    private val residentLock = Mutex()

    /** 浏览器抓取总并发上限（3.7G 内存：2 个 Chromium 为安全上限）。 */
    private val fetchSem = Semaphore(FETCH_CONCURRENCY)
    /** 同平台串行，降低风控概率；不同平台可并行。 */
    private val platformLocks = ConcurrentHashMap<String, Mutex>()

    private val pendingLogins = ConcurrentHashMap<String, LoginHandle>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // 定期回收空闲登录会话，防止放弃扫码导致 Chromium 永久驻留
        scope.launch {
            while (isActive) {
                delay(180_000) // 每 3 分钟扫描
                runCatching { cleanupIdleLogins() }
            }
        }
    }

    private fun cleanupIdleLogins() {
        val now = System.currentTimeMillis()
        val stale = pendingLogins.entries.filter { now - it.value.lastUsedAt > LOGIN_TTL_MS }
        stale.forEach { (id, handle) ->
            if (pendingLogins.remove(id, handle)) {
                log.info("回收空闲超时的登录会话：{}", id)
                handle.close()
            }
        }
    }

    private fun pw(): Playwright =
        playwright ?: synchronized(this) {
            playwright ?: Playwright.create().also { playwright = it }
        }

    /** 检测 Chromium 是否可用（需在服务器执行 `playwright install chromium` 或安装系统 Chromium） */
    suspend fun chromiumAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            launchBrowser(headless = true, requiresProxy = false).use { browser ->
                browser.newContext().use { it.newPage().navigate("about:blank") }
            }
            true
        }.getOrElse {
            log.warn("Chromium 不可用：{}。请在服务器安装：java -cp <deps> com.microsoft.playwright.CLI install chromium", it.message)
            false
        }
    }

    private fun launchBrowser(headless: Boolean, requiresProxy: Boolean): Browser {
        val opts = BrowserType.LaunchOptions()
            .setHeadless(headless)
            .setArgs(
                listOf(
                    "--disable-blink-features=AutomationControlled",
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                    "--disable-infobars"
                )
            )
        if (requiresProxy) {
            pool.activeSocksPort?.let { port ->
                opts.setProxy(PwProxy("socks5://127.0.0.1:$port"))
            }
        }
        return pw().chromium().launch(opts)
    }

    private fun newContext(browser: Browser, scraper: BrowserScraper): BrowserContext {
        val opts = Browser.NewContextOptions()
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
            .setViewportSize(1366, 900)
            .setLocale(scraper.locale)
            .setTimezoneId("Asia/Shanghai")
        val state = SessionStore.statePath(cfg.dataDir, scraper.platform.id)
        if (state.exists()) opts.setStorageState(state.readText())
        return browser.newContext(opts)
    }

    /** 获取常驻浏览器；过期/断连自动重启。调用需持有 residentLock。 */
    private fun obtainResident(requiresProxy: Boolean): Browser {
        val now = System.currentTimeMillis()
        val existing = residents[requiresProxy]
        val stale = existing == null ||
                existing.unitCount >= RESIDENT_MAX_UNITS ||
                now - existing.launchedAt > RESIDENT_MAX_AGE_MS ||
                !existing.browser.isConnected
        if (!stale) {
            existing!!.unitCount++
            return existing.browser
        }
        existing?.let { runCatching { it.browser.close() } }
        val browser = launchBrowser(headless = true, requiresProxy = requiresProxy)
        residents[requiresProxy] = Resident(browser, now, 1)
        return browser
    }

    private fun invalidateResident(requiresProxy: Boolean) {
        residents.remove(requiresProxy)?.let {
            runCatching { it.browser.close() }
        }
    }

    fun hasSession(platformId: String): Boolean =
        SessionStore.hasSession(cfg.dataDir, platformId)

    /**
     * 登录态浏览器抓取；无会话或 Chromium 缺失时返回空列表（交由降级链）。
     * 常驻浏览器 + 并发 2 + 同平台串行 + 单元硬超时 90s。
     */
    suspend fun fetch(scraper: BrowserScraper, category: InfoCategory, limit: Int): List<ContentItem> =
        fetchCatching(scraper, category, limit).getOrDefault(emptyList())

    /**
     * 同 [fetch]，但以 [Result] 返回：超时/浏览器异常为 failure（供熔断器计数），
     * 无会话/合法空结果为 success(emptyList())（不计失败、不熔断）。
     */
    suspend fun fetchCatching(
        scraper: BrowserScraper,
        category: InfoCategory,
        limit: Int
    ): Result<List<ContentItem>> = withContext(Dispatchers.IO) {
        if (scraper.loginRequired && !hasSession(scraper.platform.id)) {
            return@withContext Result.success(emptyList())
        }
        val platformLock = platformLocks.getOrPut(scraper.platform.id) { Mutex() }
        platformLock.withLock {
            fetchSem.withPermit {
                runCatching {
                    withTimeout(UNIT_TIMEOUT_MS) {
                        fetchOnce(scraper, category, limit)
                    }
                }.onFailure {
                    log.warn("浏览器抓取 {} 失败/超时（风控/选择器失效/超时）：{}",
                        scraper.platform.id, it.message)
                    // 不在单个平台超时时关闭共享常驻浏览器：其他平台可能正在使用
                    // 同一实例上的Context。真正断连会在obtainResident/newContext时重建。
                }
            }
        }
    }

    /** 单次抓取：复用常驻浏览器，仅新建/关闭 Context。newContext 失败（浏览器断连）重建一次重试。 */
    private suspend fun fetchOnce(scraper: BrowserScraper, category: InfoCategory, limit: Int): List<ContentItem> {
        val requiresProxy = scraper.platform.requiresProxy
        val browser = residentLock.withLock { obtainResident(requiresProxy) }
        val ctx = runCatching { newContext(browser, scraper) }.getOrElse {
            // 常驻浏览器可能已断连：失效重建后重试一次
            log.info("{} 常驻浏览器上下文创建失败，重建浏览器重试：{}", scraper.platform.id, it.message)
            val rebuilt = residentLock.withLock {
                invalidateResident(requiresProxy)
                obtainResident(requiresProxy)
            }
            newContext(rebuilt, scraper)
        }
        ctx.use { c ->
            val page = c.newPage()
            page.setDefaultTimeout(25_000.0)
            val kw = URLEncoder.encode(
                category.keywords.firstOrNull() ?: category.name, "UTF-8"
            )
            page.navigate(scraper.searchUrl(kw))
            HumanBehavior.settle(page, scraper.readySelector)
            HumanBehavior.scroll(page, rounds = 2)
            return scraper.extract(page, category, limit)
        }
    }

    // ---- 人工登录中继（扫码 / Agent 登录 / 过验证） ----

    /** startLogin 结果：首帧截图 + 页面是否提供二维码登录入口（客户端据此优先提示扫码）。 */
    data class LoginStartResult(val png: ByteArray, val qrDetected: Boolean)

    /** 打开登录页并返回首帧截图（PNG）与二维码检测结果，客户端展示给用户扫码。 */
    suspend fun startLogin(scraper: BrowserScraper): LoginStartResult = withContext(Dispatchers.IO) {
        pendingLogins.remove(scraper.platform.id)?.close()
        var browser: Browser? = null
        var ctx: BrowserContext? = null
        try {
            val b = launchBrowser(headless = true, requiresProxy = scraper.platform.requiresProxy)
                .also { browser = it }
            val c = newContext(b, scraper).also { ctx = it }
            val page = c.newPage()
            page.setDefaultTimeout(40_000.0)
            // DOMCONTENTLOADED 即返回：抖音等重资源站点 load 事件常超 40s 导致误报超时，
            // 页面就绪度由 HumanBehavior.settle 兜底
            page.navigate(
                scraper.loginUrl,
                Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
            )
            HumanBehavior.settle(page, extra = 3000)
            val handle = LoginHandle(scraper.platform.id, b, c, page)
            pendingLogins[scraper.platform.id] = handle
            val qr = AgentLoginEngine.detectQr(page)
            LoginStartResult(page.screenshot(), qr)
        } catch (e: Exception) {
            // 导航/启动失败：立即关闭已启动的浏览器，避免不可追踪的泄漏
            runCatching { ctx?.close() }
            runCatching { browser?.close() }
            throw e
        }
    }

    /**
     * Agent 智能登录：客户端直填账号密码，云端自动填充表单提交。
     * 会话保持打开（pendingLogins），NEEDS_MANUAL 时客户端可直接切手动远程控制接续。
     */
    suspend fun startAgentLogin(
        scraper: BrowserScraper,
        username: String,
        password: String
    ): Pair<ByteArray, AgentLoginEngine.Result> = withContext(Dispatchers.IO) {
        pendingLogins.remove(scraper.platform.id)?.close()
        var browser: Browser? = null
        var ctx: BrowserContext? = null
        try {
            val b = launchBrowser(headless = true, requiresProxy = scraper.platform.requiresProxy)
                .also { browser = it }
            val c = newContext(b, scraper).also { ctx = it }
            val page = c.newPage()
            page.setDefaultTimeout(40_000.0)
            page.navigate(
                scraper.loginUrl,
                Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
            )
            HumanBehavior.settle(page, extra = 3000)
            // 延迟弹出的登录弹窗：等待二维码/密码表单出现后再执行 Agent 识别
            AgentLoginEngine.awaitLoginUi(page)
            val handle = LoginHandle(scraper.platform.id, b, c, page)
            pendingLogins[scraper.platform.id] = handle
            val result = AgentLoginEngine.run(page, username, password) { handle.isLoggedIn(scraper) }
            page.screenshot() to result
        } catch (e: Exception) {
            runCatching { ctx?.close() }
            runCatching { browser?.close() }
            throw e
        }
    }

    /** 轮询登录页最新画面（二维码会刷新）；同步返回该帧二维码入口检测结果。 */
    suspend fun loginScreenshot(platformId: String): Pair<ByteArray, Boolean>? =
        withContext(Dispatchers.IO) {
            pendingLogins[platformId]?.let { handle ->
                runCatching { handle.screenshot() to handle.detectQr() }.getOrNull()
            }
        }

    /** 用户扫码/手动登录完成后调用：校验登录态 -> 持久化会话 -> 关闭页面。
     *  @param force true=跳过登录态校验，直接持久化（用于无法自动检测登录态的手动登录）。 */
    suspend fun completeLogin(scraper: BrowserScraper, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val handle = pendingLogins.remove(scraper.platform.id) ?: return@withContext false
            val ok = force || handle.isLoggedIn(scraper)
            if (ok) {
                handle.persist(SessionStore.statePath(cfg.dataDir, scraper.platform.id))
                log.info("{} 登录会话已持久化（force={}）", scraper.platform.id, force)
            } else {
                log.info("{} 登录校验未通过（二维码可能过期）", scraper.platform.id)
            }
            handle.close()
            ok
        }

    suspend fun cancelLogin(platformId: String) = withContext(Dispatchers.IO) {
        pendingLogins.remove(platformId)?.close()
        Unit
    }

    // ---- 远程控制（虚拟键鼠，作用于 pendingLogins 中保持打开的页面）----
    // 无活动会话或操作失败均返回 false，调用方据此决定是否提示。

    suspend fun remoteClick(platformId: String, x: Double, y: Double): Boolean =
        withContext(Dispatchers.IO) {
            pendingLogins[platformId]?.click(x, y) ?: false
        }

    suspend fun remoteType(platformId: String, text: String): Boolean =
        withContext(Dispatchers.IO) {
            pendingLogins[platformId]?.type(text) ?: false
        }

    suspend fun remoteKey(platformId: String, key: String): Boolean =
        withContext(Dispatchers.IO) {
            pendingLogins[platformId]?.pressKey(key) ?: false
        }

    suspend fun remoteNavigate(platformId: String, url: String): Boolean =
        withContext(Dispatchers.IO) {
            pendingLogins[platformId]?.navigate(url) ?: false
        }

    suspend fun remoteScroll(platformId: String, deltaX: Double, deltaY: Double): Boolean =
        withContext(Dispatchers.IO) {
            pendingLogins[platformId]?.scroll(deltaX, deltaY) ?: false
        }

    suspend fun remoteUrl(platformId: String): String? = withContext(Dispatchers.IO) {
        pendingLogins[platformId]?.let { runCatching { it.currentUrl() }.getOrNull() }
    }

    /** 活跃登录会话数（metrics 用）。 */
    fun pendingLoginCount(): Int = pendingLogins.size

    /** 进程退出时清理常驻浏览器与登录会话。 */
    fun shutdown() {
        runCatching {
            pendingLogins.values.forEach { it.close() }
            pendingLogins.clear()
            residents.values.forEach { runCatching { it.browser.close() } }
            residents.clear()
            runCatching { playwright?.close() }
        }
    }

    companion object {
        private const val FETCH_CONCURRENCY = 2
        private const val RESIDENT_MAX_UNITS = 100
        private const val RESIDENT_MAX_AGE_MS = 30L * 60 * 1000
        private const val UNIT_TIMEOUT_MS = 90_000L
        private const val LOGIN_TTL_MS = 10L * 60 * 1000
    }
}
