package com.trae.info.server.decision

import com.trae.info.core.model.SourcePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 收益调度测试——覆盖整改方案 §2.1 的场景 23–27。
 *
 * 已知缺陷（T1.3/T1.4，两者共同点：登记点与消费点脱节）：
 * - T1.3：ScrapingProcessService 的 recordUnit 调用点不传 paidCost/transferBytes，
 *   使 W_MONEY / W_BYTES 两个权重恒为 0。
 * - T1.4：DecisionStore.markExplored 无调用方，lastExploredAtEpochMs 恒为 0，
 *   到期判断恒真 → 探索加成退化为对所有来源同一常数。
 * 下方 `explorationBonusIsNotConstantAcrossAllSources` 断言**正确行为**，
 * 在缺陷修复前预期失败。
 */
class SourceSchedulerTest {

    private val now = 1_800_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    private fun obs(
        sourceId: String = "hackernews",
        attempts: Long = 0,
        successes: Long = 0,
        uniqueNew: Long = 0,
        elapsedMsTotal: Long = 0,
        paidCostTotal: Double = 0.0,
        transferBytesTotal: Long = 0,
        lastExploredAtEpochMs: Long = 0
    ) = SourceObservation(
        sourceId = sourceId, attempts = attempts, successes = successes,
        uniqueNew = uniqueNew, elapsedMsTotal = elapsedMsTotal,
        paidCostTotal = paidCostTotal, transferBytesTotal = transferBytesTotal,
        lastExploredAtEpochMs = lastExploredAtEpochMs
    )

    // ---- 23. 冷启动中性先验 ----

    @Test
    fun `source without observation gets a neutral prior`() {
        val p = SourceScheduler.priority(SourcePlatform.HACKERNEWS, null, preferenceMatch = 1.0, nowMs = now)
        assertEquals(0.5, p, 1e-9)
    }

    @Test
    fun `neutral prior is damped by low preference match`() {
        val p = SourceScheduler.priority(SourcePlatform.HACKERNEWS, null, preferenceMatch = 0.0, nowMs = now)
        // max(preferenceMatch, 0.1) → 0.5 * 0.1
        assertEquals(0.05, p, 1e-9)
    }

    @Test
    fun `zero attempt observation still takes the cold start branch`() {
        val p = SourceScheduler.priority(
            SourcePlatform.HACKERNEWS, obs(attempts = 0), preferenceMatch = 1.0, nowMs = now
        )
        assertEquals(0.5, p, 1e-9)
    }

    // ---- 25. 低样本探索加成 ----

    @Test
    fun `low sample source earns the exploration bonus`() {
        val low = obs(attempts = 2, successes = 1, uniqueNew = 1, elapsedMsTotal = 30_000)
        val p = SourceScheduler.priority(SourcePlatform.HACKERNEWS, low, preferenceMatch = 1.0, nowMs = now)
        val base = (low.usefulPerAttempt * 1.0) /
            (SourceScheduler.W_TIME * (low.avgElapsedMs / SourceScheduler.TIME_REFERENCE_MS))
        assertEquals(base + SourceScheduler.EXPLORATION_BONUS, p, 1e-9)
    }

    @Test
    fun `exploration bonus is documented at 015`() {
        assertEquals(0.15, SourceScheduler.EXPLORATION_BONUS, 1e-9)
    }

    @Test
    fun `explorationBonusIsNotConstantAcrossAllSources`() {
        // T1.4 回归护栏：若 markExplored 从未被调用，lastExploredAt 恒为 0，
        // 则"到期（>24h 未探索）"条件对**所有**来源恒真，加成为常数，
        // 与文档所述"轮换、非固定抽同一批"相反。
        // 正确行为：已探索且未到期的来源不应获得加成。
        val recentlyExplored = obs(
            attempts = 10, successes = 9, uniqueNew = 8, elapsedMsTotal = 300_000,
            lastExploredAtEpochMs = now - 60_000
        )
        val base = (recentlyExplored.usefulPerAttempt * 1.0) /
            (SourceScheduler.W_TIME * (recentlyExplored.avgElapsedMs / SourceScheduler.TIME_REFERENCE_MS))
        val p = SourceScheduler.priority(
            SourcePlatform.HACKERNEWS, recentlyExplored, preferenceMatch = 1.0, nowMs = now
        )
        assertEquals(base, p, 1e-9)
    }

