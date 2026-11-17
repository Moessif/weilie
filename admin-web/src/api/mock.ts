import { ApiError } from './error'
import { toCsv } from '@/utils/csv'
import type {
  ActivationCode,
  AdminUser,
  AppSettings,
  AuditEvent,
  CodePurpose,
  CodeStatus,
  DashboardSummary,
  HeatCell,
  HeatmapResult,
  ImportResult,
  LoginResult,
  OpsStatus,
  Org,
  OrgApplication,
  PageResult,
  PhotoItem,
  PhotoKind,
  RangeKey,
  RecentRoom,
  RoomDetailResult,
  RoomItem,
  RoomMarker,
  RoomMember,
  RoomSample,
  RoomSamplesResult,
  RoomStatusResult,
  RoomZone,
  StatusCountResult,
  TimePoint,
  TimeseriesMetric,
  TimeseriesResult,
  TopOrgsResult,
  UserItem,
  UserStatus,
} from '@/types'

/**
 * 管理站 M1 接口的本地 mock 实现。
 * - 数据确定性生成（固定随机种子），刷新后趋势/热力图基本一致；
 * - 登录会话与 mock 密码写入 localStorage，刷新不掉登录；
 * - 与真实接口同签名，页面只依赖 src/api 暴露的 api 对象。
 */

type Query = Record<string, string | number | boolean | null | undefined>

const DAY = 86_400_000
const HOUR = 3_600_000
const MINUTE = 60_000
const NOW = Date.now()

const STORAGE_SESSION = 'weilie.admin.mock.session'
const STORAGE_PASSWORD = 'weilie.admin.mock.password'
const STORAGE_MUST_CHANGE = 'weilie.admin.mock.mustChange'

/* ------------------------------------------------------------------ */
/* 确定性随机                                                          */
/* ------------------------------------------------------------------ */

function mulberry32(seed: number): () => number {
  let a = seed
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const rng = mulberry32(20261117)
const rnd = (): number => rng()
const randInt = (min: number, max: number): number => Math.floor(rnd() * (max - min + 1)) + min
const pick = <T>(arr: T[]): T => arr[Math.floor(rnd() * arr.length)]
function shuffle<T>(arr: T[]): T[] {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(rnd() * (i + 1))
    const tmp = arr[i]
    arr[i] = arr[j]
    arr[j] = tmp
  }
  return arr
}

function randomHex(len: number): string {
  const chars = '0123456789abcdef'
  let out = ''
  for (let i = 0; i < len; i++) out += chars[Math.floor(rnd() * chars.length)]
  return out
}

function randomDigits(len: number): string {
  let out = ''
  for (let i = 0; i < len; i++) out += String(randInt(0, 9))
  return out
}

const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
function randomCode(): string {
  let out = ''
  for (let i = 0; i < 8; i++) out += CODE_ALPHABET[Math.floor(rnd() * CODE_ALPHABET.length)]
  return out
}

/* ------------------------------------------------------------------ */
/* 基础数据                                                            */
/* ------------------------------------------------------------------ */

const SURNAMES = [
  '张', '王', '李', '赵', '刘', '陈', '杨', '黄', '周', '吴',
  '徐', '孙', '胡', '朱', '高', '林', '何', '郭', '马', '罗',
  '梁', '宋', '郑', '谢', '韩', '唐', '冯', '于', '董', '萧',
  '程', '曹', '袁', '邓', '许', '傅', '沈', '曾', '彭', '吕',
]
const GIVEN_NAMES = [
  '伟', '强', '磊', '洋', '勇', '军', '杰', '涛', '斌', '超',
  '建设', '建华', '志强', '文博', '晓东', '海涛', '振华', '明远', '立群', '俊杰',
  '子涵', '雨欣', '思远', '浩然', '宇轩', '欣怡', '梓萱', '一鸣', '嘉豪', '若彤',
  '晨阳', '书豪', '天佑', '博文', '雅静', '梦洁', '佳琪', '安然', '诗涵', '晓峰',
]
const DEVICE_NAMES = [
  'REDMI K80 Pro',
  'HUAWEI Mate 60',
  'Xiaomi 14 Pro',
  'OPPO Find X7 Ultra',
  'vivo X100',
  'HONOR Magic6 Pro',
  'iPhone 15',
]

interface OrgSeed {
  name: string
  short: string
  code: string
  contact: string
  phone: string
  userCount: number
}

const ORG_SEEDS: OrgSeed[] = [
  { name: '中国人民公安大学·网络空间安全学院', short: '网安学院', code: 'ppsuc-net', contact: '李建军', phone: '13811002231', userCount: 92 },
  { name: '中国人民警察大学', short: '警院', code: 'cppsu', contact: '王振华', phone: '13910214402', userCount: 64 },
  { name: '中国刑事警察学院', short: '刑技学院', code: 'cips', contact: '赵明远', phone: '13681027718', userCount: 45 },
  { name: '公安大学·指挥战术学院', short: '指挥学院', code: 'ppsuc-cmd', contact: '陈立群', phone: '13810039921', userCount: 38 },
  { name: '公安大学·公安情报学院', short: '情报学院', code: 'ppsuc-int', contact: '周文博', phone: '13701128833', userCount: 30 },
  { name: '公安大学·交通管理学院', short: '交管学院', code: 'ppsuc-traffic', contact: '孙志强', phone: '13911005512', userCount: 27 },
  { name: '公安大学·特警学院', short: '特警学院', code: 'ppsuc-swat', contact: '马骁', phone: '13611067708', userCount: 23 },
  { name: '公安大学·治安学院', short: '治安学院', code: 'ppsuc-pub', contact: '吴海涛', phone: '13711008841', userCount: 19 },
  { name: '北京市公安局网络安全保卫总队', short: '北京网安', code: 'bj-cyber', contact: '高峰', phone: '13801331199', userCount: 15 },
  { name: '浙江省公安厅科技信息化局', short: '浙江科信', code: 'zj-tech', contact: '沈鹏', phone: '13905710021', userCount: 12 },
  { name: '深圳市公安局网络警察支队', short: '深圳网警', code: 'sz-cyber', contact: '林浩', phone: '13510048822', userCount: 11 },
  { name: '广州铁路公安局网络安全处', short: '广铁网安', code: 'gzrail-cyber', contact: '何俊', phone: '13602703355', userCount: 10 },
]

const ORGS: Org[] = []
const ORG_SHORT = new Map<number, string>()
const deletedOrgIds = new Set<number>()

ORG_SEEDS.forEach((seed, index) => {
  const createdAt = NOW - (320 - index * 21) * DAY
  const quotaUsers = Math.ceil((seed.userCount * 1.25) / 10) * 10
  const org: Org = {
    id: index + 1,
    name: seed.name,
    code: seed.code,
    contactName: seed.contact,
    contactPhone: seed.phone,
    status: index === 11 ? 'SUSPENDED' : 'ACTIVE',
    quotaUsers,
    quotaRooms: Math.max(5, Math.ceil(seed.userCount / 8)),
    createdAt,
  }
  ORGS.push(org)
  ORG_SHORT.set(org.id, seed.short)
})

const USERS: UserItem[] = []
const USER_PASSWORD: Record<string, string> = {}

const statusPool: UserStatus[] = shuffle([
  ...Array<UserStatus>(306).fill('ACTIVE'),
  ...Array<UserStatus>(58).fill('PENDING'),
  ...Array<UserStatus>(14).fill('SUSPENDED'),
  ...Array<UserStatus>(8).fill('LOCKED'),
])

let userSeq = 1
let globalUserIndex = 0
ORG_SEEDS.forEach((seed, orgIndex) => {
  const org = ORGS[orgIndex]
  for (let i = 0; i < seed.userCount; i++) {
    const isAdmin = i === 0
    const status: UserStatus = isAdmin ? 'ACTIVE' : statusPool[globalUserIndex] || 'PENDING'
    const createdAt = NOW - (300 - orgIndex * 5 - i) * DAY + i * MINUTE
    const active = status === 'ACTIVE'
    const deviceId = active ? `dev_${randomHex(10)}` : null
    const lastLoginAt = active ? NOW - randInt(2, 96) * HOUR : status === 'LOCKED' ? NOW - randInt(1, 4) * DAY : null
    const displayName = isAdmin ? seed.contact : `${pick(SURNAMES)}${pick(GIVEN_NAMES)}`
    USERS.push({
      id: userSeq++,
      orgId: org.id,
      orgName: org.name,
      username: isAdmin ? `${seed.code}_admin` : `${seed.code}_${String(i).padStart(3, '0')}`,
      displayName,
      phone: `1${randInt(3, 9)}${randomDigits(9)}`,
      role: isAdmin ? 'ORG_ADMIN' : globalUserIndex % 97 === 41 ? 'AUDITOR' : 'USER',
      status,
      deviceId,
      deviceName: deviceId ? pick(DEVICE_NAMES) : null,
      lastLoginAt,
      activatedAt: active ? Math.min(createdAt + randInt(1, 10) * DAY, NOW - HOUR) : null,
      createdAt,
    })
    globalUserIndex++
  }
})

