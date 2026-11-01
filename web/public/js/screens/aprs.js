// @ts-check
// APRS-IS (AprsScreen.kt + AprsViewModel.kt): stacje w promieniu z pozycją, ruchem, PHG i pogodą; nadawanie pozycji.
// Przeglądarka łączy się z serwerem aplikacji przez WebSocket, a ten – z APRS-IS (server/aprs.mjs).
import { h, replace, button, toast, icon } from "../ui.js";
import { t, format } from "../i18n.js";
import { settings, stationPosition } from "../store.js";
import { location, apiUrl } from "../services.js";
import { GreatCircle } from "../core/geo.js";
import { Callsigns } from "../core/radio.js";
import { parseAprs, aprsHeard, aprsCallWithSsid } from "../core/aprs.js";
import { layout } from "./common.js";

/** @typedef {import("../core/aprs.js").AprsPacket} AprsPacket */

// Nasłuch żyje dłużej niż ekran (jak AprsViewModel): działa, dopóki aplikacja jest widoczna
const aprs = {
  /** @type {WebSocket | null} */
  ws: null,
  connected: false,
  canTransmit: false,
  wanted: false,
  /** @type {{call: string, lat: number, lon: number, radius: number} | null} */
  station: null,
  /** @type {Map<string, AprsPacket>} */
  packets: new Map(),
  backoff: 5000,
  /** @type {any} */
  retry: null,
  /** @type {Set<() => void>} */
  listeners: new Set(),
  /** @type {((m: any) => void) | null} */
  onBeacon: null,
};
const notify = () => aprs.listeners.forEach((l) => l());

function wsUrl(/** @type {{call: string, lat: number, lon: number, radius: number}} */ s) {
  const http = new URL(apiUrl("/aprs"), window.location.href);
  http.protocol = http.protocol === "https:" ? "wss:" : "ws:";
  http.search = new URLSearchParams({ lat: s.lat.toFixed(4), lon: s.lon.toFixed(4), radius: String(s.radius), call: s.call }).toString();
  return http.toString();
}

function connect() {
  clearTimeout(aprs.retry);
  const s = aprs.station;
  if (!s || !aprs.wanted || document.hidden) return;
  aprs.ws?.close();
  const ws = new WebSocket(wsUrl(s));
  aprs.ws = ws;
  ws.onmessage = (e) => {
    let m;
    try { m = JSON.parse(String(e.data)); } catch { return; }
    if (m.type === "connected") { aprs.connected = true; aprs.canTransmit = !!m.canTransmit; aprs.backoff = 5000; notify(); }
    else if (m.type === "packet") addPacket(m.line);
    else if (m.type === "beacon") aprs.onBeacon?.(m);
  };
  ws.onclose = () => {
    if (aprs.ws !== ws) return;
    aprs.ws = null;
    aprs.connected = false;
    notify();
    // Ponowne łączenie z narastającym odstępem (5 s … 2 min)
    if (aprs.wanted && !document.hidden) {
      aprs.retry = setTimeout(connect, aprs.backoff);
      aprs.backoff = Math.min(aprs.backoff * 2, 120_000);
    }
  };
}

/** @param {string} line */
function addPacket(line) {
  const p = parseAprs(line);
  if (!p) return;
  aprsHeard.record(p.callsign, p.latitude, p.longitude, p.timestampMs);
  const s = aprs.station;
  if (!s) return;
  p.distanceKm = Math.round(GreatCircle.distanceKm(s, { lat: p.latitude, lon: p.longitude }) * 10) / 10;
  // Serwer filtruje z zapasem – na liście tylko stacje w zadanym promieniu
  if (p.distanceKm > s.radius) return;
  aprs.packets.delete(p.callsign);
  aprs.packets.set(p.callsign, p);
  const hourAgo = Date.now() - 3_600_000;
  for (const [k, v] of aprs.packets) if (v.timestampMs < hourAgo) aprs.packets.delete(k);
  notify();
}

/** Parametry nasłuchu; ponowne łączenie tylko przy zmianie znaku/promienia albo większym przemieszczeniu. */
function updateStation() {
  const st = settings.get();
  const pos = location.get().fix ?? stationPosition();
  if (!pos) return;
  const next = { call: st.callsign, lat: pos.lat, lon: pos.lon, radius: st.aprs_radius_km };
  const cur = aprs.station;
  const moved = cur && GreatCircle.distanceKm(cur, next) > Math.max(2, next.radius * 0.1);
  aprs.station = next;
  if (!cur || cur.call !== next.call || cur.radius !== next.radius || moved || !aprs.ws) connect();
  else {
    for (const p of aprs.packets.values()) p.distanceKm = Math.round(GreatCircle.distanceKm(next, { lat: p.latitude, lon: p.longitude }) * 10) / 10;
  }
}

