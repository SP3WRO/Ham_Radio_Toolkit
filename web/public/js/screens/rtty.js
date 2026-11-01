// @ts-check
// Dekoder RTTY (RttyDecoderScreen): widmo do strojenia, AFC, automatyczny przesuw, szybkość i polaryzacja
import { h, replace, iconButton, chip, switchEl, icon } from "../ui.js";
import { t, format } from "../i18n.js";
import { RttyEngine, STANDARD_BAUDS, STANDARD_SHIFTS } from "../core/rtty.js";
import { layout } from "./common.js";
import { micSession, decodeFile, levelBar, listenButton, actionButton } from "./decoder-common.js";

const RATE = 12000;
const MAX_HZ = 3000;

/** @param {number} b */
const formatBaud = (b) => (b % 1 === 0 ? String(b) : format("%.2f", b));

/** @param {import("../app.js").ScreenContext} ctx */
export function rttyScreen(ctx) {
  let text = "";
  let busy = false;
  let showOptions = false;
  const auto = { frequency: true, shift: true, baud: true, polarity: true };
  const engine = new RttyEngine(RATE, (piece) => { text = (text + piece).slice(-3000); textDirty = true; });
  let textDirty = true;
  const mic = micSession(RATE, (x) => engine.process(x), () => renderControls());

  const canvas = /** @type {HTMLCanvasElement} */ (h("canvas", { class: "spectrum", "aria-label": "spectrum" }));
  canvas.addEventListener("click", (e) => {
    const r = canvas.getBoundingClientRect();
    engine.setCenter(((e.clientX - r.left) / r.width) * MAX_HZ);
    renderTuning();
  });
  const tuning = h("div", { class: "card pad-s row", style: { gap: "4px" } });
  const options = h("div");
  const levels = h("div", { class: "row", style: { gap: "12px" } });
  const audioBar = levelBar(t("rtty_audio_level"));
  const qualityBar = levelBar(t("rtty_quality"));
  levels.append(audioBar, qualityBar);
  const out = h("div", { class: "decoder-text code small-text" });
  const controls = h("div", { class: "col gap-s" });

  // Przyciski tworzone raz – co 0,5 s odświeżamy tylko opis (przebudowa gubiłaby dotknięcia)
  const tuningInfo = h("div", { class: "col grow", style: { alignItems: "center" } });
  const optionsBtn = iconButton("expandMore", t("rtty_options"), () => { showOptions = !showOptions; renderOptions(); renderTuning(); }, { class: "icon-btn sm" });
  tuning.append(
    iconButton("remove", "−5 Hz", () => { engine.setCenter(engine.decoder.centerHz - 5); renderTuning(); }, { class: "icon-btn sm" }),
    tuningInfo,
    iconButton("add", "+5 Hz", () => { engine.setCenter(engine.decoder.centerHz + 5); renderTuning(); }, { class: "icon-btn sm" }),
    optionsBtn);

  function renderTuning() {
    const d = engine.decoder;
    const A = (/** @type {boolean} */ a) => (a ? " A" : "");
    replace(tuningInfo,
      h("span", { class: "title-m bold code" }, `${Math.round(d.centerHz)} Hz`),
      h("span", { class: "body-s code" }, [`${Math.round(d.shiftHz)} Hz${A(auto.shift)}`, `${formatBaud(d.baud)} Bd${A(auto.baud)}`,
        `${d.activeReverse ? t("rtty_reverse") : t("rtty_normal")}${A(auto.polarity)}`].join("  •  ")),
      mic.listening && auto.frequency ? h("span", { class: `label-s ${engine.locked ? "primary" : "muted"}` }, engine.locked ? t("rtty_afc_locked") : t("rtty_afc_searching")) : null);
    optionsBtn.replaceChildren(icon(showOptions ? "expandLess" : "expandMore"));
  }

  /** @param {string} label @param {boolean} value @param {(v: boolean) => void} set */
  const autoRow = (label, value, set) => h("div", { class: "row", style: { gap: "6px" } }, h("span", { class: "label-l grow" }, label),
    chip("Auto", value, () => { set(true); renderOptions(); renderTuning(); }), chip(t("dec_manual"), !value, () => { set(false); renderOptions(); renderTuning(); }));

  function renderOptions() {
    const d = engine.decoder;
    if (!showOptions) return replace(options);
    replace(options, h("div", { class: "card pad col gap-s", style: { maxHeight: "340px", overflowY: "auto" } },
      autoRow(t("rtty_tuning"), auto.frequency, (v) => { auto.frequency = v; engine.autoFrequency = v; }),
      h("div", { class: "body-s muted" }, auto.frequency ? t("rtty_tuning_auto") : t("rtty_tuning_manual")),
      autoRow(t("rtty_shift"), auto.shift, (v) => { auto.shift = v; engine.autoShift = v; }),
      auto.shift ? null : h("div", { class: "chips" }, STANDARD_SHIFTS.map((s) => chip(`${s} Hz`, d.shiftHz === s, () => { d.shiftHz = s; renderOptions(); renderTuning(); }))),
      autoRow(t("rtty_speed"), auto.baud, (v) => { auto.baud = v; d.autoBaud = v; }),
      auto.baud ? null : h("div", { class: "chips" }, STANDARD_BAUDS.map((b) => chip(`${formatBaud(b)} Bd`, d.baud === b, () => { d.baud = b; renderOptions(); renderTuning(); }))),
      autoRow(t("rtty_polarity"), auto.polarity, (v) => { auto.polarity = v; d.autoPolarity = v; if (!v) d.reverse = d.activeReverse; }),
      auto.polarity ? null : h("label", { class: "row", style: { gap: "8px" } }, switchEl(d.reverse, (v) => { d.reverse = v; renderTuning(); }, t("rtty_reversed_long")),
        h("span", { class: "body-m" }, t("rtty_reversed_long"))),
      h("div", { class: "hr" }),
      h("label", { class: "row", style: { gap: "8px" } }, switchEl(d.unshiftOnSpace, (v) => { d.unshiftOnSpace = v; }, t("rtty_usos")), h("span", { class: "body-m" }, t("rtty_usos"))),
      h("div", { class: "label-l" }, t("rtty_squelch", Math.round(d.squelch * 100))),
      h("input", { type: "range", min: 0, max: 0.9, step: 0.01, value: d.squelch, "aria-label": t("rtty_squelch", Math.round(d.squelch * 100)),
        oninput: (/** @type {any} */ e) => { d.squelch = Number(e.target.value); /** @type {HTMLElement} */ (e.target.previousSibling).textContent = t("rtty_squelch", Math.round(d.squelch * 100)); } }),
      h("div", { class: "body-s muted" }, t("rtty_info"))));
  }

  function renderControls() {
    const rec = mic.recordingSeconds;
    replace(controls,
      listenButton(mic.listening, () => (mic.listening ? mic.stop() : mic.start()), busy),
      h("div", { class: "row", style: { gap: "8px" } },
        actionButton("upload", busy ? t("dec_decoding") : t("dec_wav_file"), () => decodeFile(RATE, (x) => engine.process(x), (b) => { busy = b; renderControls(); }), mic.listening || busy),
        actionButton(rec != null ? "stop" : "mic", rec != null ? t("dec_save_rec", rec) : t("dec_record"), () => (rec != null ? mic.saveRecording("rtty") : mic.startRecording()), !mic.listening),
        actionButton("delete", t("common_clear"), () => { text = ""; textDirty = true; })));
  }

  function drawSpectrum() {
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth, hh = canvas.clientHeight;
    if (!w) return;
    if (canvas.width !== Math.round(w * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(hh * dpr); }
    const g = /** @type {CanvasRenderingContext2D} */ (canvas.getContext("2d"));
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, hh);
    const css = getComputedStyle(canvas);
    const spec = engine.spectrum.spectrum();
    if (spec.some((v) => v > -99)) {
      const sorted = Float32Array.from(spec).sort();
      const floor = sorted[Math.floor(sorted.length / 4)];
      const top = Math.max(sorted[sorted.length - 1] + 3, floor + 20);
      g.beginPath();
      for (let i = 0; i < spec.length; i++) {
        const x = ((i * engine.spectrum.binHz) / MAX_HZ) * w;
        const y = hh - Math.min(1, Math.max(0, (spec[i] - floor) / (top - floor))) * (hh - 4);
        if (i === 0) g.moveTo(x, y); else g.lineTo(x, y);
      }
      g.strokeStyle = css.getPropertyValue("--primary") || "#1b5e9e";
      g.lineWidth = 1.5;
      g.stroke();
    }
    const d = engine.decoder;
    const hi = d.centerHz + d.shiftHz / 2, lo = d.centerHz - d.shiftHz / 2;
    const rev = d.activeReverse;
    for (const [hz, color] of [[rev ? lo : hi, "#E53935"], [rev ? hi : lo, "#1E88E5"]]) {
      const x = (/** @type {number} */ (hz) / MAX_HZ) * w;
      g.fillStyle = /** @type {string} */ (color);
      g.fillRect(x - 1, 0, 2, hh);
    }
    g.fillStyle = "rgba(128,128,128,.5)";
    for (let f = 500; f < MAX_HZ; f += 500) g.fillRect((f / MAX_HZ) * w - 1, hh - 8, 2, 8);
  }

  function frame() {
    if (!alive) return;
    drawSpectrum();
    audioBar.set(mic.level);
    qualityBar.set(engine.decoder.quality);
    if (textDirty) {
      textDirty = false;
      out.textContent = text || t("rtty_tune_hint");
      out.classList.toggle("muted", !text);
      out.scrollTop = out.scrollHeight;
    }
    requestAnimationFrame(frame);
  }
  let alive = true;
  const timer = setInterval(renderTuning, 500);
  renderTuning();
  renderOptions();
  renderControls();
  requestAnimationFrame(frame);
  const body = h("div", { class: "scroll decoder" }, canvas, tuning, options, levels, out, controls);
  const { el } = layout(ctx, { title: t("rtty_title"), body });
  return { el, destroy: () => { alive = false; clearInterval(timer); mic.stop(); } };
}