// 未激活用户首登密码不会被使用；这里只存 mock 管理端账号的密码。
USER_PASSWORD.admin = 'admin12345'
USER_PASSWORD.orgadmin = 'org12345'

const PLATFORM_ADMIN: AdminUser = {
  id: 1,
  username: 'admin',
  displayName: '系统管理员',
  role: 'SUPER_ADMIN',
  orgId: null,
  orgName: null,
  status: 'ACTIVE',
  mustChangePassword: true,
}

const ORG_DEMO_ADMIN: AdminUser = {
  id: 2,
  username: 'orgadmin',
  displayName: '组织管理员 · 李建军',
  role: 'ORG_ADMIN',
  orgId: 1,
  orgName: ORGS[0].name,
  status: 'ACTIVE',
  mustChangePassword: false,
}

/* ------------------------------- 房间 ------------------------------- */

type RoomBucket = 'WITH_ZONE' | 'POINT_ONLY' | 'EMPTY' | 'CLOSED'
interface MockRoom extends RecentRoom {
  bucket: RoomBucket
  name: string
  joinCode: string
  markers: number
  zones: number
  photos: number
  createdAt: number
}

const ROOM_NAME_PREFIXES = [
  '教学楼 A 区',
  '田径场东侧',
  '图书馆北门',
  '警体馆',
  '实验楼三层',
  '行政楼中庭',
  '训练场西侧',
  '学生食堂',
  '射击馆外围',
  '信息楼机房',
  '校门安检区',
  '地下车库 B 区',
  '会议中心',
  '综合指挥室',
  '体能训练场',
  '刑事技术楼',
]

const ROOM_BUCKETS: RoomBucket[] = shuffle<RoomBucket>([
  ...Array<RoomBucket>(22).fill('WITH_ZONE'),
  ...Array<RoomBucket>(9).fill('POINT_ONLY'),
  ...Array<RoomBucket>(21).fill('EMPTY'),
  ...Array<RoomBucket>(4).fill('CLOSED'),
])

// 按用户规模加权随机分配房间归属
const weightedOrgIds: number[] = []
ORG_SEEDS.forEach((seed, idx) => {
  const times = Math.max(1, Math.round(seed.userCount / 8))
  for (let i = 0; i < times; i++) weightedOrgIds.push(idx + 1)
})

const usedJoinCodes = new Set<string>()
function uniqueJoinCode(): string {
  let code = randomDigits(6)
  while (usedJoinCodes.has(code)) code = randomDigits(6)
  usedJoinCodes.add(code)
  return code
}

const ROOMS: MockRoom[] = []
let openRooms = 0
for (let i = 0; i < 56; i++) {
  const bucket = ROOM_BUCKETS[i]
  const orgId = pick(weightedOrgIds)
  const org = ORGS[orgId - 1] || ORGS[0]
  let members = 0
  let samples = 0
  let markers = 0
  let zones = 0
  let photos = 0
  let lastActiveAt = NOW - randInt(2, 60) * HOUR
  if (bucket === 'WITH_ZONE') {
    members = randInt(2, 6)
    samples = randInt(280, 1500)
    markers = randInt(4, 14)
    zones = randInt(1, 3)
    photos = randInt(2, 9)
    lastActiveAt = NOW - randInt(1, 90) * MINUTE
  } else if (bucket === 'POINT_ONLY') {
    members = randInt(1, 4)
    samples = randInt(60, 420)
    markers = randInt(1, 5)
    photos = randInt(0, 3)
    lastActiveAt = NOW - randInt(10, 400) * MINUTE
  } else if (bucket === 'EMPTY') {
    members = randInt(0, 2)
    samples = randInt(0, 16)
    markers = randInt(0, 1)
    lastActiveAt = NOW - randInt(6, 66) * HOUR
  } else {
    members = 0
    samples = randInt(80, 900)
    markers = randInt(0, 3)
    lastActiveAt = NOW - randInt(62, 71) * HOUR
  }
  const open = bucket !== 'CLOSED' && openRooms < 9
  if (open) openRooms++
  ROOMS.push({
    roomId: randomHex(12),
    name: `${ROOM_NAME_PREFIXES[i % ROOM_NAME_PREFIXES.length]} · 第 ${Math.floor(i / ROOM_NAME_PREFIXES.length) + 1} 组`,
    joinCode: uniqueJoinCode(),
    orgId: org.id,
    orgName: org.name,
    members: open ? Math.max(1, members) : members,
    samples,
    markers,
    zones,
    photos,
    createdAt: NOW - randInt(1, 24) * DAY - randInt(0, 23) * HOUR,
    lastActiveAt: open ? NOW - randInt(1, 42) * MINUTE : lastActiveAt,
    status: bucket === 'CLOSED' ? 'CLOSED' : open ? 'OPEN' : 'IDLE',
    bucket,
  })
}
ROOMS.sort((a, b) => b.lastActiveAt - a.lastActiveAt)

/* ----------------------------- 激活码 ------------------------------ */

const codeStatusPool: CodeStatus[] = shuffle<CodeStatus>([
  ...Array<CodeStatus>(36).fill('UNUSED'),
  ...Array<CodeStatus>(28).fill('USED'),
  ...Array<CodeStatus>(8).fill('REVOKED'),
  ...Array<CodeStatus>(8).fill('EXPIRED'),
])

const CODES: ActivationCode[] = []
let codeSeq = 1
const usedCodeValues = new Set<string>()
function uniqueCode(): string {
  let code = randomCode()
  while (usedCodeValues.has(code)) code = randomCode()
  usedCodeValues.add(code)
  return code
}

for (let i = 0; i < 80; i++) {
  const user = USERS[randInt(0, USERS.length - 1)]
  const purpose: CodePurpose = rnd() > 0.28 ? 'ACTIVATE' : 'RESET_PASSWORD'
  const createdAt = NOW - randInt(0, 30) * DAY - randInt(0, 23) * HOUR
  const expiresAt = createdAt + 7 * DAY
  let status = codeStatusPool[i]
  if (status === 'UNUSED' && expiresAt < NOW) status = 'EXPIRED'
  CODES.push({
    id: codeSeq++,
    code: uniqueCode(),
    userId: user.id,
    username: user.username,
    displayName: user.displayName,
    orgId: user.orgId,
    orgName: user.orgName,
    purpose,
    status,
    expiresAt,
    usedAt: status === 'USED' ? Math.min(createdAt + randInt(1, 60) * HOUR, NOW) : null,
    createdAt,
  })
}
CODES.sort((a, b) => b.createdAt - a.createdAt)

/* ---------------------------- 组织申请 ----------------------------- */

const APPLICATIONS: OrgApplication[] = [
  {
    id: 1,
    orgName: '南京市公安局网络安全保卫支队',
    contactName: '顾晓峰',
    contactPhone: '13905176602',
    email: 'guxf@nanjing.gov.cn',
    reason: '开展重点区域蜂窝信号覆盖实测，需要开通 30 个账号用于支队联合演练。',
    status: 'PENDING',
    reviewNote: null,
    createdAt: NOW - 2 * HOUR,
    reviewedAt: null,
  },
  {
    id: 2,
    orgName: '江苏警官学院·现代警务研究中心',
    contactName: '沈梦琪',
    contactPhone: '13813802288',
    email: 'shenmq@jspi.edu.cn',
    reason: '教学科研使用，拟在 11 月底组织一次 50 人规模的覆盖测试实训。',
    status: 'PENDING',
    reviewNote: null,
    createdAt: NOW - 26 * HOUR,
    reviewedAt: null,
  },
  {
    id: 3,
    orgName: '东南大学网络空间安全学院',
    contactName: '刘一舟',
    contactPhone: '13770551290',
    email: 'liuyz@seu.edu.cn',
    reason: '与公安大学联合课题，需要导出实测数据用于科研分析。',
    status: 'PENDING',
    reviewNote: null,
    createdAt: NOW - 3 * DAY,
    reviewedAt: null,
  },
  {
    id: 4,
    orgName: '杭州市人民检察院技术处',
    contactName: '郑一帆',
    contactPhone: '13588790031',
    email: 'zhengyf@hz.jcy.gov.cn',
    reason: '电子数据取证辅助，需要照片取证与审计功能。',
    status: 'APPROVED',
    reviewNote: '资质材料齐全，已开通。',
    createdAt: NOW - 9 * DAY,
    reviewedAt: NOW - 8 * DAY,
  },
  {
    id: 5,
    orgName: '某科技公司安全研究院',
    contactName: '匿名',
    contactPhone: '18600000000',
    email: 'test@example.com',
    reason: '希望体验平台功能。',
    status: 'REJECTED',
    reviewNote: '未提供有效单位资质材料，暂不开放。',
    createdAt: NOW - 12 * DAY,
    reviewedAt: NOW - 11 * DAY,
  },
]

/* ----------------------------- 审计日志 ---------------------------- */

