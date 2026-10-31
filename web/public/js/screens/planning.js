// @ts-check
// Szarówka (GrayLineScreen.kt), beacony NCDXF (BeaconsScreen.kt) i bandplan IARU (BandplanScreen.kt)
import { h, replace, chip, button, icon } from "../ui.js";
import { t, fmt, format, currentLanguage } from "../i18n.js";
import { stationPosition } from "../store.js";
import { location } from "../services.js";
import { Maidenhead, GreatCircle } from "../core/geo.js";
import { NcdxfBeacons } from "../core/radio.js";
import { IaruRegion, regionalInfo, PhraseLocalizer, sunInfo, formatDecimalHour, inPoland } from "../core/world.js";
import { layout, everySecond } from "./common.js";
import { world } from "./map.js";

// ---------------------------------------------------------------- szarówka
/** @param {import("../app.js").ScreenContext} ctx */
export function grayLineScreen(ctx) {
  const clock = h("div", { class: "headline-l bold primary mono center" });
  const canvas = /** @type {HTMLCanvasElement} */ (h("canvas", { style: { width: "100%", height: "260px", display: "block", borderRadius: "12px", background: "#0D47A1" } }));
  const info = h("div", { class: "col gap-m" });
  /** @type {Path2D | null} */
  let land = null;
  world().then((w) => { land = new Path2D(w.svg); draw(new Date()); });

  function draw(now) {
    const pos = stationPosition();
    const lat = pos?.lat ?? 52.23, lon = pos?.lon ?? 21.01;
    const sun = sunInfo(now, lat, lon);
    clock.textContent = now.toISOString().slice(11, 19) + " UTC";
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth, hh = canvas.clientHeight;
    if (w && hh) {
      if (canvas.width !== Math.round(w * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(hh * dpr); }
      const g = /** @type {CanvasRenderingContext2D} */ (canvas.getContext("2d"));
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.fillStyle = "#0D47A1"; g.fillRect(0, 0, w, hh);
      if (land) {
        g.save(); g.scale(w / 1000, hh / 500); g.fillStyle = "#4CAF50"; g.fill(land); g.restore();
      }
      // Linia terminatora i strona nocna
      const sunLonRad = (sun.sunLon * Math.PI) / 180;
      const decl = Math.abs(sun.declinationRad) < 0.0001 ? 0.0001 * Math.sign(sun.declinationRad + 0.000001) : sun.declinationRad;
      const pts = [];
      for (let px = 0; px <= w; px += 2) {
        const lonRad = ((px / w) * 360 - 180) * (Math.PI / 180);
        const phi = Math.atan(-(1 / Math.tan(decl)) * Math.cos(lonRad - sunLonRad));
        pts.push([px, hh * (1 - ((phi * 180) / Math.PI + 90) / 180)]);
      }
      g.beginPath();
      pts.forEach(([x, y], i) => (i ? g.lineTo(x, y) : g.moveTo(x, y)));
      if (sun.declinationDeg >= 0) { g.lineTo(w, hh); g.lineTo(0, hh); } else { g.lineTo(w, 0); g.lineTo(0, 0); }
      g.closePath(); g.fillStyle = "rgba(0,0,0,.5)"; g.fill();
      g.beginPath(); pts.forEach(([x, y], i) => (i ? g.lineTo(x, y) : g.moveTo(x, y)));
      g.strokeStyle = "#FFEB3B"; g.lineWidth = 2; g.stroke();
      g.fillStyle = "#FFEB3B"; g.beginPath(); g.arc(w * ((sun.sunLon + 180) / 360), hh * (1 - (sun.declinationDeg + 90) / 180), 7, 0, Math.PI * 2); g.fill();
      const qx = w * ((lon + 180) / 360), qy = hh * (1 - (lat + 90) / 180);
      g.fillStyle = "#F44336"; g.strokeStyle = "#fff"; g.lineWidth = 2; g.beginPath(); g.arc(qx, qy, 6, 0, Math.PI * 2); g.fill(); g.stroke();
    }
    const hemi = (v, p, n) => `${format("%.4f", Math.abs(v))}°${v >= 0 ? p : n}`;
    replace(info,
      h("div", { class: "row between" }, h("span", { class: "body-l bold" }, t("gl_your_station")),
        pos ? null : button(t("gl_allow_gps"), () => location.start(), "outlined", { icon: "myLocation" })),
      pos ? [h("div", { class: "body-m" }, `Lat: ${hemi(pos.lat, "N", "S")}`), h("div", { class: "body-m" }, `Lon: ${hemi(pos.lon, "E", "W")}`)]
        : h("div", { class: "error-text" }, t("gl_waiting_gps")),
      sun.hasEvent ? [h("div", { class: "kv", style: { marginTop: "12px" } }, h("span", { class: "bold" }, t("gl_sunrise")), h("span", null, formatDecimalHour(sun.sunriseUtc))),
        h("div", { class: "kv" }, h("span", { class: "bold" }, t("gl_sunset")), h("span", null, formatDecimalHour(sun.sunsetUtc)))]
        : h("div", { class: "error-text" }, t("gl_polar")));
  }

  const stop = everySecond(draw);
  const ro = new ResizeObserver(() => draw(new Date()));
  ro.observe(canvas);
  const { el } = layout(ctx, { title: t("gl_title") },
    clock, canvas, h("div", { class: "body-s muted" }, t("gl_legend")),
    h("section", { class: "card pad" }, info),
    h("section", { class: "card tonal pad col" }, h("div", { class: "body-l bold" }, t("gl_about")), h("div", { class: "body-m" }, t("gl_about_text"))));
  return { el, destroy: () => { stop(); ro.disconnect(); } };
}

// ---------------------------------------------------------------- beacony NCDXF
/** @param {import("../app.js").ScreenContext} ctx */
export function beaconsScreen(ctx) {
  const clock = h("div", { class: "headline-m bold primary mono" });
  const now = h("div", { class: "col gap-l" });
  const all = h("div", { class: "col gap-m" });
  /** @type {any} */
  let selected = null;
  const station = stationPosition();

  const pathText = (beacon) => {
    const target = Maidenhead.toLatLon(beacon.gridSquare);
    if (!station || !target) return null;
    const p = GreatCircle.path(station, target);
    return h("div", { class: "body-s muted" }, t("qso_distance", fmt("%,.0f", p.shortPathKm), fmt("%.0f", p.shortPathBearing), GreatCircle.compassPoint(p.shortPathBearing)));
  };

  function tick(date) {
    const sec = Math.floor(date.getTime() / 1000) % 86400;
    clock.textContent = `${date.toISOString().slice(11, 19)} UTC`;
    replace(now, NcdxfBeacons.nowTransmitting(sec).map((s) => h("section", { class: "card pad-s row between" },
      h("div", { class: "col gap-s grow" },
        h("span", { class: "body-m muted" }, format("%.3f MHz", s.bandKhz / 1000)),
        h("span", { class: "title-l bold primary" }, s.beacon.call),
        h("span", { class: "body-m" }, `${s.beacon.entity}, ${s.beacon.location}`), pathText(s.beacon)),
      h("div", { class: "col", style: { alignItems: "flex-end" } },
        h("span", { class: "title-l bold mono" }, `${s.secondsLeft} s`), h("span", { class: "body-s muted" }, t("bcn_next", s.next.call))))));
    replace(all, NcdxfBeacons.BEACONS.map((b) => {
      const isSel = selected === b;
      return h("button", { type: "button", class: `card clickable pad-s col ${isSel ? "selected" : ""}`, onclick: () => { selected = isSel ? null : b; tick(new Date()); } },
        h("div", { class: "row between" }, h("span", { class: "bold" }, b.call), h("span", { class: "body-m" }, `${b.entity} • ${b.gridSquare}`)),
        pathText(b),
        isSel ? NcdxfBeacons.BANDS_KHZ.map((khz, i) => {
          const wait = NcdxfBeacons.secondsUntil(b, i, sec);
          return h("div", { class: `body-m ${wait === 0 ? "bold primary" : ""}` },
            format("%.3f MHz: ", khz / 1000) + (wait === 0 ? t("bcn_on_air_now") : t("bcn_in", `${Math.floor(wait / 60)}:${String(wait % 60).padStart(2, "0")}`)));
        }) : null);
    }));
  }
  const stop = everySecond(tick);
  const { el } = layout(ctx, { title: t("bcn_title"), scrollClass: "tight" },
    h("div", null, clock, h("div", { class: "body-s muted" }, t("bcn_info"))),
    now, h("div", { class: "body-l bold", style: { marginTop: "8px" } }, t("bcn_all")), all,
    h("div", { class: "label-s muted" }, t("bcn_off_note")));
  return { el, destroy: stop };
}

// ---------------------------------------------------------------- bandplan
/** @type {Promise<any> | null} */
let bandplanData = null;

/** @param {import("../app.js").ScreenContext} ctx */
export function bandplanScreen(ctx) {
  const pos = location.get().fix ?? stationPosition();
  // Bez geokodera kraju nie znamy – w Polsce wiemy na pewno, poza nią zgrubnie z położenia
  const countryIso = pos && inPoland(pos) ? "PL" : null;
  const detected = countryIso ? IaruRegion.forCountry(countryIso) : pos ? IaruRegion.forPosition(pos) : null;
  /** @type {1|2|3} */
  let region = detected ?? 1;
  const host = h("div", { class: "scroll tight" });
  const title = h("span");
  /** @type {Set<string>} */
  const expanded = new Set();

  bandplanData ??= fetch("data/bandplan.json").then((r) => r.json());
  bandplanData.then((data) => {
    const localizer = new PhraseLocalizer(data.phrases, (k) => t(k), currentLanguage());
    const L = (s) => localizer.localize(s);
    const render = () => {
      title.textContent = t("bp_title", region);
      const info = regionalInfo(/** @type {1|2|3} */ (region), detected === region ? countryIso : null);
      replace(host,
        h("div", { class: "chips" }, /** @type {(1|2|3)[]} */ ([1, 2, 3]).map((r) => chip(t("bp_region", r) + (r === detected ? t("bp_you_are_here") : ""), region === r, () => { region = r; render(); }))),
        h("section", { class: "card tonal pad-s col gap-s" },
          h("div", { class: "title-s bold" }, t(`iaru_r${region}`)),
          h("div", { class: "body-s" }, `APRS: ${L(info.aprs)}`),
          h("div", { class: "body-s" }, t("bp_fm_calling", L(info.fmCalling))),
          h("div", { class: "body-s" }, t("bp_emergency", L(info.emergency))),
          h("div", { class: "label-s" }, (region === 1 ? t("bp_r1_poland") : "") + t("bp_source_note", info.source))),
        data.regions[region].map((band) => bandCard(band, L)));
    };
    const bandCard = (band, L) => {
      const key = `${region}_${band.name}`;
      const open = expanded.has(key);
      return h("section", { class: "card" },
        h("button", { type: "button", class: "row between", style: { width: "100%", padding: "16px", background: "none", border: "0", textAlign: "left", cursor: "pointer" },
          "aria-expanded": String(open), onclick: () => { if (open) expanded.delete(key); else expanded.add(key); render(); } },
          h("div", { class: "col gap-s" }, h("span", { class: "title-l bold primary" }, band.name), h("span", { class: "body-m muted" }, band.range)),
          icon(open ? "expandLess" : "expandMore")),
        open ? h("div", { style: { padding: "0 16px 16px" } }, h("div", { class: "hr", style: { marginBottom: "8px" } }),
          band.segments.map((s) => h("div", { class: "row", style: { padding: "6px 0" } },
            h("div", { class: "col gap-s", style: { flex: "1.5" } }, h("span", { class: "body-m semibold" }, s.range), h("span", { class: "body-s muted" }, L(s.description))),
            h("div", { style: { flex: "1", textAlign: "right" } }, modeBadge(s.mode, L))))) : null);
    };
    render();
  });

  const { el } = layout(ctx, { title });
  el.querySelector(".scroll")?.replaceWith(host);
  return { el };
}

function modeBadge(mode, L) {
  const color = mode.includes("CW") ? "#2196F3" : mode.includes("Wąskopasmowe") || mode.includes("Digi") ? "#9C27B0"
    : mode.includes("Phone") || mode.includes("SSB") || mode.includes("FM") ? "#4CAF50" : mode.includes("Sat") ? "#FF9800" : "#888888";
  return h("span", { class: "badge s", style: { background: color } }, L(mode));
}

