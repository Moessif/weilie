package com.gakj.weilie.server

import kotlin.test.Test
import kotlin.test.assertTrue

class ServerExportsTest {

    private fun room(): RoomState {
        val r = RoomState("r1", "测试队", "123456", 0)
        r.zones.add(
            WsMsg.Zone(
                id = "z1", by = "u1", kind = "POLYGON",
                polygon = listOf(
                    listOf(39.900, 116.300),
                    listOf(39.900, 116.301),
                    listOf(39.901, 116.301),
                    listOf(39.901, 116.300),
                ),
                note = "一号区", ts = 1,
            ),
        )
        r.zones.add(
            WsMsg.Zone(
                id = "z2", by = "u1", kind = "CIRCLE",
                centerLat = 39.9, centerLon = 116.3, radiusM = 100.0, ts = 2,
            ),
        )
        r.markers.add(WsMsg.Marker(by = "u1", byName = "甲", lat = 39.9, lon = 116.3, name = "可疑点", ts = 3))
        return r
    }

    private fun samples() = listOf(
        WsMsg.SamplePing(userId = "u1", lat = 39.9, lon = 116.3, rsrp = -95, ts = 1),
        WsMsg.SamplePing(userId = "u1", lat = 39.9001, lon = 116.3001, rsrp = -96, ts = 2),
    )

    @Test
    fun `geojson 包含区域 标记 与轨迹`() {
        val json = ServerExports.geojson(room(), samples())
        assertTrue(json.contains("FeatureCollection"))
        assertTrue(json.contains("\"kind\":\"zone\""))
        assertTrue(json.contains("\"kind\":\"marker\""))
        assertTrue(json.contains("\"kind\":\"track\""))
    }

    @Test
    fun `kml 是合法结构且含多边形与点`() {
        val kml = ServerExports.kml(room(), samples())
        assertTrue(kml.startsWith("<?xml"))
        assertTrue(kml.contains("<kml"))
        assertTrue(kml.contains("<Polygon>"))
        assertTrue(kml.contains("<Point>"))
        assertTrue(kml.trimEnd().endsWith("</kml>"))
    }

    @Test
    fun `csv 有表头且行数等于采样数`() {
        val csv = ServerExports.csv(samples())
        val lines = csv.trim().lines()
        assertTrue(lines.first().startsWith("userId,ts,lat,lon"))
        assertTrue(lines.size == 3)
    }
}