const AUDIT_SEEDS: Array<Pick<AuditEvent, 'actorName' | 'orgName' | 'action' | 'detail' | 'level'>> = [
  { actorName: '系统管理员 · 王硕', orgName: null, action: '登录', detail: '通过密码登录管理站（IP 10.12.4.31）', level: 'info' },
  { actorName: '网安学院 · 李建军', orgName: '网安学院', action: '导出数据', detail: '导出房间 9b254c026a1a 采样 CSV（235 点）', level: 'success' },
  { actorName: '系统管理员 · 王硕', orgName: null, action: '审核组织申请', detail: '通过「杭州市人民检察院技术处」的组织申请', level: 'success' },
  { actorName: '网安学院 · 李建军', orgName: '网安学院', action: '生成激活码', detail: '为 12 名待激活用户生成激活码（p33-li 等）', level: 'info' },
  { actorName: '警院 · 王振华', orgName: '警院', action: '关闭房间', detail: '关闭房间 7d01（管理员操作）', level: 'warning' },
  { actorName: '系统', orgName: null, action: '清理空房', detail: 'TTL 清理房间 3 个，释放采样 418 点', level: 'info' },
  { actorName: '指挥学院 · 陈立群', orgName: '指挥学院', action: '重置密码', detail: '重置用户 cppsu-u014 密码并解绑设备', level: 'warning' },
  { actorName: '刑技学院 · 赵明远', orgName: '刑技学院', action: '批量导入用户', detail: '导入 18 名用户，失败 1 行（用户名重复）', level: 'success' },
  { actorName: '系统管理员 · 王硕', orgName: null, action: '创建组织', detail: '开通「江苏警官学院·现代警务研究中心」', level: 'success' },
  { actorName: '情报学院 · 周文博', orgName: '情报学院', action: '解绑设备', detail: '解绑用户 ppsuc-int_007 的 REDMI K80 Pro', level: 'info' },
  { actorName: '系统管理员 · 王硕', orgName: null, action: '修改配额', detail: '网安学院用户配额 100 → 120', level: 'info' },
  { actorName: '交管学院 · 孙志强', orgName: '交管学院', action: '登录', detail: '通过密码登录管理站（IP 10.12.7.88）', level: 'info' },
  { actorName: '特警学院 · 马骁', orgName: '特警学院', action: '作废激活码', detail: '作废激活码 QK7M2XPD（重复生成）', level: 'warning' },
  { actorName: '治安学院 · 吴海涛', orgName: '治安学院', action: '停用用户', detail: '停用用户 ppsuc-pub_013（岗位调整）', level: 'warning' },
  { actorName: '系统', orgName: null, action: '指标快照', detail: '写入 60s 指标快照，房间 56 / 在线 27', level: 'info' },
  { actorName: '北京网安 · 高峰', orgName: '北京网安', action: '导出数据', detail: '导出房间 8ae194600863 采样 GeoJSON（986 点）', level: 'success' },
  { actorName: '系统管理员 · 王硕', orgName: null, action: '备份', detail: '手动备份 admin.db 成功（1.8 MB）', level: 'success' },
  { actorName: '浙江科信 · 沈鹏', orgName: '浙江科信', action: '登录失败', detail: '用户 shenpeng 密码错误 1 次', level: 'danger' },
  { actorName: '深圳网警 · 林浩', orgName: '深圳网警', action: '生成激活码', detail: '为 5 名新用户生成激活码', level: 'info' },
  { actorName: '系统管理员 · 王硕', orgName: null, action: '停用组织', detail: '停用「广州铁路公安局网络安全处」', level: 'danger' },
  { actorName: '广铁网安 · 何俊', orgName: '广铁网安', action: '新建用户', detail: '创建用户 gzrail-cyber_012（李伟）', level: 'info' },
  { actorName: '系统', orgName: null, action: '清理空房', detail: '清理过期房间 2 个', level: 'info' },
  { actorName: '警院 · 王振华', orgName: '警院', action: '修改用户', detail: '修改用户 cppsu_018 显示名为「张浩然」', level: 'info' },
  { actorName: '系统管理员 · 王硕', orgName: null, action: '登录', detail: '通过密码登录管理站（IP 10.12.4.31）', level: 'info' },
  { actorName: '刑技学院 · 赵明远', orgName: '刑技学院', action: '导出数据', detail: '导出房间 e7c2a91b3d84 KML（412 点）', level: 'success' },
  { actorName: '系统', orgName: null, action: '房间 TTL', detail: '房间 TTL 调整为 7 天', level: 'info' },
  { actorName: '网安学院 · 李建军', orgName: '网安学院', action: '关闭房间', detail: '关闭房间 a6faea623f93（任务完成）', level: 'warning' },
  { actorName: '系统管理员 · 王硕', orgName: null, action: '重置密码', detail: '重置组织管理员 njupt_admin 密码', level: 'warning' },
  { actorName: '情报学院 · 周文博', orgName: '情报学院', action: '登录失败', detail: '登录限流触发：15 分钟内第 6 次尝试', level: 'danger' },
  { actorName: '交管学院 · 孙志强', orgName: '交管学院', action: '批量导入用户', detail: '导入 24 名用户，失败 2 行（用户名重复）', level: 'success' },
  { actorName: '系统', orgName: null, action: '指标快照', detail: '写入 60s 指标快照，房间 55 / 在线 21', level: 'info' },
  { actorName: '特警学院 · 马骁', orgName: '特警学院', action: '创建组织', detail: '开通「江苏警官学院·现代警务研究中心」', level: 'success' },
  { actorName: '系统管理员 · 王硕', orgName: null, action: '备份', detail: '手动备份 admin.db 成功（1.9 MB）', level: 'success' },
  { actorName: '治安学院 · 吴海涛', orgName: '治安学院', action: '修改配额', detail: '治安学院房间配额 8 → 12', level: 'info' },
]

const AUDIT_EVENTS: AuditEvent[] = AUDIT_SEEDS.map((seed, index) => ({
  id: 10_000 - index,
  ts: NOW - index * randInt(6, 19) * MINUTE,
  ...seed,
}))

let auditReadCount = 0
function maybeAppendAudit(): void {
  auditReadCount++
  if (auditReadCount % 5 !== 0) return
  const seed = AUDIT_SEEDS[randInt(0, AUDIT_SEEDS.length - 1)]
  AUDIT_EVENTS.unshift({
    id: AUDIT_EVENTS[0].id + 1,
    ts: Date.now(),
    actorName: seed.actorName,
    orgName: seed.orgName,
    action: seed.action,
    detail: seed.detail,
    level: seed.level,
  })
  if (AUDIT_EVENTS.length > 50) AUDIT_EVENTS.pop()
  // 保持 id 唯一即可，顺序按时间倒序
}

/* ------------------------------ 取证照片 ---------------------------- */

const PHOTO_DEVICES = [
  'Xiaomi 14 Pro',
  'HUAWEI Mate 60',
  'OPPO Find X7 Ultra',
  'vivo X100 Pro',
  'Samsung Galaxy S24',
  'iPhone 15 Pro',
  'Redmi K80 Pro',
  'HONOR Magic6',
]

const PHOTO_NOTES = [
  '天线抱杆与馈线走线',
  '基站机房外景',
  '楼顶定向天线方向',
  '弱覆盖区域全景',
  '可疑私装信号放大器',
  '电梯井内信号盲区',
  '地下车库入口覆盖',
  '室内分布系统标签',
  '墙体遮挡与穿透损耗',
  '室内吸顶天线位置',
]

const PHOTOS: PhotoItem[] = Array.from({ length: 37 }, (_, i) => {
  const kind: PhotoKind = i % 6 === 1 || i % 11 === 4 ? 'SUSPICIOUS' : 'PHOTO'
  const minutesAgo = i * randInt(37, 260)
  const ts = NOW - minutesAgo * MINUTE
  const hasFile = i % 12 !== 7
  return {
    code: `WL-${String(20261117 + (i % 3))}-${String(i + 1).padStart(4, '0')}`,
    sha256: randomHex(64),
    ts,
    lat: Number((39.9042 + (randInt(-800, 800) / 10000)).toFixed(6)),
    lon: Number((116.4074 + (randInt(-900, 900) / 10000)).toFixed(6)),
    device: pick(PHOTO_DEVICES),
    kind,
    note: pick(PHOTO_NOTES),
    serverTs: ts + randInt(20, 900) * 1000,
    hasFile,
    sizeBytes: randInt(860, 7600) * 1024,
  }
}).sort((a, b) => b.ts - a.ts)

