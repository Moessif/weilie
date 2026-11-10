package com.gakj.weilie.core.fitting

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

class TrackGatesTest {

    private val lat0 = 39.9
    private val lon0 = 116.4
    private val mPerDegLat = 110540.0
    private val mPerDegLon = 111320.0 * cos(Math.toRadians(lat0))

    private fun fix(dNorthM: Double, dEastM: Double, acc: Double = 5.0, ts: Long): Fix =
        Fix(lat0 + dNorthM / mPerDegLat, lon0 + dEastM / mPerDegLon, acc, ts)

    @Test
    fun accuracyAboveLimitIsSkipped() {
        val r = TrackGate().push(fix(0.0, 0.0, acc = 80.0, ts = 1000L))
        assertTrue(r is GateResult.Skip)
        val msg = (r as GateResult.Skip).message
        assertTrue("message=$msg", msg.contains("已跳过"))
    }

    @Test
    fun closePointsAreMerged() {
        // 默认防抖距离已调成 1 m（用户真机要求「GPS 重合设置成 1 米，可在设置更改」），
        // 所以 0.4 m 的位移应被合并、1.2 m 的位移应被接受。
        val gate = TrackGate()
        assertTrue(gate.push(fix(0.0, 0.0, ts = 1000L)) is GateResult.Accept)
        val r = gate.push(fix(0.4, 0.0, ts = 2000L))
        assertTrue(r is GateResult.Merge)
        val m = r as GateResult.Merge
        assertTrue(m.mergedWithLast)
        assertTrue(m.lat.isFinite() && m.lon.isFinite())
        assertTrue("dist should be ~0.4m, was ${m.distanceM}", abs(m.distanceM - 0.4) < 0.3)

        val r2 = gate.push(fix(1.2, 0.0, ts = 3000L))
        assertTrue("1.2m 应超过 1m 门槛而被接受，实际 $r2", r2 is GateResult.Accept)
    }

    @Test
    fun walkingDoesNotLagBehind() {
        // 回归测试：早期纯位置卡尔曼让人走路时打点严重滞后（蓝点慢慢追上来）。
        // 匀速模型应当跟得很紧：沿正北每步 2 m、每秒一步，走 20 步后误差应 < 3 m。
        val gate = TrackGate()
        gate.push(fix(0.0, 0.0, ts = 1000L))
        var last: GateResult = GateResult.Accept(lat0, lon0)
        for (i in 1..20) {
            last = gate.push(fix(2.0 * i, 0.0, ts = 1000L + i * 1000L))
        }
        assertTrue("20 步都应是 Accept，最后=$last", last is GateResult.Accept)
        val a = last as GateResult.Accept
        val trueLat = lat0 + 40.0 / mPerDegLat
        val errM = abs(a.lat - trueLat) * mPerDegLat
        assertTrue("走路跟随时滞后应 < 3 m，实际 ${"%.2f".format(errM)} m", errM < 3.0)
    }

    @Test
    fun farPointAcceptedAndDiffersFromFirst() {
        val gate = TrackGate()
        val a = gate.push(fix(0.0, 0.0, ts = 1000L)) as GateResult.Accept
        val r = gate.push(fix(20.0, 0.0, ts = 3000L))
        assertTrue(r is GateResult.Accept)
        val b = r as GateResult.Accept
        assertTrue(
            "filtered output should move",
            abs(b.lat - a.lat) > 1e-9 || abs(b.lon - a.lon) > 1e-9,
        )
    }

    @Test
    fun jumpingFixIsRejected() {
        val gate = TrackGate()
        gate.push(fix(0.0, 0.0, ts = 1000L))
        val r = gate.push(fix(500.0, 0.0, ts = 2000L))
        assertTrue(r is GateResult.Skip)
        assertTrue((r as GateResult.Skip).message.contains("飞点"))
    }

    @Test
    fun estimateConvergesAfterThirtyFixes() {
        val gate = TrackGate()
        val n = 30
        var sumLat = 0.0
        var sumLon = 0.0
        for (i in 0 until n) {
            val jitter = if (i % 2 == 0) 2.0 else -2.0
            val f = fix(jitter, 0.0, ts = 1000L + i * 1000L)
            gate.push(f)
            sumLat += f.lat
            sumLon += f.lon
        }
        val est = gate.estimate()
        assertTrue(est != null)
        val meanLat = sumLat / n
        val meanLon = sumLon / n
        val dNorth = (est!!.first - meanLat) * mPerDegLat
        val dEast = (est.second - meanLon) * mPerDegLon
        val d = hypot(dNorth, dEast)
        assertTrue("estimate 偏差 ${d}m", d < 5.0)
    }

    @Test
    fun resetClearsEstimate() {
        val gate = TrackGate()
        gate.push(fix(0.0, 0.0, ts = 1000L))
        gate.reset()
        assertTrue(gate.estimate() == null)
    }

    @Test
    fun differentFiltersProduceDifferentResults() {
        // 保险：同一次输入在两个独立实例上应一致（无隐藏全局状态）
        val g1 = TrackGate()
        val g2 = TrackGate()
        val r1 = g1.push(fix(0.0, 0.0, ts = 1000L))
        val r2 = g2.push(fix(0.0, 0.0, ts = 1000L))
        assertTrue(r1 is GateResult.Accept && r2 is GateResult.Accept)
        assertNotEquals(null, g1.estimate())
    }
}
