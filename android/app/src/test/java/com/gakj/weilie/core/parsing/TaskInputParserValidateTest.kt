package com.gakj.weilie.core.parsing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 需求一：参数规格校验（不合规不可建任务） */
class TaskInputParserValidateTest {

    private fun ok(raw: String) = TaskInputParser.validate(raw).ok

    @Test
    fun emptyInputIsBlocked() {
        val v = TaskInputParser.validate("   ")
        assertFalse(v.ok)
        assertTrue(v.blocking.isNotEmpty())
    }

    @Test
    fun garbageIsBlocked() {
        val v = TaskInputParser.validate("这不是编号")
        assertFalse(v.ok)
        assertTrue(v.blocking.isNotEmpty())
    }

    @Test
    fun validEciPasses() {
        // 28bit ECI 上限 0xFFFFFFF = 268435455
        assertTrue(ok("240606319"))
        assertTrue(ok("268435455"))
    }

    @Test
    fun eciOverflowIsBlocked() {
        val v = TaskInputParser.validate("eci:268435456")
        assertFalse(v.ok)
        assertTrue(v.blocking.isNotEmpty())
    }

    @Test
    fun ltePairPasses() {
        assertTrue(ok("939868-111"))
    }

    @Test
    fun lacOutOfRangeIsBlocked() {
        val v = TaskInputParser.validate("LAC 70000 CI 1")
        assertFalse(v.ok)
        assertTrue(v.blocking.isNotEmpty())
    }

    @Test
    fun validLatLonPasses() {
        assertTrue(ok("39.9042,116.4074"))
        assertTrue(ok("39.9042,116.4074,800"))
    }

    @Test
    fun latLonZeroIsBlocked() {
        val v = TaskInputParser.validate("0,0")
        assertFalse(v.ok)
        assertTrue(v.blocking.isNotEmpty())
    }

    @Test
    fun latLonRadiusTooBigIsBlocked() {
        val v = TaskInputParser.validate("39.9042,116.4074,60000m")
        assertFalse(v.ok)
        assertTrue(v.blocking.isNotEmpty())
    }

    @Test
    fun nciBeyond36BitIsBlocked() {
        // 36bit NCI 上限 0xFFFFFFFFF = 68719476735
        val v = TaskInputParser.validate("68719476736")
        assertFalse(v.ok)
        assertTrue(v.blocking.isNotEmpty())
    }
}
