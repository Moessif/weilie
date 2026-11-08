package com.gakj.weilie.core.telephony

import com.gakj.weilie.core.model.Rat
import com.gakj.weilie.core.model.ciLabel
import java.text.Normalizer

/**
 * 基站编号编解码（3GPP 语义，纯函数、无 Android 依赖，可直接在 JVM 单测里跑）。
 *
 * 位宽依据：
 * - **NCI**（NR Cell Identity，36bit）= gNB ID（高位 22~24bit，实际宽度由运营商配置）+ CellID（低 12bit）。
 *   工程按常见 24bit gNB 口径拆分：`gNB = NCI ushr 12`、`CellID = NCI and 0xFFF`（0..4095）。
 *   真机实例：NCI 5677244420 = 0x15263E004 → gNB 1386046、CellID 4（即 1386046 * 4096 + 4 = 5677244420）。
 * - **ECI**（E-UTRAN Cell Identity，28bit）= eNB ID（高位 20bit）+ CellID（低 8bit）。
 *   拆分：`eNB = ECI ushr 8`、`CellID = ECI and 0xFF`（0..255）。
 *   LTE 实例：ECI 123456789 = 0x75BCD15 → eNB 482253、CellID 21（482253 * 256 + 21 = 123456789）。
 *
 * 说明：手机侧 Telephony API 只返回完整 NCI/ECI，不返回 gNB/eNB 位宽；本工具按常用口径拆分，
 * **仅用于人读展示与输入解析**，目标匹配/去重仍一律使用完整 NCI/ECI 原值，避免误判。
 */
object CellCodec {

    /** NCI 低 12bit 是小区号 */
    const val NCI_CELL_ID_BITS: Int = 12

    /** NCI 小区号掩码（0xFFF，0..4095） */
    const val NCI_CELL_ID_MASK: Long = 0xFFFL

    /** ECI 低 8bit 是小区号 */
    const val ECI_CELL_ID_BITS: Int = 8

    /** ECI 小区号掩码（0xFF，0..255） */
    const val ECI_CELL_ID_MASK: Long = 0xFFL

    /** NCI 36bit 上限（68719476735） */
    const val NCI_MAX: Long = 0xFFFFFFFFFL

    /** ECI 28bit 上限（268435455） */
    const val ECI_MAX: Long = 0xFFFFFFFL

    /**
     * 一次基站编号解析结果。
     *
     * 约定：能确定是 NCI 时 [nci] 非空；能确定是 ECI 时 [eci] 非空；两者不会同时非空。
     * 裸十进制数字超过 28bit 视为 NCI，否则视为 ECI（与 [com.gakj.weilie.core.parsing.TaskInputParser] 的历史口径一致）。
     * [raw] 保留用户原始输入（已 trim），便于回显。
     */
    data class CellMatch(
        val nci: Long?,
        val eci: Long?,
        val raw: String,
    )

    // ---------- 分解 ----------

    /** NCI → (gNB ID, CellID)。低 12bit 为 CellID。 */
    fun decodeNci(nci: Long): Pair<Long, Int> =
        (nci ushr NCI_CELL_ID_BITS) to (nci and NCI_CELL_ID_MASK).toInt()

    /** ECI → (eNB ID, CellID)。低 8bit 为 CellID。 */
    fun decodeEci(eci: Long): Pair<Long, Int> =
        (eci ushr ECI_CELL_ID_BITS) to (eci and ECI_CELL_ID_MASK).toInt()

    // ---------- 格式化 ----------

    /** 十六进制形态，如 5677244420 → 0x15263E004（不补前导零，保持与真机习惯写法一致）。 */
    fun hexOf(value: Long): String = "0x" + value.toString(16).uppercase()

    /**
     * 用户习惯的编号写法（十进制原值 + 括号内 gNB/eNB-CID 分解）：
     * - NR：`5677244420(gNB 1386046-4)`
     * - LTE：`123456789(eNB 482253-21)`
     * - 其它制式：原值（GSM/WCDMA/CDMA 等没有 NCI/ECI 语义，不做拆分）
     * - null → `--`
     */
    fun formatCellId(rat: Rat, ci: Long?): String {
        if (ci == null) return "--"
        return when (rat) {
            Rat.NR -> {
                val (gnb, cid) = decodeNci(ci)
                "$ci(gNB $gnb-$cid)"
            }
            Rat.LTE -> {
                val (enb, cid) = decodeEci(ci)
                "$ci(eNB $enb-$cid)"
            }
            else -> ci.toString()
        }
    }

