package com.trae.info.server.browser

import com.microsoft.playwright.Page
import kotlin.random.Random

/**
 * 拟人行为层：随机延时、鼠标移动、滚动，降低被行为风控判定的概率。
 * 注意：不绕过验证码；遇到滑块/点选验证一律转由人工完成（见登录中继）。
 */
object HumanBehavior {

    fun humanDelay(minMs: Long = 700, maxMs: Long = 2200) {
        Thread.sleep(Random.nextLong(minMs, maxMs))
    }

    /**
     * 页面打开后的停留：优先条件等待结果卡片出现（最多 8s），出现即走；
     * 选择器为空或超时则回退短盲等。比固定 sleep 更快且慢网络下更可靠。
     */
    fun settle(page: Page, readySelector: String? = null, extra: Long = 1200) {
        val appeared = readySelector != null && runCatching {
            page.waitForSelector(readySelector, Page.WaitForSelectorOptions().setTimeout(8_000.0))
            true
        }.getOrDefault(false)
        // 条件等待命中后只留短暂拟人停顿；未命中回退盲等
        page.waitForTimeout((if (appeared) 300 else 1000) + Random.nextLong(0, 500).toDouble())
        if (!appeared) humanDelay(300, extra)
    }

    /** 模拟阅读滚动；rounds 默认 2 轮（配合条件等待已无需 3 轮盲滚）。 */
    fun scroll(page: Page, rounds: Int = 2) {
        repeat(rounds) {
            page.mouse().move(
                600.0 + Random.nextInt(400),
                300.0 + Random.nextInt(300)
            )
            page.mouse().wheel(0.0, 500.0 + Random.nextInt(600))
            page.waitForTimeout((600 + Random.nextLong(0, 1200)).toDouble())
        }
    }
}
