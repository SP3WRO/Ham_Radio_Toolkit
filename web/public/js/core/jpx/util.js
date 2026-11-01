// Zamienniki funkcji pomocniczych pdf.js (shared/util.js, core/core_utils.js) dla jpx.js.
export class BaseException extends Error {
  constructor(message, name) { super(message); this.name = name; }
}
export function info() {}
export function warn() {}
export function unreachable(msg) { throw new Error(msg); }
export function log2(x) { return x <= 0 ? 0 : Math.ceil(Math.log2(x)); }
export function readUint16(data, offset) { return (data[offset] << 8) | data[offset + 1]; }
export function readUint32(data, offset) {
  return ((data[offset] << 24) | (data[offset + 1] << 16) | (data[offset + 2] << 8) | data[offset + 3]) >>> 0;
}
