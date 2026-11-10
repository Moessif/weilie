package com.gakj.weilie.core.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静默跟踪逻辑单测：状态判定与震动方案是验收硬指标（80/1000、40/300、持续震动），
 * 这里按规则逐条锁定，Service 端只消费不重算。
 */
class SilentTrackLogicTest {

    private val targetCi = 5677244420L
    private val otherCi = 1234567890L

    @Test
    fun servingEqualsTarget_isMain() {
        assertEquals(
            TrackState.MAIN,
            SilentTrackLogic.stateOf(servingCi = targetCi, targetCi = targetCi, neighborCis = emptyList()),
        )
    }

    @Test
    fun neighborContainsTarget_isNeighbor() {
        assertEquals(
            TrackState.NEIGHBOR,
            SilentTrackLogic.stateOf(
                servingCi = otherCi,
                targetCi = targetCi,
                neighborCis = listOf(111L, targetCi, 222L),
            ),
        )
    }

    @Test
    fun neitherServingNorNeighbor_isNone() {
        assertEquals(
            TrackState.NONE,
            SilentTrackLogic.stateOf(
                servingCi = otherCi,
                targetCi = targetCi,
                neighborCis = listOf(111L, 222L),
            ),
        )
    }

    @Test
    fun nullTarget_isNone() {
        assertEquals(
            TrackState.NONE,
            SilentTrackLogic.stateOf(
                servingCi = targetCi,
                targetCi = null,
                neighborCis = listOf(targetCi),
            ),
        )
    }

    @Test
    fun planMain_pulses80msEverySecond() {
        val plan = SilentTrackLogic.plan(TrackState.MAIN)
        assertEquals(80L, plan.pulseMs)
        assertEquals(1000L, plan.intervalMs)
        assertFalse(plan.repeat)
        assertEquals("主区命中", plan.label)
    }

    @Test
    fun planNeighbor_pulses40msEvery300ms() {
        val plan = SilentTrackLogic.plan(TrackState.NEIGHBOR)
        assertEquals(40L, plan.pulseMs)
        assertEquals(300L, plan.intervalMs)
        assertFalse(plan.repeat)
        assertEquals("邻区命中", plan.label)
    }

    @Test
    fun planNone_isContinuous() {
        val plan = SilentTrackLogic.plan(TrackState.NONE)
        assertTrue(plan.repeat)
        assertEquals(1000L, plan.pulseMs)
        assertEquals("脱离", plan.label)
    }

    @Test
    fun targetJudge_delegatesToLogic() {
        assertEquals(
            TrackState.MAIN,
            TargetJudge.classify(targetCi, targetCi, emptyList()),
        )
        assertEquals(
            TrackState.NEIGHBOR,
            TargetJudge.classify(otherCi, targetCi, listOf(targetCi)),
        )
        assertEquals(
            TrackState.NONE,
            TargetJudge.classify(otherCi, null, listOf(targetCi)),
        )
    }
}
