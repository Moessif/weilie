package com.gakj.weilie.ui.map

/**
 * 坐标系换算：工程内部一律用 WGS84（手机 GNSS 原始坐标、采样落库、导出都用它），
 * 百度地图瓦片与覆盖物用 BD09。两者在国内相差 300~700m，不换算就会出现
 * “人站在这条街、点画在两条街外”的尴尬，演示时一眼就穿帮。
 *
 * 链路：WGS84 →(GCJ02 偏移)→ GCJ02 →(百度二次偏移)→ BD09，以及反变换。
 * 境外坐标 WGS84 与 BD09 一致，直接返回。
 */
internal object BaiduCoords {

    private const val PI = Math.PI
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val X_PI = PI * 3000.0 / 180.0

    fun wgsToBd09(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        val (gLat, gLon) = wgsToGcj(lat, lon)
        val z = Math.sqrt(gLon * gLon + gLat * gLat) + 0.00002 * Math.sin(gLat * X_PI)
        val theta = Math.atan2(gLat, gLon) + 0.000003 * Math.cos(gLon * X_PI)
        return (z * Math.sin(theta) + 0.006) to (z * Math.cos(theta) + 0.0065)
    }

    fun bd09ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        val x = lon - 0.0065
        val y = lat - 0.006
        val z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI)
        val theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI)
        return gcjToWgs(z * Math.sin(theta), z * Math.cos(theta))
    }

    private fun wgsToGcj(lat: Double, lon: Double): Pair<Double, Double> {
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = Math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI)
        return (lat + dLat) to (lon + dLon)
    }

    /** GCJ02 → WGS84：一次减去同一位置的偏移量，误差米级，足够手机定位使用 */
    private fun gcjToWgs(gLat: Double, gLon: Double): Pair<Double, Double> {
        val (cLat, cLon) = wgsToGcj(gLat, gLon)
        return (gLat * 2 - cLat) to (gLon * 2 - cLon)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    private fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271
}
