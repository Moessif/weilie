const roomId = "370c028ed01b";
const url = `ws://47.89.181.85:8080/ws/room/${roomId}?userId=probe1&name=%E6%8E%A2%E9%92%88&role=MEMBER`;
const ws = new WebSocket(url);
const t = setTimeout(() => { console.log("TIMEOUT"); process.exit(1); }, 12000);
ws.onopen = () => { console.log("OPEN"); ws.send(JSON.stringify({ type: "hello", roomId, userId: "probe1", name: "探针", role: "MEMBER" })); };
ws.onmessage = (e) => { const s = String(e.data); console.log("MSG:", s.slice(0, 300)); if (s.includes('"state"')) { clearTimeout(t); ws.close(); console.log("WS OK"); process.exit(0); } };
ws.onerror = (e) => { console.log("ERR", e.message || e); process.exit(1); };
