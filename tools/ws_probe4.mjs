// 围猎标点可见性/删除验收探针（Node 内置 WebSocket，Node >= 22）
// 验证 v0.7.0：①visibleTo 为空=全队可见 ②仅自己可见时别人收不到 ③指定成员可见时只有该成员收到
//            ④创建者可删除自己的标点 ⑤队长可删除别人的标点 ⑥普通队员删不掉别人的标点
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
  const st = { ws, states: [], closed: false };
  ws.addEventListener('message', (ev) => {
    let m;
    try { m = JSON.parse(ev.data); } catch { return; }
    if (m.type === 'state') st.states.push(m);
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
// 取“某个标点是否在某人的最新 state 里”
const seen = (st, id) => (lastState(st)?.markers || []).some((m) => m.id === id);
let fails = 0;
const check = (ok, msg) => { if (!ok) fails++; console.log(`${ok ? 'PASS' : 'FAIL'}  ${msg}`); };

const room = await createRoom();
console.log('room =', room.roomId, 'code =', room.joinCode);

const A = await connect(room.roomId, 'p4-a', '队长A', 'CAPTAIN');
const B = await connect(room.roomId, 'p4-b', '队员B', 'MEMBER');
const C = await connect(room.roomId, 'p4-c', '队员C', 'MEMBER');
await sleep(400);

// ① 全队可见
send(A, {
  type: 'marker', by: 'p4-a', byName: '队长A', lat: 39.9, lon: 116.4, kind: 'FLAG',
  note: 'all-visible', ts: Date.now(), id: 'mk-all', name: '全队点', visibleTo: [],
});
await sleep(700);
check(seen(A, 'mk-all') && seen(B, 'mk-all') && seen(C, 'mk-all'), '全队可见标点：A/B/C 都收到');

// ② 仅自己可见（B 建，只有 B 收得到）
send(B, {
  type: 'marker', by: 'p4-b', byName: '队员B', lat: 39.9005, lon: 116.4005, kind: 'FLAG',
  note: 'self-only', ts: Date.now(), id: 'mk-self', name: '只我能看', visibleTo: ['p4-b'],
});
await sleep(700);
check(seen(B, 'mk-self'), '仅自己可见：自己能看到');
check(!seen(A, 'mk-self'), '仅自己可见：队长看不到');
check(!seen(C, 'mk-self'), '仅自己可见：其他队员看不到');

// ③ 指定成员可见（C 建，指定给 A）
send(C, {
  type: 'marker', by: 'p4-c', byName: '队员C', lat: 39.901, lon: 116.401, kind: 'FLAG',
  note: 'for-a', ts: Date.now(), id: 'mk-for-a', name: '只给队长看', visibleTo: ['p4-a'],
});
await sleep(700);
check(seen(A, 'mk-for-a'), '指定成员可见：被指定的人收到');
check(!seen(B, 'mk-for-a'), '指定成员可见：其他人收不到');
check(seen(C, 'mk-for-a'), '指定成员可见：创建者自己仍可见');

// ④ 创建者删自己的点
send(B, { type: 'markerDelete', id: 'mk-self', by: 'p4-b' });
await sleep(700);
check(!seen(B, 'mk-self'), '创建者删除自己的标点成功');

// ⑤ 队长删别人的点（A 删 C 建的全队点）
send(A, { type: 'markerDelete', id: 'mk-all', by: 'p4-a' });
await sleep(700);
check(!seen(A, 'mk-all') && !seen(B, 'mk-all') && !seen(C, 'mk-all'), '队长删除他人标点成功（三端都消失）');

// ⑥ 普通队员删不掉别人的点（B 试图删 C 的点）
send(B, { type: 'markerDelete', id: 'mk-for-a', by: 'p4-b' });
await sleep(700);
check(seen(C, 'mk-for-a'), '普通队员无法删除他人标点');

for (const st of [A, B, C]) st.ws.close();
console.log(fails === 0 ? '\nALL PASS' : `\n${fails} FAILED`);
process.exit(fails === 0 ? 0 : 1);
