package com.gakj.weilie.ui.training

import kotlinx.serialization.Serializable

/**
 * 培训解说卡（STRICT_SPEC §4）：来自 assets/training/demo_guide.json，
 * from/to 为样本索引区间，回放落在区间内时展示。
 */
@Serializable
data class GuideCard(
    val from: Int,
    val to: Int,
    val title: String,
    val text: String,
)
