package com.trae.info.server.service

import com.trae.info.core.model.Domain
import com.trae.info.core.model.InfoCategory
import com.trae.info.core.model.SourcePlatform
import kotlin.test.Test
import kotlin.test.assertTrue

class BudgetPlannerTest {
    @Test fun fiveMinuteBudgetDoesNotScheduleEveryPair() {
        val categories = (1..40).map { index ->
            InfoCategory(
                id = "c$index",
                name = "Category $index",
                domain = Domain.PRACTICAL,
                group = "test",
                description = "",
                keywords = listOf("k$index"),
                sources = SourcePlatform.entries.take(10)
            )
        }
        val plan = BudgetPlanner.plan(
            categories = categories,
            platformsByCategory = { it.sources },
            budgetMinutes = 5,
            weightOf = { category, _ -> if (category.id == "c1") 5.0 else 1.0 }
        )
        assertTrue(plan.size <= 30, "five-minute queue was ${plan.size}")
        assertTrue(plan.any { it.category.id == "c1" })
    }
}
