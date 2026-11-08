const WS = globalThis.WebSocket;
const url = "ws://47.89.181.85:8080/ws/room/4a80bd656388?userId=u1&name=%E6%B5%8B%E8%AF%95&role=CAPTAIN";
const ws = new WS(url);
let done = false;
const t = setTimeout(() => { console.log("TIMEOUT"); process.exit(1); }, 12000);
ws.onopen = () => {
  console.log("OPEN");
  ws.send(JSON.stringify({ type: "hello", roomId: "4a80bd656388", userId: "u1", name: "测试", role: "CAPTAIN" }));
};
ws.onmessage = (e) => {
  const s = String(e.data);
  console.log("MSG:", s.slice(0, 220));
  if (s.includes("\"state\"")) { done = true; clearTimeout(t); ws.close(); console.log("WS OK"); process.exit(0); }
};
ws.onerror = (e) => { console.log("ERR", e.message || e); process.exit(1); };
ws.onclose = () => { if (!done) { console.log("CLOSED early"); process.exit(1); } };