/** mock 模式下的缩略图（真实模式仍走 /api/admin/photos/{code}/file） */
export function mockPhotoThumb(code: string, kind: PhotoKind): string {
  const accent = kind === 'SUSPICIOUS' ? '#F59E0B' : '#22D3EE'
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="480" height="360" viewBox="0 0 480 360">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#0B1526"/><stop offset="1" stop-color="#07101D"/>
    </linearGradient>
    <radialGradient id="glow" cx="0.5" cy="0.42" r="0.62">
      <stop offset="0" stop-color="${accent}" stop-opacity="0.28"/>
      <stop offset="1" stop-color="${accent}" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="480" height="360" fill="url(#bg)"/>
  <rect width="480" height="360" fill="url(#glow)"/>
  <g stroke="rgba(148,163,184,0.14)">
    <path d="M0 90H480M0 180H480M0 270H480M120 0V360M240 0V360M360 0V360"/>
  </g>
  <g fill="none" stroke="${accent}" stroke-width="2" opacity="0.85">
    <circle cx="240" cy="158" r="46"/><circle cx="240" cy="158" r="9"/>
    <path d="M240 92V68M240 248V224M174 158H150M330 158H306"/>
  </g>
  <text x="240" y="300" text-anchor="middle" fill="#E6EDF7" font-family="system-ui,-apple-system,'Segoe UI',sans-serif" font-size="18" font-weight="600">${code}</text>
  <text x="240" y="324" text-anchor="middle" fill="rgba(154,168,189,0.82)" font-family="system-ui,-apple-system,'Segoe UI',sans-serif" font-size="12">MOCK 缩略图 · ${kind === 'SUSPICIOUS' ? '可疑照片' : '普通照片'}</text>
</svg>`
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

/* --------------------------- 运行时设置 --------------------------- */

const runtimeSettings: AppSettings = {
  ok: true,
  roomTtlDays: 3,
  requireToken: false,
  adminTokenConfigured: true,
  superadminUser: 'admin',
  version: '1.12.0',
  schemaVersion: 1,
  auditRetentionDays: 180,
  activationCodeTtlDays: 7,
}

const SERVER_STARTED_AT = NOW - (4 * HOUR + 12 * MINUTE)

/* ------------------------------------------------------------------ */
/* 会话与密码                                                          */
/* ------------------------------------------------------------------ */

interface MockSession {
  user: AdminUser
  csrfToken: string
}

let session: MockSession | null = (() => {
  try {
    const raw = localStorage.getItem(STORAGE_SESSION)
    return raw ? (JSON.parse(raw) as MockSession) : null
  } catch {
    return null
  }
})()

function saveSession(): void {
  try {
    if (session) localStorage.setItem(STORAGE_SESSION, JSON.stringify(session))
    else localStorage.removeItem(STORAGE_SESSION)
  } catch {
    /* ignore */
  }
}

function readMustChange(): boolean {
  try {
    const raw = localStorage.getItem(STORAGE_MUST_CHANGE)
    return raw === null ? true : raw === '1'
  } catch {
    return true
  }
}

function writeMustChange(value: boolean): void {
  try {
    localStorage.setItem(STORAGE_MUST_CHANGE, value ? '1' : '0')
  } catch {
    /* ignore */
  }
}

function readPassword(username: string): string {
  try {
    const raw = localStorage.getItem(STORAGE_PASSWORD)
    if (raw) {
      const map = JSON.parse(raw) as Record<string, string>
      if (map[username]) return map[username]
    }
  } catch {
    /* ignore */
  }
  return username === 'admin' ? 'admin12345' : username === 'orgadmin' ? 'org12345' : ''
}

function writePassword(username: string, password: string): void {
  try {
    const raw = localStorage.getItem(STORAGE_PASSWORD)
    const map = raw ? (JSON.parse(raw) as Record<string, string>) : {}
    map[username] = password
    localStorage.setItem(STORAGE_PASSWORD, JSON.stringify(map))
  } catch {
    /* ignore */
  }
}

function requireSession(): MockSession {
  if (!session) throw new ApiError('未登录或会话已过期，请重新登录', 401, 'UNAUTHORIZED')
  return session
}

function isSuperAdmin(): boolean {
  return session?.user.role === 'SUPER_ADMIN'
}

/* ------------------------------------------------------------------ */
/* 工具                                                               */
/* ------------------------------------------------------------------ */

function qStr(query: Query, key: string): string {
  const value = query[key]
  return value === undefined || value === null ? '' : String(value).trim()
}

function qNum(query: Query, key: string): number | undefined {
  const value = query[key]
  if (value === undefined || value === null || value === '') return undefined
  const n = Number(value)
  return Number.isFinite(n) ? n : undefined
}

function paginate<T>(items: T[], query: Query): PageResult<T> {
  const page = Math.max(1, qNum(query, 'page') || 1)
  const size = Math.max(1, Math.min(200, qNum(query, 'size') || 20))
  const start = (page - 1) * size
  return { ok: true, total: items.length, items: items.slice(start, start + size), page, size }
}

function orgNameById(orgId: number): string {
  return ORGS.find((o) => o.id === orgId)?.name || '未归属组织'
}

function orgShortById(orgId: number): string {
  return ORG_SHORT.get(orgId) || orgNameById(orgId)
}

function liveOrgs(): Org[] {
  return ORGS.filter((org) => !deletedOrgIds.has(org.id))
}

function liveUsers(): UserItem[] {
  return USERS.filter((user) => !deletedOrgIds.has(user.orgId) && user.status !== 'DELETED')
}

function orgStats(orgId: number): { users: number; activeUsers: number; rooms: number; samples: number } {
  const users = USERS.filter((u) => u.orgId === orgId && u.status !== 'DELETED')
  const rooms = ROOMS.filter((r) => r.orgId === orgId)
  return {
    users: users.length,
    activeUsers: users.filter((u) => u.status === 'ACTIVE').length,
    rooms: rooms.length,
    samples: rooms.reduce((sum, r) => sum + r.samples, 0),
  }
}

function withOrgStats(org: Org): Org {
  const stats = orgStats(org.id)
  return {
    ...org,
    userCount: stats.users,
    activeUsers: stats.activeUsers,
    roomCount: stats.rooms,
    sampleCount: stats.samples,
  }
}

function makeUser(orgId: number, username: string, displayName: string, phone: string, role: UserItem['role'] = 'USER'): UserItem {
  return {
    id: Math.max(...USERS.map((u) => u.id), 1) + 1,
    orgId,
    orgName: orgNameById(orgId),
    username,
    displayName: displayName || username,
    phone: phone || '',
    role,
    status: 'PENDING',
    deviceId: null,
    deviceName: null,
    lastLoginAt: null,
    activatedAt: null,
    createdAt: Date.now(),
  }
}

function revokeUnusedCodes(userId: number, purpose?: CodePurpose): void {
  CODES.forEach((code) => {
    if (code.userId === userId && code.status === 'UNUSED' && (!purpose || code.purpose === purpose)) {
      code.status = 'REVOKED'
    }
  })
}

function issueCode(userId: number, purpose: CodePurpose): ActivationCode {
  const user = USERS.find((u) => u.id === userId)
  if (!user) throw new ApiError('用户不存在', 404)
  revokeUnusedCodes(userId, purpose)
  const createdAt = Date.now()
  const code: ActivationCode = {
    id: Math.max(0, ...CODES.map((c) => c.id)) + 1,
    code: uniqueCode(),
    userId: user.id,
    username: user.username,
    displayName: user.displayName,
    orgId: user.orgId,
    orgName: user.orgName,
    purpose,
    status: 'UNUSED',
    expiresAt: createdAt + 7 * DAY,
    usedAt: null,
    createdAt,
  }
  CODES.unshift(code)
  return code
}

function refreshCodeStatus(code: ActivationCode): ActivationCode {
  if (code.status === 'UNUSED' && code.expiresAt < Date.now()) code.status = 'EXPIRED'
  return code
}

/* ------------------------------------------------------------------ */
/* 仪表盘数据                                                          */
/* ------------------------------------------------------------------ */

function dayCurve(hour: number): number {
  return (
    0.12 +
    0.8 * Math.exp(-((hour - 10.5) ** 2) / 7) +
    0.95 * Math.exp(-((hour - 15.5) ** 2) / 6) +
    0.32 * Math.exp(-((hour - 20.5) ** 2) / 9)
  )
}

function weekdayFactor(day: number): number {
  return day === 0 || day === 6 ? 0.52 : 1
}

function seriesFor(metric: TimeseriesMetric, range: RangeKey): TimePoint[] {
  const bucketMs = range === '24h' ? 5 * MINUTE : range === '7d' ? HOUR : 4 * HOUR
  const count = range === '24h' ? 288 : range === '7d' ? 168 : 180
  const bucketMinutes = bucketMs / MINUTE
  const end = Math.floor(Date.now() / bucketMs) * bucketMs
  const gen = mulberry32(metric.length * 7919 + range.length * 104729 + 20261117)
  const noise = (amp: number) => (gen() - 0.5) * amp
  const points: TimePoint[] = []
  for (let i = count - 1; i >= 0; i--) {
    const ts = end - i * bucketMs
    const date = new Date(ts)
    const hour = date.getHours() + date.getMinutes() / 60
    const wf = weekdayFactor(date.getDay())
    let value: number
    if (metric === 'samples') {
      const scale = bucketMinutes / 5
      value = (dayCurve(hour) * 72 + noise(14)) * wf * scale
    } else if (metric === 'online') {
      value = (6 + dayCurve(hour) * 30) * wf + noise(4)
    } else {
      value = (28 + dayCurve(hour) * 78) * wf + noise(10)
    }
    points.push({ ts, value: Math.max(0, Math.round(value)) })
  }
  return points
}

function sampleCountByOrg(): { orgId: number; orgName: string; samples: number }[] {
  const map = new Map<number, number>()
  ROOMS.forEach((room) => map.set(room.orgId, (map.get(room.orgId) || 0) + room.samples))
  return liveOrgs()
    .map((org) => ({
      orgId: org.id,
      orgName: orgShortById(org.id),
      samples: map.get(org.id) || 0,
    }))
    .sort((a, b) => b.samples - a.samples)
}

function heatmapCells(days: number): HeatCell[] {
  const cells: HeatCell[] = []
  const gen = mulberry32(days * 31337 + 99)
  for (let weekday = 0; weekday < days; weekday++) {
    for (let hour = 0; hour < 24; hour++) {
      const weekend = weekday >= 5 ? 0.52 : 1
      const base = dayCurve(hour) * weekend * 58
      const value = Math.max(0, Math.round(base + (gen() - 0.5) * 18))
      cells.push({ weekday, hour, value })
    }
  }
  return cells
}

/* ------------------------------------------------------------------ */
/* M2：房间、照片、导出辅助                                            */
/* ------------------------------------------------------------------ */

function hashString(input: string): number {
  let hash = 2166136261
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  return hash >>> 0
}

function toRoomItem(room: MockRoom): RoomItem {
  const { bucket: _bucket, ...rest } = room
  return rest
}

function roomGeo(room: MockRoom): { lat: number; lon: number; alt: number } {
  const gen = mulberry32(hashString(room.roomId) + 17)
  return {
    lat: 39.9042 + (gen() - 0.5) * 0.028,
    lon: 116.4074 + (gen() - 0.5) * 0.034,
    alt: 12 + gen() * 42,
  }
}

const ZONE_KINDS = ['重点覆盖区', '信号盲区', '任务区域', '排查区域']
const MARKER_KINDS = ['目标出现', '可疑信号', '标点']

function buildRoomDetail(room: MockRoom): RoomDetailResult {
  const gen = mulberry32(hashString(room.roomId))
  const geo = roomGeo(room)
  const orgUsers = USERS.filter((user) => user.orgId === room.orgId && user.status !== 'DELETED')
  const memberCount = Math.min(room.members, 8)
  const members: RoomMember[] = Array.from({ length: memberCount }, (_, i) => {
    const user = orgUsers[(hashString(room.roomId) + i * 7) % Math.max(1, orgUsers.length)]
    return {
      userId: user?.username || `member-${i + 1}`,
      name: user?.displayName || `成员 ${i + 1}`,
      role: i === 0 ? '创建者' : '成员',
      lat: Number((geo.lat + (gen() - 0.5) * 0.0022).toFixed(6)),
      lon: Number((geo.lon + (gen() - 0.5) * 0.0026).toFixed(6)),
      lastSeenAt: NOW - Math.round(gen() * 90) * MINUTE,
      online: room.status === 'OPEN' && i < Math.max(1, Math.round(memberCount * 0.7)),
    }
  })

  const zones: RoomZone[] = Array.from({ length: room.zones }, (_, i) => ({
    id: `zone-${room.roomId.slice(0, 4)}-${i + 1}`,
    kind: pick(ZONE_KINDS),
    note: `半径内需完成 ${randInt(6, 24)} 个采样点复核`,
    assignees: members.slice(0, Math.min(members.length, 2 + (i % 2))).map((m) => m.name),
    centerLat: Number((geo.lat + (i - room.zones / 2) * 0.0008).toFixed(6)),
    centerLon: Number((geo.lon + (i - room.zones / 2) * 0.001).toFixed(6)),
    radiusM: randInt(60, 260),
  }))

  const markers: RoomMarker[] = Array.from({ length: room.markers }, (_, i) => ({
    id: `mark-${room.roomId.slice(0, 4)}-${i + 1}`,
    name: `标点 ${i + 1}`,
    byName: members[i % Math.max(1, members.length)]?.name || '系统',
    lat: Number((geo.lat + (randInt(-14, 14) / 10000)).toFixed(6)),
    lon: Number((geo.lon + (randInt(-14, 14) / 10000)).toFixed(6)),
    kind: pick(MARKER_KINDS),
    ts: NOW - randInt(5, 300) * MINUTE,
  }))

  const samples = buildRoomSamples(room)
  return {
    ok: true,
    room: toRoomItem(room),
    members,
    zones,
    markers,
    recentSamples: samples.slice(-12),
  }
}

function buildRoomSamples(room: MockRoom, limit = 500): RoomSample[] {
  if (room.samples === 0) return []
  const geo = roomGeo(room)
  const gen = mulberry32(hashString(room.roomId) + 31)
  const count = Math.min(limit, Math.max(24, Math.min(room.samples, 240)))
  const start = room.createdAt
  const end = Math.max(start + HOUR, room.lastActiveAt)
  const span = end - start
  return Array.from({ length: count }, (_, i) => {
    const lat = geo.lat + (gen() - 0.5) * 0.0042
    const lon = geo.lon + (gen() - 0.5) * 0.005
    return {
      userId: `member-${(i % Math.max(1, room.members || 1)) + 1}`,
      lat: Number(lat.toFixed(6)),
      lon: Number(lon.toFixed(6)),
      ts: Math.round(start + (span * i) / Math.max(1, count - 1)),
      targetSeen: gen() > 0.62,
      rsrp: -60 - Math.round(gen() * 55),
    }
  })
}

function filterPhotos(query: Query): PhotoItem[] {
  const kind = qStr(query, 'kind')
  const keyword = qStr(query, 'q').toLowerCase()
  const onlyUploaded = qStr(query, 'onlyUploaded') === 'true'
  let items = [...PHOTOS]
  if (kind) items = items.filter((photo) => photo.kind === kind)
  if (onlyUploaded) items = items.filter((photo) => photo.hasFile)
  if (keyword) {
    items = items.filter(
      (photo) =>
        photo.code.toLowerCase().includes(keyword) ||
        photo.device.toLowerCase().includes(keyword) ||
        photo.note.toLowerCase().includes(keyword),
    )
  }
  return items.sort((a, b) => b.ts - a.ts)
}

function filterAudit(query: Query): AuditEvent[] {
  const level = qStr(query, 'level')
  const action = qStr(query, 'action')
  const keyword = qStr(query, 'q').toLowerCase()
  const orgId = qNum(query, 'orgId')
  const orgName = orgId ? ORGS.find((org) => org.id === orgId)?.name : ''
  let items = [...AUDIT_EVENTS]
  if (level) items = items.filter((event) => event.level === level)
  if (action) items = items.filter((event) => event.action.includes(action))
  if (orgName) items = items.filter((event) => event.orgName === orgName)
  if (keyword) {
    items = items.filter(
      (event) =>
        event.actorName.toLowerCase().includes(keyword) ||
        event.action.toLowerCase().includes(keyword) ||
        event.detail.toLowerCase().includes(keyword) ||
        (event.orgName || '').toLowerCase().includes(keyword),
    )
  }
  return items.sort((a, b) => b.ts - a.ts)
}

function auditCsv(events: AuditEvent[]): string {
  return toCsv(
    ['时间', '操作人', '组织', '动作', '等级', '详情'],
    events.map((event) => [
      new Date(event.ts).toISOString(),
      event.actorName,
      event.orgName || '平台',
      event.action,
      event.level,
      event.detail,
    ]),
  )
}

/* ------------------------------------------------------------------ */
/* 路由                                                               */
/* ------------------------------------------------------------------ */

function delay(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 90 + Math.random() * 180))
}

export async function mockRequest(
  method: string,
  rawPath: string,
  body?: unknown,
  query?: Query,
): Promise<unknown> {
  await delay()
  const path = rawPath.replace(/\/+$/, '') || '/'
  const q = query || {}
  const payload = (body || {}) as Record<string, unknown>
  const ok = <R>(data: R): R => data

  /* ----------------------------- 认证 ----------------------------- */

  if (method === 'POST' && path === '/auth/login') {
    const username = String(payload.username || '').trim()
    const password = String(payload.password || '')
    const expected = readPassword(username)
    if (!expected || password !== expected) {
      throw new ApiError('用户名或密码错误', 401, 'BAD_CREDENTIALS')
    }
    let user: AdminUser
    if (username === 'admin') {
      user = { ...PLATFORM_ADMIN, mustChangePassword: readMustChange() }
    } else if (username === 'orgadmin') {
      user = { ...ORG_DEMO_ADMIN }
    } else {
      throw new ApiError('用户名或密码错误', 401, 'BAD_CREDENTIALS')
    }
    session = { user, csrfToken: `mock-csrf-${randomHex(16)}` }
    if (username === 'admin') writeMustChange(user.mustChangePassword)
    saveSession()
    return ok<LoginResult>({ ok: true, user, csrfToken: session.csrfToken })
  }

  if (method === 'GET' && path === '/auth/me') {
    const current = requireSession()
    return ok<LoginResult>({ ok: true, user: current.user, csrfToken: current.csrfToken })
  }

  if (method === 'POST' && path === '/auth/logout') {
    session = null
    saveSession()
    return ok({ ok: true })
  }

  if (method === 'POST' && path === '/auth/change-password') {
    const current = requireSession()
    const oldPassword = String(payload.oldPassword || '')
    const newPassword = String(payload.newPassword || '')
    if (oldPassword !== readPassword(current.user.username)) {
      throw new ApiError('原密码不正确', 400, 'BAD_OLD_PASSWORD')
    }
    if (!/^(?=.*[A-Za-z])(?=.*\d).{8,32}$/.test(newPassword)) {
      throw new ApiError('新密码需 8–32 位，且同时包含字母与数字', 400, 'WEAK_PASSWORD')
    }
    writePassword(current.user.username, newPassword)
    current.user = { ...current.user, mustChangePassword: false }
    if (current.user.username === 'admin') writeMustChange(false)
    session = current
    saveSession()
    return ok({ ok: true })
  }

  /* --------------------------- 仪表盘 ----------------------------- */

  requireSession()
  const live = liveUsers()
  const activeUsers = live.filter((u) => u.status === 'ACTIVE').length
  const pendingUsers = live.filter((u) => u.status === 'PENDING').length

  if (method === 'GET' && path === '/dashboard/summary') {
    const summary: DashboardSummary = {
      ok: true,
      kpi: {
        orgs: liveOrgs().length,
        orgsDelta: 2,
        users: live.length,
        activeUsers,
        pendingUsers,
        onlineMembers: 27,
        onlinePeak: 41,
        rooms: ROOMS.length,
        activeRooms: ROOMS.filter((r) => r.status === 'OPEN').length,
        todaySamples: 12480,
        samplesDeltaPct: 18.0,
        photos: PHOTOS.length,
        audits24h: 128,
      },
      server: { version: '1.12.0', uptimeSec: 4 * 3600 + 12 * 60, roomsInMemory: ROOMS.length, onlineMembers: 27 },
    }
    return ok(summary)
  }

  if (method === 'GET' && path === '/dashboard/timeseries') {
    const metric = (qStr(q, 'metric') || 'samples') as TimeseriesMetric
    const range = (qStr(q, 'range') || '24h') as RangeKey
    if (!['samples', 'online', 'ws'].includes(metric)) throw new ApiError('metric 参数不合法', 400)
    if (!['24h', '7d', '30d'].includes(range)) throw new ApiError('range 参数不合法', 400)
    return ok<TimeseriesResult>({ ok: true, points: seriesFor(metric, range) })
  }

  if (method === 'GET' && path === '/dashboard/top-orgs') {
    return ok<TopOrgsResult>({ ok: true, items: sampleCountByOrg().slice(0, 8) })
  }

  if (method === 'GET' && path === '/dashboard/heatmap') {
    const days = Math.min(7, Math.max(1, qNum(q, 'days') || 7))
    return ok<HeatmapResult>({ ok: true, cells: heatmapCells(days) })
  }

  if (method === 'GET' && path === '/dashboard/user-status') {
    const order: UserStatus[] = ['ACTIVE', 'PENDING', 'SUSPENDED', 'LOCKED']
    return ok<StatusCountResult>({
      ok: true,
      items: order.map((status) => ({ status, count: live.filter((u) => u.status === status).length })),
    })
  }

  if (method === 'GET' && path === '/dashboard/room-status') {
    return ok<RoomStatusResult>({
      ok: true,
      items: [
        { key: 'WITH_ZONE', count: ROOMS.filter((r) => r.bucket === 'WITH_ZONE').length },
        { key: 'POINT_ONLY', count: ROOMS.filter((r) => r.bucket === 'POINT_ONLY').length },
        { key: 'EMPTY', count: ROOMS.filter((r) => r.bucket === 'EMPTY').length },
        { key: 'CLOSED', count: ROOMS.filter((r) => r.bucket === 'CLOSED').length },
      ],
    })
  }

  if (method === 'GET' && path === '/dashboard/recent-rooms') {
    const items: RecentRoom[] = [...ROOMS]
      .sort((a, b) => b.lastActiveAt - a.lastActiveAt)
      .slice(0, 8)
      .map(({ bucket: _bucket, ...room }) => room)
    return ok({ ok: true, items })
  }

  if (method === 'GET' && path === '/audit') {
    maybeAppendAudit()
    return ok(paginate(filterAudit(q), q))
  }

  /* ----------------------------- 房间 ------------------------------ */

  if (method === 'GET' && path === '/rooms') {
    const status = qStr(q, 'status')
    const orgId = qNum(q, 'orgId')
    const keyword = qStr(q, 'q').toLowerCase()
    let items = [...ROOMS]
    if (status) items = items.filter((room) => room.status === status)
    if (orgId) items = items.filter((room) => room.orgId === orgId)
    if (keyword) {
      items = items.filter(
        (room) =>
          room.roomId.toLowerCase().includes(keyword) ||
          room.name.toLowerCase().includes(keyword) ||
          room.joinCode.toLowerCase().includes(keyword),
      )
    }
    items.sort((a, b) => b.lastActiveAt - a.lastActiveAt)
    return ok(paginate(items.map(toRoomItem), q))
  }

  if (method === 'POST' && path === '/rooms/purge') {
    const days = Math.max(1, qNum(q, 'days') || 3)
    const cutoff = Date.now() - days * DAY
    const before = ROOMS.length
    for (let i = ROOMS.length - 1; i >= 0; i--) {
      const room = ROOMS[i]
      if (room.status !== 'OPEN' && room.lastActiveAt < cutoff) ROOMS.splice(i, 1)
    }
    return ok({ ok: true, removed: before - ROOMS.length })
  }

  let roomMatch = path.match(/^\/rooms\/([A-Za-z0-9]+)$/)
  if (method === 'GET' && roomMatch) {
    const room = ROOMS.find((item) => item.roomId === roomMatch?.[1])
    if (!room) throw new ApiError('房间不存在或已关闭', 404, 'ROOM_NOT_FOUND')
    return ok(buildRoomDetail(room))
  }

  roomMatch = path.match(/^\/rooms\/([A-Za-z0-9]+)\/samples$/)
  if (method === 'GET' && roomMatch) {
    const room = ROOMS.find((item) => item.roomId === roomMatch?.[1])
    if (!room) throw new ApiError('房间不存在或已关闭', 404, 'ROOM_NOT_FOUND')
    const limit = Math.min(500, Math.max(1, qNum(q, 'limit') || 500))
    return ok<RoomSamplesResult>({ ok: true, items: buildRoomSamples(room, limit) })
  }

  roomMatch = path.match(/^\/rooms\/([A-Za-z0-9]+)\/close$/)
  if (method === 'POST' && roomMatch) {
    const room = ROOMS.find((item) => item.roomId === roomMatch?.[1])
    if (!room) throw new ApiError('房间不存在或已关闭', 404, 'ROOM_NOT_FOUND')
    room.status = 'CLOSED'
    room.bucket = 'CLOSED'
    room.members = 0
    room.lastActiveAt = Date.now()
    return ok({ ok: true })
  }

  /* ----------------------------- 照片 ------------------------------ */

  if (method === 'GET' && path === '/photos') {
    return ok(paginate(filterPhotos(q), q))
  }

  /* ----------------------------- 运维 ------------------------------ */

  if (method === 'GET' && path === '/ops/status') {
    const uptimeSec = Math.floor((Date.now() - SERVER_STARTED_AT) / 1000)
    const totalSamples = ROOMS.reduce((sum, room) => sum + room.samples, 0)
    const status: OpsStatus = {
      ok: true,
      server: {
        version: runtimeSettings.version,
        uptimeSec,
        startedAt: SERVER_STARTED_AT,
        javaVersion: 'OpenJDK 17.0.11',
        osName: 'Linux 5.15.0 · x86_64',
        cpuCores: 8,
        heapUsedBytes: 336 * 1024 * 1024,
        heapMaxBytes: 1024 * 1024 * 1024,
        threads: 46,
        dataDir: '/var/lib/weilie',
        dbBytes: 2_248_704,
        photosDirBytes: 1_486_028_800,
        roomsDirBytes: 398_458_880,
        diskFreeBytes: 42_949_672_960,
      },
      rooms: {
        total: ROOMS.length,
        open: ROOMS.filter((room) => room.status === 'OPEN').length,
        online: Math.min(27, USERS.filter((user) => user.status === 'ACTIVE').length),
      },
      metrics: {
        wsConnections: 22,
        messages: 1_284_920,
        samples: totalSamples,
        photosUploaded: PHOTOS.filter((photo) => photo.hasFile).length,
        rateLimited: 37,
        errors: 3,
        exports: 128,
      },
      limits: {
        roomTtlDays: runtimeSettings.roomTtlDays,
        requireToken: runtimeSettings.requireToken,
        adminTokenConfigured: runtimeSettings.adminTokenConfigured,
      },
    }
    return ok(status)
  }

  /* ----------------------------- 设置 ------------------------------ */

  if (method === 'GET' && path === '/settings') {
    return ok({ ...runtimeSettings })
  }

  if (method === 'PATCH' && path === '/settings') {
    if (payload.roomTtlDays !== undefined) {
      const ttl = Number(payload.roomTtlDays)
      if (!Number.isInteger(ttl) || ttl < 1 || ttl > 30) {
        throw new ApiError('房间 TTL 需为 1–30 天的整数', 400, 'INVALID_SETTING')
      }
      runtimeSettings.roomTtlDays = ttl
    }
    if (payload.requireToken !== undefined) {
      runtimeSettings.requireToken = Boolean(payload.requireToken)
    }
    return ok({ ...runtimeSettings })
  }

  /* ----------------------------- 组织 ----------------------------- */

  if (method === 'GET' && path === '/orgs') {
    const keyword = qStr(q, 'q').toLowerCase()
    const status = qStr(q, 'status')
    let items = liveOrgs().map(withOrgStats)
    if (!isSuperAdmin()) items = items.filter((org) => org.id === session?.user.orgId)
    if (keyword) {
      items = items.filter(
        (org) =>
          org.name.toLowerCase().includes(keyword) ||
          org.code.toLowerCase().includes(keyword) ||
          (org.contactName || '').toLowerCase().includes(keyword),
      )
    }
    if (status) items = items.filter((org) => org.status === status)
    items.sort((a, b) => b.createdAt - a.createdAt)
    return ok(paginate(items, q))
  }

  if (method === 'POST' && path === '/orgs') {
    const name = String(payload.name || '').trim()
    const code = String(payload.code || '').trim()
    if (!name) throw new ApiError('组织名称不能为空', 400)
    if (!/^[a-z0-9][a-z0-9-]{1,30}$/.test(code)) throw new ApiError('组织编码需为小写字母、数字或连字符（2–31 位）', 400)
    if (ORGS.some((org) => org.code === code)) throw new ApiError('组织编码已存在', 409)

    const orgId = Math.max(0, ...ORGS.map((o) => o.id)) + 1
    const org: Org = {
      id: orgId,
      name,
      code,
      contactName: String(payload.contactName || ''),
      contactPhone: String(payload.contactPhone || ''),
      status: 'ACTIVE',
      quotaUsers: Number(payload.quotaUsers) || 50,
      quotaRooms: Number(payload.quotaRooms) || 5,
      createdAt: Date.now(),
    }
    ORGS.push(org)
    ORG_SHORT.set(org.id, name.replace(/^.*·/, '').slice(0, 8))

    const adminUsername = String(payload.adminUsername || '').trim()
    const adminDisplayName = String(payload.adminDisplayName || '').trim() || '组织管理员'
    const adminPassword = String(payload.adminPassword || '')
    if (!adminUsername) throw new ApiError('组织管理员用户名不能为空', 400)
    if (USERS.some((u) => u.username === adminUsername)) throw new ApiError('管理员用户名已存在', 409)
    if (adminPassword.length < 8) throw new ApiError('管理员初始密码至少 8 位', 400)

    const adminUser = makeUser(org.id, adminUsername, adminDisplayName, String(payload.contactPhone || ''), 'ORG_ADMIN')
    adminUser.status = 'ACTIVE'
    adminUser.activatedAt = Date.now()
    adminUser.createdAt = Date.now()
    USERS.push(adminUser)

    return ok({ ok: true, org: withOrgStats(org), adminUser })
  }

  let match = path.match(/^\/orgs\/(\d+)$/)
  if (match) {
    const orgId = Number(match[1])
    const org = ORGS.find((item) => item.id === orgId)
    if (!org || deletedOrgIds.has(orgId)) throw new ApiError('组织不存在', 404)
    if (method === 'GET') {
      const stats = orgStats(orgId)
      return ok({ ok: true, org: withOrgStats(org), stats })
    }
    if (method === 'PATCH') {
      if (payload.name !== undefined) org.name = String(payload.name)
      if (payload.contactName !== undefined) org.contactName = String(payload.contactName)
      if (payload.contactPhone !== undefined) org.contactPhone = String(payload.contactPhone)
      if (payload.quotaUsers !== undefined) org.quotaUsers = Number(payload.quotaUsers)
      if (payload.quotaRooms !== undefined) org.quotaRooms = Number(payload.quotaRooms)
      if (payload.status !== undefined) org.status = payload.status as Org['status']
      // 同步用户名下的 orgName
      USERS.forEach((user) => {
        if (user.orgId === orgId) user.orgName = org.name
      })
      return ok({ ok: true, org: withOrgStats(org) })
    }
    if (method === 'DELETE') {
      deletedOrgIds.add(orgId)
      return ok({ ok: true })
    }
  }

  /* --------------------------- 组织申请 ---------------------------- */

  if (method === 'GET' && path === '/org-applications') {
    const status = qStr(q, 'status')
    let items = [...APPLICATIONS]
    if (status) items = items.filter((item) => item.status === status)
    items.sort((a, b) => b.createdAt - a.createdAt)
    return ok(paginate(items, q))
  }

  match = path.match(/^\/org-applications\/(\d+)\/approve$/)
  if (method === 'POST' && match) {
    const app = APPLICATIONS.find((item) => item.id === Number(match?.[1]))
    if (!app) throw new ApiError('申请不存在', 404)
    if (app.status !== 'PENDING') throw new ApiError('该申请已处理', 409)
    const code = String(payload.code || '').trim()
    const adminUsername = String(payload.adminUsername || '').trim()
    const adminPassword = String(payload.adminPassword || '')
    if (!/^[a-z0-9][a-z0-9-]{1,30}$/.test(code)) throw new ApiError('组织编码需为小写字母、数字或连字符（2–31 位）', 400)
    if (ORGS.some((org) => org.code === code)) throw new ApiError('组织编码已存在', 409)
    if (!adminUsername) throw new ApiError('管理员用户名不能为空', 400)
    if (USERS.some((u) => u.username === adminUsername)) throw new ApiError('管理员用户名已存在', 409)
    if (adminPassword.length < 8) throw new ApiError('管理员初始密码至少 8 位', 400)

    const org: Org = {
      id: Math.max(0, ...ORGS.map((o) => o.id)) + 1,
      name: app.orgName,
      code,
      contactName: app.contactName,
      contactPhone: app.contactPhone,
      status: 'ACTIVE',
      quotaUsers: 200,
      quotaRooms: 20,
      createdAt: Date.now(),
    }
    ORGS.push(org)
    ORG_SHORT.set(org.id, app.orgName.replace(/^.*·/, '').slice(0, 8))
    const adminUser = makeUser(org.id, adminUsername, `${app.contactName}`, app.contactPhone, 'ORG_ADMIN')
    adminUser.status = 'ACTIVE'
    adminUser.activatedAt = Date.now()
    USERS.push(adminUser)
    app.status = 'APPROVED'
    app.reviewNote = '管理站审批通过'
    app.reviewedAt = Date.now()
    return ok({ ok: true, org: withOrgStats(org), adminUser })
  }

  match = path.match(/^\/org-applications\/(\d+)\/reject$/)
  if (method === 'POST' && match) {
    const app = APPLICATIONS.find((item) => item.id === Number(match?.[1]))
    if (!app) throw new ApiError('申请不存在', 404)
    if (app.status !== 'PENDING') throw new ApiError('该申请已处理', 409)
    app.status = 'REJECTED'
    app.reviewNote = String(payload.reason || '未通过审核')
    app.reviewedAt = Date.now()
    return ok({ ok: true })
  }

  /* ----------------------------- 用户 ----------------------------- */

  if (method === 'GET' && path === '/users') {
    const orgId = qNum(q, 'orgId')
    const status = qStr(q, 'status')
    const keyword = qStr(q, 'q').toLowerCase()
    let items = liveUsers().filter((user) => user.status !== 'DELETED')
    if (!isSuperAdmin()) items = items.filter((user) => user.orgId === session?.user.orgId)
    if (orgId) items = items.filter((user) => user.orgId === orgId)
    if (status) items = items.filter((user) => user.status === status)
    if (keyword) {
      items = items.filter(
        (user) =>
          user.username.toLowerCase().includes(keyword) ||
          user.displayName.toLowerCase().includes(keyword) ||
          (user.phone || '').includes(keyword),
      )
    }
    items.sort((a, b) => b.createdAt - a.createdAt)
    return ok(paginate(items, q))
  }

  if (method === 'POST' && path === '/users') {
    const orgId = Number(payload.orgId)
    const org = liveOrgs().find((item) => item.id === orgId)
    if (!org) throw new ApiError('请选择有效的组织', 400)
    if (!isSuperAdmin() && orgId !== session?.user.orgId) throw new ApiError('无权在该组织下创建用户', 403)
    const username = String(payload.username || '').trim()
    if (!/^[a-zA-Z0-9_.-]{3,32}$/.test(username)) throw new ApiError('用户名需 3–32 位字母、数字或 _ . -', 400)
    if (USERS.some((u) => u.orgId === orgId && u.username === username && u.status !== 'DELETED')) {
      throw new ApiError('该组织下用户名已存在', 409)
    }
    const user = makeUser(
      orgId,
      username,
      String(payload.displayName || '').trim() || username,
      String(payload.phone || '').trim(),
      (payload.role as UserItem['role']) || 'USER',
    )
    USERS.push(user)
    return ok({ ok: true, user })
  }

  if (method === 'POST' && path === '/users/import') {
    const orgId = Number(payload.orgId)
    const org = liveOrgs().find((item) => item.id === orgId)
    if (!org) throw new ApiError('请选择有效的组织', 400)
    if (!isSuperAdmin() && orgId !== session?.user.orgId) throw new ApiError('无权在该组织下创建用户', 403)
    const rows = Array.isArray(payload.rows) ? (payload.rows as Array<Record<string, unknown>>) : []
    if (!rows.length) throw new ApiError('导入数据为空', 400)
    if (rows.length > 200) throw new ApiError('单次最多导入 200 行', 400)

    const failed: ImportResult['failed'] = []
    const codes: ImportResult['codes'] = []
    let created = 0
    const existingNames = new Set(USERS.filter((u) => u.orgId === orgId && u.status !== 'DELETED').map((u) => u.username))
    rows.forEach((row, index) => {
      const username = String(row.username || '').trim()
      const displayName = String(row.displayName || '').trim() || username
      const phone = String(row.phone || '').trim()
      const rowNo = index + 2
      if (!/^[a-zA-Z0-9_.-]{3,32}$/.test(username)) {
        failed.push({ row: rowNo, reason: `用户名「${username || '空'}」格式不正确` })
        return
      }
      if (existingNames.has(username)) {
        failed.push({ row: rowNo, reason: `用户名「${username}」已存在` })
        return
      }
      const user = makeUser(orgId, username, displayName, phone)
      USERS.push(user)
      existingNames.add(username)
      const code = issueCode(user.id, 'ACTIVATE')
      codes.push({ username, code: code.code })
      created++
    })
    return ok<ImportResult>({ ok: true, created, failed, codes })
  }

  match = path.match(/^\/users\/(\d+)\/activation-code$/)
  if (method === 'POST' && match) {
    const userId = Number(match[1])
    const purpose: CodePurpose = payload.purpose === 'RESET_PASSWORD' ? 'RESET_PASSWORD' : 'ACTIVATE'
    const code = issueCode(userId, purpose)
    return ok({ ok: true, id: code.id, code: code.code, expiresAt: code.expiresAt, purpose: code.purpose })
  }

  match = path.match(/^\/users\/(\d+)\/reset-password$/)
  if (method === 'POST' && match) {
    const userId = Number(match[1])
    const code = issueCode(userId, 'RESET_PASSWORD')
    // 重置密码要求先解绑旧设备
    const user = USERS.find((u) => u.id === userId)
    if (user) {
      user.deviceId = null
      user.deviceName = null
    }
    return ok({ ok: true, id: code.id, code: code.code, expiresAt: code.expiresAt, purpose: code.purpose })
  }

  match = path.match(/^\/users\/(\d+)\/unbind-device$/)
  if (method === 'POST' && match) {
    const user = USERS.find((u) => u.id === Number(match?.[1]))
    if (!user) throw new ApiError('用户不存在', 404)
    user.deviceId = null
    user.deviceName = null
    return ok({ ok: true })
  }

  match = path.match(/^\/users\/(\d+)\/codes$/)
  if (method === 'GET' && match) {
    const items = CODES.filter((code) => code.userId === Number(match?.[1]))
      .map(refreshCodeStatus)
      .sort((a, b) => b.createdAt - a.createdAt)
    return ok({ ok: true, items })
  }

  match = path.match(/^\/users\/(\d+)$/)
  if (match) {
    const userId = Number(match[1])
    const user = USERS.find((u) => u.id === userId)
    if (!user) throw new ApiError('用户不存在', 404)
    if (method === 'PATCH') {
      if (payload.displayName !== undefined) user.displayName = String(payload.displayName)
      if (payload.phone !== undefined) user.phone = String(payload.phone)
      if (payload.role !== undefined) user.role = payload.role as UserItem['role']
      if (payload.status !== undefined) {
        const status = payload.status as UserStatus
        if (!['ACTIVE', 'PENDING', 'SUSPENDED', 'LOCKED', 'DELETED'].includes(status)) {
          throw new ApiError('状态不合法', 400)
        }
        user.status = status
        if (status !== 'ACTIVE') {
          user.deviceId = null
          user.deviceName = null
        }
      }
      return ok({ ok: true, user })
    }
    if (method === 'DELETE') {
      user.status = 'DELETED'
      return ok({ ok: true })
    }
  }

  /* --------------------------- 激活码 ----------------------------- */

  if (method === 'GET' && path === '/activation-codes') {
    const orgId = qNum(q, 'orgId')
    const status = qStr(q, 'status')
    const keyword = qStr(q, 'q').toLowerCase()
    let items = CODES.map(refreshCodeStatus)
    if (!isSuperAdmin()) items = items.filter((code) => code.orgId === session?.user.orgId)
    if (orgId) items = items.filter((code) => code.orgId === orgId)
    if (status) items = items.filter((code) => code.status === status)
    if (keyword) {
      items = items.filter(
        (code) =>
          code.code.toLowerCase().includes(keyword) ||
          code.username.toLowerCase().includes(keyword) ||
          code.displayName.toLowerCase().includes(keyword),
      )
    }
    return ok(paginate(items, q))
  }

  match = path.match(/^\/activation-codes\/(\d+)\/revoke$/)
  if (method === 'POST' && match) {
    const code = CODES.find((item) => item.id === Number(match?.[1]))
    if (!code) throw new ApiError('激活码不存在', 404)
    if (code.status === 'USED') throw new ApiError('激活码已被使用，无法作废', 409)
    code.status = 'REVOKED'
    return ok({ ok: true })
  }

  throw new ApiError(`mock 未实现接口：${method} ${path}`, 404, 'MOCK_NOT_IMPLEMENTED')
}

/* ------------------------------------------------------------------ */
/* 文件下载（mock）                                                    */
/* ------------------------------------------------------------------ */

export interface MockDownloadFile {
  filename: string
  mime: string
  content: string
}

export function mockDownload(path: string, query?: Query): MockDownloadFile {
  requireSession()
  const q = query || {}

  const roomMatch = path.match(/^\/rooms\/([A-Za-z0-9]+)\/export$/)
  if (roomMatch) {
    const room = ROOMS.find((item) => item.roomId === roomMatch[1])
    if (!room) throw new ApiError('房间不存在或已关闭', 404, 'ROOM_NOT_FOUND')
    const format = (qStr(q, 'format') || 'csv').toLowerCase()
    const samples = buildRoomSamples(room)
    if (format === 'csv') {
      const content = toCsv(
        ['用户', '纬度', '经度', '时间', '发现目标', 'RSRP(dBm)'],
        samples.map((sample) => [
          sample.userId,
          sample.lat,
          sample.lon,
          new Date(sample.ts).toISOString(),
          sample.targetSeen ? '1' : '0',
          sample.rsrp ?? '',
        ]),
      )
      return { filename: `room-${room.roomId}.csv`, mime: 'text/csv;charset=utf-8', content }
    }
    if (format === 'geojson') {
      const content = JSON.stringify(
        {
          type: 'FeatureCollection',
          features: samples.map((sample) => ({
            type: 'Feature',
            geometry: { type: 'Point', coordinates: [sample.lon, sample.lat] },
            properties: {
              roomId: room.roomId,
              roomName: room.name,
              userId: sample.userId,
              ts: sample.ts,
              targetSeen: sample.targetSeen,
              rsrp: sample.rsrp,
            },
          })),
        },
        null,
        2,
      )
      return { filename: `room-${room.roomId}.geojson`, mime: 'application/geo+json', content }
    }
    if (format === 'kml') {
      const placemarks = samples
        .map(
          (sample) => `    <Placemark>
      <name>${sample.userId} · ${sample.targetSeen ? '发现目标' : '未发现'}</name>
      <description>${new Date(sample.ts).toISOString()} · RSRP ${sample.rsrp ?? '—'} dBm</description>
      <Point><coordinates>${sample.lon},${sample.lat},0</coordinates></Point>
    </Placemark>`,
        )
        .join('\n')
      const content = `<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <name>${room.name}（${room.roomId}）</name>
${placemarks}
  </Document>
</kml>`
      return { filename: `room-${room.roomId}.kml`, mime: 'application/vnd.google-earth.kml+xml', content }
    }
    throw new ApiError(`不支持的导出格式：${format}`, 400, 'BAD_EXPORT_FORMAT')
  }

  if (path === '/audit/export') {
    const events = filterAudit(q)
    return {
      filename: `audit-${new Date().toISOString().slice(0, 10)}.csv`,
      mime: 'text/csv;charset=utf-8',
      content: auditCsv(events),
    }
  }

  throw new ApiError(`mock 未实现下载接口：${path}`, 404, 'MOCK_NOT_IMPLEMENTED')
}
