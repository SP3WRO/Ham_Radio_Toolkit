// @ts-check
// Satelity (SatelliteScreen.kt): radar nieboskłonu, lista z najbliższymi przelotami, częstotliwości.
// TLE i częstotliwości z serwera (raz na dobę), przeloty liczone w przeglądarce (SGP4) – działa też offline.
import { h, replace, iconButton, textField, icon } from "../ui.js";
import { t, format, uiLocale } from "../i18n.js";
import { stationPosition } from "../store.js";
import { location, apiUrl } from "../services.js";
import { Maidenhead } from "../core/geo.js";
import { satrec, nextPass, lookAngles, skyTrack, noradId } from "../core/sgp4.js";
import { layout } from "./common.js";

const CACHE_KEY = "hrt.satellites.v1";
const MAX_AGE = 24 * 3_600_000;
const POPULAR = ["ISS", "SO-50", "AO-91", "PO-101", "FO-29", "XW-3", "AO-73", "AO-7", "FUNCUBE", "LILACSAT", "CAS-4A", "CAS-4B"];

/** @typedef {import("../core/sgp4.js").Tle} Tle */
/** @typedef {{aos: number, los: number, maxEl: number} | "always" | "none"} PassState */

// Stan żyjący dłużej niż ekran (jak SatelliteViewModel): dane, policzone przeloty, wybrany satelita
const state = {
  /** @type {{tles: Tle[], satnogs: any, amsat: any[] | null, fetchedAt: number} | null} */
  data: null,
  /** @type {Map<string, import("../core/sgp4.js").SatRec>} */
  recs: new Map(),
  /** @type {Map<string, PassState>} */
  passes: new Map(),
  passesFor: "",
  /** @type {string | null} */
  expanded: null,
  query: "",
};
/** @type {Promise<Record<string, string[][]>> | null} */
let builtinFreqs = null;

function readCache() {
  try { return JSON.parse(localStorage.getItem(CACHE_KEY) ?? "null"); } catch { return null; }
}

/** Aktywne satelity (SatNOGS) – gdy listy brak, wszystkie z TLE. @param {any} data */
function applyData(data) {
  const active = new Set(data.satnogs?.active ?? []);
  const tles = /** @type {Tle[]} */ (data.tles);
  const shown = active.size ? tles.filter((x) => active.has(noradId(x.line2))) : tles;
  state.data = { ...data, tles: shown.length ? shown : tles };
  state.recs = new Map();
  for (const tle of state.data.tles) {
    const r = satrec(tle);
    if (r) state.recs.set(tle.name, r);
  }
  state.passesFor = "";
}

