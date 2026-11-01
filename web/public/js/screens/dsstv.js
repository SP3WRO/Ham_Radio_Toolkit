// @ts-check
// DSSTV – HamDRM (DsstvDecoderScreen + DsstvReceiver): odbiór cyfrowych obrazków (EasyPal/QSSTV) z mikrofonu albo pliku,
// stan synchronizacji, postęp pliku, podgląd JPEG 2000 w trakcie odbioru, lista odebranych obrazków do zapisania.
import { h, replace, iconButton, icon, toast, saveTextFile } from "../ui.js";
import { t, fmt } from "../i18n.js";
import { DrmReceiver } from "../core/drm/receiver.js";
import { DrmDataDecoder } from "../core/drm/data.js";
import { decodeJpeg2000, jpeg2000ToRgba } from "../core/jpeg2000.js";
import { layout } from "./common.js";
import { micSession, decodeFile, levelBar, listenButton } from "./decoder-common.js";

/** @typedef {import("../core/drm/receiver.js").DrmSyncStatus} DrmSyncStatus */
/** @typedef {import("../core/drm/data.js").DrmFileProgress} DrmFileProgress */
/** @typedef {{name: string, url: string | null, width: number, height: number, bytes: Uint8Array, extension: string, callsign: string, receivedMs: number, rsCorrected: number | null}} Picture */