    /**
     * 专家面板一行式编号：十进制原值 · 分解 · 十六进制。
     * - NR：`NCI 5677244420 · gNB 1386046 · CID 4 · 0x15263E004`
     * - LTE：`ECI 123456789 · eNB 482253 · CID 21 · 0x75BCD15`
     * - 其它：`CI 12345 · 0x3039`
     * - null → `--`
     */
    fun formatCellBreakdown(rat: Rat, ci: Long?): String {
        if (ci == null) return "--"
        val hex = hexOf(ci)
        return when (rat) {
            Rat.NR -> {
                val (gnb, cid) = decodeNci(ci)
                "NCI $ci · gNB $gnb · CID $cid · $hex"
            }
            Rat.LTE -> {
                val (enb, cid) = decodeEci(ci)
                "ECI $ci · eNB $enb · CID $cid · $hex"
            }
            else -> "${rat.ciLabel()} $ci · $hex"
        }
    }

    /**
     * 「制式」字段展示文案：NSA 组网下 registered 小区是 LTE 锚点、NR 在邻区里，
     * 所以不能只报 serving 的 RAT，要按 [anchorRat]/NR 邻区数显示 NSA。
     * - NR 服务小区 → `5G SA（NR）`
     * - LTE 服务小区 + 检出未注册 NR 邻区 → `5G NSA（LTE 锚点，NR 邻区 N 个）`
     * - 其它 → 制式原名
     */
    fun displayRat(servingRat: Rat?, anchorRat: Rat?, nrNeighborCount: Int): String = when {
        servingRat == null -> "--"
        servingRat == Rat.NR -> "5G SA（NR）"
        servingRat == Rat.LTE && nrNeighborCount > 0 ->
            "5G NSA（LTE 锚点，NR 邻区 $nrNeighborCount 个）"
        servingRat == Rat.LTE -> "4G LTE"
        anchorRat != null -> "${servingRat.displayName}（锚点 ${anchorRat.displayName}）"
        else -> servingRat.displayName
    }

    /**
     * 从 [com.gakj.weilie.core.model.CellIdentity.display] 生成的文本里提取「习惯写法编号」，
     * 供小区切换轨迹显示「旧编号 → 新编号」用，如：
     * - 输入 `NR(5G)·460-01·TAC 12345·NCI 5677244420(gNB 1386046-4) (0x15263E004)·PCI 231`
     * - 输出 `5677244420(gNB 1386046-4)`
     *
     * 只匹配独立的 NCI/ECI/CI 前缀（用否定后顾避免把 `PCI 231` 误当成 `CI 231`）；
     * 文本为空或提取不到时返回 null。
     */
    fun cellNumberFromDisplay(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = DISPLAY_NUMBER.find(text) ?: return null
        return m.groupValues[1] + m.groupValues[2]
    }

    // ---------- 输入解析 ----------

    /**
     * 解析民警/上级输入的基站编号，兼容以下写法（大小写、空格、全角字符、中文括号均容错）：
     * - 纯数字：`5677244420`（>28bit 视为 NCI，否则视为 ECI）
     * - 带括号分解：`5677244420(1386046-4)` / `5677244420（gNB 1386046-4）` / `123456789(eNB 482253-21)`
     * - gNB-CID 形式：`1386046-4`（按 5G 语义反算 `nci = gNB * 4096 + CID`）
     * - 带前缀：`eci:123456789` / `ECI 123456789` / `nci:5677244420` / `gnb:1386046-4` / `enb:482253-21`
     * - 十六进制：`0x15263E004` / `15263E004H`
     *
     * 歧义说明：`A-B` 形式在 5G 语义下按 gNB-CID 反算 NCI；若 A ≤ 65535 也可能被上级写成
     * LAC-CI（2G/3G），此歧义由上层 [com.gakj.weilie.core.parsing.TaskInputParser] 优先按 LAC+CI 解释，
     * 需要强制 5G 语义时用 `gnb:` 前缀。
     *
     * 纯函数：不读设备、不抛异常，无法识别返回 null。
     */
    fun parseCellInput(raw: String): CellMatch? {
        val s = normalize(raw)
        if (s.isEmpty()) return null
        // 1) 带前缀（eci:/nci:/gnb:/enb: 等），可能后面还跟括号分解
        parseWithLabel(s, raw)?.let { return it }
        // 2) 无前缀：括号分解 / A-B / 纯数字 / 十六进制
        return parseCore(s, ratHint = null, raw = raw)
    }

    /** 带前缀形式：`NCI 5677244420(1386046-4)`、`eci:123456789`、`gnb:1386046-4`。 */
    private fun parseWithLabel(s: String, raw: String): CellMatch? {
        val m = LABELED_FORM.matchEntire(s) ?: return null
        val label = m.groupValues[1]
        val rest = m.groupValues[2].trim()
        if (rest.isEmpty()) return null
        // ECI/LTE/ENB 明确按 4G 语义；NCI/NR/GNB 明确按 5G 语义（gNB-CID 形式即由此反算 nci）
        val ratHint = when (label) {
            "ECI", "LTE", "ENB" -> Rat.LTE
            else -> Rat.NR
        }
        return parseCore(rest, ratHint, raw)
    }

