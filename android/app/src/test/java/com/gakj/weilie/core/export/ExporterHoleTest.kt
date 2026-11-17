package com.gakj.weilie.core.export

import com.gakj.weilie.core.model.SampleRecord
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出带内洞与面积（v0.13.3）：
 * KML 必须写 innerBoundaryIs，GeoJSON 必须是多环 Polygon，且两者都带覆盖面积。
 */
class ExporterHoleTest {

    private val outer = listOf(
        39.9000 to 116.3000, 39.9000 to 116.3020, 39.9020 to 116.3020, 39.9020 to 116.3000,
    )
    private val hole = listOf(
        39.9005 to 116.3005, 39.9005 to 116.3015, 39.9015 to 116.3015, 39.9015 to 116.3005,
    )

    private fun sample(i: Int) = SampleRecord(
        tsEpochMs = 1_700_000_000_000L + i * 1000L,
        lat = 39.900 + i * 1e-4,
        lon = 116.300 + i * 1e-4,
        accuracyM = 8f,
        rsrp = -95,
        onTarget = true,
        targetSeen = true,
    )

    @Test
    fun kml_writes_inner_boundary_and_area() {
        val kml = KmlExporter.export(
            title = "洞测试",
            samples = listOf(sample(0), sample(1)),
            hullLatLon = outer,
            isolines = emptyList(),
            holesLatLon = listOf(hole),
            coverageAreaM2 = 12_000.0,
            hullAreaM2 = 18_000.0,
            engine = "轨迹缓冲",
        )

        assertTrue("缺少 innerBoundaryIs", kml.contains("<innerBoundaryIs>"))
        assertTrue("缺少外环", kml.contains("<outerBoundaryIs>"))
        assertTrue("描述缺少覆盖面积：$kml", kml.contains("覆盖面积 12000"))
        assertTrue("描述缺少外环面积", kml.contains("外环面积 18000"))
        assertTrue("描述缺少引擎", kml.contains("引擎 轨迹缓冲"))
    }

    @Test
    fun geojson_polygon_has_two_rings_and_area_properties() {
        val geo = GeoJsonExporter.export(
            samples = listOf(sample(0)),
            target = null,
            hullLatLon = outer,
            bufferedLatLon = null,
            isolines = emptyList(),
            holesLatLon = listOf(hole),
            coverageAreaM2 = 12_000.0,
            hullAreaM2 = 18_000.0,
            engine = "轨迹缓冲",
        )

        val root = org.json.JSONObject(geo)
        val features = root.getJSONArray("features")
        var hullFeature: org.json.JSONObject? = null
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            if (f.getJSONObject("properties").optString("kind") == "fitted_hull") hullFeature = f
        }
        assertTrue("缺少 fitted_hull 要素", hullFeature != null)

        val rings = hullFeature!!.getJSONObject("geometry").getJSONArray("coordinates")
        assertTrue("应为外环 + 1 个洞，实际 ${rings.length()}", rings.length() == 2)

        val props = hullFeature.getJSONObject("properties")
        assertTrue("缺少 area_m2", props.has("area_m2"))
        assertTrue("缺少 hull_area_m2", props.has("hull_area_m2"))
        assertTrue("缺少 holes", props.has("holes"))
        assertTrue("缺少 engine", props.has("engine"))
    }

    @Test
    fun no_holes_still_exports_single_ring() {
        val geo = GeoJsonExporter.export(
            samples = listOf(sample(0)),
            target = null,
            hullLatLon = outer,
            bufferedLatLon = null,
            isolines = emptyList(),
        )
        val root = org.json.JSONObject(geo)
        val features = root.getJSONArray("features")
        var hullFeature: org.json.JSONObject? = null
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            if (f.getJSONObject("properties").optString("kind") == "fitted_hull") hullFeature = f
        }
        val rings = hullFeature!!.getJSONObject("geometry").getJSONArray("coordinates")
        assertTrue("无洞时应为单环", rings.length() == 1)
    }
}
