package com.gakj.weilie.core.parsing

import com.gakj.weilie.core.model.Rat
import com.gakj.weilie.core.model.TargetCell
import com.gakj.weilie.core.model.TargetFormat
import com.gakj.weilie.core.model.ciLabel
import com.gakj.weilie.core.telephony.CellCodec

/**
 * 上级参数多格式解析器。
 * 支持输入（可混合全半角、中英文冒号、大小写）：
 *  1) ECI  —— "ECI 217546496" / "217546496" / "0x0CF7A40" / "0CF7A40H" / "460-00-217546496"
 *  2) NCI  —— "NCI 68719476735" / 九位以上十进制 / 十六进制
 *  3) LAC+CI —— "LAC 12345 CI 67890" / "12345-67890" / "LAC:12345,CI:67890"
 *  4) 经纬度+半径 —— "31.2304,121.4737,500m" / "31.2304 121.4737 半径500" / "31.2304,121.4737"
 * 解析失败时给出人话错误；能识别的部分尽量识别并在 warnings 提示。
 */
object TaskInputParser {

    data class ParseResult(
        val target: TargetCell?,
        val error: String?,
        val normalized: String,
    )

    fun parse(raw: String): ParseResult {
        val s = normalize(raw)
        if (s.isBlank()) return ParseResult(null, "请输入上级提供的参数", raw.trim())

        // 1) 经纬度（+半径）
        parseLatLon(s)?.let { (lat, lon, radius, warns) ->
            return ParseResult(
                TargetCell(
                    format = TargetFormat.LATLON_RADIUS, rawInput = raw.trim(),
                    centerLat = lat, centerLon = lon, givenRadiusM = radius, warnings = warns,
                ),
                null, "经纬度 ($lat, $lon)" + (radius?.let { " 半径 ${it}m" } ?: ""),
            )
        }

        // 2) LAC+CI
        parseLacCi(s)?.let { (lac, ci, extraMcc) ->
            return ParseResult(
                TargetCell(
                    format = TargetFormat.LAC_CI, rawInput = raw.trim(), ratHint = Rat.GSM,
                    lac = lac, cid = ci, mcc = extraMcc, mnc = null, warnings = emptyList(),
                ),
                null, "LAC $lac · CI $ci",
            )
        }

        // 3) 带标签单标识
        val labeled = regex1("""(?:ECI|LTE)\s*[:：=]?\s*(0X[0-9A-F]+|\d{1,15})""", s)
        if (labeled != null) return numberToResult(raw, labeled, Rat.LTE, isNci = false)

        val labeledNci = regex1("""(?:NCI|NR)\s*[:：=]?\s*(0X[0-9A-F]+|\d{1,15})""", s)
        if (labeledNci != null) return numberToResult(raw, labeledNci, Rat.NR, isNci = true)

        // 4) MCC-MNC-编号 前缀
        val m = Regex("""(\d{3})-(\d{2})-(0X[0-9A-F]+|\d{1,15})""").find(s)
        if (m != null) {
            val (mcc, mnc, num) = m.destructured
            val base = numberToResult(raw, num, Rat.LTE, isNci = false).target ?: return ParseResult(null, "编号无法解析", raw.trim())
            val digits = num.toLongOrNull()
            val guessed = digits?.let { guessRat(it) } ?: Rat.LTE
            return ParseResult(base.copy(mcc = mcc, mnc = mnc, ratHint = guessed), null, base.normalizedFor(mcc, mnc))
        }

        // 5) 十六进制（0x 前缀或 H 后缀）
        val hex = Regex("""^(0X[0-9A-F]+|[0-9A-F]+H)$""").find(s)
        if (hex != null) {
            val num = hex.value.removePrefix("0X").removeSuffix("H")
            val v = num.toLongOrNull(16)
            if (v != null) return numberToResult(raw, "0X$num", guessRat(v), isNci = v > 0xFFFFFFFL)
        }

        // 6) 裸数字：按位数猜
        val bare = Regex("""^\d{1,15}$""").find(s)
        if (bare != null) {
            val v = bare.value.toLong()
            val rat = guessRat(v)
            return numberToResult(raw, bare.value, rat, isNci = v > 0xFFFFFFFL)
        }

        // 7) 基站编号编解码兜底（CellCodec）：覆盖上面老格式不认的写法
        //    - 原值+括号分解："5677244420(1386046-4)"
        //    - gNB-CID / eNB-CID："1386046-4"、"gnb:1386046-4"、"enb:482253-21"
        //    - 带冒号前缀："nci:5677244420"
        //    注意：A-B 中 A ≤ 65535 的写法已被上面的 LAC+CI 抢先认领（兼容旧行为），
        //    必须走 5G 语义时用 gnb: 前缀。
        CellCodec.parseCellInput(raw)?.let { m ->
            m.nci?.let { return numberToResult(raw, it.toString(), Rat.NR, isNci = true) }
            m.eci?.let { return numberToResult(raw, it.toString(), Rat.LTE, isNci = false) }
        }

        return ParseResult(
            null,
            "无法识别的基站编号：支持 ECI / NCI / LAC+CI / 经纬度+半径，" +
                "也可粘贴带分解的写法如 5677244420(1386046-4) / 1386046-4（十六进制可用 0x 前缀）",
            raw.trim(),
        )
    }