    /** 无前缀核心解析：括号分解 → A-B → 纯十进制 → 十六进制。 */
    private fun parseCore(s: String, ratHint: Rat?, raw: String): CellMatch? {
        val trimmedRaw = raw.trim()
        // 1) 原值 + 括号分解：5677244420(1386046-4)
        PAREN_FORM.matchEntire(s)?.let { m ->
            val value = m.groupValues[1].toLongOrNull() ?: return null
            return assignByHint(value, ratHint, trimmedRaw)
        }
        // 2) A-B 分解形式（gNB-CID / eNB-CID）
        SPLIT_FORM.matchEntire(s)?.let { m ->
            val high = m.groupValues[1].toLongOrNull() ?: return null
            val low = m.groupValues[2].toLongOrNull() ?: return null
            if (ratHint == Rat.LTE) {
                if (low !in 0L..ECI_CELL_ID_MASK) return null
                return CellMatch(null, (high shl ECI_CELL_ID_BITS) or low, trimmedRaw)
            }
            if (low !in 0L..NCI_CELL_ID_MASK) return null
            val nci = (high shl NCI_CELL_ID_BITS) or low
            if (nci > NCI_MAX) return null
            return CellMatch(nci, null, trimmedRaw)
        }
        // 3) 纯十进制
        if (PLAIN_DECIMAL.matches(s)) {
            val value = s.toLongOrNull() ?: return null
            return assignByHint(value, ratHint, trimmedRaw)
        }
        // 4) 十六进制（0x 前缀或 H 后缀；裸字母串不当十六进制，避免 "ABC" 被误判）
        if (HEX_FORM.matches(s)) {
            val digits = s.removePrefix("0X").removeSuffix("H")
            val value = digits.toLongOrNull(16) ?: return null
            if (value < 0) return null
            return assignByHint(value, ratHint, trimmedRaw)
        }
        return null
    }

    /** 按显式前缀分配 nci/eci；无前缀时按位宽猜（>28bit → NCI，否则 ECI）。 */
    private fun assignByHint(value: Long, ratHint: Rat?, raw: String): CellMatch? {
        if (value < 0) return null
        return when (ratHint) {
            Rat.NR -> CellMatch(value, null, raw)
            Rat.LTE -> CellMatch(null, value, raw)
            else -> if (value > ECI_MAX) CellMatch(value, null, raw) else CellMatch(null, value, raw)
        }
    }

    /** NFKC 归一化：全角数字/字母/冒号/括号、中文括号 → 半角，再统一大写。 */
    private fun normalize(raw: String): String =
        Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
            .replace('：', ':')
            .replace('（', '(')
            .replace('）', ')')
            .uppercase()
            .trim()

    // ---------- 正则 ----------

    /** 带前缀：ECI:/LTE:/NCI:/NR:/GNB:/ENB:，前缀后必须有冒号/等号或空格（避免 "NR900" 之类被误判）。 */
    private val LABELED_FORM = Regex("""^(ECI|LTE|NCI|NR|GNB|ENB)(?:\s*[:=]\s*|\s+)(.+)$""")

    /** 原值(分解)：5677244420(1386046-4) / 5677244420(gNB 1386046-4)。 */
    private val PAREN_FORM =
        Regex("""^(\d{1,15})\s*\(\s*(?:GNB|ENB)?\s*[:=]?\s*(\d{1,9})\s*[-–—]\s*(\d{1,6})\s*\)$""")

    /** A-B：gNB-CID（5G）或 eNB-CID（显式 eNB/ECI 前缀时）。 */
    private val SPLIT_FORM = Regex("""^(\d{1,9})\s*[-–—]\s*(\d{1,6})$""")

    /** 纯十进制（最多 15 位，避免溢出；NCI 实际最多 11 位）。 */
    private val PLAIN_DECIMAL = Regex("""^\d{1,15}$""")

    /** 十六进制：0x 前缀或 H 后缀。 */
    private val HEX_FORM = Regex("""^(0X[0-9A-F]+|[0-9A-F]+H)$""")

    /** 从 display() 文本里提取编号：独立的 NCI/ECI/CI 前缀 + 十进制号码 + 可选括号分解。 */
    private val DISPLAY_NUMBER = Regex("""(?<![A-Z])(?:NCI|ECI|CI)\s+(\d+)(\([^)]*\))?""")
}
