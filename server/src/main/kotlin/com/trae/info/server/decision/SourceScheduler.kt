package com.trae.info.server.decision

import com.trae.info.core.model.SourcePlatform
import com.trae.info.server.service.BudgetPlanner
import kotlin.math.max

/**
 * 动态收益调度（方案 §5.2）：
 * priority = expectedUseful / max(cost, ε) + explorationBonus
 * - expectedUseful = smoothedObservedUsefulPerAttempt × preferenceMatch
 * - cost 归一化：wTime×latency/ref + wMoney×paid/ref + wBytes×bytes/ref
 * - 观测来自 [SourceObservation]（DecisionStore 持久，跨轮累积）；
 * - 探索：到期的低样本/未探索来源获得加成（非固定抽同一批）。
 */
object SourceScheduler {

    /** 调度公式权重（配置初值，方案 §5.2）。 */
    const val W_TIME = 1.0
    const val W_MONEY = 4.0
    const val W_BYTES = 0.2
    private const val EPSILON = 0.01

    /** 参考量纲（归一化基准，按目标 VPS 实测修正）。 */
    const val TIME_REFERENCE_MS = 30_000.0
    const val MONEY_REFERENCE = 1.0
    const val BYTES_REFERENCE = 20_000_000.0

    /** 低样本来源的探索加成；到 exploreAfterMs 未被探索则衰减获取。 */
    const val EXPLORATION_BONUS = 0.15
    private const val EXPLORE_AFTER_MS = 24L * 60 * 60 * 1000
    private const val LOW_SAMPLE_ATTEMPTS = 3L

    /**
     * 计算平台在给定偏好匹配度下的调度优先级（越大越先派发）。
     * 无观测的新源返回中性 0.5（冷启动先验，方案：收集足够数据后由观测替代）。
     */
    fun priority(
        platform: SourcePlatform,
        observation: SourceObservation?,
        preferenceMatch: Double,
        nowMs: Long = System.currentTimeMillis()
    ): Double {
        if (observation == null || observation.attempts == 0L) return 0.5 * max(preferenceMatch, 0.1)
        val expectedUseful = observation.usefulPerAttempt * max(preferenceMatch, 0.05)
        val cost = W_TIME * (observation.avgElapsedMs / TIME_REFERENCE_MS) +
            W_MONEY * (observation.paidCostTotal / MONEY_REFERENCE) +
            W_BYTES * (observation.transferBytesTotal.toDouble() / BYTES_REFERENCE)
        val base = expectedUseful / max(cost, EPSILON)
        val bonus = when {
            observation.attempts < LOW_SAMPLE_ATTEMPTS -> EXPLORATION_BONUS
            nowMs - observation.lastExploredAtEpochMs > EXPLORE_AFTER_MS -> EXPLORATION_BONUS
            else -> 0.0
        }
        return base + bonus
    }

    /**
     * 记录一次单元观测（由抓取进程在单元完成后调用；无论灰度开关状态都记录，供未来调度）。
     * 返回更新后的观测值（调用方负责落盘 DecisionStore）。
     */
    fun observe(
        current: SourceObservation?,
        sourceId: String,
        success: Boolean,
        uniqueNew: Int,
        elapsedMs: Long,
        paidCost: Double = 0.0,
        transferBytes: Long = 0,
        nowMs: Long = System.currentTimeMillis()
    ): SourceObservation {
        val cur = current ?: SourceObservation(sourceId = sourceId)
        return cur.copy(
            attempts = cur.attempts + 1,
            successes = cur.successes + if (success) 1 else 0,
            uniqueNew = cur.uniqueNew + uniqueNew,
            elapsedMsTotal = cur.elapsedMsTotal + elapsedMs,
            paidCostTotal = cur.paidCostTotal + paidCost,
            transferBytesTotal = cur.transferBytesTotal + transferBytes,
            lastAttemptAtEpochMs = nowMs,
            lastSuccessAtEpochMs = if (success) nowMs else cur.lastSuccessAtEpochMs
        )
    }

    /**
     * 收益调度排序（sourceSchedulerV2 开启时供规划期使用）：
     * 与 BudgetPlanner 旧口径（weight/unitCost）的差异 = 观测驱动的收益替代静态成本比。
     */
    fun orderUnits(
        platforms: List<SourcePlatform>,
        observationOf: (SourcePlatform) -> SourceObservation?,
        preferenceMatchOf: (SourcePlatform) -> Double,
        nowMs: Long = System.currentTimeMillis()
    ): List<SourcePlatform> = platforms.sortedByDescending {
        val base = priority(it, observationOf(it), preferenceMatchOf(it), nowMs)
        // 融合既有静态成本档（浏览器主通道仍需高成本约束），观测收益为主、静态成本为辅
        base / BudgetPlanner.unitCostSec(it)
    }
}