package com.gakj.weilie.core.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityTdscdma
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.gakj.weilie.BuildConfig
import com.gakj.weilie.core.model.CellIdentity
import com.gakj.weilie.core.model.Rat
import com.gakj.weilie.core.model.SignalData
import com.gakj.weilie.core.model.WifiAp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 邻区及其信号测量值（[CellIdentity] 本身不携带 RSRP，专家面板展示邻区强度用）。 */
data class NeighborCell(
    val identity: CellIdentity,
    val signal: SignalData? = null,
)

/** 一次完整的小区快照 */
data class CellSnapshot(
    val tsEpochMs: Long = System.currentTimeMillis(),
    val serving: CellIdentity? = null,
    val signal: SignalData? = null,
    val neighbors: List<CellIdentity> = emptyList(),
    val wifi: List<WifiAp> = emptyList(),
    val deviceModel: String = Build.MODEL ?: "",
    /**
     * 网络锚点制式，用于判断"其实是 5G"：
     * - NR 服务小区 → [Rat.NR]（SA 5G）
     * - LTE 服务小区且检出未注册 NR 邻区 → [Rat.LTE]（NSA：4G 锚点 + 5G 辅载波，编号仍是 LTE 的 ECI）
     * - 其它 → null
     */
    val anchorRat: Rat? = null,
    /** 检出但未注册的 NR 小区数量；仅看邻居列表，不额外抓取 */
    val nrNeighborCount: Int = 0,
    /**
     * 每张 SIM 卡各自驻留的小区（一行一卡，NR 优先；NSA 时同一张卡可能有两行：NR 行在前、LTE 锚点在后）。
     * 读不到订阅列表（没有 READ_PHONE_STATE / 老 ROM）时退化为一条 slot=0 的记录，保证 UI 不空。
     */
    val sims: List<SimCell> = emptyList(),
    /**
     * 当前「数据网络类型」映射（`tm.dataNetworkType`，读不到/无权限为 null）。
     * 用途：ROM（HyperOS/Android 17）可能把未注册 NR 小区从 allCellInfo 过滤掉，
     * 此时 CellInfo 只剩 LTE，但数据网实际是 NR(NSA)，用它兜底标注。
     */
    val dataRat: Rat? = null,
    /** [dataRat] 的展示标签："5G"、"4G"、"3G"、"2G"，未知为空串。 */
    val dataRatLabel: String = "",
    /** 与 [neighbors] 顺序一致的邻区测量值（[CellIdentity] 不携带信号，专家面板展示邻区强度用）。 */
    val neighborCells: List<NeighborCell> = emptyList(),
    /** 数据卡当前组网形态（SA / NSA / LTE / LEGACY），决定是否显示 LTE 锚点 */
    val netMode: NetMode = NetMode.UNKNOWN,
    /** 5G NR 小区：SA 的服务小区，NSA 的辅载波；用于目标 NCI 匹配 */
    val nrServing: CellIdentity? = null,
    /** [nrServing] 的测量值（SA 时与 [signal] 同源；NSA 时是 NR 辅载波的 SS-RSRP） */
    val nrSignal: SignalData? = null,
    /** NSA 的 LTE 锚点（仅 [netMode] == NSA 时非空） */
    val anchorLte: CellIdentity? = null,
    /** 本快照取自哪张卡（默认数据卡）的 subscriptionId；-1 表示读不到订阅信息 */
    val dataSubId: Int = -1,
    /** 邻区为空时的原因说明（机型/ROM 限制），UI 直接展示而不是冷冰冰的 0 */
    val neighborNote: String = "",
)

/** 免 root 采集通道抽象（华为 AIDL 通道未来实现同一接口插入） */
interface CellSnapshotSource {
    val snapshot: StateFlow<CellSnapshot?>
    fun start()
    fun stop()
}

/**
 * Telephony 免 root 采集实现。
 * - API ≥31：registerTelephonyCallback（CellInfo + SignalStrength）
 * - API 26–30：PhoneStateListener（LISTEN_CELL_INFO | LISTEN_SIGNAL_STRENGTHS）
 * - 同时 1s 轮询 allCellInfo 兜底（部分 ROM 监听不回调）
 * - 邻区 = 未注册小区；部分机型不返回邻区（W1 摸底重点）
 */
class AndroidCellReader(private val context: Context) : CellSnapshotSource {

    private val tm: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val wm: WifiManager? =
        context.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /** 读订阅列表要 READ_PHONE_STATE；拿不到时按默认订阅回退（见 readSims） */
    private val sm: SubscriptionManager? =
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

