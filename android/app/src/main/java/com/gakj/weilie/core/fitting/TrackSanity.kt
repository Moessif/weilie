package com.gakj.weilie.core.fitting

import com.gakj.weilie.core.model.SampleRecord
import kotlin.math.abs

/**
 * 采样点合理性筛选：喂给「成面 / 热力 / 缓冲带」之前先过一遍，避免一个飞点把结果撑成巨幅图形。
 *
 * 真机上偶发的情况：某一秒的定位失败会给出 (0,0)、精度几百米、或者和前后点差几公里的坐标，
 * 这类点直接参与凹包/凸包会把整个图形拉到城市尺度——地图上就表现为「一大片异常覆盖」，
 * 而且只要样本数不变、渲染指纹不变，它就会一直留在画面上。
 *
 * 规则（都可调）：
 * 1. 经纬度有限、|lat| ≤ 85、不是 (0,0)；
 * 2. 有精度值时必须 ≤ [maxAccuracyM]（默认 120 m，GPS 冷启动那种粗定位不参与成面）；
 * 3. 与全部点的中位位置相距 ≤ [maxSpreadM]（默认 3 km），把飞点剔掉。
 */
object TrackSanity {

    fun saneSamples(
        samples: List<SampleRecord>,
        maxAccuracyM: Double = 120.0,
        maxSpreadM: Double = 3_000.0,
    ): List<SampleRecord> {
        val geoOk = samples.filter { s ->
            s.lat.isFinite() && s.lon.isFinite() &&
                abs(s.lat) <= 85.0 && abs(s.lon) <= 180.0 &&
                !(abs(s.lat) < 1e-7 && abs(s.lon) < 1e-7) &&
                (s.accuracyM == null || s.accuracyM <= maxAccuracyM)
        }
        if (geoOk.size <= 2) return geoOk
        // 中位位置（比均值抗飞点）
        val lat0 = geoOk.map { it.lat }.sorted()[geoOk.size / 2]
        val lon0 = geoOk.map { it.lon }.sorted()[geoOk.size / 2]
        return geoOk.filter { s ->
            Geo.haversineMeters(s.lat, s.lon, lat0, lon0) <= maxSpreadM
        }
    }
}
