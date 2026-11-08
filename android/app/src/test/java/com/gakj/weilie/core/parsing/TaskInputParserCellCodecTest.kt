package com.gakj.weilie.core.parsing

import com.gakj.weilie.core.model.Rat
import com.gakj.weilie.core.model.TargetFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标输入解析接入 CellCodec 后的回归/新增用例：
 * - 旧格式（纯数字 / ECI 标签 / LAC-CI / 十六进制）行为不变；
 * - 新格式（括号分解、gNB-CID、gnb:/enb: 前缀）能识别；
 * - 失败提示包含"无法识别的基站编号"。
 */
class TaskInputParserCellCodecTest {

    @Test
    fun `parses nci with parenthesized breakdown`() {
        val r = TaskInputParser.parse("5677244420(1386046-4)")
        println("[verify] parse(\"5677244420(1386046-4)\") → format=${r.target?.format} nci=${r.target?.nci} eci=${r.target?.eci} normalized=${r.normalized}")
        assertNull(r.error)
        assertEquals(TargetFormat.NCI, r.target!!.format)
        assertEquals(5677244420L, r.target!!.nci)
        assertNull(r.target!!.eci)
    }

    @Test
    fun `parses gnb-cid split form as nci`() {
        val r = TaskInputParser.parse("1386046-4")
        println("[verify] parse(\"1386046-4\") → format=${r.target?.format} nci=${r.target?.nci} normalized=${r.normalized}")
        assertNull(r.error)
        assertEquals(TargetFormat.NCI, r.target!!.format)
        assertEquals(5677244420L, r.target!!.nci)
    }

    @Test
    fun `parses prefixed gnb and enb forms`() {
        val g = TaskInputParser.parse("gnb:1386046-4")
        val e = TaskInputParser.parse("ENB:482253-21")
        println("[verify] parse(\"gnb:1386046-4\") → nci=${g.target?.nci} normalized=${g.normalized}")
        println("[verify] parse(\"ENB:482253-21\") → eci=${e.target?.eci} normalized=${e.normalized}")
        assertEquals(5677244420L, g.target!!.nci)
        assertEquals(123456789L, e.target!!.eci)
    }

    @Test
    fun `legacy formats keep working`() {
        val lacCi = TaskInputParser.parse("12345-67890")
        println("[verify] parse(\"12345-67890\") → format=${lacCi.target?.format} lac=${lacCi.target?.lac} cid=${lacCi.target?.cid}")
        assertEquals(TargetFormat.LAC_CI, lacCi.target!!.format)

        val eci = TaskInputParser.parse("eci:123456789")
        assertEquals(TargetFormat.ECI, eci.target!!.format)
        assertEquals(123456789L, eci.target!!.eci)

        val hex = TaskInputParser.parse("0x75BCD15")
        assertEquals(123456789L, hex.target!!.eci)
    }

    @Test
    fun `unrecognizable input gives explicit error`() {
        for (raw in listOf("garbage", "abc def g")) {
            val r = TaskInputParser.parse(raw)
            println("[verify] parse(\"$raw\") → error=${r.error}")
            assertNull(r.target)
            assertNotNull(r.error)
            assertTrue("错误提示需说明是无法识别的基站编号：${r.error}", r.error!!.contains("无法识别的基站编号"))
        }
    }

    @Test
    fun `nci target format from paren form keeps raw input`() {
        val raw = "5677244420（1386046-4）" // 中文括号/全角
        val r = TaskInputParser.parse(raw)
        println("[verify] parse(\"$raw\") → nci=${r.target?.nci} ratHint=${r.target?.ratHint}")
        assertEquals(5677244420L, r.target!!.nci)
        assertEquals(Rat.NR, r.target!!.ratHint)
        assertEquals(raw.trim(), r.target!!.rawInput)
    }
}