    private var simsCache: List<SimCell> = emptyList()
    private var simsCachedAt = 0L

    /** 按卡拆分后的原始小区（1s 缓存），服务小区判定与 SIM 行共用同一份，避免重复 IPC */
    private var subCellsCache: List<SubCells> = emptyList()
    private var subCellsCachedAt = 0L

    private val _snapshot = MutableStateFlow<CellSnapshot?>(null)
    override val snapshot: StateFlow<CellSnapshot?> = _snapshot

    private var callback31: Any? = null
    private var listener: PhoneStateListener? = null
    private var listening = false

    /**
     * Shizuku（shell 身份）读到的完整小区列表。多数 ROM 对普通应用只返回驻留小区，
     * 授权 Shizuku 后从 `dumpsys telephony.registry` 补上邻区；未授权时始终为空、行为与旧版一致。
     */
    @Volatile
    private var privileged: List<Pair<CellIdentity, SignalData?>> = emptyList()

    @Volatile
    private var privilegedUsed = false

    private var privilegedScope: CoroutineScope? = null
    private var privilegedJob: Job? = null

    /** Shizuku 是否安装（用于邻区为空时给出「去设置里授权」的引导），只查一次 */
    private val shizukuInstalled: Boolean by lazy { ShizukuCells.isInstalled(context) }

