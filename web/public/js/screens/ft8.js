// @ts-check
// Dekoder FT8 / FT4 (Ft8DecoderScreen + Ft8Receiver): nasłuch zsynchronizowany z zegarem UTC, sloty 15 s / 7,5 s,
// dekodowanie w osobnym wątku (Web Worker), plik WAV, filtr CQ, dotknięcie wiadomości otwiera wyszukiwarkę znaków.
import { h, replace, iconButton, chip, icon, pickFile, toast } from "../ui.js";
import { t, plural } from "../i18n.js";
import { FTX, isCq, senderCall, dtLabel } from "../core/ft8.js";
import { decodeAudioFile } from "../audio.js";
import { layout } from "./common.js";
import { micSession, levelBar, listenButton } from "./decoder-common.js";

/** @typedef {import("../core/ft8.js").Ft8Decode} Ft8Decode */
/** @typedef {import("../core/ft8.js").FtxMode} FtxMode */

const RATE = 12000;
const FREQS = {
  FT8: "160 m 1,840 • 80 m 3,573 • 40 m 7,074 • 30 m 10,136 • 20 m 14,074 • 17 m 18,100 • 15 m 21,074 • 12 m 24,915 • 10 m 28,074 • 6 m 50,313 • 2 m 144,174 MHz (USB)",
  FT4: "80 m 3,575 • 40 m 7,0475 • 30 m 10,140 • 20 m 14,080 • 17 m 18,104 • 15 m 21,140 • 12 m 24,919 • 10 m 28,180 • 6 m 50,318 • 2 m 144,170 MHz (USB)",
};

// Wyniki i emisja przeżywają wyjście z ekranu
/** @type {{decodes: Ft8Decode[], mode: FtxMode, onlyCq: boolean}} */
const store = { decodes: [], mode: "FT8", onlyCq: false };

/** Wątek dekodowania (jeden na aplikację). */
let worker = /** @type {Worker | null} */ (null);
let nextId = 1;
/** @type {Map<number, (r: any) => void>} */
const pending = new Map();
/** @param {FtxMode} mode @param {Float32Array} samples @param {number} slotStartMs @param {boolean} fromFile @returns {Promise<Ft8Decode[]>} */
function decodeSlot(mode, samples, slotStartMs, fromFile) {
  if (!worker) {
    worker = new Worker(new URL("../audio/ft8-worker.js", import.meta.url), { type: "module" });
    worker.onmessage = (e) => { pending.get(e.data.id)?.(e.data); pending.delete(e.data.id); };
  }
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, (r) => (r.error ? reject(new Error(r.error)) : resolve(r.result)));
    /** @type {Worker} */ (worker).postMessage({ id, mode, samples, slotStartMs, fromFile }, [samples.buffer]);
  });
}

