// @ts-check
// Dekoder SSTV (SstvDecoderScreen + Robot36Receiver): przewijany podgląd jak w Robot36, wybór trybu (Auto = VIS),
// mikrofon albo plik dźwiękowy, zapis podglądu do PNG, odebrane obrazy na liście do zapisania/udostępnienia.
import { h, replace, selectField, saveTextFile, toast } from "../ui.js";
import { t } from "../i18n.js";
import { SstvReceiver, SSTV_MODES, AUTO_MODE, argbToRgba } from "../core/sstv.js";
import { layout } from "./common.js";
import { micSession, decodeFile, levelBar, listenButton, actionButton } from "./decoder-common.js";

const RATE = 16000;
const MIN_SCOPE = 496;

// Odbiornik i odebrane obrazy przeżywają wyjście z ekranu
const rx = new SstvReceiver();
/** @type {{url: string, blob: Blob, name: string, mode: string}[]} */
const received = [];
let selectedMode = AUTO_MODE;

const stamp = () => {
  const d = new Date(), p = (/** @type {number} */ n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
};

/** @param {Int32Array} pixels @param {number} w @param {number} hh @returns {HTMLCanvasElement} */
function toCanvas(pixels, w, hh) {
  const c = document.createElement("canvas");
  c.width = w; c.height = hh;
  /** @type {CanvasRenderingContext2D} */ (c.getContext("2d")).putImageData(new ImageData(/** @type {any} */ (argbToRgba(pixels)), w, hh), 0, 0);
  return c;
}

/** @param {HTMLCanvasElement} c @returns {Promise<Blob>} */
const pngOf = (c) => new Promise((res, rej) => c.toBlob((b) => (b ? res(b) : rej(new Error("PNG"))), "image/png"));

/** @param {Blob} blob @param {string} name */
async function savePng(blob, name) {
  try {
    await saveTextFile(name, /** @type {any} */ (blob), "image/png");
  } catch {
    toast(t("sstv_save_error"));
  }
}

/** @param {import("../app.js").ScreenContext} ctx */
export function sstvScreen(ctx) {
  let status = t("dec_ready");
  let fileBusy = false;
  let dirty = true;
  let scopeHeight = MIN_SCOPE;

  /** @param {{pixels: Int32Array, width: number, height: number, mode: string}} img */
  const onImage = (img) => {
    status = t("sstv_receiving", img.mode);
    const name = `${stamp()}.png`;
    pngOf(toCanvas(img.pixels, img.width, img.height)).then((blob) => {
      received.unshift({ url: URL.createObjectURL(blob), blob, name, mode: img.mode });
      while (received.length > 20) URL.revokeObjectURL(/** @type {any} */ (received.pop()).url);
      renderGallery();
    }).catch(() => {});
  };

  const mic = micSession(RATE, (x) => { if (rx.process(x, onImage)) dirty = true; }, () => {
    if (mic.listening) { rx.start(RATE); status = t("r36_listening"); } else if (!fileBusy) status = t("web_ft8_stopped");
    renderControls(); renderStatus();
  });

  const canvas = /** @type {HTMLCanvasElement} */ (h("canvas", { class: "sstv-scope", "aria-label": t("sstv_preview") }));
  const scopeCard = h("div", { class: "sstv-scope-card" }, canvas);
  const level = levelBar(t("sstv_level"));
  const statusHost = h("div", { class: "card pad-s row", style: { gap: "8px" } });
  const modeHost = h("div");
  const controls = h("div", { class: "col gap-s" });
  const gallery = h("div");

  function renderStatus() {
    replace(statusHost,
      h("span", { class: "body-l bold primary nowrap" }, t("sstv_receiving", rx.modeLabel ?? "—")),
      h("span", { class: "body-m ellipsis grow", style: { textAlign: "right" } }, status));
  }

  function renderMode() {
    replace(modeHost, selectField({
      label: t("sstv_mode"), value: selectedMode,
      options: [[AUTO_MODE, t("sstv_auto")], ...SSTV_MODES.map((m) => /** @type {[string, string]} */ ([m, m]))],
      onchange: (/** @type {string} */ v) => { selectedMode = v; rx.setMode(v); renderStatus(); },
    }));
  }

  async function saveScope() {
    const view = rx.scopeView(1280);
    let c = toCanvas(view.pixels, view.width, view.height);
    // HF Fax: rozciągnięcie do 1808 px i przesunięcie marginesu (postProcessScopeImage)
    if (rx.modeLabel === "HF Fax") {
      const real = 1808, shift = rx.faxShift, realShift = Math.trunc((shift * real) / view.width);
      const out = document.createElement("canvas");
      out.width = real; out.height = view.height;
      const g = /** @type {CanvasRenderingContext2D} */ (out.getContext("2d"));
      if (shift > 0) g.drawImage(c, 0, 0, shift, view.height, real - realShift, 0, realShift, view.height);
      g.drawImage(c, shift, 0, view.width - shift, view.height, 0, 1, real - realShift, view.height);
      c = out;
    }
    const name = `${stamp()}.png`;
    await savePng(await pngOf(c), name);
  }

  function renderControls() {
    replace(controls,
      listenButton(mic.listening, () => (mic.listening ? mic.stop() : mic.start()), fileBusy),
      h("div", { class: "row", style: { gap: "8px" } },
        actionButton("upload", fileBusy ? t("dec_decoding") : t("dec_wav_file"), () => decodeFile(RATE, (x) => { if (rx.process(x, onImage)) dirty = true; }, (busy) => {
          fileBusy = busy;
          if (busy) { rx.start(RATE); status = t("dec_decoding_file"); } else { rx.finish(onImage); dirty = true; status = t("r36_file_done"); }
          renderControls(); renderStatus();
        }, Math.trunc(RATE / 50)), mic.listening || fileBusy),
        actionButton("delete", t("common_clear"), () => { rx.clearScope(); dirty = true; }),
        actionButton("save", t("common_save"), saveScope)));
  }

  function renderGallery() {
    if (!received.length) return replace(gallery);
    replace(gallery, h("div", { class: "col gap-s" },
      h("div", { class: "col" }, h("span", { class: "title-s" }, t("web_sstv_received")), h("span", { class: "label-s muted" }, t("web_sstv_tap_save"))),
      h("div", { class: "sstv-gallery" }, received.map((r) =>
        h("button", { type: "button", class: "plain-btn sstv-thumb", "aria-label": `${r.mode} ${r.name}`, onclick: () => savePng(r.blob, r.name) },
          h("img", { src: r.url, alt: r.mode }), h("span", { class: "label-s" }, r.mode))))));
  }

  function draw() {
    // Wysokość podglądu dobrana do proporcji karty, jak w Robot36 (640 × 496..1280)
    const cw = scopeCard.clientWidth, ch = scopeCard.clientHeight;
    const want = cw > 0 ? Math.max(MIN_SCOPE, Math.min(1280, Math.round((640 * ch) / cw))) : MIN_SCOPE;
    if (want !== scopeHeight) { scopeHeight = want; dirty = true; }
    if (!dirty) return;
    dirty = false;
    const view = rx.scopeView(scopeHeight);
    if (canvas.width !== view.width || canvas.height !== view.height) { canvas.width = view.width; canvas.height = view.height; }
    /** @type {CanvasRenderingContext2D} */ (canvas.getContext("2d")).putImageData(new ImageData(/** @type {any} */ (argbToRgba(view.pixels)), view.width, view.height), 0, 0);
  }

  let alive = true;
  let lastLabel = rx.modeLabel;
  function frame() {
    if (!alive) return;
    draw();
    level.set(mic.level);
    if (rx.modeLabel !== lastLabel) { lastLabel = rx.modeLabel; renderStatus(); }
    requestAnimationFrame(frame);
  }
  renderStatus(); renderMode(); renderControls(); renderGallery();
  requestAnimationFrame(frame);
  const body = h("div", { class: "scroll decoder sstv" }, scopeCard, level, statusHost, modeHost, controls, gallery);
  const { el } = layout(ctx, { title: t("sstv_title"), body });
  return { el, destroy: () => { alive = false; mic.stop(); } };
}
