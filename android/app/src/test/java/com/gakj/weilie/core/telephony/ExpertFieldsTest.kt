package com.gakj.weilie.core.telephony

import com.gakj.weilie.core.model.WifiAp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * BUG-01 专家面板新增字段的纯函数单测：
 * 信号等级门限、从展示文本反提编号、切换轨迹行、WiFi OUI 厂商表。
 */
class ExpertFieldsTest {

    @Test
    fun signalLevelGradeUsesThresholds() {
        assertEquals("—", SignalLevel.grade(null))
        assertEquals("极佳", SignalLevel.grade(-70))
        assertEquals("良好", SignalLevel.grade(-90))
        assertEquals("一般", SignalLevel.grade(-100))
        assertEquals("较弱", SignalLevel.grade(-110))
        assertEquals("很弱", SignalLevel.grade(-120))
    }

    @Test
    fun cellNumberFromDisplayExtractsNumberAndBreakdown() {
        assertEquals(
            "5677244420(gNB 1386046-4)",
            CellCodec.cellNumberFromDisplay("NCI 5677244420(gNB 1386046-4)"),
        )
        assertEquals(
            "5677244420(gNB 1386046-4)",
            CellCodec.cellNumberFromDisplay(
                "NR(5G)·460-01·TAC 12345·NCI 5677244420(gNB 1386046-4) (0x15263E004)·PCI 231",
            ),
        )
        assertEquals(
            "123456789(eNB 482253-21)",
            CellCodec.cellNumberFromDisplay("ECI 123456789(eNB 482253-21)"),
        )
        assertEquals("5677244420", CellCodec.cellNumberFromDisplay("NCI 5677244420"))
        // 「PCI 231」里的 CI 不能被当成基站编号
        assertNull(CellCodec.cellNumberFromDisplay("PCI 231"))
        assertNull(CellCodec.cellNumberFromDisplay(null))
        assertNull(CellCodec.cellNumberFromDisplay(""))
    }

    @Test
    fun cellTraceLineFormatsTimeAndTransition() {
        val line = CellTrace.line(
            0L,
            null,
            "NR(5G)·460-01·TAC 12345·NCI 5677244420(gNB 1386046-4) (0x15263E004)·PCI 231",
            ZoneId.of("Asia/Shanghai"),
        )
        assertEquals("08:00:00  — → 5677244420(gNB 1386046-4)", line)
    }

    @Test
    fun wifiVendorLookupMapsOuiAndRejectsRandomMac() {
        assertEquals("树莓派", WifiVendor.lookup("B8:27:EB:12:34:56"))
        assertEquals("树莓派", WifiVendor.lookup("b8-27-eb-aa-bb-cc"))
        assertEquals("华为", WifiVendor.lookup("00:E0:FC:11:22:33"))
        assertEquals("TP-LINK", WifiVendor.lookup("50:C7:BF:00:11:22"))
        // 本地管理地址（随机 MAC）不查表
        assertNull(WifiVendor.lookup("02:11:22:33:44:55"))
        // 未收录 OUI
        assertNull(WifiVendor.lookup("04:1B:2C:3D:4E:5F"))
        assertNull(WifiVendor.lookup(null))
        assertNull(WifiVendor.lookup("zz"))
    }

    @Test
    fun wifiApVendorDefaultsToNull() {
        assertNull(WifiAp("a", "B8:27:EB:12:34:56", -50, 6).vendor)
        assertEquals(
            "树莓派",
            WifiAp("a", "B8:27:EB:12:34:56", -50, 6, vendor = "树莓派").vendor,
        )
    }
}
