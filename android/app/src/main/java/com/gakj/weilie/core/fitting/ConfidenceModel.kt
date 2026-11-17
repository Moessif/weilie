package com.gakj.weilie.core.fitting

/**
 * 置信度模型（v0.13.1 抽出，便于单测与调参）。
 *
 * 旧模型：`score = (样本量因子 × 0.5 + 方向覆盖度 × 0.5) × 100`
 * —— 样本量在 120 点后饱和，于是"点数多"就能拿高分，掩盖方向/时段的缺口。
 *
 * 新模型：**短板模型**
 * ```
 * score = 100 × min(样本量因子, 方向覆盖度, 时段覆盖)
 * ```
 * 含义：置信度由最弱的一项决定——只走一条街，方向覆盖低，分数就上不去；
 * 只在清晨测了一次，时段覆盖低，也上不去。更接近"围测是否采全"的语义。
 */
object ConfidenceModel {

    const val SAMPLE_SATURATION = 120.0
    const val SECTORS = 16

    data class Result(
        val score: Int,
        val level: CoverageResult.Confidence.Level,
        val sampleCountFactor: Double,
        val directionCoverage: Double,
        val timeCoverage: Double,
        val reasons: List<String>,
    )

    fun compute(
        sampleCount: Int,
        sectorsFilled: Int,
        timeCoverage: Double,
    ): Result {
        val countFactor = minOf(1.0, sampleCount / SAMPLE_SATURATION)
        val dirCov = (sectorsFilled.toDouble() / SECTORS).coerceIn(0.0, 1.0)
        val timeCov = timeCoverage.coerceIn(0.0, 1.0)

        val score = (minOf(countFactor, dirCov, timeCov) * 100).toInt()
        val level = when {
            score >= 70 && sectorsFilled >= 12 -> CoverageResult.Confidence.Level.高
            score >= 40 -> CoverageResult.Confidence.Level.中
            else -> CoverageResult.Confidence.Level.低
        }

        // 短板原因按影响从大到小（因子小的在前），最多 3 条
        val reasons = ArrayList<String>(3)
        if (countFactor < 1.0) reasons.add("有效点仅 $sampleCount 个")
        if (dirCov < 1.0) reasons.add("只覆盖 $sectorsFilled/$SECTORS 个方向")
        if (timeCov < 1.0) reasons.add("只走了 ${(timeCov * 3).toInt()}/3 个时段")
        reasons.sortBy { r ->
            when {
                r.startsWith("有效点") -> countFactor
                r.startsWith("只覆盖") -> dirCov
                else -> timeCov
            }
        }

        return Result(
            score = score,
            level = level,
            sampleCountFactor = countFactor,
            directionCoverage = dirCov,
            timeCoverage = timeCov,
            reasons = reasons,
        )
    }
}
