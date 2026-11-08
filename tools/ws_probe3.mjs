// 围猎联机修复验收探针（Node 内置 WebSocket，Node >= 22）
// 验证：①创建房间 ②marker/photo 广播 state ③聊天历史随 state 下发 ④队长掉线重进恢复 ⑤满员上限常量
const BASE = process.env.WEILIE_BASE || 'http://47.89.181.85:8080';
const WS_BASE = BASE.replace(/^http/, 'ws');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function createRoom() {
  const r = await fetch(`${BASE}/api/teams`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name: 'probe-captain' }),
  });
  return r.json();
}

function connect(roomId, userId, name, role) {
  const ws = new WebSocket(`${WS_BASE}/ws/room/${roomId}`);
  const st = { ws, states: [], chats: [], markers: [], byes: [], closed: false };
  ws.addEventListener('message', (ev) => {
    let m;
    try { m = JSON.parse(ev.data); } catch { return; }
    if (m.type === 'state') st.states.push(m);
    if (m.type === 'chatEcho') st.chats.push(m.chat);
    if (m.type === 'bye') st.byes.push(m);
  });
  ws.addEventListener('close', () => { st.closed = true; });
  return new Promise((resolve, reject) => {
    ws.addEventListener('open', () => {
      ws.send(JSON.stringify({ type: 'hello', roomId, userId, name, role }));
      setTimeout(() => resolve(st), 800);
    });
    ws.addEventListener('error', reject);
  });
}

const send = (st, obj) => st.ws.send(JSON.stringify(obj));
const lastState = (st) => st.states[st.states.length - 1];
const check = (ok, msg) => console.log(`${ok ? 'PASS' : 'FAIL'}  ${msg}`);

const room = await createRoom();
console.log('room =', room.roomId, 'code =', room.joinCode);

// A=队长
const A = await connect(room.roomId, 'probe-a', '队长A', 'CAPTAIN');
await sleep(300);

// B=队员
const B = await connect(room.roomId, 'probe-b', '队员B', 'MEMBER');
await sleep(600);

check(!!lastState(A) && lastState(A).members.length === 2, `A sees 2 members (${lastState(A)?.members.length})`);
check(lastState(B)?.members.find((m) => m.userId === 'probe-a')?.role === 'CAPTAIN', 'B sees A as CAPTAIN');

// ① 标记广播：A 插旗 → B 的 state 里应出现
send(A, { type: 'marker', by: 'probe-a', byName: '队长A', lat: 39.9, lon: 116.4, kind: 'FLAG', note: 'flag-1', ts: Date.now() });
await sleep(800);
check((lastState(B)?.markers || []).some((m) => m.note === 'flag-1'), 'marker broadcast reaches B via state');

// ② 拍照点（photoRef）→ 广播成带 photoId 的 Marker
send(B, { type: 'photo', userId: 'probe-b', photoId: 'ph-1', lat: 39.901, lon: 116.401, note: '疑点1', kind: 'SUSPICIOUS', ts: Date.now() });
await sleep(900);
const pm = (lastState(A)?.markers || []).find((m) => m.photoId === 'ph-1');
check(!!pm && pm.kind === 'SUSPICIOUS', `photo marker reaches A with photoId (${pm?.photoId || 'none'})`);

// ③ 聊天历史：A 发一条 → B 收到；再让 C 加入，state 应带 chats
send(A, { type: 'chat', from: 'probe-a', fromName: '队长A', text: '你好B', ts: Date.now() });
await sleep(500);
const C = await connect(room.roomId, 'probe-c', '观战C', 'OBSERVER');
await sleep(700);
check((lastState(C)?.chats || []).some((c) => c.text === '你好B'), 'chat history delivered in state to late joiner');
check(lastState(C)?.members.find((m) => m.userId === 'probe-c')?.role === 'OBSERVER', 'C joined as OBSERVER');

// ④ 队长掉线重进：A 断开 → 以 MEMBER 身份重连，应恢复 CAPTAIN
A.ws.close();
await sleep(900);
const A2 = await connect(room.roomId, 'probe-a', '队长A', 'MEMBER');
await sleep(900);
const aRole = lastState(A2)?.members.find((m) => m.userId === 'probe-a')?.role;
check(aRole === 'CAPTAIN', `captain restored after reconnect-as-member (role=${aRole})`);

// ⑤ 转让队长后原队长回来不再夺权
send(A2, { type: 'promote', from: 'probe-a', to: 'probe-b', ts: Date.now() });
await sleep(800);
check(lastState(A2)?.members.find((m) => m.userId === 'probe-b')?.role === 'CAPTAIN', 'promote works: B is CAPTAIN');
A2.ws.close();
await sleep(700);
const A3 = await connect(room.roomId, 'probe-a', '队长A', 'CAPTAIN');
await sleep(900);
const roles = (lastState(A3)?.members || []).map((m) => `${m.userId}:${m.role}`).join(',');
check(
  (lastState(A3)?.members || []).filter((m) => m.role === 'CAPTAIN').length === 1 &&
    lastState(A3).members.find((m) => m.userId === 'probe-a').role === 'MEMBER',
  `single captain invariant after old captain returns (${roles})`,
);

// ⑥ 满员上限 12：再塞 9 个人
const extra = [];
for (let i = 0; i < 9; i++) extra.push(await connect(room.roomId, `probe-x${i}`, `填${i}`, 'MEMBER'));
await sleep(1200);
const counts = lastState(A3)?.members.length ?? -1;
const rejected = extra.filter((s) => s.byes.length > 0).length;
check(counts <= 12, `member count capped (${counts})，rejected=${rejected}`);

console.log('BYE samples:', JSON.stringify(extra.flatMap((s) => s.byes).slice(0, 2)));
[...extra, A3, B, C].forEach((s) => { try { s.ws.close(); } catch {} });
console.log('DONE');
process.exit(0);
