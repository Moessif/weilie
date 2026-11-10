package com.gakj.weilie.core.tracking

/**
 * 静默跟踪的三态：主区命中 / 邻区命中 / 脱离。
 *
 * 判定只依赖「驻留小区 + 邻区列表 + 目标 CI」三个输入，不碰定位与 UI，
 * 便于在 Service 里 1Hz 直接复用，也便于单测覆盖。
 */
enum class TrackState { MAIN, NEIGHBOR, NONE }

/**
 * 状态对应的震动方案。
 *
 * - repeat=false：按 [intervalMs] 间隔重复单次 [pulseMs] 脉冲（Handler 定时实现）；
 * - repeat=true ：持续震动（波形循环，见 Service 端 createWaveform）。
 * 震动强弱本身就是区分度：主区慢而长、邻区急而短、脱离持续震，用户无需看屏。
 */
data class VibrationPlan(
    val pulseMs: Long,
    val intervalMs: Long,
    val repeat: Boolean,
    val label: String,
)

/**
 * 静默跟踪的纯逻辑层：状态判定 + 震动方案。
 * 刻意不做成 class 实例：Service 只需在快照回调里调用两个无状态函数。
 */
object SilentTrackLogic {

    /**
     * 判定当前跟踪状态。
     * 规则顺序不能换：先无目标（尚未建任务/经纬度目标无 CI）→ 再驻留命中 → 再邻区命中 → 否则脱离。
     */
    fun stateOf(servingCi: Long?, targetCi: Long?, neighborCis: List<Long>): TrackState = when {
        targetCi == null -> TrackState.NONE
        servingCi == targetCi -> TrackState.MAIN
        targetCi in neighborCis -> TrackState.NEIGHBOR
        else -> TrackState.NONE
    }

    /** 按状态取震动方案；数值与验收标准一一对应，改动前先看单测。 */
    fun plan(state: TrackState): VibrationPlan = when (state) {
        TrackState.MAIN -> VibrationPlan(pulseMs = 80L, intervalMs = 1000L, repeat = false, label = "主区命中")
        TrackState.NEIGHBOR -> VibrationPlan(pulseMs = 40L, intervalMs = 300L, repeat = false, label = "邻区命中")
        TrackState.NONE -> VibrationPlan(pulseMs = 1000L, intervalMs = 0L, repeat = true, label = "脱离")
    }
}

/**
 * 目标判定门面：外部（或后续新增调用方）只关心「现在算哪种状态」，
 * 统一委托给 [SilentTrackLogic]，避免两套判定规则漂移。
 */
object TargetJudge {
    fun classify(servingCi: Long?, targetCi: Long?, neighborCis: List<Long>): TrackState =
        SilentTrackLogic.stateOf(servingCi, targetCi, neighborCis)
}
