// Most APRS-IS dla przeglądarki (AprsClient.kt): przeglądarka nie otworzy połączenia TCP, więc łączy się z serwerem
// przez WebSocket (/api/aprs?lat=..&lon=..&radius=..&call=..), a serwer – z APRS-IS z filtrem okręgu.
// Do telefonu idą tylko linie z pozycją (parsowane w przeglądarce); nadawanie wyłącznie gotowej ramki pozycji
// budowanej tu, na serwerze, z limitem 1 na 30 s – serwer nie jest otwartą bramką do APRS-IS.
import { createHash } from "node:crypto";
import { connect } from "node:net";
import { aprsPasscode, aprsBeacon } from "../public/js/core/aprs.js";
import { Callsigns } from "../public/js/core/radio.js";

const APRS_HOST = process.env.APRS_HOST ?? "euro.aprs2.net";
const APRS_PORT = Number(process.env.APRS_PORT ?? 14580);
const MAX_TOTAL = 60;
const MAX_PER_IP = 3;
const BEACON_INTERVAL = 30_000;
const GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

let total = 0;
/** @type {Map<string, number>} */
const perIp = new Map();

/** Ramka WebSocket (serwer → klient, bez maski). @param {string} text */
function frame(text, opcode = 0x1) {
  const payload = Buffer.from(text, "utf8");
  const len = payload.length;
  const head = len < 126 ? Buffer.from([0x80 | opcode, len])
    : len < 65536 ? Buffer.from([0x80 | opcode, 126, len >> 8, len & 0xff])
      : (() => { const b = Buffer.alloc(10); b[0] = 0x80 | opcode; b[1] = 127; b.writeBigUInt64BE(BigInt(len), 2); return b; })();
  return Buffer.concat([head, payload]);
}