/** @param {import("../app.js").ScreenContext} ctx */
export function satellitesScreen(ctx) {
  let loading = false;
  let error = "";
  let computing = /** @type {[number, number] | null} */ (null);
  /** @type {any} */
  let trackKey = null;
  /** @type {{az: number, el: number}[]} */
  let track = [];
  const sky = h("div", { class: "card pad col gap-s", style: { alignItems: "center" } });
  const header = h("div", { class: "row between" });
  const list = h("div", { class: "col gap-s" });
  const statusLine = h("div", { class: "label-s muted" });
  const refreshBtn = /** @type {HTMLButtonElement} */ (iconButton("refresh", t("sat_update_online"), () => load(true)));
  const search = textField({ label: t("sat_search"), value: state.query, oninput: (v) => { state.query = v; renderList(); } });
  const timeFmt = new Intl.DateTimeFormat(uiLocale(), { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });

  /** Pozycja stacji: GPS na żywo albo zapisana / z lokatora (zaokrąglona do ~1 km, żeby nie przeliczać bez potrzeby). */
  function site() {
    const fix = location.get().fix;
    const p = fix ?? stationPosition();
    if (!p) return null;
    return { lat: Math.round(p.lat * 100) / 100, lon: Math.round(p.lon * 100) / 100, alt: Math.round(((fix?.altitude ?? 100)) / 100) * 100 };
  }

  async function load(force = false) {
    const cached = readCache();
    if (!state.data && cached) applyData(cached);
    if (!force && cached && Date.now() - cached.fetchedAt < MAX_AGE) return computeAll();
    loading = true;
    refreshBtn.disabled = true;
    renderHeader();
    try {
      const res = await fetch(apiUrl(`/satellites${force ? "?refresh=1" : ""}`), { signal: AbortSignal.timeout(120000) });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error ?? `HTTP ${res.status}`);
      try { localStorage.setItem(CACHE_KEY, JSON.stringify(data)); } catch { /* za mało miejsca – zostaje w pamięci */ }
      applyData(data);
      error = "";
    } catch (e) {
      error = /** @type {Error} */ (e).message;
    } finally {
      loading = false;
      refreshBtn.disabled = false;
    }
    computeAll();
  }

  /** @param {import("../core/sgp4.js").SatRec} rec @param {any} s @param {number} now @returns {PassState} */
  function passFor(rec, s, now) {
    const p = nextPass(rec, s, now);
    if (p) return p;
    const look = lookAngles(rec, s, now);
    return look && look.el > 0 && rec.deep ? "always" : "none";
  }

  /** Przeloty wszystkich satelitów – partiami, żeby ekran nie zamarzał; popularne najpierw. */
  async function computeAll() {
    const s = site();
    renderAll();
    if (!s || !state.data) return;
    const key = `${state.data.fetchedAt}|${s.lat}|${s.lon}|${s.alt}`;
    if (state.passesFor === key) return;
    state.passesFor = key;
    const names = [...state.recs.keys()].sort((a, b) => Number(POPULAR.some((p) => b.toUpperCase().includes(p))) - Number(POPULAR.some((p) => a.toUpperCase().includes(p))));
    /** @type {Map<string, PassState>} */
    const result = new Map(state.passes);
    computing = [0, names.length];
    const now = Date.now();
    for (let i = 0; i < names.length; i++) {
      if (state.passesFor !== key || !alive) return;
      const rec = /** @type {any} */ (state.recs.get(names[i]));
      result.set(names[i], passFor(rec, s, now));
      if (i % 6 === 5 || i === names.length - 1) {
        computing = [i + 1, names.length];
        state.passes = new Map(result);
        renderHeader();
        tickers.forEach((u) => u());
        await new Promise((r) => setTimeout(r, 0));
      }
    }
    computing = null;
    state.passes = result;
    renderAll();
  }

  /** Odświeżenie przelotów, które już się skończyły (co 15 s). */
  function refreshEnded() {
    const s = site();
    if (!s || computing) return;
    const now = Date.now();
    let changed = false;
    for (const [name, p] of state.passes) {
      if (typeof p === "object" && p.los <= now) {
        const rec = state.recs.get(name);
        if (rec) { state.passes.set(name, passFor(rec, s, now)); changed = true; }
      }
    }
    if (changed) renderList();
  }

  /** Satelita na radarze: wybrany, trwający przelot albo najbliższy. */
  function compassTarget(now) {
    if (state.expanded && state.recs.has(state.expanded)) return state.expanded;
    let best = null, bestAos = Infinity;
    for (const [name, p] of state.passes) {
      if (typeof p !== "object" || p.los <= now) continue;
      if (now >= p.aos) return name;
      if (p.aos < bestAos) { bestAos = p.aos; best = name; }
    }
    return best ?? [...state.recs.keys()][0] ?? null;
  }

  function renderSky() {
    const s = site();
    const now = Date.now();
    const name = s ? compassTarget(now) : null;
    const rec = name ? state.recs.get(name) : null;
    const look = rec && s ? lookAngles(rec, s, now) : null;
    const p = name ? state.passes.get(name) : null;
    if (rec && s && typeof p === "object") {
      const key = `${name}|${p.aos}`;
      if (key !== trackKey) { trackKey = key; track = skyTrack(rec, s, p.aos, p.los); }
    } else if (trackKey) { trackKey = null; track = []; }
    const loc = s ? Maidenhead.fromLatLon(s.lat, s.lon, 6) : null;
    replace(sky,
      h("div", { class: "row between", style: { width: "100%" } }, h("span", { class: "body-m bold primary" }, t("sat_skyplot")), loc ? h("span", { class: "tag neutral" }, `LOC: ${loc}`) : null),
      skyplot(look, name, track),
      name && look ? h("div", { class: "row", style: { width: "100%", justifyContent: "space-evenly" } },
        stat(t("sat_satellite"), name, "primary"), stat(t("sat_azimuth"), format("%.1f°", look.az), ""),
        stat(t("sat_elevation"), format("%.1f°", look.el), look.el > 0 ? "tertiary" : ""))
        : h("div", { class: "body-s muted" }, s ? t("sat_pick_hint") : t("web_sat_no_position")));
  }

  /** @param {string} label @param {string} value @param {string} cls */
  const stat = (label, value, cls) => h("div", { class: "col", style: { alignItems: "center" } },
    h("span", { class: "label-s muted" }, label), h("span", { class: `body-l bold ${cls}` }, value));

  function renderHeader() {
    const n = filtered().length;
    replace(header, h("span", { class: "body-m bold" }, t("sat_list_header", n)),
      computing ? h("span", { class: "row body-s secondary", style: { gap: "6px" } }, h("span", { class: "spinner s" }), t("sat_calculating", computing[0], computing[1]))
        : loading ? h("span", { class: "spinner s" }) : null);
    const age = state.data?.fetchedAt ? new Intl.DateTimeFormat(uiLocale(), { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(state.data.fetchedAt) : null;
    statusLine.textContent = [age ? t("web_sat_tle_age", age) : null, error || null].filter(Boolean).join(" • ");
  }

  function filtered() {
    if (!state.data) return [];
    const q = state.query.trim().toLowerCase();
    const sortKey = (/** @type {string} */ n) => { const p = state.passes.get(n); return typeof p === "object" ? p.aos : p === "always" ? -Infinity : Infinity; };
    return [...state.recs.keys()].filter((n) => n.toLowerCase().includes(q)).sort((a, b) => sortKey(a) - sortKey(b) || a.localeCompare(b));
  }

  function renderList() {
    renderHeader();
    tickers = [];
    if (!state.data) return replace(list, h("div", { class: "body-m muted", style: { padding: "24px 8px", textAlign: "center" } }, loading ? "" : error ? `${t("web_sat_no_data")} (${error})` : t("web_sat_no_data")));
    const names = filtered();
    if (!names.length) return replace(list, h("div", { class: "body-m muted", style: { padding: "24px", textAlign: "center" } }, t("sat_none")));
    tickers = [];
    replace(list, names.map((n) => satCard(n)));
  }

  /** Aktualizacje liczników na kartach co sekundę (bez przebudowy listy – inaczej gubiłyby się dotknięcia). */
  /** @type {(() => void)[]} */
  let tickers = [];

  /** @param {string} name */
  function satCard(name) {
    const open = state.expanded === name;
    const lineHost = h("div");
    const maxHost = h("span", { class: "body-s bold pre", style: { textAlign: "right" } });
    const card = h("div", { class: "card pad-s col gap-s" },
      h("button", { type: "button", class: "row plain-btn", style: { width: "100%", textAlign: "left" }, onclick: () => { state.expanded = open ? null : name; renderAll(); } },
        h("span", { class: "primary", style: { display: "flex" } }, icon("satelliteAlt")),
        h("div", { class: "col grow" }, h("span", { class: "body-l bold" }, name), lineHost),
        maxHost),
      open ? frequencies(name) : null);
    const update = () => {
      const now = Date.now();
      const p = state.passes.get(name);
      const passing = typeof p === "object" && now >= p.aos && now < p.los;
      card.classList.toggle("running", passing);
      card.classList.toggle("selected", !passing && open);
      let cls = "body-s", text;
      if (!site()) { cls += " muted"; text = t("web_sat_no_position"); }
      else if (p === undefined) { cls += " muted"; text = t("sat_calculating_pass"); }
      else if (p === "always") text = t("web_sat_always");
      else if (p === "none") { cls += " muted"; text = t("web_sat_no_pass"); }
      else if (passing) {
        const left = Math.max(0, Math.floor((p.los - now) / 1000));
        cls += " bold primary";
        text = t("sat_in_pass", Math.floor(left / 60), left % 60);
      } else {
        const mins = Math.max(0, Math.floor((p.aos - now) / 60000));
        text = t("sat_aos_los", timeFmt.format(p.aos), mins >= 60 ? `${Math.floor(mins / 60)}h ${mins % 60}m` : `${mins}m`, timeFmt.format(p.los));
      }
      lineHost.className = cls;
      lineHost.textContent = text;
      maxHost.textContent = typeof p === "object" ? `Max El\n${format("%.1f°", p.maxEl)}` : "";
      maxHost.classList.toggle("tertiary", typeof p === "object" && p.maxEl > 30);
    };
    update();
    tickers.push(update);
    return card;
  }


  /** @param {string} name */
  function frequencies(name) {
    const rec = state.recs.get(name);
    const norad = rec?.norad ?? "";
    const amsat = (state.data?.amsat ?? []).filter((f) => (f.norad && f.norad === norad) || f.name.toUpperCase() === name.toUpperCase());
    const satnogs = /** @type {any[]} */ (state.data?.satnogs?.byNorad?.[Number(norad)] ?? []);
    const host = h("div", { class: "col gap-s" }, h("div", { class: "hr" }));
    const mhz = (/** @type {number | null} */ hz) => (hz != null ? format("%.3f", hz / 1e6) : t("sat_none_short"));
    if (amsat.length) {
      host.append(h("div", { class: "body-m bold" }, t("sat_freq_amsat")),
        ...amsat.map((f) => h("div", { class: "col" },
          h("div", { class: "row between" }, h("span", { class: "body-m bold primary" }, `Mode: ${f.mode ?? "N/A"}`), f.callsign ? h("span", { class: "tag neutral" }, t("sat_callsign", f.callsign)) : null),
          h("div", { class: "row between body-s" }, h("span", null, `Up: ${f.uplink ?? t("sat_none_short")}`), h("span", null, `Dn: ${f.downlink ?? t("sat_none_short")}`),
            f.beacon ? h("span", { class: "muted" }, `Bcn: ${f.beacon}`) : null))));
      return host;
    }
    if (satnogs.length) {
      host.append(h("div", { class: "body-m bold" }, t("sat_freq_satnogs")),
        ...satnogs.map((x) => h("div", { class: "row body-m", style: { gap: "8px" } }, h("span", { class: "semibold", style: { flex: "0.3" } }, `${x.mode ?? "N/A"}:`),
          h("span", { style: { flex: "0.35" } }, `Up: ${mhz(x.up)}`), h("span", { style: { flex: "0.35" } }, `Dn: ${mhz(x.down)}`))));
      return host;
    }
    builtinFreqs ??= fetch("data/sat_freqs.json").then((r) => r.json()).catch(() => ({}));
    builtinFreqs.then((db) => {
      const hit = Object.entries(db).find(([k]) => k.toLowerCase().includes(name.toLowerCase()) || name.toLowerCase().includes(k.toLowerCase()))?.[1];
      host.append(...(hit?.length ? [h("div", { class: "body-m bold" }, t("sat_freq_builtin")),
        ...hit.map(([mode, up, down, tone]) => h("div", { class: "row body-m wrap", style: { gap: "8px" } }, h("span", { class: "semibold" }, `${mode}:`),
          h("span", null, `Up: ${up}`), h("span", null, `Dn: ${down}`), h("span", { class: "body-s muted" }, `Info: ${tone}`)))]
        : [h("div", { class: "body-s muted", style: { fontStyle: "italic" } }, t("sat_no_freqs"))]));
    });
    return host;
  }

  function renderAll() { renderSky(); renderList(); }

  let alive = true;
  let tick = 0;
  const timer = setInterval(() => {
    tick++;
    renderSky();
    if (tick % 15 === 0) refreshEnded();
    tickers.forEach((u) => u());
  }, 1000);
  const unsub = location.subscribe(() => computeAll());
  location.alreadyGranted().then((g) => { if (g) location.start(); });
  load();
  const { el } = layout(ctx, { title: t("sat_title"), actions: [refreshBtn] }, sky, search, h("div", { class: "col gap-s" }, header, statusLine), list);
  return { el, destroy: () => { alive = false; clearInterval(timer); unsub(); } };
}

// ---------------------------------------------------------------- radar nieboskłonu (SVG)
const NS = "http://www.w3.org/2000/svg";
/** @param {string} tag @param {Record<string, any>} attrs */
function svg(tag, attrs, text = "") {
  const e = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, String(v));
  if (text) e.textContent = text;
  return e;
}

