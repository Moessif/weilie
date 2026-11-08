package com.gakj.weilie.core.telephony

import com.gakj.weilie.core.model.Rat

/**
 * 「每张 SIM 卡各自驻留小区」的选择器（纯函数层，不引用 android.telephony）。
 *
 * 为什么单独抽出来：真机双卡（移动 + 联通）时，`TelephonyManager.allCellInfo`（默认订阅）
 * 只能看到一张卡的小区；要一行一张卡展示，必须按订阅读完后各自挑选。选择规则里有不少
 * 边界（已注册优先、NR 优先、NSA 辅载波补一行、MAX_VALUE 无效值），放进纯函数后可以在
 * JVM 单测里覆盖，不必上真机/模拟器。
 *
 * 选择规则（对齐 CellularZ 的展示口径）：
 * 1. 该卡小区里先找「已注册」的 5G 小区（NR），取 SS-RSRP 最强的一条；
 * 2. 没有已注册 5G，再找「已注册」的 4G 小区（LTE），取 RSRP 最强的一条；
 * 3. 第 2 步成立且该卡还扫到「未注册」的 5G 邻区（NSA 的辅载波）时，额外补一行 5G，
 *    这样双卡下「移动 NSA 5G」也能被看到。两行 [SimCell.slot] 相同，**NR 行在前**；
 * 4. 若 ROM 把未注册 NR 邻区过滤掉了（只剩 LTE 行），但数据网络类型仍是 NR，则把该卡
 *    [SimCell.rat] 标成 "NR(NSA)"（见 [withNsaFallback]）。
 */
internal data class NrDesc(
    val pci: Int = -1,
    val arfcn: Int = -1,
    val nci: Long = -1L,
    val ssRsrp: Int = SimCell.UNKNOWN,
    val ssSinr: Int = SimCell.UNKNOWN,
    val mcc: String? = null,
    val mnc: String? = null,
    val isRegistered: Boolean = false,
)

internal data class LteDesc(
    val pci: Int = -1,
    val earfcn: Int = -1,
    val eci: Long = -1L,
    val rsrp: Int = SimCell.UNKNOWN,
    val sinr: Int = SimCell.UNKNOWN,
    val mcc: String? = null,
    val mnc: String? = null,
    val isRegistered: Boolean = false,
)

/** 一张 SIM 卡的小区输入（已从 CellInfo 抽成纯描述）。 */
internal data class SimInput(
    val slot: Int,
    val carrier: String,
    val mccMnc: String = "",
    val nrCells: List<NrDesc> = emptyList(),
    val lteCells: List<LteDesc> = emptyList(),
    /**
     * 当前数据网络类型（`TelephonyManager.dataNetworkType` 映射），用于 ROM 过滤掉未注册
     * NR 邻区时的 NSA 兜底；读不到为 null。
     */
    val dataRat: Rat? = null,
    /**
     * 该卡是否为默认数据卡。`dataNetworkType` 只描述默认数据卡，所以兜底标注只对它生效，
     * 避免把另一张仅驻留 LTE 的卡误标成 NSA。无法判断默认卡时传 true（旧语义：对所有卡生效）。
     */
    val isDataSub: Boolean = true,
)

/** 一张卡 → 0~2 行 SimCell（NSA 时 NR 行在前、LTE 锚点行在后）。 */
internal fun planSimCells(input: SimInput): List<SimCell> {
    val registeredNr = input.nrCells
        .filter { it.isRegistered }
        .maxByOrNull { it.ssRsrp }
    if (registeredNr != null) return listOf(nrRow(input, registeredNr))

    val registeredLte = input.lteCells
        .filter { it.isRegistered }
        .maxByOrNull { it.rsrp }
        ?: return emptyList()
    val lteRow = lteRow(input, registeredLte)

    // NSA：4G 已注册 + 扫到未注册 NR 邻区 → 该卡补一行 5G（与 LTE 行同 slot）
    val nsaNr = input.nrCells
        .filter { !it.isRegistered && it.nci > 0 }
        .maxByOrNull { it.ssRsrp }
    if (nsaNr != null) return listOf(nrRow(input, nsaNr), lteRow)

    // ROM 把未注册 NR 邻区过滤掉时只剩 LTE 行，用数据网络类型兜底标注 NSA 接入
    return listOf(lteRow.withNsaFallback(input))
}

