// @ts-check
// Wspólne elementy ekranów dekoderów (DigitalDecoderScreens.kt): nasłuch, poziomy, plik dźwiękowy, nagrywanie.
import { h, icon, toast, pickFile, saveTextFile } from "../ui.js";
import { t } from "../i18n.js";
import { startMic, decodeAudioFile } from "../audio.js";
import { wavBytes, audioLevel } from "../core/dsp.js";

/** @param {unknown} e */
export function micError(e) {
  const name = /** @type {any} */ (e)?.name;
  toast(name === "NotAllowedError" ? t("dec_mic_denied") : `${t("dec_mic_failed")}: ${/** @type {Error} */ (e)?.message ?? e}`);
}

/** Pasek poziomu z etykietą; set(v) ustawia 0..1. @param {string} label */
export function levelBar(label) {
  const fill = h("div");
  const el = h("div", { class: "col grow" }, h("span", { class: "label-s muted" }, label), h("div", { class: "meter", style: { marginTop: "2px" } }, fill));
  return Object.assign(el, { set: (/** @type {number} */ v) => { fill.style.width = `${Math.round(Math.min(1, Math.max(0, v)) * 100)}%`; } });
}

/**
 * Nasłuch z mikrofonu z nagrywaniem (do 10 min) w [rate].
 * @param {number} rate @param {(x: Float32Array) => void} onSamples @param {() => void} onChange
 */
export function micSession(rate, onSamples, onChange) {
  /** @type {{stop: () => void} | null} */
  let mic = null;
  /** @type {Float32Array[] | null} */
  let recording = null;
  let recorded = 0;
  const s = {
    level: 0,
    get listening() { return !!mic; },
    get recordingSeconds() { return recording ? Math.floor(recorded / rate) : null; },
    async start() {
      if (mic) return;
      try {
        mic = await startMic((x) => {
          s.level = audioLevel(x);
          if (recording && recorded < rate * 600) { recording.push(x.slice()); recorded += x.length; }
          onSamples(x);
        }, rate);
      } catch (e) {
        mic = null;
        micError(e);
      }
      onChange();
    },
    stop() {
      if (recording) s.saveRecording();
      mic?.stop();
      mic = null;
      s.level = 0;
      onChange();
    },
    startRecording() { recording = []; recorded = 0; onChange(); },
    /** @param {string} [prefix] */
    saveRecording(prefix = "rec") {
      const parts = recording ?? [];
      recording = null;
      onChange();
      if (!parts.length) return;
      const all = new Float32Array(parts.reduce((n, p) => n + p.length, 0));
      let o = 0;
      for (const p of parts) { all.set(p, o); o += p.length; }
      const stamp = new Date().toISOString().replace(/[-:]/g, "").replace("T", "_").slice(0, 15);
      saveTextFile(`${prefix}_${stamp}.wav`, /** @type {any} */ (wavBytes(all, rate)), "audio/wav");
    },
  };
  return s;
}

/**
 * Dekodowanie pliku dźwiękowego partiami (ekran nie zamarza). @param {number} rate @param {(x: Float32Array) => void} onSamples
 * @param {(busy: boolean) => void} onBusy @param {number} [chunk] próbek na partię
 */
export async function decodeFile(rate, onSamples, onBusy, chunk = 600) {
  const file = await pickFile("audio/*,.wav");
  if (!file) return;
  onBusy(true);
  try {
    const samples = await decodeAudioFile(file, rate);
    const perTick = Math.max(chunk, Math.floor(rate / 2 / chunk) * chunk);
    for (let p = 0; p < samples.length; p += perTick) {
      const end = Math.min(samples.length, p + perTick);
      for (let q = p; q < end; q += chunk) onSamples(samples.subarray(q, Math.min(end, q + chunk)));
      await new Promise((r) => setTimeout(r, 0));
    }
  } catch (e) {
    toast(t("dec_file_error", /** @type {Error} */ (e).message ?? String(e)));
  } finally {
    onBusy(false);
  }
}

/** Duży przycisk nasłuchu. @param {boolean} listening @param {() => void} onclick @param {boolean} [disabled] */
export const listenButton = (listening, onclick, disabled = false) =>
  h("button", { type: "button", class: `btn btn-filled listen ${listening ? "danger" : ""}`, onclick, disabled },
    icon(listening ? "stop" : "mic"), listening ? t("dec_stop_listening") : t("dec_start_listening"));

/** @param {string} ic @param {string} label @param {() => void} onclick @param {boolean} [disabled] */
export const actionButton = (ic, label, onclick, disabled = false) =>
  h("button", { type: "button", class: "btn btn-tonal grow small", onclick, disabled }, icon(ic), label);
