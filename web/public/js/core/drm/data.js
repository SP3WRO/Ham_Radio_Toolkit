// @ts-check
// Warstwa danych DRM (DrmDataDecoder.kt): tryb pakietowy, grupy danych MSC i protokół MOT (obiekty z nazwą i treścią).
import { drmCrc } from "./tables.js";
import { isRsExtension, rsFileDecode } from "./channel.js";

/** @typedef {{transportId: number, name: string | null, receivedSegments: number, totalSegments: number | null, bodySize: number | null}} DrmFileProgress */
/** @typedef {{name: string, extension: string, bytes: Uint8Array, rsCorrected: number | null}} DrmFile */

/** @param {string} name */
const extOf = (name) => { const i = name.lastIndexOf("."); return i < 0 ? "" : name.slice(i + 1); };
/** @param {Uint8Array[]} parts */
function concat(parts) {
  const out = new Uint8Array(parts.reduce((a, p) => a + p.length, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}

export class DrmDataDecoder {
  /**
   * @param {{onProgress?: (p: DrmFileProgress) => void, onFile?: (f: DrmFile) => void, onPartial?: (name: string, prefix: Uint8Array) => void}} [cb]
   */
  constructor(cb = {}) {
    this.cb = cb;
    /** @type {number[]} */
    this.unit = [];
    this.unitActive = false;
    this.lastCi = -1;
    /** @type {Map<number, any>} */
    this.objects = new Map();
    this.counter = 0;
  }

  /** Pakiet z ramki MSC. Zwraca, czy CRC było poprawne. @param {number[]} packet */
  pushPacket(packet) {
    if (packet.length < 4) return false;
    const n = packet.length;
    if (drmCrc(packet, 0, n - 2, 16) !== ((packet[n - 2] << 8) | packet[n - 1])) { this.unitActive = false; return false; }
    const h = packet[0];
    const first = (h & 0x80) !== 0, last = (h & 0x40) !== 0, padded = (h & 0x08) !== 0, ci = h & 7;
    let from = 1, to = n - 2;
    if (padded) { from = 2; to = Math.min(to, 2 + packet[1]); }
    if (first) { this.unit = []; this.unitActive = true; }
    else if (!this.unitActive || ci !== (this.lastCi + 1) % 8) { this.unitActive = false; this.lastCi = ci; return true; }
    this.lastCi = ci;
    for (let i = from; i < to; i++) this.unit.push(packet[i]);
    if (last && this.unitActive) { this.unitActive = false; this.pushDataGroup(this.unit); }
    return true;
  }

  /** @param {number[]} g */
  pushDataGroup(g) {
    if (g.length < 4) return;
    const ext = (g[0] & 0x80) !== 0, hasCrc = (g[0] & 0x40) !== 0, hasSegment = (g[0] & 0x20) !== 0, hasUA = (g[0] & 0x10) !== 0;
    const type = g[0] & 0x0f;
    const end = hasCrc ? g.length - 2 : g.length;
    if (hasCrc && drmCrc(g, 0, g.length - 2, 16) !== ((g[g.length - 2] << 8) | g[g.length - 1])) return;
    let p = 2 + (ext ? 2 : 0);
    let lastSeg = false, segNum = 0;
    if (hasSegment) {
      if (p + 2 > end) return;
      lastSeg = (g[p] & 0x80) !== 0;
      segNum = ((g[p] & 0x7f) << 8) | g[p + 1];
      p += 2;
    }
    let tid = -1;
    if (hasUA) {
      if (p >= end) return;
      const len = g[p] & 0x0f;
      if ((g[p] & 0x10) !== 0 && p + 3 <= end) tid = (g[p + 1] << 8) | g[p + 2];
      p += 1 + len;
    }
    if (tid < 0 || p + 2 > end) return;
    const segSize = ((g[p] & 0x1f) << 8) | g[p + 1];
    p += 2;
    if (p + segSize > end) return;
    const data = Uint8Array.from(g.slice(p, p + segSize));
    let obj = this.objects.get(tid);
    if (!obj) {
      obj = { transportId: tid, headerSegments: new Map(), headerLast: -1, bodySegments: new Map(), bodyLast: -1, segmentSize: 0, name: null, bodySize: null, delivered: false, lastUpdate: 0, lastPreviewSegments: 0 };
      this.objects.set(tid, obj);
    }
    obj.lastUpdate = ++this.counter;
    if (type === 3) {
      obj.headerSegments.set(segNum, data);
      if (lastSeg) obj.headerLast = segNum;
      this.parseHeader(obj);
    } else if (type === 4) {
      obj.bodySegments.set(segNum, data);
      if (lastSeg) obj.bodyLast = segNum;
      obj.segmentSize = Math.max(obj.segmentSize, segSize);
    } else return;
    while (this.objects.size > 4) {
      let oldest = null;
      for (const [k, v] of this.objects) if (!oldest || v.lastUpdate < oldest[1].lastUpdate) oldest = [k, v];
      this.objects.delete(/** @type {any} */ (oldest)[0]);
    }
    // Po dostarczeniu pliku powtórzenia segmentów nie wracają do paska postępu
    if (!obj.delivered) this.cb.onProgress?.({ transportId: tid, name: obj.name, receivedSegments: obj.bodySegments.size, totalSegments: obj.bodyLast >= 0 ? obj.bodyLast + 1 : null, bodySize: obj.bodySize });
    this.tryDeliver(obj);
    this.preview(obj);
  }

  /** @param {any} obj */
  preview(obj) {
    if (obj.delivered || !obj.name || isRsExtension(extOf(obj.name))) return;
    let contiguous = 0;
    while (obj.bodySegments.has(contiguous)) contiguous++;
    const total = obj.bodyLast >= 0 ? obj.bodyLast + 1 : contiguous + 10;
    if (contiguous - obj.lastPreviewSegments < Math.max(2, Math.trunc(total / 10))) return;
    obj.lastPreviewSegments = contiguous;
    this.cb.onPartial?.(obj.name, concat(Array.from({ length: contiguous }, (_, i) => obj.bodySegments.get(i))));
  }

  /** @param {any} obj */
  parseHeader(obj) {
    if (obj.headerLast < 0) return;
    for (let i = 0; i <= obj.headerLast; i++) if (!obj.headerSegments.has(i)) return;
    const h = concat(Array.from({ length: obj.headerLast + 1 }, (_, i) => obj.headerSegments.get(i)));
    if (h.length < 7) return;
    obj.bodySize = (h[0] << 20) | (h[1] << 12) | (h[2] << 4) | (h[3] >> 4);
    const headerSize = ((h[3] & 0x0f) << 9) | (h[4] << 1) | (h[5] >> 7);
    let p = 7;
    const end = Math.min(headerSize, h.length);
    while (p < end) {
      const pli = h[p] >> 6, paramId = h[p] & 0x3f;
      p++;
      let len;
      if (pli === 0) len = 0; else if (pli === 1) len = 1; else if (pli === 2) len = 4;
      else {
        if (p >= end) return;
        const extFlag = (h[p] & 0x80) !== 0;
        len = extFlag && p + 1 < end ? ((h[p] & 0x7f) << 8) | h[p + 1] : h[p] & 0x7f;
        p += extFlag ? 2 : 1;
      }
      if (p + len > end) return;
      if (paramId === 12 && len >= 1) obj.name = String.fromCharCode(...h.subarray(p + 1, p + len)).trim();
      p += len;
    }
    this.tryDeliver(obj);
  }

  /** @param {any} obj */
  tryDeliver(obj) {
    if (obj.delivered || !obj.name || obj.bodyLast < 0) return;
    const name = obj.name, last = obj.bodyLast, ext = extOf(name).toLowerCase();
    let complete = true;
    for (let i = 0; i <= last; i++) if (!obj.bodySegments.has(i)) { complete = false; break; }
    if (complete) return this.deliver(obj, name, ext, concat(Array.from({ length: last + 1 }, (_, i) => obj.bodySegments.get(i))), null);
    if (isRsExtension(ext) && obj.segmentSize > 0) {
      const segSize = obj.segmentSize;
      const lastSize = obj.bodySegments.get(last)?.length ?? segSize;
      const total = last * segSize + lastSize;
      const buf = new Uint8Array(total), missing = new Uint8Array(total);
      for (let i = 0; i <= last; i++) {
        const seg = obj.bodySegments.get(i), start = i * segSize, len = i === last ? lastSize : segSize;
        if (!seg) missing.fill(1, start, Math.min(start + len, total));
        else buf.set(seg.subarray(0, Math.min(seg.length, total - start)), start);
      }
      const missingCount = missing.reduce((a, b) => a + b, 0);
      const k = ext === "rs1" ? 224 : ext === "rs2" ? 192 : ext === "rs3" ? 160 : 128;
      if (missingCount / total > (255 - k) / 255) return;
      this.deliver(obj, name, ext, buf, missing);
    }
  }

  /** @param {any} obj @param {string} name @param {string} ext @param {Uint8Array} bytes @param {Uint8Array | null} missing */
  deliver(obj, name, ext, bytes, missing) {
    if (isRsExtension(ext)) {
      const res = rsFileDecode(bytes, ext, missing);
      if (!res) return;
      obj.delivered = true;
      const base = name.includes(".") ? name.slice(0, name.lastIndexOf(".")) : name;
      this.cb.onFile?.({ name: `${base}.${res.extension}`, extension: res.extension, bytes: res.bytes, rsCorrected: res.corrected });
    } else {
      obj.delivered = true;
      this.cb.onFile?.({ name, extension: ext, bytes, rsCorrected: null });
    }
  }
}
