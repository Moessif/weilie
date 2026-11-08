package com.gakj.weilie.core.parsing

import com.gakj.weilie.core.model.TargetFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskInputParserTest {

    @Test
    fun `parses labeled ECI`() {
        val r = TaskInputParser.parse("ECI 123456789")
        assertNull(r.error)
        val t = r.target!!
        assertEquals(TargetFormat.ECI, t.format)
        assertEquals(123456789L, t.eci)
    }

    @Test
    fun `parses labeled NCI long value`() {
        val r = TaskInputParser.parse("NCI 460001234567890")
        val t = r.target!!
        assertEquals(TargetFormat.NCI, t.format)
        assertEquals(460001234567890L, t.nci)
        assertTrue(t.warnings.isNotEmpty()) // 超 36bit 提醒
    }

    @Test
    fun `parses LAC-CI dash form`() {
        val r = TaskInputParser.parse("12345-67890")
        val t = r.target!!
        assertEquals(TargetFormat.LAC_CI, t.format)
        assertEquals(12345L, t.lac)
        assertEquals(67890L, t.cid)
    }

    @Test
    fun `parses LAC CI labeled pair`() {
        val r = TaskInputParser.parse("LAC:12345 CI:67890")
        val t = r.target!!
        assertEquals(TargetFormat.LAC_CI, t.format)
        assertEquals(12345L, t.lac)
        assertEquals(67890L, t.cid)
    }

    @Test
    fun `parses latlon with radius`() {
        val r = TaskInputParser.parse("31.2304, 121.4737 R=800")
        val t = r.target!!
        assertEquals(TargetFormat.LATLON_RADIUS, t.format)
        assertEquals(31.2304, t.centerLat!!, 1e-6)
        assertEquals(121.4737, t.centerLon!!, 1e-6)
        assertEquals(800.0, t.givenRadiusM!!, 0.001)
    }

    @Test
    fun `parses latlon with Chinese meter keyword`() {
        val r = TaskInputParser.parse("31.2304 121.4737 半径500米")
        val t = r.target!!
        assertEquals(TargetFormat.LATLON_RADIUS, t.format)
        assertEquals(500.0, t.givenRadiusM!!, 0.001)
    }

    @Test
    fun `latlon without radius gets warning`() {
        val r = TaskInputParser.parse("31.2304 121.4737")
        assertNotNull(r.target)
        assertTrue(r.target!!.warnings.any { it.contains("半径") })
    }

    @Test
    fun `parses hex with 0x prefix`() {
        val r = TaskInputParser.parse("0x75B8D95")
        val t = r.target!!
        assertEquals(TargetFormat.ECI, t.format)
        assertEquals("75B8D95".toLong(16), t.eci)
    }

    @Test
    fun `parses MCC-MNC form`() {
        val r = TaskInputParser.parse("460-00-123456789")
        val t = r.target!!
        assertEquals("460", t.mcc)
        assertEquals("00", t.mnc)
        assertEquals(123456789L, t.eci)
    }

    @Test
    fun `rejects empty and nonsense`() {
        assertNull(TaskInputParser.parse("").target)
        assertNull(TaskInputParser.parse("abc def g").target)
        assertNull(TaskInputParser.parse("999.999.9").target)
    }

    @Test
    fun `fullwidth input normalized via NFKC`() {
        val r = TaskInputParser.parse("ＥＣＩ：１２３４５６７８９")
        assertNotNull("全角应被 NFKC 归一化后识别", r.target)
        assertEquals(TargetFormat.ECI, r.target!!.format)
        assertEquals(123456789L, r.target!!.eci)
    }

    @Test
    fun `eci normalized string shows enodeb and cid split`() {
        val r = TaskInputParser.parse("123456789")
        val expectEneb = 123456789L ushr 8
        val expectCid = 123456789L and 0xFF
        assertTrue(r.normalized.contains("eNodeB $expectEneb"))
        assertTrue(r.normalized.contains("CID $expectCid"))
    }

    @Test
    fun `bare long number guessed as LTE ECI`() {
        val r = TaskInputParser.parse("217546496")
        val t = r.target!!
        assertEquals(TargetFormat.ECI, t.format)
        assertEquals(217546496L, t.eci)
    }
}