if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) { aprs.ws?.close(); aprs.ws = null; aprs.connected = false; }
    else if (aprs.wanted) connect();
  });
}

/** Podgląd symbolu z dwuznakowego kodu (tabela lub nakładka + symbol). @param {string} code @param {number} [size] */
export function aprsSymbolPreview(code, size = 32) {
  const table = code[0];
  const el = aprsIcon(/** @type {any} */ ({ table: table === "/" ? "/" : "\\", symbol: code[1], overlay: table !== "/" && table !== "\\" ? table : null }));
  if (size !== 32) { el.style.transform = `scale(${size / 32})`; el.style.margin = `${(size - 32) / 2}px`; }
  return el;
}

/** Symbol APRS z arkusza (16×6). @param {AprsPacket} p */
function aprsIcon(p) {
  const code = p.symbol.charCodeAt(0) - 33;
  const el = h("span", { class: `aprs-icon ${p.table === "/" ? "primary-table" : "alt-table"}`, role: "img", "aria-label": t("aprs_icon_desc", p.table + p.symbol) });
  if (code >= 0 && code < 96) el.style.backgroundPosition = `${-(code % 16) * 32}px ${-Math.floor(code / 16) * 32}px`;
  if (p.overlay) el.append(h("span", { class: "aprs-overlay" }, p.overlay));
  return el;
}