    private val hasLocationPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val hasPhoneStatePermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    override fun start() {
        if (listening) return
        listening = true
        startPrivilegedLoop()
        if (!hasLocationPermission) {
            // 无定位权限时 getAllCellInfo 会抛 SecurityException，快照保持空，UI 引导授权
            return
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CellInfoListener, TelephonyCallback.SignalStrengthsListener {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) = consume(cellInfo, null)
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = consume(tm.allCellInfo ?: emptyList(), signalStrength)
            }
            callback31 = cb
            try {
                tm.registerTelephonyCallback(context.mainExecutor, cb)
            } catch (_: SecurityException) {
                // 权限授予与 telephony registry 同步存在竞态（真机/模拟器均观察到）：
                // 注册失败不致命——SamplingController 的 1s pollNow() 轮询兜底继续供数
                callback31 = null
            } catch (_: IllegalStateException) {
                callback31 = null
            }
        } else {
            val l = object : PhoneStateListener() {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) = consume(cellInfo, null)
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = consume(tm.allCellInfo ?: emptyList(), signalStrength)
            }
            listener = l
            try {
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CELL_INFO or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            } catch (_: SecurityException) {
                listener = null
            }
        }
    }

    override fun stop() {
        if (!listening) return
        listening = false
        stopPrivilegedLoop()
        try {
            if (Build.VERSION.SDK_INT >= 31 && callback31 != null) {
                @Suppress("UNCHECKED_CAST")
                tm.unregisterTelephonyCallback(callback31 as TelephonyCallback)
            } else if (listener != null) {
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: Exception) {
        }
        callback31 = null; listener = null
    }

    /** 供轮询兜底调用 */
    fun pollNow() {
        if (!hasLocationPermission) return
        consume(tm.allCellInfo ?: emptyList(), null)
    }

    @Synchronized
    private fun consume(cellInfos: List<CellInfo>, ss: SignalStrength?) {
        val now = System.currentTimeMillis()
        // 1) 按 SubscriptionId 分卡读小区。真机双卡实测：`tm.allCellInfo` 会把两张卡的小区
        //    混在一个列表里，直接取「最后一个已注册小区」会让联通 LTE 覆盖掉移动 NR —— 这就是
        //    「目标 NCI 永远不命中 + 双卡串台」的根因。只认默认数据卡的小区。
        val subs = readSubCells(now)
        val primary = subs.firstOrNull { it.isData && it.cells.isNotEmpty() }
            ?: subs.firstOrNull { it.cells.isNotEmpty() }
        if (primary == null) {
            // 一张卡都读不到（无定位权限 / ROM 全屏蔽）：保持空快照，由 UI 引导授权
            if (_snapshot.value == null) {
                _snapshot.value = CellSnapshot(tsEpochMs = now, deviceModel = Build.MODEL ?: "")
            }
            return
        }
        val parsed = ArrayList<Pair<CellIdentity, SignalData?>>(primary.cells.size)
        for (ci in primary.cells) {
            try {
                parsed.add(parseCellInfo(ci, ss))
            } catch (_: Exception) {
                // 单个小区解析失败不影响整体
            }
        }

        val registeredNr = parsed
            .filter { it.first.isRegistered && it.first.rat == Rat.NR }
            .maxByOrNull { it.second?.rsrp ?: Int.MIN_VALUE }
        val registeredLte = parsed
            .filter { it.first.isRegistered && it.first.rat == Rat.LTE }
            .maxByOrNull { it.second?.rsrp ?: Int.MIN_VALUE }
        val registeredAny = parsed.firstOrNull { it.first.isRegistered }
        val nrCells = parsed.filter { it.first.rat == Rat.NR && (it.first.ci ?: 0L) > 0L }
        val dataRat = readDataRat(primary.subId)

        // 2) SA / NSA 状态机：
        //    - NR 注册 + LTE 注册 → NSA（LTE 是锚点）
        //    - 只有 NR 注册 → SA（没有锚点，UI 必须隐藏「锚点 LTE」）
        //    - 只有 LTE 注册但扫到 NR 辅载波（或数据网是 NR）→ NSA
        val netMode = NetJudge.classify(
            registeredNr = registeredNr != null,
            registeredLte = registeredLte != null,
            registeredAny = registeredAny != null,
            nrNeighborCount = nrCells.count { !it.first.isRegistered },
            dataRatIsNr = dataRat == Rat.NR,
        )
        // 服务小区：SA 取 NR；NSA 取 LTE 锚点；其余取已注册小区
        val servingPair = when (netMode) {
            NetMode.SA -> registeredNr ?: registeredAny
            NetMode.NSA -> registeredLte ?: registeredAny
            else -> registeredAny ?: registeredNr ?: registeredLte
        }
        val serving = servingPair?.first
        val signal = servingPair?.second
        val nrPair = when (netMode) {
            NetMode.SA -> registeredNr
            NetMode.NSA -> registeredNr ?: nrCells.maxByOrNull { it.second?.rsrp ?: Int.MIN_VALUE }
            else -> null
        }
        val nrServing = nrPair?.first
        val nrSignal = nrPair?.second
        val anchorLte = if (netMode == NetMode.NSA) serving else null

        val neighbors = ArrayList<CellIdentity>()
        val neighborCells = ArrayList<NeighborCell>()
        for (p in parsed) {
            if (p.first.isRegistered) continue
            neighbors.add(p.first)
            neighborCells.add(NeighborCell(p.first, p.second))
        }
        // 3) Shizuku 增强：多数 ROM 只返回驻留小区，授权后从 shell 全量列表补邻区。
        //    只补系统没给的（按 rat+ci 去重），且只补未注册小区——服务小区判定仍以系统 API 为准。
        val known = parsed.map { it.first.rat to it.first.ci }.toHashSet()
        var shizukuFilled = false
        for ((id, sig) in privileged) {
            if (id.isRegistered) continue
            if (!known.add(id.rat to id.ci)) continue
            neighbors.add(id)
            neighborCells.add(NeighborCell(id, sig))
            shizukuFilled = true
        }
        privilegedUsed = shizukuFilled
        val wifi = readWifi()
        val nrNeighborCount = neighbors.count { it.rat == Rat.NR }
        val anchorRat = when (netMode) {
            NetMode.SA -> Rat.NR
            NetMode.NSA -> Rat.LTE
            else -> serving?.rat
        }
        val sims = readSims(subs, primary, parsed, dataRat, now)
        val neighborNote = when {
            shizukuFilled -> ""
            neighbors.isNotEmpty() -> ""
            netMode == NetMode.SA -> "5G SA 模式下多数机型不提供邻区（系统限制）"
            shizukuInstalled && !ShizukuCells.isAuthorized() ->
                "当前系统未返回邻区：可在「设置 → 邻区增强（Shizuku）」授权后读取完整邻区"
            else -> "当前系统/芯片限制，未返回邻区（不影响主区命中判定）"
        }
        _snapshot.value = CellSnapshot(
            tsEpochMs = now,
            serving = serving,
            signal = signal,
            neighbors = neighbors,
            wifi = wifi,
            anchorRat = anchorRat,
            nrNeighborCount = nrNeighborCount,
            sims = sims,
            dataRat = dataRat,
            dataRatLabel = dataRatLabelOf(dataRat),
            neighborCells = neighborCells,
            netMode = netMode,
            nrServing = nrServing,
            nrSignal = nrSignal,
            anchorLte = anchorLte,
            dataSubId = primary.subId,
            neighborNote = neighborNote,
        )
        if (BuildConfig.DEBUG) {
            // 真机排查用：一眼看出「哪张卡 / 什么制式 / 服务小区编号」，便于对上级给的编号
            android.util.Log.d(
                "CellReader",
                "sub=${primary.subId} slot=${primary.slot} ${primary.carrier} mode=$netMode " +
                    "serving=${serving?.display()} nr=${nrServing?.display()} " +
                    "rsrp=${signal?.rsrp} nrRsrp=${nrSignal?.rsrp} neighbors=${neighbors.size} " +
                    "dataRat=${dataRatLabelOf(dataRat)}",
            )
        }
    }

    /**
     * 按「一行一张 SIM 卡」读取各卡驻留小区。
     *
     * 与读服务小区共用 [readSubCells] 的 1s 缓存，避免每秒对每张卡重复 IPC。
     * 取值路径：`SubscriptionManager.getActiveSubscriptionInfoList`（需 READ_PHONE_STATE）
     * → 每卡 `createForSubscriptionId(subId).allCellInfo` → [NrDesc]/[LteDesc] → [planSimCells]。
     */
    private fun readSims(
        subs: List<SubCells>,
        primary: SubCells,
        primaryParsed: List<Pair<CellIdentity, SignalData?>>,
        dataRat: Rat?,
        now: Long,
    ): List<SimCell> {
        if (now - simsCachedAt < SIMS_REFRESH_MS && simsCache.isNotEmpty()) return simsCache
        val inputs = ArrayList<SimInput>(2)
        for (sub in subs) {
            if (sub.cells.isEmpty()) continue
            inputs += SimInput(
                slot = sub.slot,
                carrier = sub.carrier,
                mccMnc = sub.mccMnc,
                nrCells = if (Build.VERSION.SDK_INT >= 30) {
                    sub.cells.filterIsInstance<CellInfoNr>().mapNotNull { nrDescOf(it) }
                } else {
                    emptyList()
                },
                lteCells = sub.cells.filterIsInstance<CellInfoLte>().mapNotNull { lteDescOf(it) },
                // 每张卡用各自的 dataNetworkType：只对默认数据卡用全局值，
                // 否则「联通 LTE 卡」也会被标成 NSA 5G（真机截图里的串台现象）
                dataRat = if (sub.isData) dataRat else readDataRat(sub.subId),
                isDataSub = sub.isData,
            )
        }
        val perSub = planAllSims(inputs)
        val sims = perSub.ifEmpty {
            fallbackSims(primary.cells, primaryParsed, dataRat, primary.subId)
        }
        simsCache = sims
        simsCachedAt = now
        return sims
    }

    /** 小区广播的 PLMN（MCC, MNC）；CDMA/读不到时返回 null。 */
    private fun plmnOfCell(ci: CellInfo): Pair<String?, String?>? = when (ci) {
        is CellInfoNr -> (ci.cellIdentity as? CellIdentityNr)?.let { it.mccString to it.mncString }
        is CellInfoLte -> ci.cellIdentity.mccString to ci.cellIdentity.mncString
        is CellInfoWcdma -> ci.cellIdentity.mccString to ci.cellIdentity.mncString
        is CellInfoGsm -> ci.cellIdentity.mccString to ci.cellIdentity.mncString
        is CellInfoTdscdma -> ci.cellIdentity.mccString to ci.cellIdentity.mncString
        else -> null
    }

    /** 运营商名 → 内部键（用于把「别家小区」从这张卡的列表里剔除）。 */
    private fun carrierKeyOf(carrier: String): String? = NetJudge.carrierKey(carrier)

    /** 中国 PLMN → 运营商键。460-07 这类运营商自有 MNC 与小区广播的 460-00 都归到同一家。 */
    private fun operatorKeyOf(mcc: String?, mnc: String?): String? = NetJudge.operatorKey(mcc, mnc)

    /** 一张卡的小区列表（含运营商信息），供服务小区判定与 SIM 行共用。 */
    private data class SubCells(
        val subId: Int,
        val slot: Int,
        val carrier: String,
        val mccMnc: String,
        val cells: List<CellInfo>,
        val isData: Boolean,
    )

    /**
     * 按订阅把 `allCellInfo` 拆成「一张卡一份」。
     *
     * 没有 READ_PHONE_STATE / ROM 不返回订阅时退化为一份全局小区（isData=true），
     * 保证老设备仍能工作，但双卡串台问题只有在能拿到订阅时才能根治。
     */
    private fun readSubCells(now: Long): List<SubCells> {
        if (now - subCellsCachedAt < SIMS_REFRESH_MS && subCellsCache.isNotEmpty()) return subCellsCache
        val dataSubId = try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (_: Exception) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        val out = ArrayList<SubCells>(2)
        if (hasPhoneStatePermission) {
            val subs: List<SubscriptionInfo> = try {
                sm?.activeSubscriptionInfoList.orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            for (sub in subs) {
                try {
                    val subTm = try {
                        tm.createForSubscriptionId(sub.subscriptionId)
                    } catch (_: Exception) {
                        tm
                    }
                    val cells: List<CellInfo> = try {
                        subTm.allCellInfo.orEmpty()
                    } catch (_: SecurityException) {
                        emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    // 运营商名优先级：卡自带 carrierName → displayName → 网络名 → 卡槽号
                    val carrier = sub.carrierName?.toString()?.takeIf { it.isNotBlank() }
                        ?: sub.displayName?.toString()?.takeIf { it.isNotBlank() }
                        ?: subTm.networkOperatorName?.takeIf { it.isNotBlank() }
                        ?: "SIM ${sub.simSlotIndex + 1}"
                    // 真机实测：HyperOS 的 createForSubscriptionId(subId).allCellInfo 会把两张卡的小区
                    // 混在一起返回（移动卡的列表里出现联通 LTE）。按「小区广播的 PLMN → 运营商」
                    // 与这张卡的运营商比对，剔除别家小区；认不出的一律保留（漫游/MVNO 兜底）。
                    val expected = carrierKeyOf(carrier)
                    val ownCells = if (expected == null) {
                        cells
                    } else {
                        cells.filter { ci ->
                            val plmn = plmnOfCell(ci) ?: return@filter true
                            val key = operatorKeyOf(plmn.first, plmn.second) ?: return@filter true
                            key == expected
                        }.ifEmpty { cells }
                    }
                    out += SubCells(
                        subId = sub.subscriptionId,
                        slot = sub.simSlotIndex.coerceAtLeast(0),
                        carrier = carrier,
                        mccMnc = subTm.networkOperator.orEmpty(),
                        cells = ownCells,
                        isData = dataSubId < 0 || sub.subscriptionId == dataSubId,
                    )
                } catch (_: Exception) {
                    // 单张卡失败不影响其它卡
                }
            }
        }
        if (out.none { it.cells.isNotEmpty() }) {
            val all = try {
                tm.allCellInfo.orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            if (all.isNotEmpty()) {
                val carrier = tm.networkOperatorName?.takeIf { it.isNotBlank() }
                    ?: tm.simOperatorName?.takeIf { it.isNotBlank() }
                    ?: "SIM 1"
                out += SubCells(
                    subId = dataSubId,
                    slot = 0,
                    carrier = carrier,
                    mccMnc = tm.networkOperator.orEmpty(),
                    cells = all,
                    isData = true,
                )
            }
        }
        subCellsCache = out
        subCellsCachedAt = now
        return out
    }

    /** CellInfoNr → 纯描述。API < 30 没有可用的 NR identity，返回 null。 */
    private fun nrDescOf(ci: CellInfoNr): NrDesc? {
        if (Build.VERSION.SDK_INT < 30) return null
        val id = ci.cellIdentity as? CellIdentityNr ?: return null
        val cs = ci.cellSignalStrength as? CellSignalStrengthNr
        return NrDesc(
            pci = id.pci,
            arfcn = id.nrarfcn,
            nci = id.nci,
            ssRsrp = cs?.ssRsrp ?: SimCell.UNKNOWN,
            ssSinr = cs?.ssSinr ?: SimCell.UNKNOWN,
            mcc = id.mccString,
            mnc = id.mncString,
            isRegistered = ci.isRegistered,
        )
    }

    /** CellInfoLte → 纯描述。 */
    private fun lteDescOf(ci: CellInfoLte): LteDesc {
        val id = ci.cellIdentity
        val cs = ci.cellSignalStrength
        return LteDesc(
            pci = id.pci.takeIf { it != Int.MAX_VALUE } ?: -1,
            earfcn = id.earfcn.takeIf { it != Int.MAX_VALUE } ?: -1,
            eci = id.ci.takeIf { it != Int.MAX_VALUE && it != 0 }
                ?.let { it.toLong() and 0xFFFFFFFL } ?: -1L,
            rsrp = cs.rsrpCompat() ?: SimCell.UNKNOWN,
            sinr = cs.sinrCompat() ?: SimCell.UNKNOWN,
            mcc = id.mccString,
            mnc = id.mncString,
            isRegistered = ci.isRegistered,
        )
    }

    /**
     * 读不到订阅列表（无 READ_PHONE_STATE / 老 ROM）时的兜底：只产出一行，默认数据卡优先。
     *
     * 真机双卡时副卡常常拿不到自己的小区，若给每张卡都塞同一份数据会显示两行完全相同的小区；
     * 这里宁可只显示默认数据卡（拿不到默认卡就 slot 0）一行，另一张卡不显示行。
     */
    private fun fallbackSims(
        cellInfos: List<CellInfo>,
        parsed: List<Pair<CellIdentity, SignalData?>>,
        dataRat: Rat?,
        defaultDataSubId: Int,
    ): List<SimCell> {
        val subs: List<SubscriptionInfo> = try {
            sm?.activeSubscriptionInfoList.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val primarySub = subs.firstOrNull { it.subscriptionId == defaultDataSubId }
            ?: subs.minByOrNull { it.simSlotIndex }
        val slot = primarySub?.simSlotIndex?.coerceAtLeast(0) ?: 0
        val carrier = primarySub?.carrierName?.toString()?.takeIf { it.isNotBlank() }
            ?: primarySub?.displayName?.toString()?.takeIf { it.isNotBlank() }
            ?: tm.networkOperatorName?.takeIf { it.isNotBlank() }
            ?: tm.simOperatorName?.takeIf { it.isNotBlank() }
            ?: "SIM ${slot + 1}"
        val mccMnc = primarySub?.let { sub ->
            runCatching { tm.createForSubscriptionId(sub.subscriptionId).networkOperator }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: tm.networkOperator.orEmpty()
        val nrCells = if (Build.VERSION.SDK_INT >= 30) {
            cellInfos.filterIsInstance<CellInfoNr>().mapNotNull { nrDescOf(it) }
        } else {
            emptyList()
        }
        val lteCells = cellInfos.filterIsInstance<CellInfoLte>().mapNotNull { lteDescOf(it) }
        if (nrCells.isNotEmpty() || lteCells.isNotEmpty()) {
            val planned = planSimCells(SimInput(slot, carrier, mccMnc, nrCells, lteCells, dataRat = dataRat))
            if (planned.isNotEmpty()) return planned
        }
        // 连 LTE/NR 都没有（2G/3G 或 ROM 全屏蔽）：用 registered 服务小区给一条，避免 UI 空白
        val registered = parsed.firstOrNull { it.first.isRegistered } ?: return emptyList()
        val (id, sig) = registered
        return listOf(
            SimCell(
                slot = slot,
                carrier = carrier,
                rat = if (id.rat == Rat.LTE && dataRat == Rat.NR) "NR(NSA)" else id.rat.name,
                band = "-",
                arfcn = id.arfcn ?: -1,
                pci = id.pci ?: -1,
                cellId = id.ci ?: -1L,
                rsrp = sig?.rsrp ?: SimCell.UNKNOWN,
                sinr = sig?.sinr ?: SimCell.UNKNOWN,
                mccMnc = mccMnc,
            )
        )
    }

    /**
     * 当前数据网络类型（不是小区制式）。ROM（HyperOS/Android 17）可能把未注册的 NR 小区
     * 从 allCellInfo 里过滤掉，导致 UI 只能从 registered 小区推制式；这里用
     * `tm.dataNetworkType` 兜底判断「实际是 5G NSA」，读不到返回 null（保持旧行为）。
     */
    /**
     * 指定卡（默认数据卡）当前的数据网络类型。
     *
     * 必须按 subId 读：`tm.dataNetworkType` 只描述默认数据卡，双卡下用它给另一张卡
     * 贴「5G NSA」标签就是串台。读不到返回 null（保持旧行为）。
     */
    private fun readDataRat(subId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID): Rat? {
        val subTm = if (subId >= 0) {
            try {
                tm.createForSubscriptionId(subId)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        if (subTm != null) {
            networkTypeOrNull { subTm.dataNetworkType }?.let { ratOfNetworkType(it) }?.let { return it }
            networkTypeOrNull { subTm.voiceNetworkType }?.let { ratOfNetworkType(it) }?.let { return it }
        }
        networkTypeOrNull { tm.dataNetworkType }?.let { ratOfNetworkType(it) }?.let { return it }
        // 语音网络类型仅作补充（个别 ROM 数据网络类型不可用但语音可用）
        return networkTypeOrNull { tm.voiceNetworkType }?.let { ratOfNetworkType(it) }
    }

    /** 读网络类型并做异常保护：HyperOS 上可能抛 SecurityException（权限竞态）/其它 ROM 限制 → null。 */
    private fun networkTypeOrNull(read: () -> Int): Int? = try {
        read().takeIf { it > 0 }
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }

    /** TelephonyManager.NETWORK_TYPE_* → [Rat]；未知/无映射返回 null。 */
    private fun ratOfNetworkType(type: Int): Rat? = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> Rat.NR
        TelephonyManager.NETWORK_TYPE_LTE -> Rat.LTE
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> Rat.TDSCDMA
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP -> Rat.WCDMA
        TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS -> Rat.GSM
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD -> Rat.CDMA
        else -> null
    }

    /** [CellSnapshot.dataRat] → 给 UI 的展示标签。 */
    private fun dataRatLabelOf(rat: Rat?): String = when (rat) {
        Rat.NR -> "5G"
        Rat.LTE -> "4G"
        Rat.WCDMA, Rat.TDSCDMA -> "3G"
        Rat.GSM, Rat.CDMA -> "2G"
        else -> ""
    }

    private fun readWifi(): List<WifiAp> {
        if (!hasLocationPermission) return emptyList()
        return try {
            wm?.scanResults
                ?.sortedByDescending { it.level }
                ?.take(8)
                ?.map {
                    WifiAp(
                        ssid = it.SSID,
                        bssid = it.BSSID,
                        rssi = it.level,
                        channel = it.frequency?.let { f -> frequencyToChannel(f) },
                        vendor = WifiVendor.lookup(it.BSSID),
                    )
                }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun frequencyToChannel(freq: Int): Int = when {
        freq in 2412..2484 -> (freq - 2407) / 5
        freq in 5170..5825 -> (freq - 5000) / 5
        freq >= 5955 -> (freq - 5950) / 5
        else -> 0
    }

    private fun parseCellInfo(ci: CellInfo, ss: SignalStrength?): Pair<CellIdentity, SignalData?> {
        return when (ci) {
            is CellInfoLte -> {
                val id: CellIdentityLte = ci.cellIdentity
                val cid = CellIdentity(
                    rat = Rat.LTE,
                    mcc = id.mccString, mnc = id.mncString,
                    lacOrTac = id.tac.takeIf { it != Int.MAX_VALUE }?.toLong(),
                    ci = id.ci.takeIf { it != Int.MAX_VALUE && it != 0 }?.let { it.toLong() and 0xFFFFFFFL },
                    pci = id.pci.takeIf { it != Int.MAX_VALUE },
                    arfcn = id.earfcn.takeIf { it != Int.MAX_VALUE },
                    band = if (Build.VERSION.SDK_INT >= 28) id.bands.firstOrNull() else null,
                    isRegistered = ci.isRegistered,
                )
                val cs = ci.cellSignalStrength
                cid to SignalData(
                    rsrp = cs.rsrpCompat(), rsrq = cs.rsrqCompat(), sinr = cs.sinrCompat(), rssi = cs.rssiCompat(), dbm = cs.dbm,
                )
            }
            is CellInfoGsm -> {
                val id: CellIdentityGsm = ci.cellIdentity
                val cid = CellIdentity(
                    rat = Rat.GSM, mcc = id.mccString, mnc = id.mncString,
                    lacOrTac = id.lac.takeIf { it != Int.MAX_VALUE }?.toLong(),
                    ci = id.cid.takeIf { it != Int.MAX_VALUE && it >= 0 }?.toLong(),
                    arfcn = id.arfcn.takeIf { it != Int.MAX_VALUE },
                    isRegistered = ci.isRegistered,
                )
                val cs = ci.cellSignalStrength
                cid to SignalData(rssi = cs.dbm.takeIf { it in -120..0 }, dbm = cs.dbm)
            }
            is CellInfoWcdma -> {
                val id: CellIdentityWcdma = ci.cellIdentity
                val cid = CellIdentity(
                    rat = Rat.WCDMA, mcc = id.mccString, mnc = id.mncString,
                    lacOrTac = id.lac.takeIf { it != Int.MAX_VALUE }?.toLong(),
                    ci = id.cid.takeIf { it != Int.MAX_VALUE && it >= 0 }?.let { it.toLong() and 0xFFFFL },
                    arfcn = id.uarfcn.takeIf { it != Int.MAX_VALUE },
                    isRegistered = ci.isRegistered,
                )
                val cs = ci.cellSignalStrength
                cid to SignalData(dbm = cs.dbm, rssi = cs.dbm.takeIf { it in -120..0 })
            }
            is CellInfoTdscdma -> {
                val id: CellIdentityTdscdma = ci.cellIdentity
                val cid = CellIdentity(
                    rat = Rat.TDSCDMA, mcc = id.mccString, mnc = id.mncString,
                    lacOrTac = id.lac.takeIf { it != Int.MAX_VALUE }?.toLong(),
                    ci = id.cid.takeIf { it != Int.MAX_VALUE && it >= 0 }?.toLong(),
                    isRegistered = ci.isRegistered,
                )
                cid to SignalData(dbm = ci.cellSignalStrength.dbm)
            }
            is CellInfoCdma -> {
                val id: CellIdentityCdma = ci.cellIdentity
                val cs: CellSignalStrengthCdma = ci.cellSignalStrength
                val cid = CellIdentity(
                    rat = Rat.CDMA,
                    ci = id.basestationId.takeIf { it != Int.MAX_VALUE && it >= 0 }?.toLong(),
                    isRegistered = ci.isRegistered,
                )
                cid to SignalData(dbm = cs.dbm)
            }
            is CellInfoNr -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    val id = ci.cellIdentity as? CellIdentityNr
                    val cs = ci.cellSignalStrength as? CellSignalStrengthNr
                    if (id == null) return Pair(CellIdentity(isRegistered = ci.isRegistered), null)
                    val cid = CellIdentity(
                        rat = Rat.NR, mcc = id.mccString, mnc = id.mncString,
                        lacOrTac = id.tac.takeIf { it != Int.MAX_VALUE }?.toLong(),
                        ci = id.nci.takeIf { it > 0 },
                        pci = id.pci.takeIf { it != Int.MAX_VALUE },
                        arfcn = id.nrarfcn.takeIf { it != Int.MAX_VALUE },
                        isRegistered = ci.isRegistered,
                    )
                    cid to SignalData(
                        rsrp = cs?.ssRsrp?.takeIf { it != Int.MAX_VALUE },
                        rsrq = cs?.ssRsrq?.takeIf { it != Int.MAX_VALUE },
                        sinr = cs?.ssSinr?.takeIf { it != Int.MAX_VALUE },
                        dbm = cs?.dbm,
                    )
                } else null
            }
            else -> null
        } ?: Pair(CellIdentity(isRegistered = ci.isRegistered), null)
    }

    // CellSignalStrengthLte 各字段在不同 API 级别的取值包装（MAX_VALUE → null）
    private fun CellSignalStrengthLte.rsrpCompat(): Int? = if (Build.VERSION.SDK_INT >= 29) rsrp.takeIf { it != Int.MAX_VALUE } else null
    private fun CellSignalStrengthLte.rsrqCompat(): Int? = if (Build.VERSION.SDK_INT >= 29) rsrq.takeIf { it != Int.MAX_VALUE } else null
    private fun CellSignalStrengthLte.rssiCompat(): Int? = if (Build.VERSION.SDK_INT >= 29) rssi.takeIf { it != Int.MAX_VALUE } else null
    private fun CellSignalStrengthLte.sinrCompat(): Int? = if (Build.VERSION.SDK_INT >= 29) rssnr.takeIf { it != Int.MAX_VALUE }?.let { (it / 10.0).toInt() } else null

    /** 授权 Shizuku 后每 2 秒读一次系统全量小区；未授权直接返回，不影响原有流程 */
    private fun startPrivilegedLoop() {
        if (privilegedScope != null) return
        if (!ShizukuCells.isAuthorized()) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        privilegedScope = scope
        privilegedJob = scope.launch {
            while (isActive) {
                val cells = ShizukuCells.readRegistry()
                if (cells != null) privileged = ShizukuCells.toPairs(cells)
                delay(PRIVILEGED_REFRESH_MS)
            }
        }
    }

    private fun stopPrivilegedLoop() {
        privilegedJob?.cancel()
        privilegedJob = null
        privilegedScope?.cancel()
        privilegedScope = null
        privileged = emptyList()
        privilegedUsed = false
    }

    private companion object {
        /** Shizuku 刷新间隔：dumpsys 有进程开销，2 秒足够跟上邻区变化 */
        const val PRIVILEGED_REFRESH_MS = 2_000L
        /** 各卡小区/订阅表刷新间隔：避免 CellInfo 回调一秒多次时对每张卡重复 IPC */
        const val SIMS_REFRESH_MS = 1_000L
    }
}
