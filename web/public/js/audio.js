// @ts-check
// Dźwięk w przeglądarce: odtwarzanie (trener CW) i nasłuch z mikrofonu (dekodery). Na iPhonie AudioContext
// można uruchomić tylko po dotknięciu – dlatego start() i play() wołamy wyłącznie z obsługi przycisków.
import { StreamResampler } from "./core/dsp.js";

/** @type {AudioContext | null} */
let ctx = null;
function context() {
  const AC = window.AudioContext ?? /** @type {any} */ (window).webkitAudioContext;
  if (!ctx || ctx.state === "closed") ctx = new AC();
  return /** @type {AudioContext} */ (ctx);
}

/** iOS 17+: tryb sesji audio (dźwięk mimo przełącznika wyciszenia / nagrywanie). @param {"playback" | "play-and-record"} type */
function audioSession(type) {
  try {
    const s = /** @type {any} */ (navigator).audioSession;
    if (s && s.type !== type) s.type = type;
  } catch { /* starsze przeglądarki */ }
}

/** Odtworzenie próbek; zwraca stop() i obietnicę końca. @param {Float32Array} samples @param {number} sampleRate */
export function playSamples(samples, sampleRate) {
  audioSession("playback");
  const ac = context();
  ac.resume();
  const buf = ac.createBuffer(1, samples.length, sampleRate);
  buf.getChannelData(0).set(samples);
  const src = ac.createBufferSource();
  src.buffer = buf;
  src.connect(ac.destination);
  /** @type {() => void} */
  let resolve = () => {};
  const done = new Promise((r) => { resolve = () => r(undefined); });
  src.onended = () => resolve();
  src.start();
  return { done, stop: () => { try { src.stop(); } catch { /* już zatrzymane */ } resolve(); } };
}

/** Częstotliwość próbkowania, z jaką będzie działał nasłuch (przed startem – przybliżona). */
export const audioRate = () => context().sampleRate;

/**
 * Nasłuch z mikrofonu bez systemowej obróbki (echo, szumy, automatyczne wzmocnienie psują sygnały cyfrowe).
 * [onChunk] dostaje próbki −1..1 w [targetRate] (albo w natywnej częstotliwości, gdy targetRate nie podano).
 * @param {(samples: Float32Array, rate: number) => void} onChunk @param {number} [targetRate]
 */
export async function startMic(onChunk, targetRate) {
  if (!navigator.mediaDevices?.getUserMedia) throw new Error(window.isSecureContext ? "brak mikrofonu" : "wymagane HTTPS");
  audioSession("play-and-record");
  const ac = context();
  await ac.resume();
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1 },
  });
  const source = ac.createMediaStreamSource(stream);
  const rate = targetRate ?? ac.sampleRate;
  const resampler = new StreamResampler(ac.sampleRate, rate);
  const deliver = (/** @type {Float32Array} */ x) => { const y = resampler.process(x); if (y.length) onChunk(y, rate); };
  /** @type {AudioNode} */
  let node;
  try {
    await ac.audioWorklet.addModule(new URL("./audio/capture-worklet.js", import.meta.url));
    const w = new AudioWorkletNode(ac, "hrt-capture", { numberOfInputs: 1, numberOfOutputs: 0 });
    w.port.onmessage = (e) => deliver(e.data);
    node = w;
  } catch {
    // Starsze Safari bez AudioWorklet
    const sp = ac.createScriptProcessor(2048, 1, 1);
    sp.onaudioprocess = (e) => deliver(new Float32Array(e.inputBuffer.getChannelData(0)));
    sp.connect(ac.destination);
    node = sp;
  }
  source.connect(node);
  return {
    rate,
    stop() {
      try { source.disconnect(); node.disconnect(); } catch { /* już odłączone */ }
      stream.getTracks().forEach((t) => t.stop());
    },
  };
}

/** Plik dźwiękowy (WAV, M4A…) → próbki mono w [targetRate]. @param {File} file @param {number} targetRate */
export async function decodeAudioFile(file, targetRate) {
  const ac = context();
  const buf = await ac.decodeAudioData(await file.arrayBuffer());
  const mono = new Float32Array(buf.length);
  for (let c = 0; c < buf.numberOfChannels; c++) {
    const d = buf.getChannelData(c);
    for (let i = 0; i < d.length; i++) mono[i] += d[i] / buf.numberOfChannels;
  }
  return new StreamResampler(buf.sampleRate, targetRate).process(mono).slice();
}
