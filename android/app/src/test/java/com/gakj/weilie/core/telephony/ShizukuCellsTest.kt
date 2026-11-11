package com.gakj.weilie.core.telephony

import com.gakj.weilie.core.model.Rat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `dumpsys telephony.registry` 解析测试。
 *
 * 样本取自 Android 14 的典型输出（LTE 驻留 + LTE 邻区 + NR 邻区），
 * 这些字符串就是 Shizuku 授权后 app 实际拿到的东西，解析错了邻区就会静默消失。
 */
class ShizukuCellsTest {

    private val sample = """
        mCellInfo=[CellInfoLte:{mRegistered=YES mTimeStamp=123456789
          CellIdentityLte:{ mCi=240606319 mPci=141 mTac=4308 mEarfcn=1650 mBandwidth=20000 mMcc=460 mMnc=01 mAlphaLong=CHN-UNICOM mAlphaShort=CU }
          CellSignalStrengthLte: rssi=-55 rsrp=-84 rsrq=-10 rssnr=20 cqi=2147483647 ta=3}, CellInfoLte:{mRegistered=NO
          mTimeStamp=123456790 CellIdentityLte:{ mCi=240606320 mPci=142 mTac=4308 mEarfcn=1650 mBandwidth=20000 mMcc=460 mMnc=01 }
          CellSignalStrengthLte: rssi=-80 rsrp=-105 rsrq=-14 rssnr=3 cqi=2147483647 ta=2147483647}, CellInfoNr:{mRegistered=NO
          mTimeStamp=123456791 CellIdentityNr:{ mPci=23 mTac=4308 mNrArfcn=504990 mMcc=460 mMnc=11 mNci=5677244420 }
          CellSignalStrengthNr:{ ssRsrp=-95 ssRsrq=-12 ssSinr=5 }}]
    """.trimIndent()


    @Test
    fun parses_serving_lte_cell() {
        val cells = ShizukuCells.parseDump(sample)
        assertEquals(3, cells.size)
        val serving = cells.first { it.registered }
        assertEquals(Rat.LTE, serving.rat)
        assertEquals(240606319L, serving.ci)
        assertEquals(141, serving.pci)
        assertEquals(1650, serving.arfcn)
        assertEquals(4308L, serving.tac)
        assertEquals("460", serving.mcc)
        assertEquals("01", serving.mnc)
        assertEquals(-84, serving.rsrp)
        assertEquals(-10, serving.rsrq)
        assertEquals(20, serving.sinr)
        // 1650 → B3
        assertEquals(3, serving.identity().band)
    }

    @Test
    fun parses_lte_neighbor_with_signal() {
        val cells = ShizukuCells.parseDump(sample)
        val neighbor = cells.first { it.rat == Rat.LTE && !it.registered }
        assertEquals(240606320L, neighbor.ci)
        assertEquals(142, neighbor.pci)
        assertEquals(-105, neighbor.rsrp)
        // Int.MAX_VALUE 的 ta 不影响解析
        assertTrue(neighbor.ci != null)
    }

    @Test
    fun parses_nr_neighbor_nci_and_band() {
        val cells = ShizukuCells.parseDump(sample)
        val nr = cells.first { it.rat == Rat.NR }
        assertEquals(5677244420L, nr.ci)
        assertEquals(23, nr.pci)
        assertEquals(504990, nr.arfcn)
        assertEquals(41, nr.identity().band)
        assertEquals(-95, nr.rsrp)
        assertEquals(5, nr.sinr)
    }

    @Test
    fun missing_signal_values_become_null() {
        val text = """
            mCellInfo=[CellInfoLte:{mRegistered=NO CellIdentityLte:{ mCi=100 mPci=1 mEarfcn=1300 }
              CellSignalStrengthLte: rssi=2147483647 rsrp=2147483647 rsrq=2147483647 rssnr=2147483647 ta=2147483647}]
        """.trimIndent()
        val cell = ShizukuCells.parseDump(text).single()
        assertNull(cell.rsrp)
        assertNull(cell.rsrq)
        assertNull(cell.sinr)
    }

    @Test
    fun to_pairs_dedupes_and_sorts_by_signal() {
        val cells = ShizukuCells.parseDump(sample)
        val pairs = ShizukuCells.toPairs(cells)
        assertEquals(3, pairs.size)
        // 按 RSRP 从强到弱：-84 > -95 > -105
        assertEquals(listOf(-84, -95, -105), pairs.map { it.second?.rsrp })
        assertEquals(240606319L, pairs[0].first.ci)
        assertEquals(Rat.NR, pairs[1].first.rat)
        // 注册态保留（服务小区可用于交叉校验）
        assertTrue(pairs[0].first.isRegistered)
        assertTrue(!pairs[1].first.isRegistered)
    }

    @Test
    fun duplicate_entries_keep_first() {
        val text = """
            mCellInfo=[CellInfoLte:{mRegistered=NO CellIdentityLte:{ mCi=100 mPci=1 mEarfcn=1300 }}, CellInfoLte:{mRegistered=NO CellIdentityLte:{ mCi=100 mPci=2 mEarfcn=1300 }}]
        """.trimIndent()
        val pairs = ShizukuCells.toPairs(ShizukuCells.parseDump(text))
        assertEquals(1, pairs.size)
        assertEquals(1, pairs[0].first.pci)
    }

    @Test
    fun empty_or_garbage_input_returns_empty() {
        assertTrue(ShizukuCells.parseDump("").isEmpty())
        assertTrue(ShizukuCells.parseDump("no telephony here").isEmpty())
        // 花括号没闭合时不能死循环，返回已解析出的部分/空
        assertTrue(ShizukuCells.parseDump("mCellInfo=[CellInfoLte:{mRegistered=YES").isEmpty())
    }

    @Test
    fun band_table_helpers_used_by_regcell() {
        assertNotNull(ShizukuCells.RegCell.bandOf(Rat.LTE, 1650))
        assertEquals(1, ShizukuCells.RegCell.bandOf(Rat.LTE, 100))
        assertEquals(78, ShizukuCells.RegCell.bandOf(Rat.NR, 630000))
        assertNull(ShizukuCells.RegCell.bandOf(Rat.GSM, 100))
        assertNull(ShizukuCells.RegCell.bandOf(Rat.LTE, null))
    }
}