/** Odczyt ramek klienta (zawsze maskowanych). Zwraca [ramki, reszta bufora]. @param {Buffer} buf */
function readFrames(buf) {
  /** @type {{opcode: number, data: Buffer}[]} */
  const out = [];
  let off = 0;
  while (buf.length - off >= 2) {
    const opcode = buf[off] & 0x0f;
    const masked = (buf[off + 1] & 0x80) !== 0;
    let len = buf[off + 1] & 0x7f;
    let p = off + 2;
    if (len === 126) { if (buf.length - p < 2) break; len = buf.readUInt16BE(p); p += 2; }
    else if (len === 127) { if (buf.length - p < 8) break; len = Number(buf.readBigUInt64BE(p)); p += 8; }
    if (len > 4096) throw new Error("za duża ramka");
    const maskLen = masked ? 4 : 0;
    if (buf.length - p < maskLen + len) break;
    const mask = masked ? buf.subarray(p, p + 4) : null;
    p += maskLen;
    const data = Buffer.from(buf.subarray(p, p + len));
    if (mask) for (let i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
    out.push({ opcode, data });
    off = p + len;
  }
  return [out, buf.subarray(off)];
}

/**
 * Obsługa "upgrade" na serwerze HTTP. @param {import("node:http").IncomingMessage} req @param {import("node:stream").Duplex} socket
 * @param {string} ip @param {string} userAgent
 */
export function handleAprsUpgrade(req, socket, ip, userAgent) {
  const url = new URL(req.url ?? "/", "http://localhost");
  const key = req.headers["sec-websocket-key"];
  const lat = Number(url.searchParams.get("lat")), lon = Number(url.searchParams.get("lon"));
  const radius = Math.min(500, Math.max(1, Number(url.searchParams.get("radius") ?? 50)));
  const reject = (/** @type {number} */ code, /** @type {string} */ text) => {
    socket.end(`HTTP/1.1 ${code} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  };
  if (String(req.headers.upgrade ?? "").toLowerCase() !== "websocket" || !key) return reject(400, "Bad Request");
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return reject(400, "Bad Request");
  if (total >= MAX_TOTAL || (perIp.get(ip) ?? 0) >= MAX_PER_IP) return reject(429, "Too Many Requests");

  const accept = createHash("sha1").update(String(key) + GUID).digest("base64");
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
  total++;
  perIp.set(ip, (perIp.get(ip) ?? 0) + 1);

  // Bez poprawnego znaku krótkofalarskiego – logowanie tylko do odczytu (N0CALL, pass -1)
  const rawCall = String(url.searchParams.get("call") ?? "");
  const txCall = Callsigns.isValidAmateur(rawCall) ? Callsigns.baseCall(rawCall) : null;
  const canTransmit = !!txCall && txCall.length <= 9;
  const send = (/** @type {any} */ obj) => { if (!socket.destroyed) socket.write(frame(JSON.stringify(obj))); };
  let closed = false;
  let lastBeacon = 0;

  const aprs = connect({ host: APRS_HOST, port: APRS_PORT });
  aprs.setTimeout(90_000);
  aprs.setEncoding("utf8");
  aprs.on("connect", () => {
    const filter = `r/${lat.toFixed(4)}/${lon.toFixed(4)}/${Math.round(radius)}`;
    aprs.write(`user ${canTransmit ? txCall : "N0CALL"} pass ${canTransmit ? aprsPasscode(/** @type {string} */ (txCall)) : -1} vers HamRadioToolkit-Web 1.0 filter ${filter}\r\n`);
  });
  let pending = "";
  aprs.on("data", (chunk) => {
    pending += chunk;
    const lines = pending.split("\n");
    pending = lines.pop() ?? "";
    if (pending.length > 8192) pending = "";
    for (const raw of lines) {
      const line = raw.replace(/\r$/, "");
      if (line.startsWith("#")) {
        if (line.startsWith("# logresp")) send({ type: "connected", canTransmit, verified: / verified/.test(line) && !/unverified/.test(line) });
        continue;
      }
      // Tylko pakiety z pozycją (=, !, @, /, Mic-E) – reszta i tak nie jest pokazywana
      const info = line.slice(line.indexOf(":") + 1);
      if (line.indexOf(">") > 0 && "=!@/`'".includes(info[0] ?? "")) send({ type: "packet", line });
    }
  });
  const shutdown = () => {
    if (closed) return;
    closed = true;
    total--;
    const n = (perIp.get(ip) ?? 1) - 1;
    if (n <= 0) perIp.delete(ip); else perIp.set(ip, n);
    aprs.destroy();
    if (!socket.destroyed) { try { socket.end(frame("", 0x8)); } catch { /* już zamknięte */ } }
  };
  aprs.on("timeout", () => { send({ type: "error", message: "APRS-IS: brak danych" }); shutdown(); });
  aprs.on("error", (e) => { send({ type: "error", message: `APRS-IS: ${e.message}` }); shutdown(); });
  aprs.on("close", shutdown);

  let buf = Buffer.alloc(0);
  socket.on("data", (chunk) => {
    try {
      let frames;
      [frames, buf] = readFrames(Buffer.concat([buf, chunk]));
      for (const f of frames) {
        if (f.opcode === 0x8) return shutdown();
        if (f.opcode === 0x9) { socket.write(frame(f.data.toString(), 0xa)); continue; }
        if (f.opcode !== 0x1) continue;
        const msg = JSON.parse(f.data.toString("utf8"));
        if (msg?.type !== "beacon") continue;
        if (!canTransmit) { send({ type: "beacon", ok: false, message: "listen-only" }); continue; }
        if (Date.now() - lastBeacon < BEACON_INTERVAL) { send({ type: "beacon", ok: false, message: "rate" }); continue; }
        const la = Number(msg.lat), lo = Number(msg.lon);
        const ssid = String(msg.ssid ?? "");
        const icon = String(msg.icon ?? "/>");
        if (!Number.isFinite(la) || !Number.isFinite(lo) || Math.abs(la) > 90 || Math.abs(lo) > 180 || !/^-?(\d|1[0-5])?$/.test(ssid) || icon.length !== 2) {
          send({ type: "beacon", ok: false, message: "invalid" });
          continue;
        }
        lastBeacon = Date.now();
        const line = aprsBeacon({ call: /** @type {string} */ (txCall), ssid, icon, comment: String(msg.comment ?? "").replace(/[\x00-\x1f\x7f]/g, ""), lat: la, lon: lo });
        aprs.write(`${line}\r\n`);
        send({ type: "beacon", ok: true, line });
      }
    } catch {
      shutdown();
    }
  });
  socket.on("close", shutdown);
  socket.on("error", shutdown);
  void userAgent;
}