/** @param {import("../app.js").ScreenContext} ctx */
export function ft8Screen(ctx) {
  let status = t("dec_ready");
  let busy = false;
  let fileBusy = false;
  let expanded = false;
  let showFreqs = false;
  // Bieżący slot (nasłuch)
  const slotLen = () => Math.round(FTX[store.mode].slotTime * 1000);
  const buffer = new Float32Array(15 * RATE);
  let fill = 0, slotIndex = -Infinity, collecting = false, submitted = false;
  /** @type {FtxMode} */
  let slotMode = store.mode;

  const mic = micSession(RATE, onAudio, () => { status = mic.listening ? t("ft8_wait_slot_start") : t("web_ft8_stopped"); renderAll(); });

  /** @param {Float32Array} x */
  function onAudio(x) {
    const mode = store.mode, sMs = slotLen();
    const decodeSamples = Math.trunc((FTX[mode].slotTime - 0.4) * RATE);
    const endMs = Date.now();
    const startMs = endMs - (x.length * 1000) / RATE;
    const index = Math.floor(startMs / sMs);
    const inSlot = startMs - index * sMs;
    if (index !== slotIndex || mode !== slotMode) {
      slotIndex = index; slotMode = mode; submitted = false; fill = 0;
      // Start w pierwszej ćwiartce slotu jeszcze się opłaca – brakujący początek to cisza
      collecting = inSlot <= sMs / 4;
      if (collecting) { fill = Math.trunc((inSlot * RATE) / 1000); buffer.fill(0, 0, fill); status = t("ft8_receiving", FTX[mode].label); }
      else status = t("ft8_wait_slot_mode", FTX[mode].label);
      renderStatus();
    }
    if (!collecting || submitted) return;
    const n = Math.min(x.length, decodeSamples - fill);
    if (n > 0) { buffer.set(x.subarray(0, n), fill); fill += n; }
    if (fill >= decodeSamples) {
      submitted = true;
      busy = true;
      renderStatus();
      const slotStart = slotIndex * sMs;
      decodeSlot(mode, buffer.slice(0, decodeSamples), slotStart, false)
        .then((r) => { publish(r); status = t("ft8_last_slot", FTX[mode].label, plural("ft8_messages", r.length, r.length)); })
        .catch(() => {})
        .finally(() => { busy = false; renderStatus(); });
    }
  }

  /** @param {Ft8Decode[]} r */
  function publish(r) {
    if (!r.length) return;
    store.decodes = [...r, ...store.decodes].slice(0, 600);
    renderList();
  }

  async function decodeFileSlots() {
    const file = await pickFile("audio/*,.wav");
    if (!file) return;
    const mode = store.mode;
    fileBusy = true;
    status = t("dec_decoding_file_mode", FTX[mode].label);
    renderAll();
    try {
      const samples = await decodeAudioFile(file, RATE);
      const slotSamples = Math.trunc(FTX[mode].slotTime * RATE);
      const maxSlots = Math.floor(600_000 / slotLen());
      let total = 0;
      for (let start = 0, slot = 0; start < samples.length && slot < maxSlots; start += slotSamples, slot++) {
        const count = Math.min(slotSamples, samples.length - start);
        if (count < slotSamples / 2 && slot > 0) break;
        const chunk = new Float32Array(slotSamples);
        chunk.set(samples.subarray(start, start + count));
        const r = await decodeSlot(mode, chunk, slot * slotLen(), true);
        publish(r);
        total += r.length;
      }
      status = t("ft8_file_decoded", FTX[mode].label, plural("ft8_messages", total, total));
    } catch (e) {
      status = t("dec_file_error", /** @type {Error} */ (e).message);
      toast(status);
    } finally {
      fileBusy = false;
      renderAll();
    }
  }

  const clock = h("span", { class: "title-m bold code" });
  const progress = h("div", { class: "meter" }, h("div"));
  const statusHost = h("div", { class: "row", style: { gap: "8px" } });
  const audioBar = levelBar("Audio");
  audioBar.style.flex = "0 0 90px";
  const panel = h("div");
  const filters = h("div");
  const list = h("div", { class: "ft8-list code" });
  const bottom = h("div", { class: "row", style: { gap: "8px" } });
  const expandBtn = iconButton("fullscreen", t("ft8_full_list"), () => { expanded = !expanded; renderAll(); });

  function renderStatus() {
    replace(statusHost, h("span", { class: "body-s grow ellipsis" }, status), busy ? h("span", { class: "spinner s" }) : null, mic.listening ? audioBar : null);
  }

  function renderPanel() {
    if (expanded) return replace(panel, progress);
    replace(panel, h("div", { class: "card pad-s col gap-s" },
      h("div", { class: "row", style: { gap: "6px" } }, clock, h("span", { class: "grow" }),
        ...(/** @type {FtxMode[]} */ (["FT8", "FT4"])).map((m) => {
          const c = chip(FTX[m].label, store.mode === m, () => { if (!fileBusy) { store.mode = m; renderAll(); } });
          if (fileBusy) /** @type {HTMLButtonElement} */ (c).disabled = true;
          return c;
        })),
      progress, statusHost));
  }

  function renderFilters() {
    if (expanded) return replace(filters);
    const shown = visible();
    replace(filters, h("div", { class: "row", style: { gap: "8px" } },
      chip(t("common_all"), !store.onlyCq, () => { store.onlyCq = false; renderFilters(); renderList(); }),
      chip(t("ft8_only_cq"), store.onlyCq, () => { store.onlyCq = true; renderFilters(); renderList(); }),
      h("span", { class: "grow" }), h("span", { class: "label-m muted" }, String(shown.length)),
      iconButton(showFreqs ? "expandLess" : "expandMore", t("ft8_frequencies", FTX[store.mode].label), () => { showFreqs = !showFreqs; renderFilters(); })),
    showFreqs ? h("div", { class: "body-s muted" }, t("ft8_freq_hint", FTX[store.mode].label, FREQS[store.mode])) : null);
  }

  const visible = () => (store.onlyCq ? store.decodes.filter((d) => isCq(d.message)) : store.decodes);
  const utc = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false, timeZone: "UTC" });

  function renderList() {
    const shown = visible();
    if (!shown.length) {
      return replace(list, h("div", { class: "centered muted body-m", style: { padding: "24px", textAlign: "center", fontFamily: "inherit" } },
        store.decodes.length ? t("ft8_no_match") : t("ft8_wait_slot", FTX[store.mode].label, store.mode === "FT4" ? "7.5" : "15")));
    }
    /** @type {any[]} */
    const rows = [];
    let lastSlot = NaN;
    for (const d of shown) {
      if (d.slotStartMs !== lastSlot) {
        lastSlot = d.slotStartMs;
        rows.push(h("div", { class: "ft8-slot label-s primary" }, d.fromFile
          ? t("ft8_file_segment", Math.floor(d.slotStartMs / Math.round(FTX[d.mode].slotTime * 1000)) + 1, FTX[d.mode].label)
          : `${utc.format(d.slotStartMs)} UTC • ${FTX[d.mode].label}`));
      }
      const call = senderCall(d.message);
      const cq = isCq(d.message);
      const meta = `${(d.snr >= 0 ? "+" : "") + d.snr}`.padStart(3) + " " + dtLabel(d.dt).padStart(4) + " " + String(d.freqHz).padStart(4);
      rows.push(h(call ? "button" : "div", { type: call ? "button" : null, class: `ft8-row ${call ? "plain-btn" : ""}`, onclick: call ? () => ctx.navigate(`callsign_lookup/${encodeURIComponent(call)}`) : null },
        h("span", { class: "muted" }, meta), h("span", { class: cq ? "cq" : "" }, d.text)));
    }
    rows.push(h("div", { class: "label-s muted", style: { padding: "12px", fontFamily: "system-ui, sans-serif" } }, t("ft8_legend")));
    replace(list, rows);
  }

  function renderBottom() {
    replace(bottom,
      h("div", { class: "grow" }, listenButton(mic.listening, () => (mic.listening ? mic.stop() : mic.start()), fileBusy)),
      expanded ? null : h("button", { type: "button", class: "btn btn-tonal square", "aria-label": t("ft8_decode_wav"), onclick: decodeFileSlots, disabled: mic.listening || fileBusy }, icon("upload")),
      expanded ? null : h("button", { type: "button", class: "btn btn-tonal square", "aria-label": t("common_clear"), onclick: () => { store.decodes = []; renderAll(); } }, icon("delete")));
  }

  function renderAll() {
    expandBtn.replaceChildren(icon(expanded ? "fullscreenExit" : "fullscreen"));
    renderStatus(); renderPanel(); renderFilters(); renderList(); renderBottom();
  }

  const timer = setInterval(() => {
    const now = Date.now();
    clock.textContent = `${utc.format(now)} UTC`;
    /** @type {HTMLElement} */ (progress.firstChild).style.width = `${Math.min(100, ((now % slotLen()) / slotLen()) * 100)}%`;
    audioBar.set(mic.level);
  }, 250);
  renderAll();
  const body = h("div", { class: "screen decoder-screen" }, panel, filters, list, bottom);
  const { el } = layout(ctx, { title: t("ft8_title"), body, actions: [expandBtn] });
  return { el, destroy: () => { clearInterval(timer); mic.stop(); } };
}