    // ---- 26. 成本项单调性 ----

    @Test
    fun `paid cost lowers priority`() {
        val cheap = obs(attempts = 5, successes = 5, uniqueNew = 5, elapsedMsTotal = 150_000)
        val pricey = cheap.copy(paidCostTotal = 5.0)
        val pCheap = SourceScheduler.priority(SourcePlatform.HACKERNEWS, cheap, 1.0, now)
        val pPricey = SourceScheduler.priority(SourcePlatform.HACKERNEWS, pricey, 1.0, now)
        assertTrue(pPricey < pCheap, "paid cost must reduce priority: $pPricey vs $pCheap")
    }

    @Test
    fun `transfer bytes lower priority`() {
        val lean = obs(attempts = 5, successes = 5, uniqueNew = 5, elapsedMsTotal = 150_000)
        val heavy = lean.copy(transferBytesTotal = 200_000_000)
        val pLean = SourceScheduler.priority(SourcePlatform.HACKERNEWS, lean, 1.0, now)
        val pHeavy = SourceScheduler.priority(SourcePlatform.HACKERNEWS, heavy, 1.0, now)
        assertTrue(pHeavy < pLean, "transfer bytes must reduce priority: $pHeavy vs $pLean")
    }

    @Test
    fun `money weight is the dominant cost term as documented`() {
        assertEquals(1.0, SourceScheduler.W_TIME, 1e-9)
        assertEquals(4.0, SourceScheduler.W_MONEY, 1e-9)
        assertEquals(0.2, SourceScheduler.W_BYTES, 1e-9)
    }

    @Test
    fun `higher yield per attempt raises priority`() {
        val low = obs(attempts = 10, successes = 10, uniqueNew = 1, elapsedMsTotal = 300_000)
        val high = obs(attempts = 10, successes = 10, uniqueNew = 9, elapsedMsTotal = 300_000)
        val pLow = SourceScheduler.priority(SourcePlatform.HACKERNEWS, low, 1.0, now)
        val pHigh = SourceScheduler.priority(SourcePlatform.HACKERNEWS, high, 1.0, now)
        assertTrue(pHigh > pLow)
    }

    // ---- observe() 计数语义 ----

    @Test
    fun `observe accumulates counters`() {
        val first = SourceScheduler.observe(null, "hackernews", true, 5, 1_000, nowMs = now)
        assertEquals(1, first.attempts)
        assertEquals(1, first.successes)
        assertEquals(5, first.uniqueNew)

        val second = SourceScheduler.observe(first, "hackernews", false, 0, 2_000, nowMs = now + 1)
        assertEquals(2, second.attempts)
        assertEquals(1, second.successes, "failed attempt must not increment successes")
        assertEquals(5, second.uniqueNew)
        assertEquals(3_000, second.elapsedMsTotal)
        assertEquals(now + 1, second.lastAttemptAtEpochMs)
        assertEquals(now, second.lastSuccessAtEpochMs, "lastSuccessAt stays at the last success")
    }

    @Test
    fun `observation smoothing avoids extreme rates on tiny samples`() {
        // 拉普拉斯平滑：attempts=0 → 0.5，successes=1/attempts=1 → 2/3
        assertEquals(0.5, obs(attempts = 0).successRate, 1e-9)
        assertEquals(2.0 / 3.0, obs(attempts = 1, successes = 1).successRate, 1e-9)
        assertEquals(1.0 / 3.0, obs(attempts = 1, successes = 0).successRate, 1e-9)
    }

    @Test
    fun `usefulPerAttempt has a floor on zero-attempt observations`() {
        assertEquals(0.2, obs(attempts = 0).usefulPerAttempt, 1e-9)
    }

    // ---- 27. 排序入口一致 ----

    @Test
    fun `orderUnits prefers higher priority per unit cost`() {
        val platforms = listOf(SourcePlatform.HACKERNEWS, SourcePlatform.CNKI)
        val ordered = SourceScheduler.orderUnits(
            platforms = platforms,
            observationOf = { null },              // 两者均为冷启动
            preferenceMatchOf = { 1.0 },
            nowMs = now
        )
        // HN 单元成本 6s（文本 API），CNKI 为浏览器主通道 40s（见 BudgetPlanner）
        assertEquals(SourcePlatform.HACKERNEWS, ordered.first())
    }
}
