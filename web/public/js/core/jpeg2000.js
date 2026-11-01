// @ts-check
// JPEG 2000 dla DSSTV (Jpeg2000Decoder.kt): dekoder z pdf.js (core/jpx/, licencja Apache 2.0) + składanie kafli w obraz RGB/szary.
// Niepełny plik (podgląd w trakcie odbioru) dekoduje się do obrazu niższej jakości, jeśli są już nagłówki kafla.
import { JpxImage } from "./jpx/jpx.js";

/** @typedef {{width: number, height: number, components: number, pixels: Uint8ClampedArray}} Jpeg2000Image */

/** @param {Uint8Array} data @returns {Jpeg2000Image} */
export function decodeJpeg2000(data) {
  const jpx = /** @type {any} */ (new JpxImage());
  try {
    jpx.parse(data);
  } catch (e) {
    throw new Error(`JPEG 2000: ${/** @type {Error} */ (e).message}`);
  }
  if (!jpx.tiles?.length || !jpx.width || !jpx.height) throw new Error("JPEG 2000: brak danych obrazu");
  const width = jpx.width, height = jpx.height, components = jpx.componentsCount;
  const pixels = new Uint8ClampedArray(width * height * components);
  for (const tile of jpx.tiles) {
    for (let y = 0; y < tile.height; y++) {
      const dy = tile.top + y;
      if (dy < 0 || dy >= height) continue;
      const src = y * tile.width * components;
      const dst = (dy * width + tile.left) * components;
      const n = Math.min(tile.width, width - tile.left) * components;
      pixels.set(tile.items.subarray(src, src + n), dst);
    }
  }
  return { width, height, components, pixels };
}

/** Obraz → RGBA (ImageData). @param {Jpeg2000Image} img */
export function jpeg2000ToRgba(img) {
  const out = new Uint8ClampedArray(img.width * img.height * 4);
  const c = img.components;
  for (let i = 0, p = 0, o = 0; i < img.width * img.height; i++, p += c, o += 4) {
    const r = img.pixels[p];
    out[o] = r; out[o + 1] = c >= 3 ? img.pixels[p + 1] : r; out[o + 2] = c >= 3 ? img.pixels[p + 2] : r; out[o + 3] = 255;
  }
  return out;
}
