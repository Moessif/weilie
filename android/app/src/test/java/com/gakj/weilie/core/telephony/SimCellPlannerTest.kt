package com.gakj.weilie.core.telephony

import com.gakj.weilie.core.model.Rat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双卡驻留小区选择器（纯函数）与水印文案的 JVM 单测。
 *
 * 不碰 android.telephony 运行时：用例只构造 [NrDesc]/[LteDesc]/[SimInput]，
 * 真机上「每张卡能读到什么」由 AndroidCellReader 负责，这里验证「读到了怎么挑、怎么显示」。
 */
class SimCellPlannerTest {

    private fun nr(
        pci: Int = 231,
        arfcn: Int = 504990,
        nci: Long = 5677244420L,
        rsrp: Int = -88,
        sinr: Int = 18,
        registered: Boolean = true,
    ) = NrDesc(pci, arfcn, nci, rsrp, sinr, "460", "00", registered)

    private fun lte(
        pci: Int = 321,
        earfcn: Int = 1506,
        eci: Long = 939868L,
        rsrp: Int = -95,
        sinr: Int = 7,
        registered: Boolean = true,
    ) = LteDesc(pci, earfcn, eci, rsrp, sinr, "460", "01", registered)

    @Test
    fun bandTableMapsChinaBands() {
        assertEquals("N41", BandTable.nrBand(504990))
        assertEquals("B3", BandTable.lteBand(1506))
        assertEquals("-", BandTable.nrBand(1))
        assertEquals("-", BandTable.lteBand(999999)) // 超出国内常用频段表
    }

    @Test
    fun simCellIdTextUsesGnbCidAndEnbCid() {
        val nrCell = SimCell(0, "中国移动", "NR", "N41", 504990, 231, 5677244420L, -88, 18)
        assertEquals("1386046-4", nrCell.cellIdText)

        // 939868 = 3671 * 256 + 92，故 eNB-CID 为 3671-92
        val lteCell = SimCell(1, "中国联通", "LTE", "B3", 1506, 321, 939868L, -95, 7)
        assertEquals("3671-92", lteCell.cellIdText)

        val unknown = SimCell(0, "x", "NR", "-", -1, -1, -1L, SimCell.UNKNOWN, SimCell.UNKNOWN)
        assertEquals("--", unknown.cellIdText)
    }

    @Test
    fun registeredNrIsPreferredOverLte() {
        val rows = planSimCells(
            SimInput(
                0, "中国移动", "46000",
                nrCells = listOf(nr(rsrp = -88), nr(pci = 232, rsrp = -105)),
                lteCells = listOf(lte()),
            )
        )
        assertEquals(1, rows.size)
        assertEquals("NR", rows[0].rat)
        assertEquals(231, rows[0].pci) // 取 SS-RSRP 最强的一条
        assertEquals("N41", rows[0].band)
        assertEquals("46000", rows[0].mccMnc)
    }

    @Test
    fun nsaProducesNrRowBeforeLteAnchorOnSameSlot() {
        // 移动卡：只注册在 LTE 上，但扫到 NR 邻区（NSA 辅载波）→ 两行同 slot，NR 在前
        val rows = planSimCells(
            SimInput(
                1, "中国移动", "46000",
                nrCells = listOf(nr(registered = false, rsrp = -100)),
                lteCells = listOf(lte(registered = true)),
            )
        )
        assertEquals(2, rows.size)
        assertEquals(listOf("NR", "LTE"), rows.map { it.rat })
        assertEquals(listOf(1, 1), rows.map { it.slot })
        assertEquals(5677244420L, rows[0].cellId)
        assertEquals(939868L, rows[1].cellId)
    }

    @Test
    fun lteOnlyGivesSingleRow() {
        val rows = planSimCells(SimInput(1, "中国联通", "46001", lteCells = listOf(lte())))
        assertEquals(1, rows.size)
        assertEquals("LTE", rows[0].rat)
        assertEquals("B3", rows[0].band)
        assertEquals("3671-92", rows[0].cellIdText)
    }