const RATE = 12000;
const MAX_PICTURES = 30;
const MIME = /** @type {Record<string, string>} */ ({ jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", gif: "image/gif", bmp: "image/bmp", webp: "image/webp", avif: "image/avif" });

/** @type {Picture[]} */
let pictures = [];

/** Obrazek → adres blob (JPEG 2000 przez własny dekoder, reszta – przeglądarka). @param {string} ext @param {Uint8Array} bytes */
async function toImage(ext, bytes) {
  ext = ext.toLowerCase();
  if (ext === "jp2" || ext === "j2k" || ext === "jpc") {
    const img = decodeJpeg2000(bytes);
    const c = document.createElement("canvas");
    c.width = img.width; c.height = img.height;
    /** @type {CanvasRenderingContext2D} */ (c.getContext("2d")).putImageData(new ImageData(/** @type {any} */ (jpeg2000ToRgba(img)), img.width, img.height), 0, 0);
    const blob = await new Promise((res) => c.toBlob(res, "image/png"));
    return blob ? { url: URL.createObjectURL(blob), width: img.width, height: img.height } : null;
  }
  const mime = MIME[ext];
  if (!mime) return null;
  const blob = new Blob([/** @type {any} */ (bytes)], { type: mime });
  const bmp = await createImageBitmap(blob);
  const size = { width: bmp.width, height: bmp.height };
  bmp.close();
  return { url: URL.createObjectURL(blob), ...size };
}

/** @param {import("../app.js").ScreenContext} ctx */
export function dsstvScreen(ctx) {
  let showHelp = false;
  let busy = false;
  /** @type {DrmSyncStatus} */
  let sync = { locked: false };
  /** @type {DrmFileProgress | null} */
  let progress = null;
  /** @type {string | null} */
  let previewUrl = null;
  let selected = 0;
  let found = 0;
  const label = new Array(9).fill(" ");
  let callsign = "";
  /** @type {DrmReceiver} */
  let receiver;
  /** @type {DrmDataDecoder} */
  let data;
  let imageQueue = Promise.resolve();

  function reset() {
    label.fill(" ");
    data = new DrmDataDecoder({
      onProgress: (p) => { progress = p; dirty = true; },
      onFile: (f) => { imageQueue = imageQueue.then(() => deliver(f)); },
      onPartial: (name, prefix) => { imageQueue = imageQueue.then(() => partial(name, prefix)); },
    });
    receiver = new DrmReceiver({
      sampleRate: RATE,
      onStatus: (s) => {
        sync = s;
        if (s.fac) {
          for (let i = 0; i < 3; i++) { const pos = s.fac.frameId * 3 + i; if (pos < 9) label[pos] = s.fac.labelPart[i] ?? " "; }
          callsign = label.join("").trim();
        }
        dirty = true;
      },
      onFrame: (bytes) => { data.pushPacket(bytes); },
    });
  }

  /** @param {import("../core/drm/data.js").DrmFile} f */
  async function deliver(f) {
    /** @type {{url: string, width: number, height: number} | null} */
    let img = null;
    try { img = await toImage(f.extension, f.bytes); } catch { img = null; }
    pictures = [{ name: f.name, url: img?.url ?? null, width: img?.width ?? 0, height: img?.height ?? 0, bytes: f.bytes, extension: f.extension,
      callsign, receivedMs: Date.now(), rsCorrected: f.rsCorrected }, ...pictures].slice(0, MAX_PICTURES);
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = null; progress = null; selected = 0; found++;
    dirty = true; renderPictures();
  }

  /** @param {string} name @param {Uint8Array} prefix */
  async function partial(name, prefix) {
    const ext = name.slice(name.lastIndexOf(".") + 1).toLowerCase();
    if (ext !== "jp2" && ext !== "j2k") return;
    try {
      const img = await toImage(ext, prefix);
      if (!img) return;
      if (previewUrl) URL.revokeObjectURL(previewUrl);
      previewUrl = img.url;
      renderPictures();
    } catch { /* za mało danych na podgląd */ }
  }

  reset();
  const mic = micSession(RATE, (x) => {
    try { receiver.process(x); } catch { reset(); }
  }, () => { renderControls(); dirty = true; });

  const help = h("div");
  const syncLine = h("span", { class: "body-m bold grow" });
  const audioBar = levelBar("Audio");
  audioBar.style.flex = "0 0 80px";
  const syncRow = h("div", { class: "row", style: { gap: "8px" } }, syncLine);
  const details = h("div", { class: "label-m muted" });
  const progressHost = h("div", { class: "col gap-s" });
  const statusCard = h("div", { class: "card pad-s col gap-s" }, syncRow, details, progressHost);
  const pictureHost = h("div", { class: "dsstv-picture" });
  const infoLine = h("div", { class: "label-m" });
  const thumbs = h("div", { class: "sstv-gallery" });
  const controls = h("div", { class: "row", style: { gap: "8px" } });
  const helpBtn = iconButton("expandMore", t("dec_how_to"), () => { showHelp = !showHelp; renderHelp(); });
  let dirty = true;

  function renderHelp() {
    helpBtn.replaceChildren(icon(showHelp ? "expandLess" : "expandMore"));
    replace(help, showHelp ? h("div", { class: "body-s muted" }, t("ds_help")) : null);
  }

  function syncText() {
    const s = sync;
    if (busy && !s.locked) return t("ds_searching_file");
    if (!mic.listening && !busy) return t("dec_ready");
    if (!s.locked) return t("ds_searching");
    if (!s.fac) return t("ds_sync_fac", s.mode?.label ?? "", s.occupancy?.label ?? "");
    const f = s.fac;
    return `${t("ds_mode", s.mode?.label ?? "")} • ${f.qamLabel} • ${f.occupancy.label} • ` +
      (f.longInterleave ? t("ds_interleave_long") : t("ds_interleave_short")) + (f.highProtection ? t("ds_strong_protection") : "");
  }

  function renderStatus() {
    syncLine.textContent = syncText();
    if (mic.listening && !audioBar.isConnected) syncRow.append(audioBar);
    if (!mic.listening && audioBar.isConnected) audioBar.remove();
    details.textContent = sync.locked
      ? (callsign ? t("ds_sender", callsign) : "") + (sync.snrDb != null ? fmt("MER %.1f dB • ", sync.snrDb) : "") +
        t("ds_offset", ((sync.frequencyOffsetHz ?? 0) >= 0 ? "+" : "") + fmt("%.0f", sync.frequencyOffsetHz ?? 0), sync.facOkPercent ?? 0)
      : "";
    details.style.display = sync.locked ? "" : "none";
    const p = progress;
    if (!p) return replace(progressHost);
    const total = p.totalSegments;
    replace(progressHost,
      h("span", { class: "label-m" }, t("ds_receiving", p.name ?? t("ds_file"), total != null ? t("ds_of", p.receivedSegments, total) : String(p.receivedSegments))),
      h("div", { class: `meter ${total ? "" : "indeterminate"}` }, h("div", { style: { width: total ? `${Math.min(100, (p.receivedSegments / total) * 100)}%` : "30%" } })));
  }

  /** JPEG 2000 zapisujemy jako PNG (iOS go nie otworzy), inne formaty – w oryginale. @param {Picture} p */
  const save = async (p) => {
    const jp2 = ["jp2", "j2k", "jpc"].includes(p.extension.toLowerCase());
    const stamp = new Date(p.receivedMs).toISOString().replace(/[-:]/g, "").replace("T", "_").slice(0, 15);
    const call = p.callsign.replace(/[^A-Za-z0-9/-]/g, "").replace(/\//g, "-");
    const name = `DSSTV_${stamp}${call ? `_${call}` : ""}.${jp2 && p.url ? "png" : p.extension}`;
    const blob = jp2 && p.url ? await (await fetch(p.url)).blob() : new Blob([/** @type {any} */ (p.bytes)]);
    await saveTextFile(name, /** @type {any} */ (blob), jp2 && p.url ? "image/png" : MIME[p.extension.toLowerCase()] ?? "application/octet-stream");
  };

  function renderPictures() {
    const current = pictures[selected];
    const shown = previewUrl ?? current?.url ?? null;
    replace(pictureHost, shown
      ? h("button", { type: "button", class: "plain-btn", "aria-label": t("ds_picture"), onclick: () => { if (current && !previewUrl) save(current); } }, h("img", { src: shown, alt: t("ds_picture") }))
      : h("div", { class: "col centered muted", style: { textAlign: "center", gap: "6px" } }, icon("image"),
        h("span", { class: "body-m" }, current ? t("ds_cannot_show", current.name) : t("ds_will_appear"))));
    infoLine.textContent = current && !previewUrl
      ? `${current.name}` + (current.callsign ? ` • ${current.callsign}` : "") + (current.url ? ` • ${current.width}×${current.height}` : "") +
        fmt(" • %.1f kB", current.bytes.length / 1024) + (current.rsCorrected != null ? t("ds_rs_fixed", String(current.rsCorrected)) : "") +
        ` • ${t("web_sstv_tap_save")}`
      : "";
    replace(thumbs, pictures.length > 1 ? pictures.map((p, i) => h("button", { type: "button", class: `plain-btn sstv-thumb ${i === selected ? "selected" : ""}`, "aria-label": p.name,
      onclick: () => { selected = i; renderPictures(); } }, p.url ? h("img", { src: p.url, alt: p.name }) : h("span", { class: "label-s" }, p.extension))) : []);
  }

  function renderControls() {
    replace(controls,
      h("div", { class: "grow" }, listenButton(mic.listening, () => { if (mic.listening) mic.stop(); else { reset(); mic.start(); } }, busy)),
      h("button", { type: "button", class: "btn btn-tonal square", "aria-label": t("ft8_decode_wav"), disabled: mic.listening || busy,
        onclick: () => decodeFile(RATE, (x) => receiver.process(x), (b) => {
          busy = b;
          if (b) { reset(); found = 0; } else imageQueue.then(() => toast(found > 0 ? t("ds_received_count", found) : t("ds_none_found")));
          renderControls(); dirty = true;
        }, RATE / 10) }, icon("upload")),
      h("button", { type: "button", class: "btn btn-tonal square", "aria-label": t("common_clear"),
        onclick: () => { pictures.forEach((p) => p.url && URL.revokeObjectURL(p.url)); pictures = []; progress = null; if (previewUrl) URL.revokeObjectURL(previewUrl); previewUrl = null; renderPictures(); dirty = true; } }, icon("delete")));
  }

  let alive = true;
  function frame() {
    if (!alive) return;
    if (dirty) { dirty = false; renderStatus(); }
    audioBar.set(mic.level);
    requestAnimationFrame(frame);
  }
  renderHelp(); renderStatus(); renderPictures(); renderControls();
  requestAnimationFrame(frame);
  const body = h("div", { class: "screen decoder-screen" }, help, statusCard, pictureHost, infoLine, thumbs, controls);
  const { el } = layout(ctx, { title: "DSSTV (HamDRM)", body, actions: [helpBtn] });
  return { el, destroy: () => { alive = false; mic.stop(); } };
}