/**
 * 多张卡 → 按 slot 升序展平（同一张卡的两行相邻，NR 在前）。
 *
 * 双卡去重：部分 ROM 的 `createForSubscriptionId(subId).allCellInfo` 对两张卡返回同一份
 * 默认数据卡的小区（副卡拿不到自己的数据），直接展平会出现两行运营商不同、但小区编号/
 * RSRP 完全相同的数据。这里按小区身份去重，**默认数据卡优先保留**，其次卡槽升序；
 * 另一张卡没有独立数据时自然不显示行（不要复制）。
 */
internal fun planAllSims(inputs: List<SimInput>): List<SimCell> {
    val ordered = inputs.sortedWith(compareByDescending<SimInput> { it.isDataSub }.thenBy { it.slot })
    val seen = HashSet<String>()
    val kept = LinkedHashMap<Int, MutableList<SimCell>>()
    for (input in ordered) {
        val rows = planSimCells(input).filter { seen.add(it.identityKey()) }
        if (rows.isNotEmpty()) kept.getOrPut(input.slot) { ArrayList() }.addAll(rows)
    }
    return kept.entries.sortedBy { it.key }.flatMap { it.value }
}

/**
 * 小区身份去重键：制式 + 频点 + PCI + 小区号相同即视为同一小区。
 * 编号全无效的行（2G/3G 兜底等）用对象身份键，避免误合并。
 */
private fun SimCell.identityKey(): String =
    if (cellId > 0 || (arfcn > 0 && pci >= 0)) "$rat|$arfcn|$pci|$cellId"
    else "row@${System.identityHashCode(this)}"

/**
 * 「其实是 5G」兜底（只改标签，不伪造小区）：
 *
 * HyperOS / Android 17 等 ROM 可能把未注册的 NR 小区从 `allCellInfo` 里过滤掉，此时该卡
 * 只有 LTE 锚点可读，界面就会显示成「中国联通 LTE」。但 `TelephonyManager.dataNetworkType`
 * 仍可能是 NR：只把 rat 标成 "NR(NSA)"，band/arfcn/cellId 保留 LTE 锚点的值（编号仍可读），
 * 既明确告诉用户「当前是 5G 接入、锚点在 LTE」，又不冒充一个并不存在的 NR 小区。
 */
private fun SimCell.withNsaFallback(input: SimInput): SimCell =
    if (input.isDataSub && input.dataRat == Rat.NR && rat == "LTE") copy(rat = "NR(NSA)") else this

// ---------- 取值与清洗 ----------

private fun nrRow(input: SimInput, d: NrDesc): SimCell = SimCell(
    slot = input.slot,
    carrier = input.carrier,
    rat = "NR",
    band = BandTable.nrBand(saneArfcn(d.arfcn)),
    arfcn = saneArfcn(d.arfcn),
    pci = if (d.pci in 0..1007) d.pci else -1,
    cellId = if (d.nci > 0) d.nci else -1L,
    rsrp = saneRsrp(d.ssRsrp),
    sinr = saneSinr(d.ssSinr),
    mccMnc = mccMncOf(input.mccMnc, d.mcc, d.mnc),
)

private fun lteRow(input: SimInput, d: LteDesc): SimCell = SimCell(
    slot = input.slot,
    carrier = input.carrier,
    rat = "LTE",
    band = BandTable.lteBand(saneArfcn(d.earfcn)),
    arfcn = saneArfcn(d.earfcn),
    pci = if (d.pci in 0..503) d.pci else -1,
    cellId = if (d.eci > 0) d.eci else -1L,
    rsrp = saneRsrp(d.rsrp),
    sinr = saneSinr(d.sinr),
    mccMnc = mccMncOf(input.mccMnc, d.mcc, d.mnc),
)

/** ARFCN/EARFCN 无效（MAX_VALUE/负/0）→ -1。 */
private fun saneArfcn(v: Int): Int = if (v > 0 && v != Int.MAX_VALUE) v else -1

/** RSRP 无效（MAX_VALUE/正数/离谱小值）→ [SimCell.UNKNOWN]（UI 显示 "--"）。 */
private fun saneRsrp(v: Int): Int = if (v in -200..-1) v else SimCell.UNKNOWN

/** SINR 无效（MAX_VALUE/离谱值）→ [SimCell.UNKNOWN]。 */
private fun saneSinr(v: Int): Int = if (v in -100..100) v else SimCell.UNKNOWN

/** 运营商编码：优先用订阅卡自己的 networkOperator，缺失才回落到小区广播的 MCC-MNC。 */
private fun mccMncOf(primary: String, mcc: String?, mnc: String?): String {
    if (primary.isNotBlank()) return primary
    if (mcc.isNullOrBlank() || mnc.isNullOrBlank()) return ""
    val mncPadded = if (mnc.length <= 2) mnc.padStart(2, '0') else mnc
    return mcc + mncPadded
}
