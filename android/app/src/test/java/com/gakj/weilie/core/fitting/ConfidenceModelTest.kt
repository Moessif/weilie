package com.gakj.weilie.core.fitting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 置信度短板模型（v0.13.1）。
 *
 * 关键断言：分数由**最弱的一项**决定，而不是线性加权。
 */
class ConfidenceModelTest {

    @Test
    fun straight_line_with_plenty_points_is_low_because_of_direction() {
        // 120 点、只覆盖 4/16 个方向、时段齐全
        val r = ConfidenceModel.compute(sampleCount = 120, sectorsFilled = 4, timeCoverage = 1.0)

        assertEquals("分数应被方向因子限制", 25, r.score)
        assertEquals(CoverageResult.Confidence.Level.低, r.level)
        assertTrue("应给出方向原因", r.reasons.any { it.contains("方向") })
    }

    @Test
    fun full_loop_in_three_time_windows_is_high() {
        val r = ConfidenceModel.compute(sampleCount = 120, sectorsFilled = 16, timeCoverage = 1.0)

        assertEquals(100, r.score)
        assertEquals(CoverageResult.Confidence.Level.高, r.level)
        assertTrue(r.reasons.isEmpty())
    }

    @Test
    fun few_points_dominated_by_sample_factor() {
        val r = ConfidenceModel.compute(sampleCount = 5, sectorsFilled = 3, timeCoverage = 1.0 / 3.0)

        assertTrue("score=${r.score}", r.score <= 5)
        assertEquals(CoverageResult.Confidence.Level.低, r.level)
        assertTrue(r.reasons.any { it.contains("有效点") })
    }

    @Test
    fun single_time_window_limits_score_even_with_many_points() {
        // 120 点、方向全覆盖，但只在同一时段采集
        val r = ConfidenceModel.compute(sampleCount = 120, sectorsFilled = 16, timeCoverage = 1.0 / 3.0)

        assertEquals(33, r.score)
        assertEquals(CoverageResult.Confidence.Level.低, r.level)
        assertTrue(r.reasons.any { it.contains("时段") })
    }

    @Test
    fun short_board_is_weaker_than_linear_weighting() {
        // 旧公式：0.5×1.0 + 0.5×0.25 = 0.625 → 62 分（会给出"中"）
        // 新模型：min(1.0, 0.25, 1.0) = 0.25 → 25 分（"低"）
        val r = ConfidenceModel.compute(sampleCount = 200, sectorsFilled = 4, timeCoverage = 1.0)
        assertEquals(25, r.score)
        assertTrue("短板模型必须低于线性加权", r.score < 62)
    }
}