/** @param {{az: number, el: number} | null} look @param {string | null} name @param {{az: number, el: number}[]} track */
function skyplot(look, name, track) {
  const size = 240, c = size / 2, R = c * 0.8;
  const pt = (/** @type {number} */ az, /** @type {number} */ el) => {
    const r = ((90 - Math.max(0, el)) / 90) * R, a = ((az - 90) * Math.PI) / 180;
    return [c + r * Math.cos(a), c + r * Math.sin(a)];
  };
  const root = svg("svg", { viewBox: `0 0 ${size} ${size}`, width: size, height: size, class: "skyplot" });
  root.append(svg("circle", { cx: c, cy: c, r: R, class: "sky-bg" }));
  for (const el of [0, 30, 60]) root.append(svg("circle", { cx: c, cy: c, r: ((90 - el) / 90) * R, class: el ? "sky-ring dashed" : "sky-ring" }));
  root.append(svg("line", { x1: c, y1: c - R, x2: c, y2: c + R, class: "sky-ring" }), svg("line", { x1: c - R, y1: c, x2: c + R, y2: c, class: "sky-ring" }));
  const lo = R + 14;
  for (const [txt, x, y, cls] of [["N", c, c - lo, "sky-n"], ["S", c, c + lo, "sky-l"], ["E", c + lo, c, "sky-l"], ["W", c - lo, c, "sky-l"]]) {
    root.append(svg("text", { x, y, class: /** @type {string} */ (cls), "text-anchor": "middle", "dominant-baseline": "central" }, /** @type {string} */ (txt)));
  }
  if (track.length > 1) root.append(svg("polyline", { points: track.map((p) => pt(p.az, p.el).join(",")).join(" "), class: "sky-track" }));
  if (look && look.el >= -5) {
    const [x, y] = pt(look.az, look.el);
    const cls = look.el > 0 ? "sky-sat up" : "sky-sat";
    root.append(svg("circle", { cx: x, cy: y, r: 12, class: `${cls} halo` }), svg("circle", { cx: x, cy: y, r: 6, class: cls }));
    if (name) root.append(svg("text", { x: x + (x > c + 40 ? -10 : 10), y, class: `sky-name ${look.el > 0 ? "up" : ""}`, "text-anchor": x > c + 40 ? "end" : "start", "dominant-baseline": "central" }, name));
  }
  return root;
}