/** @param {import("../app.js").ScreenContext} ctx */
export function aprsScreen(ctx) {
  /** @type {Set<string>} */
  const expanded = new Set();
  const stationCard = h("div");
  const list = h("div", { class: "col gap-s", style: { paddingBottom: "88px" } });
  const fab = h("div", { style: { display: "contents" } });

  const st = () => settings.get();
  const txCall = () => { const c = st().callsign; return Callsigns.isValidAmateur(c) && Callsigns.baseCall(c).length <= 9 ? Callsigns.baseCall(c) : null; };

  function sendBeacon() {
    const pos = location.get().fix ?? stationPosition();
    if (!pos) return toast(t("aprs_no_gps"));
    const s = st();
    const full = aprsCallWithSsid(/** @type {string} */ (txCall()), s.aprs_ssid);
    const msg = JSON.stringify({ type: "beacon", ssid: s.aprs_ssid, icon: s.aprs_icon, comment: s.aprs_comment, lat: pos.lat, lon: pos.lon });
    aprs.onBeacon = (m) => {
      aprs.onBeacon = null;
      toast(m.ok ? t("aprs_position_sent", full) : m.message === "rate" ? t("web_aprs_wait") : `${t("common_unknown_error")}: ${m.message}`);
    };
    if (aprs.ws?.readyState === WebSocket.OPEN && aprs.connected && aprs.canTransmit) aprs.ws.send(msg);
    else toast(t("aprs_connecting"));
  }

  function render() {
    const s = st();
    const tx = txCall();
    const full = tx ? aprsCallWithSsid(tx, s.aprs_ssid) : t("aprs_listen_only");
    replace(stationCard, h("div", { class: "card info-card" },
      h("div", { class: "info-card-head" }, h("span", { class: "info-card-title" }, t("aprs_my_station")),
        h("span", { class: `dot ${aprs.connected ? "ok" : ""}`, title: aprs.connected ? "online" : "offline" })),
      h("div", { class: "headline-s" }, full),
      h("div", { class: "body-m muted" }, t("aprs_radius", s.aprs_radius_km) + (s.aprs_comment.trim() ? t("aprs_comment", s.aprs_comment) : "")),
      h("div", { class: "body-s muted" }, t("web_aprs_listening")),
      tx ? null : [h("div", { class: "body-s tertiary" }, t("aprs_need_callsign")),
        h("div", null, button(t("aprs_enter_callsign"), () => ctx.navigate("settings"), "text", { style: { marginLeft: "-12px" } }))]));
    const packets = [...aprs.packets.values()].sort((a, b) => (a.distanceKm ?? 0) - (b.distanceKm ?? 0));
    if (!packets.length) {
      const pos = location.get().fix ?? stationPosition();
      replace(list, h("div", { class: "centered muted body-l", style: { padding: "48px 16px", textAlign: "center" } },
        !pos ? t("aprs_waiting_gps") : aprs.connected ? t("aprs_waiting_frames", s.aprs_radius_km) : t("aprs_connecting")));
    } else replace(list, packets.map(item));
    replace(fab, tx ? h("button", { type: "button", class: "fab extended", onclick: sendBeacon }, icon("cellTower"), t("aprs_send_position")) : null);
  }

  /** @param {AprsPacket} p */
  function item(p) {
    const open = expanded.has(p.callsign);
    const mins = Math.max(0, Math.floor((Date.now() - p.timestampMs) / 60000));
    const detail = (/** @type {string} */ label, /** @type {string} */ value) => h("div", { class: "body-s" }, h("span", { class: "muted" }, `${label}: `), value);
    const wx = [p.tempCelsius != null ? format("%.1f °C", p.tempCelsius) : null, p.humidityPercent != null ? t("aprs_humidity", String(p.humidityPercent)).replace("%%", "%") : null,
      p.pressureHpa != null ? format("%.1f hPa", p.pressureHpa) : null,
      p.windSpeedKmh != null ? t("aprs_wind", Math.round(p.windSpeedKmh)) + (p.windDirDeg != null ? t("aprs_wind_from", String(p.windDirDeg)) : "") : null,
      p.windGustKmh != null ? t("aprs_gusts", Math.round(p.windGustKmh)) : null,
      p.rain1hMm != null ? t("aprs_rain_1h", format("%.1f", p.rain1hMm)) : null, p.rain24hMm != null ? t("aprs_rain_24h", format("%.1f", p.rain24hMm)) : null].filter(Boolean);
    return h("button", { type: "button", class: "card clickable pad-s col gap-s", onclick: () => { if (open) expanded.delete(p.callsign); else expanded.add(p.callsign); render(); } },
      h("div", { class: "row", style: { gap: "12px" } }, aprsIcon(p),
        h("div", { class: "col grow" }, h("span", { class: "title-m primary" }, p.callsign),
          p.comment && !open ? h("span", { class: "body-s muted ellipsis" }, p.comment) : null),
        h("div", { class: "col", style: { alignItems: "flex-end" } }, h("span", { class: "title-s" }, `${format("%.1f", p.distanceKm ?? 0)} km`),
          h("span", { class: "label-s muted" }, mins === 0 ? t("aprs_now") : t("aprs_min_ago", mins)))),
      open ? h("div", { class: "col gap-s", style: { textAlign: "left" } }, h("div", { class: "hr" }),
        detail(t("aprs_position"), format("%.4f° %s, %.4f° %s", Math.abs(p.latitude), p.latitude >= 0 ? "N" : "S", Math.abs(p.longitude), p.longitude >= 0 ? "E" : "W")),
        p.speedKmh != null || p.courseDegrees != null ? detail(t("aprs_movement"), [p.speedKmh != null ? `${Math.round(p.speedKmh)} km/h` : null,
          p.courseDegrees != null ? t("aprs_course", String(p.courseDegrees)) : null].filter(Boolean).join(", ")) : null,
        p.altitudeM != null ? detail(t("aprs_altitude"), t("aprs_altitude_value", Math.round(p.altitudeM))) : null,
        p.powerW != null ? detail(t("aprs_phg"), [`${p.powerW} W`, p.antennaHeightM != null ? t("aprs_antenna", Math.round(p.antennaHeightM)) : null,
          p.antennaGainDb != null ? t("aprs_gain", String(p.antennaGainDb)) : null].filter(Boolean).join(", ")) : null,
        wx.length ? detail(t("aprs_weather"), wx.join(", ")) : null,
        p.comment ? h("div", { class: "body-s", style: { fontStyle: "italic" } }, p.comment) : null) : null);
  }

  aprs.wanted = true;
  aprs.listeners.add(render);
  const unsubGps = location.subscribe(() => { updateStation(); render(); });
  const unsubSettings = settings.subscribe?.(() => { updateStation(); render(); });
  location.alreadyGranted().then((g) => { if (g) location.start(); });
  updateStation();
  render();
  const timer = setInterval(render, 30_000);
  const body = h("div", { class: "screen", style: { position: "relative" } }, h("div", { class: "scroll" }, stationCard, list), fab);
  const { el } = layout(ctx, { title: "APRS-IS", body });
  return { el, destroy: () => { aprs.listeners.delete(render); unsubGps(); unsubSettings?.(); clearInterval(timer); } };
}
