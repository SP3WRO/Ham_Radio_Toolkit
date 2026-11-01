// @ts-check
// Balony – Horus Binary (HorusDecoderScreen + HorusReceiver): 4FSK 100 Bd z mikrofonu albo pliku, widmo strojenia,
// ostatnia telemetria (wysokość, wznoszenie, odległość i elewacja od stacji), lista ramek.
import { h, replace, iconButton, icon, toast } from "../ui.js";
import { t, fmt, format } from "../i18n.js";
import { HorusModem, HorusStream, decodeTelemetry, parsePayloadIds, parseCustomFields, hasPosition, balloonElevationDeg, secondsBetween, tuningSpectrum } from "../core/horus.js";
import { Maidenhead, GreatCircle } from "../core/geo.js";
import { stationPosition } from "../store.js";
import { location, proxied } from "../services.js";
import { layout } from "./common.js";
import { micSession, decodeFile, levelBar, listenButton } from "./decoder-common.js";

/** @typedef {import("../core/horus.js").HorusTelemetry} HorusTelemetry */
/** @typedef {import("../core/horus.js").HorusRawFrame} HorusRawFrame */
/** @typedef {{receivedMs: number, raw: HorusRawFrame, telemetry: HorusTelemetry | null, snrDb: number, fromFile: boolean, computedAscentMs: number | null}} FrameEntry */

const RATE = 12000;
const SPECTRUM_MAX_HZ = 4000;
const MAX_HISTORY = 500;

// Ramki przeżywają wyjście z ekranu
/** @type {FrameEntry[]} */
let frames = [];

/** Listy Projektu Horus: świeże przez serwer (raz w tygodniu), w razie braku sieci – kopia w aplikacji. */
/** @type {Promise<{ids: Map<number, string>, formats: Map<string, import("../core/horus.js").HorusCustomFormat>}> | null} */
let listsPromise = null;
function loadLists() {
  /** @param {string} name */
  const get = async (name) => {
    try { return await (await proxied("horus", name)).text(); } catch { return await (await fetch(`data/horus/${name}`)).text(); }
  };
  listsPromise ??= Promise.all([get("payload_id_list.txt"), get("custom_field_list.json")]).then(([a, b]) => {
    let ids = parsePayloadIds(a), formats = new Map();
    try { formats = parseCustomFields(b); } catch { /* zostaje domyślny format RS41ng */ }
    return { ids, formats };
  }).catch(() => ({ ids: new Map(), formats: new Map() }));
  return listsPromise;
}

/** @param {string} label */
const label = (label) => (label.startsWith("@") ? t(label.slice(1)) : label);