    /**
     * 规格校验：不合规的目标参数不允许创建任务（用户要求「不符合规格的不可创建任务」）。
     * 与 [parse] 的区别是这里严格按各制式的编号位宽/坐标范围卡口，而 parse 只尽量识别。
     */
    data class Validation(
        val ok: Boolean,
        val blocking: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    fun validate(raw: String): Validation {
        val parsed = parse(raw)
        val target = parsed.target
        if (target == null) {
            return Validation(ok = false, blocking = listOf(parsed.error ?: "无法识别该参数"))
        }
        val blocking = mutableListOf<String>()
        val warnings = target.warnings.toMutableList()
        when (target.format) {
            TargetFormat.NCI -> {
                val nci = target.nci ?: 0L
                if (nci <= 0L) blocking += "NCI 必须大于 0"
                if (nci > 0xFFFFFFFFFL) blocking += "NCI 不能超过 36 位（最大 68719476735），请核对上级原文"
            }
            TargetFormat.ECI -> {
                val eci = target.eci ?: 0L
                if (eci <= 0L) blocking += "ECI 必须大于 0"
                if (eci > 0xFFFFFFFL) blocking += "ECI 不能超过 28 位（最大 268435455），请核对上级原文"
            }
            TargetFormat.LAC_CI -> {
                val lac = target.lac ?: 0L
                val ci = target.cid ?: 0L
                if (lac !in 1L..65535L) blocking += "LAC/TAC 应在 1 ~ 65535 之间"
                if (ci <= 0L) blocking += "CI 必须大于 0"
                if (ci > 0xFFFFFFFFL) blocking += "CI 过大，4G/5G 请改用 ECI / NCI"
            }
            TargetFormat.LATLON_RADIUS -> {
                val lat = target.centerLat
                val lon = target.centerLon
                if (lat == null || lon == null) {
                    blocking += "经纬度不完整"
                } else {
                    if (lat !in -90.0..90.0) blocking += "纬度应在 -90 ~ 90 之间"
                    if (lon !in -180.0..180.0) blocking += "经度应在 -180 ~ 180 之间"
                    if (lat == 0.0 && lon == 0.0) blocking += "坐标 (0, 0) 不是有效位置"
                }
                target.givenRadiusM?.let { r ->
                    if (r <= 0.0) blocking += "半径必须大于 0"
                    if (r > 50_000.0) blocking += "半径超过 50 公里，请确认单位是「米」"
                }
            }
        }
        // 用户明确写了「ECI / LTE」却超出 28 位：parse 会把超限值降级成 NCI，
        // 但校验要拦住——否则民警以为填的是 4G 小区，实际系统按 5G NCI 处理。
        val labeledEci = Regex("""(?:ECI|LTE)\s*[:：=]?\s*(0X[0-9A-F]+|\d{1,15})""").find(normalize(raw))
        if (labeledEci != null && target.format == TargetFormat.NCI) {
            blocking += "按 ECI 校验超出 28 位上限（最大 268435455），请核对上级原文"
        }
        if (target.format != TargetFormat.LATLON_RADIUS && target.unifiedCi() == null) {
            blocking += "缺少可用的统一小区编号"
        }
        return Validation(ok = blocking.isEmpty(), blocking = blocking, warnings = warnings)
    }

    private fun numberToResult(raw: String, numStr: String, rat: Rat, isNci: Boolean): ParseResult {
        val v = if (numStr.startsWith("0X")) numStr.substring(2).toLongOrNull(16) else numStr.toLongOrNull()
        if (v == null || v < 0) return ParseResult(null, "编号不是有效数字", raw.trim())
        val warns = mutableListOf<String>()
        if (isNci) {
            if (v > 0xFFFFFFFFFL) warns += "NCI 超过 36bit 上限，请核对"
            return ParseResult(
                TargetCell(format = TargetFormat.NCI, rawInput = raw.trim(), ratHint = Rat.NR, nci = v, warnings = warns),
                null, "NCI $v (0x%09X)".format(v),
            )
        }
        val maxCi = if (rat == Rat.LTE) 0xFFFFFFFL else 0xFFFFL
        if (v > maxCi) {
            if (rat == Rat.LTE && v <= 0xFFFFFFFFFL) warns += "超过 ECI 28bit 上限，按 NCI 处理"
            if (warns.isNotEmpty()) {
                return ParseResult(
                    TargetCell(format = TargetFormat.NCI, rawInput = raw.trim(), ratHint = Rat.NR, nci = v, warnings = warns),
                    null, "NCI $v",
                )
            }
        }
        val warn2 = if (rat == Rat.LTE && v > 0xFFFFFFF) listOf("ECI 超过 28bit 上限，请核对") else emptyList()
        // 拆解统一走 CellCodec（28bit ECI = eNB 20bit + CellID 8bit），保留 eNodeB 措辞以免影响既有文案/用例
        val split = if (rat == Rat.LTE) {
            val (enb, cid) = CellCodec.decodeEci(v)
            " · eNodeB $enb · CID $cid"
        } else ""
        return ParseResult(
            TargetCell(format = TargetFormat.ECI, rawInput = raw.trim(), ratHint = rat, eci = v, warnings = warn2),
            null, "${rat.ciLabel()} $v" + split,
        )
    }

    private fun guessRat(v: Long): Rat = when {
        v > 0xFFFFFFFL -> Rat.NR        // 超 28bit → 视为 NCI
        v > 0xFFFFL -> Rat.LTE          // 超过 16bit → 视为 ECI
        else -> Rat.LTE
    }

    private fun parseLacCi(s: String): Triple<Long, Long, String?>? {
        val m = Regex("""LAC\s*[:：=]?\s*(\d{1,5})\s*[,，;；]?\s*(?:CI|CID)\s*[:：=]?\s*(\d{1,10})""").find(s)
        if (m != null) return Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), null)
        val p = Regex("""^(\d{1,5})\s*[-–]\s*(\d{1,10})$""").find(s)
        if (p != null) {
            val lac = p.groupValues[1].toLong(); val ci = p.groupValues[2].toLong()
            // 启发式：LAC ≤ 65535；CI 合理（非过小）
            if (lac in 1..65535L && ci > 0) return Triple(lac, ci, null)
        }
        return null
    }

    private fun parseLatLon(s: String): Quadruple<Double, Double, Double?, List<String>>? {
        val m = Regex("""(\d{1,2}\.\d{3,})\s*[,，;；\s]\s*(\d{1,3}\.\d{3,})""").find(s) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        val warns = mutableListOf<String>()
        if (lat !in -90.0..90.0) { warns += "纬度超出范围"; return null }
        if (lon !in -180.0..180.0) { warns += "经度超出范围"; return null }
        val r = Regex("""(?:R=|半径|半径[:：]?|R )\s*(\d{1,6})\s*(?:M|米)?""").find(s.substring(m.range.last))?.groupValues?.get(1)?.toDouble()
            ?: Regex("""[,，;；]\s*(\d{1,6})\s*(?:M|米)\b""").find(s.substring(m.range.last))?.groupValues?.get(1)?.toDouble()
        if (r == null) warns += "未提供半径，建议按上级口径补填（默认按 1000m 展示参考圆）"
        return Quadruple(lat, lon, r, warns)
    }

    private fun normalize(raw: String): String {
        // NFKC：全角字母/数字/符号 → 半角（ＥＣＩ：１２３４ → ECI:1234）
        val nfkc = java.text.Normalizer.normalize(raw.trim(), java.text.Normalizer.Form.NFKC)
        return nfkc
            .replace("：", ":")
            .replace("，", ",")
            .replace("；", ";")
            .replace("（", "(")
            .replace("）", ")")
            .uppercase()
    }

    private fun regex1(pattern: String, s: String): String? =
        Regex(pattern).find(s)?.groupValues?.get(1)

    private fun TargetCell.normalizedFor(mcc: String, mnc: String): String =
        "$mcc-$mnc · ${ratHint.ciLabel()} ${unifiedCi()}"

    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
