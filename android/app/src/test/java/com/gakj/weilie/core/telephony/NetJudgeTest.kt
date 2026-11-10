package com.gakj.weilie.core.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组网形态与运营商判定（纯 JVM 逻辑，不依赖 Android 框架）。
 *
 * 这些用例对应真机上的两个 P0：SA/NSA 误判（移动卡 NR_SA 却渲染 LTE 锚点）、
 * 双卡串台（联通 LTE 被当成移动卡的锚点）。
 */
class NetJudgeTest {

    @Test
    fun nrAndLteRegisteredIsNsa() {
        assertEquals(
            NetMode.NSA,
            NetJudge.classify(registeredNr = true, registeredLte = true, registeredAny = true, nrNeighborCount = 1, dataRatIsNr = true),
        )
        assertTrue(NetMode.NSA.is5g)
    }

    @Test
    fun onlyNrRegisteredIsSa() {
        assertEquals(
            NetMode.SA,
            NetJudge.classify(registeredNr = true, registeredLte = false, registeredAny = true, nrNeighborCount = 3, dataRatIsNr = true),
        )
        // SA 也必须算 5G，但 UI 不允许出现锚点
        assertTrue(NetMode.SA.is5g)
    }

    @Test
    fun lteWithNrNeighborIsNsa() {
        assertEquals(
            NetMode.NSA,
            NetJudge.classify(registeredNr = false, registeredLte = true, registeredAny = true, nrNeighborCount = 2, dataRatIsNr = false),
        )
    }

    @Test
    fun lteWithNrDataNetworkIsNsa() {
        assertEquals(
            NetMode.NSA,
            NetJudge.classify(registeredNr = false, registeredLte = true, registeredAny = true, nrNeighborCount = 0, dataRatIsNr = true),
        )
    }

    @Test
    fun plainLteIsLte() {
        val mode = NetJudge.classify(registeredNr = false, registeredLte = true, registeredAny = true, nrNeighborCount = 0, dataRatIsNr = false)
        assertEquals(NetMode.LTE, mode)
        assertFalse(mode.is5g)
    }

    @Test
    fun legacyAndUnknown() {
        assertEquals(
            NetMode.LEGACY,
            NetJudge.classify(registeredNr = false, registeredLte = false, registeredAny = true, nrNeighborCount = 0, dataRatIsNr = false),
        )
        assertEquals(
            NetMode.UNKNOWN,
            NetJudge.classify(registeredNr = false, registeredLte = false, registeredAny = false, nrNeighborCount = 0, dataRatIsNr = false),
        )
    }

    @Test
    fun carrierKeys() {
        assertEquals("CMCC", NetJudge.carrierKey("中国移动"))
        assertEquals("CUCC", NetJudge.carrierKey("中国联通"))
        assertEquals("CTCC", NetJudge.carrierKey("中国电信"))
        assertEquals("CBN", NetJudge.carrierKey("中国广电"))
        assertEquals("RAIL", NetJudge.carrierKey("中国铁路"))
        assertNull(NetJudge.carrierKey("T-Mobile"))
    }

    @Test
    fun operatorKeys() {
        // 移动卡自己上报 460-07，小区广播常见 460-00：必须都归到移动
        assertEquals("CMCC", NetJudge.operatorKey("460", "07"))
        assertEquals("CMCC", NetJudge.operatorKey("460", "0"))
        assertEquals("CMCC", NetJudge.operatorKey("460", "00"))
        assertEquals("CUCC", NetJudge.operatorKey("460", "01"))
        assertEquals("CUCC", NetJudge.operatorKey("460", "09"))
        assertEquals("CTCC", NetJudge.operatorKey("460", "11"))
        assertEquals("CBN", NetJudge.operatorKey("460", "15"))
        assertEquals("RAIL", NetJudge.operatorKey("460", "20"))
        assertNull(NetJudge.operatorKey("460", "99"))
        assertNull(NetJudge.operatorKey("310", "260"))
        assertNull(NetJudge.operatorKey("460", null))
    }

    @Test
    fun belongsToFiltersOtherCarrierCells() {
        // 移动卡的列表里混进 460-01 联通小区 → 必须剔除
        assertTrue(NetJudge.belongsTo("460", "00", "中国移动"))
        assertTrue(NetJudge.belongsTo("460", "07", "中国移动"))
        assertFalse(NetJudge.belongsTo("460", "01", "中国移动"))
        assertFalse(NetJudge.belongsTo("460", "00", "中国联通"))
    }

    @Test
    fun unknownCarrierOrPlmnKeepsCell() {
        // 漫游 / MVNO：认不出来就别乱删，宁可多显示
        assertTrue(NetJudge.belongsTo("234", "10", "中国移动"))
        assertTrue(NetJudge.belongsTo("460", "00", "T-Mobile"))
    }
}