    @Test
    fun noRegisteredCellGivesNoRow() {
        val rows = planSimCells(
            SimInput(
                1, "中国联通", "46001",
                nrCells = listOf(nr(registered = false)),
                lteCells = listOf(lte(registered = false)),
            )
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun invalidValuesAreSanitized() {
        val rows = planSimCells(
            SimInput(
                0, "中国移动", "",
                nrCells = listOf(
                    NrDesc(
                        pci = Int.MAX_VALUE,
                        arfcn = Int.MAX_VALUE,
                        nci = 0L,
                        ssRsrp = Int.MAX_VALUE,
                        ssSinr = Int.MAX_VALUE,
                        isRegistered = true,
                    )
                ),
            )
        )
        assertEquals(1, rows.size)
        assertEquals(-1, rows[0].pci)
        assertEquals(-1, rows[0].arfcn)
        assertEquals(-1L, rows[0].cellId)
        assertEquals(SimCell.UNKNOWN, rows[0].rsrp)
        assertEquals(SimCell.UNKNOWN, rows[0].sinr)
        assertEquals("--", rows[0].rsrpText)
        assertEquals("--", rows[0].sinrText)
        assertEquals("--", rows[0].cellIdText)
    }

    @Test
    fun planAllSimsSortsBySlotAndKeepsNsaRowsAdjacent() {
        val rows = planAllSims(
            listOf(
                // 联通卡（slot 1）：只有 4G（小区与移动卡不同，避免被双卡去重）
                SimInput(1, "中国联通", "46001", lteCells = listOf(lte(pci = 322, eci = 939869L, earfcn = 1651))),
                // 移动卡（slot 0）：4G 锚点 + NR 邻区 → NSA 两行
                SimInput(0, "中国移动", "46000", nrCells = listOf(nr(registered = false)), lteCells = listOf(lte())),
            )
        )
        assertEquals(listOf(0, 0, 1), rows.map { it.slot })
        assertEquals(listOf("NR", "LTE", "LTE"), rows.map { it.rat })
    }

    @Test
    fun doubleSimSameFallbackCellShowsOnlyDataSubRow() {
        // 真机双卡：副卡拿不到自己的小区，两张卡读到同一份默认数据卡数据 → 只保留数据卡一行
        val sameNr = listOf(nr())
        val rows = planAllSims(
            listOf(
                SimInput(0, "中国移动", "46000", nrCells = sameNr, isDataSub = true),
                SimInput(1, "中国联通", "46001", nrCells = sameNr, isDataSub = false),
            )
        )
        assertEquals(1, rows.size)
        assertEquals(0, rows[0].slot)
        assertEquals("中国移动", rows[0].carrier)
        assertEquals(5677244420L, rows[0].cellId)
    }

    @Test
    fun doubleSimSameFallbackKeepsDataSubEvenWhenSlotHigher() {
        // 默认数据卡在卡槽 2：重复数据也只能保留数据卡这一行
        val sameLte = listOf(lte())
        val rows = planAllSims(
            listOf(
                SimInput(0, "中国移动", "46000", lteCells = sameLte, isDataSub = false),
                SimInput(1, "中国联通", "46001", lteCells = sameLte, isDataSub = true),
            )
        )
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].slot)
        assertEquals("中国联通", rows[0].carrier)
    }

    @Test
    fun doubleSimWithOwnCellsKeepsBothRows() {
        // 两张卡各有真实且不同的小区：照常各显示一行
        val rows = planAllSims(
            listOf(
                SimInput(0, "中国移动", "46000", nrCells = listOf(nr())),
                SimInput(1, "中国联通", "46001", nrCells = listOf(nr(pci = 232, arfcn = 504992, nci = 5677244421L, rsrp = -99))),
            )
        )
        assertEquals(2, rows.size)
        assertEquals(listOf(0, 1), rows.map { it.slot })
        assertEquals(listOf("中国移动", "中国联通"), rows.map { it.carrier })
    }

