package com.trae.info.server.browser

import com.microsoft.playwright.Page
import org.slf4j.LoggerFactory

/**
 * Agent 智能登录引擎：启发式表单识别与自动填充（不依赖外部 agent 框架，直接驱动 Playwright）。
 *
 * 流程（二维码优先原则）：
 * 1. 检测页面是否提供二维码登录入口；
 * 2. 存在密码登录 Tab 时自动切换，识别账号/密码输入框并填充凭据；
 * 3. 识别登录按钮并提交；
 * 4. 提交后检测滑块/验证码 → NEEDS_MANUAL（云端会话保持打开，客户端可切手动远程控制）；
 *    登录态校验通过 → LOGGED_IN。
 *
 * 安全约束：凭据仅内存使用，不落盘、不写日志；不破解任何验证码。
 */
object AgentLoginEngine {

    private val log = LoggerFactory.getLogger(AgentLoginEngine::class.java)

    enum class Stage { QR_ONLY, NEEDS_MANUAL, LOGGED_IN, FAILED }

    data class Result(val stage: Stage, val message: String)

    // ---- 启发式选择器（按优先级，全部走 runCatching，页面结构变化时优雅降级为手动） ----

    /** 二维码特征：img src 或 class/id 含 qrcode/qr-img 等关键词（覆盖规范命名的站点）。 */
    private val qrSelectors = listOf(
        "img[src*='qrcode']", "img[src*='QRCode']",
        "[class*='qrcode']", "[id*='qrcode']", "[id*='qr-']", "[id*='qr_']",
        "[class*='qr-img']", "[class*='qr_code']",
        "[class*='scan-code']", "[class*='login-qr']"
    )

    /** 扫码文案信号：抖音等混淆类名站点靠页面文案识别（Playwright :text-matches 伪类）。 */
    private const val QR_TEXT_SELECTOR = ":text-matches(\"扫码登录|扫一扫|二维码登录|扫码注册登录\", \"i\")"

    /** 密码登录 Tab 特征（抖音/B站/知乎等登录弹窗默认二维码 Tab）。 */
    private val passwordTabSelectors = listOf(
        "text=密码登录", "text=账号登录", "text=账号密码登录", "text=使用密码登录"
    )

    /** 账号输入框特征（name/placeholder 关键词优先，最后兜底任意可见 text 输入框）。 */
    private val accountSelectors = listOf(
        "input[name*='user' i]", "input[name*='account' i]", "input[name*='phone' i]",
        "input[name*='mobile' i]", "input[name*='email' i]", "input[name*='login' i]",
        "input[placeholder*='账号' i]", "input[placeholder*='手机' i]",
        "input[placeholder*='用户' i]", "input[placeholder*='邮箱' i]"
    )

    private const val PASSWORD_SELECTOR = "input[type='password']"

    /** 滑块/验证码特征：出现即视为需人工完成验证。 */
    private val captchaSelectors = listOf(
        "[class*='captcha']", "[id*='captcha']", "[class*='slider']",
        "iframe[src*='captcha']", "iframe[src*='verify']"
    )

    /** 登录按钮特征。 */
    private val submitSelectors = listOf(
        "button:has-text('登 录')", "button:has-text('登录')", "input[type='submit']",
        "button[type='submit']", "button:has-text('Log in')", "button:has-text('Sign in')"
    )

    private fun hasElement(page: Page, selector: String): Boolean =
        runCatching { page.querySelector(selector) != null }.getOrDefault(false)

    /**
     * 页面是否提供二维码登录入口。
     * 三级信号任一命中即判定：1) class/src 关键词；2) 扫码文案（混淆类名站点如抖音）；
     * 3) 近方形大图/画布结构（二维码载体的通用几何特征，配合文案信号防误报）。
     */
    fun detectQr(page: Page): Boolean {
        val selHit = qrSelectors.firstOrNull { hasElement(page, it) }
        if (selHit != null) {
            log.info("detectQr HIT via selector: {}", selHit)
            return true
        }
        val textSignal = runCatching { page.querySelector(QR_TEXT_SELECTOR) != null }.getOrDefault(false)
        if (textSignal) {
            log.info("detectQr HIT via scan text")
            return true
        }
        val geo = runCatching {
            val squares = page.querySelectorAll("img, canvas").mapNotNull { el ->
                val box = el.boundingBox() ?: return@mapNotNull null
                val w = box.width; val h = box.height
                if (w >= 90 && h >= 90 && w / h in 0.75..1.33) {
                    "${el.evaluate("e => e.tagName")}(${w.toInt()}x${h.toInt()})"
                } else null
            }
            val loginCtx = hasElement(page, "text=登录") || hasElement(page, "text=登录/注册")
            // 仅结构信号不足以判定（头像等也近方形），须与任意「登录/注册」文案同现
            val hit = squares.isNotEmpty() && loginCtx
            log.info("detectQr geo: squares={}, loginCtx={}, hit={}", squares, loginCtx, hit)
            hit
        }.getOrElse {
            log.warn("detectQr geo threw: {}", it.message)
            false
        }
        return geo
    }

