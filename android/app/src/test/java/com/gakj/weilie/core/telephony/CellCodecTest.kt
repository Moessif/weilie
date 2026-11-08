package com.gakj.weilie.core.telephony

import com.gakj.weilie.core.model.CellIdentity
import com.gakj.weilie.core.model.Rat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CellCodec 纯函数验证（JVM 单测，无需真机）。
 * - NCI 36bit = gNB + 12bit CellID：5677244420 → gNB 1386046 / CID 4
 * - ECI 28bit = eNB + 8bit CellID：123456789 → eNB 482253 / CID 21
 */
class CellCodecTest {

    @Test
    fun `decodeNci matches real device nci`() {
        val (gnb, cid) = CellCodec.decodeNci(5677244420L)
        println("[verify] decodeNci(5677244420) = ($gnb, $cid) hex=${CellCodec.hexOf(5677244420L)}")
        assertEquals(1386046L, gnb)
        assertEquals(4, cid)
        // 反算回原值：gNB * 4096 + CID
        assertEquals(5677244420L, gnb * 4096 + cid)
    }

    @Test
    fun `decodeEci splits 28bit eci`() {
        val (enb, cid) = CellCodec.decodeEci(123456789L)
        println("[verify] decodeEci(123456789) = ($enb, $cid) hex=${CellCodec.hexOf(123456789L)}")
        assertEquals(482253L, enb)
        assertEquals(21, cid)
        assertEquals(123456789L, enb * 256 + cid)
    }

    @Test
    fun `formatCellId uses habitual notation`() {
        val nr = CellCodec.formatCellId(Rat.NR, 5677244420L)
        val lte = CellCodec.formatCellId(Rat.LTE, 123456789L)
        println("[verify] formatCellId(NR, 5677244420) = $nr")
        println("[verify] formatCellId(LTE, 123456789) = $lte")
        assertEquals("5677244420(gNB 1386046-4)", nr)
        assertEquals("123456789(eNB 482253-21)", lte)
        assertEquals("12345", CellCodec.formatCellId(Rat.GSM, 12345L))
        assertEquals("--", CellCodec.formatCellId(Rat.NR, null))
    }

    @Test
    fun `formatCellBreakdown matches expert panel line`() {
        val nr = CellCodec.formatCellBreakdown(Rat.NR, 5677244420L)
        val lte = CellCodec.formatCellBreakdown(Rat.LTE, 123456789L)
        println("[verify] formatCellBreakdown(NR) = $nr")
        println("[verify] formatCellBreakdown(LTE) = $lte")
        assertEquals("NCI 5677244420 · gNB 1386046 · CID 4 · 0x15263E004", nr)
        assertEquals("ECI 123456789 · eNB 482253 · CID 21 · 0x75BCD15", lte)
    }

    @Test
    fun `parseCellInput four formats agree on nci`() {
        val inputs = listOf(
            "5677244420",
            "5677244420(1386046-4)",
            "1386046-4",
            "gnb:1386046-4", // 第四种：gNB-CID 的显式前缀写法
        )
        for (raw in inputs) {
            val m = CellCodec.parseCellInput(raw)
            println("[verify] parseCellInput(\"$raw\") = nci=${m?.nci} eci=${m?.eci} raw=${m?.raw}")
            assertNotNull("应能识别：$raw", m)
            assertEquals(5677244420L, m!!.nci)
            assertNull(m.eci)
        }
        // 任务里的 "eci:123456789" 属于 4G 形态，按 eci 解出（与 NCI 用例互补）
        val eci = CellCodec.parseCellInput("eci:123456789")
        println("[verify] parseCellInput(\"eci:123456789\") = nci=${eci?.nci} eci=${eci?.eci}")
        assertEquals(123456789L, eci?.eci)
        assertNull(eci?.nci)
    }

    @Test
    fun `parseCellInput tolerates labels spaces fullwidth and hex`() {
        val cases = mapOf(
            "5677244420（１３８６０４６－４）" to 5677244420L, // 中文括号 + 全角数字
            "  5677244420 ( 1386046 - 4 ) " to 5677244420L,
            "NCI 5677244420(1386046-4)" to 5677244420L,
            "nci:5677244420" to 5677244420L,
            "0x15263E004" to 5677244420L,
            "15263E004H" to 5677244420L,
        )
        for ((raw, expect) in cases) {
            val m = CellCodec.parseCellInput(raw)
            println("[verify] parseCellInput(\"$raw\") → nci=${m?.nci}")
            assertEquals("输入格式：$raw", expect, m?.nci)
        }
        assertEquals(
            123456789L,
            CellCodec.parseCellInput("enb:482253-21")?.eci,
        )
        println("[verify] parseCellInput(\"enb:482253-21\") = eci=${CellCodec.parseCellInput("enb:482253-21")?.eci}")
    }

    @Test
    fun `parseCellInput rejects nonsense`() {
        assertNull(CellCodec.parseCellInput(""))
        assertNull(CellCodec.parseCellInput("   "))
        assertNull(CellCodec.parseCellInput("garbage"))
        assertNull(CellCodec.parseCellInput("abc def g"))
        assertNull(CellCodec.parseCellInput("NR900")) // 前缀后必须有分隔符
        println("[verify] nonsense inputs → null ✓（含 \"garbage\"）")
    }

    @Test
    fun `cellIdentity display uses habitual id form`() {
        val nr = CellIdentity(rat = Rat.NR, mcc = "460", mnc = "01", lacOrTac = 12345L, ci = 5677244420L, pci = 231)
        val lte = CellIdentity(rat = Rat.LTE, mcc = "460", mnc = "00", lacOrTac = 12345L, ci = 123456789L, pci = 231)
        println("[verify] CellIdentity.display() NR = ${nr.display()}")
        println("[verify] CellIdentity.display() LTE = ${lte.display()}")
        assertTrue(nr.display().contains("5677244420(gNB 1386046-4)"))
        assertTrue(nr.display().contains("0x15263E004"))
        assertTrue(lte.display().contains("123456789(eNB 482253-21)"))
    }

    @Test
    fun `displayRat reports NSA from anchor`() {
        val nsa = CellCodec.displayRat(Rat.LTE, Rat.LTE, 2)
        val sa = CellCodec.displayRat(Rat.NR, Rat.NR, 0)
        val lte = CellCodec.displayRat(Rat.LTE, null, 0)
        println("[verify] displayRat(LTE + 2 NR neigh) = $nsa")
        println("[verify] displayRat(NR) = $sa")
        println("[verify] displayRat(LTE only) = $lte")
        assertTrue(nsa.contains("NSA"))
        assertTrue(sa.contains("SA"))
        assertEquals("4G LTE", lte)
    }
}