    @Test
    fun watermarkFlatLinesTwoSimsIsSixRowsPlusFooter() {
        val mobile = SimCell(0, "中国移动", "NR", "N41", 504990, 231, 5677244420L, -88, 18)
        val unicom = SimCell(1, "中国联通", "LTE", "B3", 1506, 321, 939868L, -95, 7)
        val lines = CellWatermark.flatLines(listOf(mobile, unicom), 39.9042, 116.4074, 0L, "北京")

        assertEquals(10, lines.size) // 6 行小区 + 4 行 footer
        assertEquals("中国移动 NR N41    中国联通 LTE B3", lines[0])
        assertEquals("NRARFCN:504990    EARFCN:1506", lines[1])
        assertEquals("NR-PCI:231    PCI:321", lines[2])
        assertEquals("NCI:1386046-4    ECI:3671-92", lines[3])
        assertEquals("SS-RSRP:-88    RSRP:-95", lines[4])
        assertEquals("SS-SINR:18    SINR:7", lines[5])
        assertEquals("北京", lines[6])
        assertEquals("LAT:39.904200", lines[7])
        assertEquals("LON:116.407400", lines[8])
        assertTrue(
            "时间行应为 yyyy.MM.dd HH:mm:ss，实际 ${lines[9]}",
            Regex("""\d{4}\.\d{2}\.\d{2} \d{2}:\d{2}:\d{2}""").matches(lines[9]),
        )
    }

    @Test
    fun watermarkWithoutSimsStillShowsFooter() {
        val lines = CellWatermark.flatLines(emptyList(), 0.0, 0.0, 0L)
        assertEquals(5, lines.size) // 1 行"无蜂窝数据" + 4 行 footer
        assertEquals("无蜂窝数据（未授权定位/电话状态，或飞行模式）", lines[0])
        assertEquals("位置", lines[1])
    }

    @Test
    fun lteOnlyWithDataRatNrIsMarkedAsNsa() {
        // ROM 把未注册 NR 小区过滤掉：CellInfo 只剩 LTE，但数据网仍是 NR → 标成 NR(NSA)
        val rows = planSimCells(
            SimInput(
                1, "中国联通", "46001",
                lteCells = listOf(lte()),
                dataRat = Rat.NR,
            )
        )
        assertEquals(1, rows.size)
        assertEquals("NR(NSA)", rows[0].rat) // 不冒充 NR 小区
        assertEquals("NR(NSA) B3", rows[0].ratBand)
        // CellWatermark 首行 = "carrier ratBand"，自动显示成「中国联通 NR(NSA) B3」
        assertEquals("中国联通 NR(NSA) B3", CellWatermark.flatLines(rows, 0.0, 0.0, 0L)[0])
        assertEquals(1506, rows[0].arfcn) // band/arfcn 保留 LTE 锚点
        assertEquals(939868L, rows[0].cellId) // 编号仍是 LTE 锚点 ECI，可读
        assertEquals("3671-92", rows[0].cellIdText)

        // 非默认数据卡不应用该兜底：dataNetworkType 只描述默认数据卡
        val other = planSimCells(
            SimInput(
                0, "中国移动", "46000",
                lteCells = listOf(lte()),
                dataRat = Rat.NR,
                isDataSub = false,
            )
        )
        assertEquals("LTE", other[0].rat)
    }

    @Test
    fun lteOnlyWithDataRatLteStaysLte() {
        val rows = planSimCells(
            SimInput(
                1, "中国联通", "46001",
                lteCells = listOf(lte()),
                dataRat = Rat.LTE,
            )
        )
        assertEquals(1, rows.size)
        assertEquals("LTE", rows[0].rat)
    }
}