/** @param {import("../app.js").ScreenContext} ctx */
export function horusScreen(ctx) {
  let modem = newModem();
  let stream = new HorusStream(modem);
  let showHelp = false;
  let busy = false;
  /** @type {number | null} */
  let snr = null;
  /** @type {number[]} */
  let tones = [];
  /** @type {Float32Array} */
  let spectrum = new Float32Array(0);
  /** @type {Awaited<ReturnType<typeof loadLists>> | null} */
  let lists = null;
  loadLists().then((l) => { lists = l; });

  function newModem() { const m = new HorusModem(RATE); m.setFreqEstLimits(100, 4000); return m; }
  function reset() { modem = newModem(); stream = new HorusStream(modem); snr = null; tones = []; spectrum = new Float32Array(0); }

  function publishModemState() {
    snr = modem.snrDb;
    tones = [...modem.toneFrequencies];
    spectrum = tuningSpectrum(modem.fsk, SPECTRUM_MAX_HZ, 200);
  }

  let found = 0;
  /** @param {HorusRawFrame} raw @param {boolean} fromFile */
  function onFrame(raw, fromFile) {
    const telemetry = decodeTelemetry(raw, lists?.ids, lists?.formats);
    const now = Date.now();
    /** @type {number | null} */
    let ascent = null;
    if (telemetry?.altitudeM != null) {
      const prev = frames.find((f) => f.telemetry?.callsign === telemetry.callsign && f.telemetry?.altitudeM != null);
      if (prev?.telemetry) {
        const dt = secondsBetween(prev.telemetry.timeUtc, telemetry.timeUtc) ?? (!fromFile && !prev.fromFile ? (now - prev.receivedMs) / 1000 : null);
        if (dt != null && dt >= 1 && dt <= 600) ascent = (telemetry.altitudeM - /** @type {number} */ (prev.telemetry.altitudeM)) / dt;
      }
    }
    frames = [{ receivedMs: now, raw, telemetry, snrDb: modem.snrDb, fromFile, computedAscentMs: ascent }, ...frames].slice(0, MAX_HISTORY);
    found++;
    renderLatest(); renderList();
  }

  const mic = micSession(RATE, (x) => stream.process(x, (f) => onFrame(f, false), publishModemState), () => { renderControls(); renderStatus(); });

  const help = h("div");
  const statusText = h("span", { class: "title-s bold grow" });
  const audioBar = levelBar("Audio");
  audioBar.style.flex = "0 0 90px";
  const statusRow = h("div", { class: "row", style: { gap: "8px" } }, statusText);
  const canvas = /** @type {HTMLCanvasElement} */ (h("canvas", { class: "spectrum", style: { height: "64px" }, "aria-label": "spectrum" }));
  const toneText = h("div", { class: "label-s muted code" });
  const tuning = h("div", { class: "card pad-s col gap-s" }, statusRow, canvas, toneText);
  const latest = h("div");
  const list = h("div", { class: "ft8-list code" });
  const controls = h("div", { class: "row", style: { gap: "8px" } });
  const helpBtn = iconButton("expandMore", t("dec_how_to"), () => { showHelp = !showHelp; renderHelp(); });

  function renderHelp() {
    helpBtn.replaceChildren(icon(showHelp ? "expandLess" : "expandMore"));
    replace(help, showHelp ? h("div", { class: "body-s muted" }, t("hor_help")) : null);
  }

  function renderStatus() {
    statusText.textContent = busy ? t("dec_decoding_file") : mic.listening ? (snr != null ? format("SNR %.1f dB", snr) : t("dec_listening")) : t("dec_ready");
    if (mic.listening && !audioBar.isConnected) statusRow.append(audioBar);
    if (!mic.listening && audioBar.isConnected) audioBar.remove();
    toneText.textContent = !tones.length || tones.every((f) => f <= 0) ? t("hor_tones_none") : t("hor_tones", tones.map((f) => Math.trunc(f)).join(" / "));
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
    g.fillStyle = css.getPropertyValue("--outline-variant") || "rgba(128,128,128,.4)";
    for (let k = 1; k < SPECTRUM_MAX_HZ / 1000; k++) g.fillRect((w * k * 1000) / SPECTRUM_MAX_HZ, 0, 1, hh);
    g.fillStyle = css.getPropertyValue("--tertiary") || "#7d5260";
    for (const f of tones) if (f > 0 && f <= SPECTRUM_MAX_HZ) g.fillRect((w * f) / SPECTRUM_MAX_HZ - 1, 0, 2, hh);
    if (spectrum.length < 2) return;
    g.beginPath();
    spectrum.forEach((db, i) => {
      const x = (w * i) / (spectrum.length - 1), y = hh * Math.min(1, Math.max(0, -db / 60));
      if (i === 0) g.moveTo(x, y); else g.lineTo(x, y);
    });
    g.strokeStyle = css.getPropertyValue("--primary") || "#1b5e9e";
    g.lineWidth = 1.5;
    g.stroke();
  }

  function renderLatest() {
    const entry = frames.find((f) => f.telemetry);
    const tel = entry?.telemetry;
    if (!entry || !tel) return replace(latest);
    const ago = Math.max(0, Math.floor((Date.now() - entry.receivedMs) / 1000));
    const ascent = tel.ascentRateMs ?? entry.computedAscentMs;
    /** @type {[string, string][]} */
    const values = [];
    if (tel.altitudeM != null) values.push([t("hor_altitude"), fmt("%,d m", tel.altitudeM)]);
    if (ascent != null) values.push([t("hor_ascent"), (ascent >= 0 ? "+" : "") + fmt("%.1f m/s", ascent)]);
    if (tel.speedKmh != null) values.push([t("hor_speed"), `${tel.speedKmh} km/h`]);
    if (tel.satellites != null) values.push([t("hor_satellites"), String(tel.satellites)]);
    if (tel.temperatureC != null) values.push([t("hor_temperature"), fmt("%.0f °C", tel.temperatureC)]);
    if (tel.batteryV != null) values.push([t("hor_battery"), fmt("%.2f V", tel.batteryV)]);
    values.push(["SNR", fmt("%.1f dB", entry.snrDb)]);
    const station = location.get().fix ?? stationPosition();
    /** @type {any[]} */
    const pos = [];
    if (hasPosition(tel)) {
      pos.push(h("div", { class: "body-m code" }, `${format("%.5f, %.5f", tel.latitude, tel.longitude)}  •  ${Maidenhead.fromLatLon(tel.latitude, tel.longitude)}`));
      if (station) {
        const path = GreatCircle.path(station, { lat: tel.latitude, lon: tel.longitude });
        const el = tel.altitudeM != null ? t("hor_elevation", fmt("%.1f", balloonElevationDeg(path.shortPathKm, tel.altitudeM))) : "";
        pos.push(h("div", { class: "body-m bold" }, t("qso_distance", fmt("%,.1f", path.shortPathKm), fmt("%.0f", path.shortPathBearing), GreatCircle.compassPoint(path.shortPathBearing)) + el));
      }
    } else pos.push(h("div", { class: "body-s" }, t("hor_no_gps")));
    const copy = async () => {
      const text = format("%s %.5f, %.5f", tel.callsign, tel.latitude, tel.longitude) + (tel.altitudeM != null ? ` ${tel.altitudeM} m` : "");
      try { await navigator.clipboard.writeText(text); toast(t("hor_copied")); } catch { toast(text); }
    };
    replace(latest, h("div", { class: "card running pad-s col gap-s" },
      h("div", { class: "row", style: { gap: "4px" } },
        h("div", { class: "col grow" },
          h("span", { class: "title-l bold" }, tel.callsign),
          h("span", { class: "label-m" }, `v${tel.version} • #${tel.sequence}` + (tel.timeUtc ? ` • ${tel.timeUtc} UTC` : "") + (entry.fromFile ? t("hor_from_file") : t("hor_seconds_ago", ago)))),
        hasPosition(tel) ? h("a", { class: "icon-btn", "aria-label": t("hor_show_map"), target: "_blank", rel: "noopener",
          href: `https://maps.apple.com/?ll=${tel.latitude},${tel.longitude}&q=${encodeURIComponent(tel.callsign)}` }, icon("map")) : null,
        hasPosition(tel) ? iconButton("contentCopy", t("hor_copy_position"), copy) : null),
      h("div", { class: "horus-values" }, values.map(([k, v]) => h("div", { class: "col" }, h("span", { class: "label-s muted-on" }, k), h("span", { class: "body-m bold" }, v)))),
      ...pos,
      tel.extra.length ? h("div", { class: "body-s" }, tel.extra.map(([k, v]) => `${label(k)}: ${v}`).join("  •  ")) : null));
  }

  const hms = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
  function renderList() {
    if (!frames.length) return replace(list, h("div", { class: "centered muted body-m", style: { padding: "24px", textAlign: "center", fontFamily: "inherit" } }, t("hor_wait")));
    replace(list, frames.map((f) => {
      const tel = f.telemetry;
      const text = tel
        ? `${tel.callsign} #${tel.sequence}` + (tel.altitudeM != null ? fmt(" %,d m", tel.altitudeM) : "") +
          (hasPosition(tel) ? format(" %.4f %.4f", tel.latitude, tel.longitude) : "") + fmt(" %.0f dB", f.snrDb)
        : t("hor_unknown_format", String(f.raw.version), f.raw.hex);
      return h("div", { class: "ft8-row" }, h("span", { class: "muted" }, f.fromFile ? t("hor_file") : hms.format(f.receivedMs)), h("span", null, text));
    }));
  }

  function renderControls() {
    replace(controls,
      h("div", { class: "grow" }, listenButton(mic.listening, () => { if (mic.listening) mic.stop(); else { reset(); mic.start(); } }, busy)),
      h("button", { type: "button", class: "btn btn-tonal square", "aria-label": t("ft8_decode_wav"), disabled: mic.listening || busy,
        onclick: () => decodeFile(RATE, (x) => stream.process(x, (f) => onFrame(f, true), publishModemState), (b) => {
          busy = b;
          if (b) { reset(); found = 0; } else toast(t("hor_file_result", found));
          renderControls(); renderStatus();
        }, RATE / 10) }, icon("upload")),
      h("button", { type: "button", class: "btn btn-tonal square", "aria-label": t("common_clear"), onclick: () => { frames = []; renderLatest(); renderList(); } }, icon("delete")));
  }

  let alive = true;
  let lastDraw = 0;
  function frame(/** @type {number} */ now) {
    if (!alive) return;
    if (now - lastDraw > 200) { lastDraw = now; drawSpectrum(); renderStatus(); }
    audioBar.set(mic.level);
    requestAnimationFrame(frame);
  }
  const ticker = setInterval(renderLatest, 1000);
  renderHelp(); renderStatus(); renderLatest(); renderList(); renderControls();
  requestAnimationFrame(frame);
  const body = h("div", { class: "screen decoder-screen" }, help, tuning, latest, list, controls);
  const { el } = layout(ctx, { title: t("hor_title"), body, actions: [helpBtn] });
  return { el, destroy: () => { alive = false; clearInterval(ticker); mic.stop(); } };
}
