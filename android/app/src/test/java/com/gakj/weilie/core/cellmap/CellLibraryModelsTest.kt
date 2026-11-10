package com.gakj.weilie.core.cellmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CellLibraryModelsTest {

    private fun site(
        mcc: Int = 460,
        mnc: Int = 0,
        lac: Int = 4213,
        cellid: Long = 18712839,
        rat: String = "LTE",
        lat: Double = 39.9011,
        lon: Double = 116.3914,
        samples: Int = 3,
        rangeM: Int = 900,
    ) = CellSite(mcc, mnc, lac, cellid, rat, lat, lon, rangeM, samples, 0L)

    @Test
    fun keyIsStableAndUnique() {
        assertEquals("460-0-4213-18712839", site().key)
        assertEquals("460-1-6665-110181241", site(mnc = 1, lac = 6665, cellid = 110181241L).key)
    }

    @Test
    fun operatorNameCoversThreeCarriers() {
        assertEquals("中国移动", site(mnc = 0).operatorName)
        assertEquals("中国移动", site(mnc = 8).operatorName)
        assertEquals("中国联通", site(mnc = 1).operatorName)
        assertEquals("中国电信", site(mnc = 11).operatorName)
        assertEquals("中国广电", site(mnc = 15).operatorName)
    }

    @Test
    fun operatorNameFallsBackForForeignMcc() {
        val s = site(mcc = 310, mnc = 260)
        assertEquals("MCC 310 / MNC 260", s.operatorName)
    }

    @Test
    fun ratLabelIsHumanReadable() {
        assertEquals("4G LTE", site(rat = "LTE").ratLabel)
        assertEquals("5G NR", site(rat = "NR").ratLabel)
        assertEquals("2G GSM", site(rat = "GSM").ratLabel)
        assertEquals("3G WCDMA", site(rat = "UMTS").ratLabel)
        assertEquals("制式未知", site(rat = "").ratLabel)
    }

    @Test
    fun titleAndCellTextCompose() {
        val s = site()
        assertEquals("中国移动 · 4G LTE", s.title)
        assertEquals("460-0 · LAC/TAC 4213 · CID 18712839", s.cellText)
        assertEquals("0x11D8907", s.hexCi)
    }

    @Test
    fun distanceMatchesKnownOffset() {
        // 纬度差 0.009 度 ≈ 1001.9 m
        val d = site(lat = 39.9101, lon = 116.3914).distanceM(39.9011, 116.3914)
        assertTrue("应约 1002m，实际 $d", d in 990.0..1010.0)
        // 经度差 0.01 度在 39.9 纬度 ≈ 855 m
        val d2 = site(lat = 39.9011, lon = 116.4014).distanceM(39.9011, 116.3914)
        assertTrue("应约 855m，实际 $d2", d2 in 840.0..870.0)
    }

    @Test
    fun invalidCoordinatesAreRejected() {
        assertFalse(site(lat = 0.0, lon = 0.0).isValid())
        assertFalse(site(lat = Double.NaN, lon = 1.0).isValid())
        assertFalse(site(lat = 200.0, lon = 1.0).isValid())
        assertTrue(site().isValid())
    }

    @Test
    fun accuracyTextReflectsSampleCount() {
        assertTrue(site(samples = 0).accuracyText.contains("精度未知"))
        assertTrue(site(samples = 2).accuracyText.contains("精度有限"))
        assertTrue(site(samples = 12).accuracyText.contains("12 次上报"))
    }

    @Test
    fun nearestPicksClosestSite() {
        val far = site(cellid = 1, lat = 39.9500)
        val near = site(cellid = 2, lat = 39.9015)
        val picked = CellLibraryClient.nearest(listOf(far, near), 39.9011, 116.3914)
        assertEquals(2L, picked?.cellid)
    }

    @Test
    fun formatDistanceSwitchesUnit() {
        assertEquals("320 m", CellLibraryClient.formatDistance(320.4))
        assertEquals("1.4 km", CellLibraryClient.formatDistance(1400.0))
        assertEquals("—", CellLibraryClient.formatDistance(Double.NaN))
    }

    @Test
    fun attributionIsPresent() {
        assertTrue(CellSite.SOURCE_ATTRIBUTION.contains("CC BY-SA 4.0"))
        assertTrue(CellSite.SOURCE_ATTRIBUTION.contains("opencellid.org"))
    }
}