    /**
     * 等待登录 UI 出现（二维码/密码表单/密码Tab 任一可见信号）。
     * 抖音等站点登录弹窗在 DOMContentLoaded 后约 12s（生产环境内存紧张时更久）才从隐藏骨架
     * 变为可见；Playwright text 引擎不匹配隐藏元素，故轮询窗口需足够宽（30s）。
     * 出现即返回 true，超时返回 false（由调用方按无信号路径处理）。
     */
    fun awaitLoginUi(page: Page, timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (detectQr(page) || hasElement(page, PASSWORD_SELECTOR) ||
                passwordTabSelectors.any { hasElement(page, it) }
            ) return true
            runCatching { page.waitForTimeout(1000.0) }
        }
        return detectQr(page) || hasElement(page, PASSWORD_SELECTOR)
    }

    /**
     * 执行 Agent 登录。调用方保证页面已导航到登录页并完成 settle（建议先 awaitLoginUi）；
     * [loggedInCheck] 用平台 scraper 的登录态检测（与「校验登录」同源）。
     * 任何一步失败都不关闭浏览器会话——客户端可直接切手动远程控制接续。
     */
    fun run(page: Page, username: String, password: String, loggedInCheck: () -> Boolean): Result {
        val qrPresent = detectQr(page)

        // 1. 尝试切换到密码登录 Tab（默认二维码 Tab 的站点）
        passwordTabSelectors.firstOrNull { hasElement(page, it) }?.let { tab ->
            runCatching { page.click(tab) }
            HumanBehavior.settle(page, extra = 1500)
        }

        // 2. 二维码优先：无密码表单时明确提示扫码
        if (!hasElement(page, PASSWORD_SELECTOR)) {
            return if (qrPresent) Result(Stage.QR_ONLY, "页面提供二维码登录：推荐用手机 App 扫描画面中的二维码")
            else Result(Stage.NEEDS_MANUAL, "未识别到账号密码表单，请切换手动远程控制登录")
        }

        // 3. 填充账号
        val accountSelector = accountSelectors.firstOrNull { hasElement(page, it) }
            ?: return Result(Stage.NEEDS_MANUAL, "未识别到账号输入框，请切换手动远程控制登录")
        runCatching {
            page.querySelector(accountSelector)?.let { el ->
                el.click(); el.fill(username)
            }
        }.onFailure { return Result(Stage.FAILED, "填写账号失败：${it.message}") }

        // 4. 填充密码
        runCatching {
            page.querySelector(PASSWORD_SELECTOR)?.let { el ->
                el.click(); el.fill(password)
            }
        }.onFailure { return Result(Stage.FAILED, "填写密码失败：${it.message}") }

        // 5. 提交
        val submitSelector = submitSelectors.firstOrNull { hasElement(page, it) }
            ?: return Result(Stage.NEEDS_MANUAL, "已填写账号密码但未识别到登录按钮，请手动点击登录")
        runCatching { page.click(submitSelector) }
            .onFailure { return Result(Stage.FAILED, "点击登录按钮失败：${it.message}") }
        HumanBehavior.settle(page, extra = 4000)

        // 6. 结果判定：验证码优先于登录态（部分站点登录后仍残留验证组件）
        if (captchaSelectors.any { hasElement(page, it) }) {
            return Result(Stage.NEEDS_MANUAL, "触发滑块/验证码校验，请切换手动远程控制完成验证")
        }
        if (loggedInCheck()) {
            return Result(Stage.LOGGED_IN, "Agent 登录成功")
        }
        return Result(Stage.NEEDS_MANUAL, "已提交登录请求，请查看画面确认；如需人工验证请切换手动远程控制")
    }
}
