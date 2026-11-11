package com.gakj.weilie.core.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellTargetShortcutTest {

    private fun sim(
        rat: String = "LTE",
        cellId: Long = 47108L,
        carrier: String = "中国移动",
        band: String = "B1",
    ) = SimCell(
        slot = 0,
        carrier = carrier,
        rat = rat,
        band = band,
        arfcn = 100,
        pci = 12,
        cellId = cellId,
        rsrp = -90,
        sinr = 10,
    )

    @Test
    fun nrBecomesNci() {
        // 5677244420 = gNB 1386046 · CID 4
        assertEquals("NCI 5677244420", CellTargetShortcut.rawOf(sim(rat = "NR", cellId = 5677244420L, band = "N41")))
    }

    @Test
    fun lteBecomesEci() {
        assertEquals("ECI 47108", CellTargetShortcut.rawOf(sim(rat = "LTE", cellId = 47108L)))
    }

    @Test
    fun legacyRatIsNotSupported() {
        // 2G/3G 供应商不给 LAC，拼不出合规参数，宁可不填
        assertNull(CellTargetShortcut.rawOf(sim(rat = "WCDMA", cellId = 12345L)))
        assertNull(CellTargetShortcut.rawOf(sim(rat = "GSM", cellId = 12345L)))
    }

    @Test
    fun unknownCellIdIsRejected() {
        assertNull(CellTargetShortcut.rawOf(sim(rat = "NR", cellId = -1L)))
        assertNull(CellTargetShortcut.rawOf(sim(rat = "LTE", cellId = 0L)))
    }

    @Test
    fun hintShowsCarrierRatAndDecoder() {
        assertEquals("中国移动 · NR N41 · 1386046-4", CellTargetShortcut.hintOf(sim(rat = "NR", cellId = 5677244420L, band = "N41")))
        assertEquals("中国移动 · LTE B1 · 184-4", CellTargetShortcut.hintOf(sim(rat = "LTE", cellId = 47108L)))
    }

    @Test
    fun pickPrefersNrThenLte() {
        val nr = sim(rat = "NR", cellId = 5677244420L, band = "N41")
        val lte = sim(rat = "LTE", cellId = 47108L)
        assertEquals(nr, CellTargetShortcut.pick(listOf(lte, nr)))
        assertEquals(lte, CellTargetShortcut.pick(listOf(lte)))
        assertNull(CellTargetShortcut.pick(listOf(sim(rat = "GSM", cellId = 12L))))
        assertNull(CellTargetShortcut.pick(emptyList()))
    }

    @Test
    fun pickFollowsDataCardServingCell() {
        // 双卡：卡1 是 NR 驻留，但数据卡（卡2）注册的是 LTE → 应跟数据卡走
        val nrOnSim1 = sim(rat = "NR", cellId = 5677244420L, band = "N41", carrier = "中国移动")
        val lteOnSim2 = sim(rat = "LTE", cellId = 433254L, carrier = "中国联通").copy(slot = 1)
        assertEquals(lteOnSim2, CellTargetShortcut.pick(listOf(nrOnSim1, lteOnSim2), servingCi = 433254L))
        // 匹配不到时按 NR 优先兜底
        assertEquals(nrOnSim1, CellTargetShortcut.pick(listOf(nrOnSim1, lteOnSim2), servingCi = 999L))
    }
}
