// @ts-check
// DX Cluster (DxClusterScreen.kt), aktywacje na żywo (LiveSpotsScreen.kt) i miejsca do aktywacji (ActivationsScreen.kt)
import { h, replace, iconButton, icon, chip, textField, button, dialog, toast, switchEl } from "../ui.js";
import { t, fmt, format } from "../i18n.js";
import { settings, stationPosition } from "../store.js";
import { logbook } from "../db.js";
import { cty, proxied, apiUrl } from "../services.js";
import { followedAwardStations, awardTextFor, refreshBandmap, BANDMAP_POLL_MS } from "./awards.js";
import { GreatCircle } from "../core/geo.js";
import { bandForKhz, Callsigns, parseNum } from "../core/radio.js";
import { DX_BANDS, DX_MODES, filterDxSpots, spotKhz, guessModeFromSpot, qsoFrequency, parsePota, parseWwff } from "../core/spots.js";
import { layout } from "./common.js";
import { qsoDialog } from "./logger.js";

/** @typedef {import("../core/spots.js").DxSpot} DxSpot */
/** @typedef {import("../core/spots.js").LiveSpot} LiveSpot */

/** Linijka "akcja dyplomowa" pod spotem. @param {Map<string, string[]>} tags @param {string} call */
function award(tags, call) {
  const text = tags.size ? awardTextFor(tags, call) : null;
  return text ? h("div", { class: "body-s bold tertiary ellipsis" }, t("spot_award", text)) : null;
}

const allLabel = (x) => (x === "ALL" ? t("common_all") : x);
const chipRow = (options, selected, onselect) => h("div", { class: "chip-scroll" }, options.map((o) => chip(allLabel(o), selected === o, () => onselect(o))));

/** Okno QSO ze spotu – emisja i raport jak w aplikacji. */
function logFromSpot(callsign, khz, mode, huntedRef, toastText) {
  const rst = mode === "CW" ? "599" : "59";
  qsoDialog({
    prefillCallsign: callsign, prefillHuntedRef: huntedRef,
    defaults: { frequency: qsoFrequency(khz), mode: mode || "SSB", rstSent: rst, rstRcvd: rst },
    onsave: async (q) => { await logbook.put(q); toast(toastText(q)); },
  });
}

// ---------------------------------------------------------------- DX Cluster
const dx = { band: "ALL", mode: "ALL", query: "", countries: /** @type {Set<string>} */ (new Set()), showFilters: false };
/** @type {DxSpot[]} */
let dxSpots = [];
/** @type {string | null} */
let dxUpdated = null;

/** @param {import("../app.js").ScreenContext} ctx */
export function dxClusterScreen(ctx) {
  const filterHost = h("div");
  const summary = h("div", { class: "row between", style: { padding: "2px 16px" } });
  const list = h("div", { class: "scroll tight", style: { paddingBottom: "96px" } });
  let loading = false;
  /** @type {string | null} */
  let error = null;
  /** @type {import("../core/logbook.js").CtyDatabase | null} */
  let db = null;
  let countryQuery = "";
  cty().then((d) => { db = d; render(); }).catch(() => {});

  const countryOf = (s) => db?.resolve(s.dx_call)?.name ?? (s.dx_country?.trim() || null);
  const filterActive = () => dx.band !== "ALL" || dx.mode !== "ALL" || !!dx.query.trim() || dx.countries.size > 0;

  async function fetchSpots() {
    loading = true;
    error = null;
    render();
    try {
      dxSpots = await (await proxied("dxsummit", "spots?limit=100", { cache: "no-store" })).json();
      dxUpdated = new Date().toLocaleTimeString();
    } catch (e) {
      error = t("dx_error_download", /** @type {Error} */ (e).message);
    } finally {
      loading = false;
      render();
    }
  }

  function renderFilters() {
    if (!dx.showFilters) return replace(filterHost);
    const counts = new Map();
    for (const s of filterDxSpots(dxSpots, dx)) { const c = countryOf(s); if (c) counts.set(c, (counts.get(c) ?? 0) + 1); }
    const all = [...new Set([...counts.keys(), ...dx.countries])].sort((a, b) =>
      Number(dx.countries.has(b)) - Number(dx.countries.has(a)) || (counts.get(b) ?? 0) - (counts.get(a) ?? 0) || (a < b ? -1 : 1));
    const shown = countryQuery.trim() ? all.filter((c) => dx.countries.has(c) || c.toLowerCase().includes(countryQuery.trim().toLowerCase())) : all;
    const search = textField({ label: t("dx_search_hint"), value: dx.query, oninput: (v) => { dx.query = v; renderList(); } });
    const countrySearch = textField({ label: t("dx_country_hint"), value: countryQuery, oninput: (v) => { countryQuery = v; renderCountryChips(); } });
    const countryChips = h("div", { class: "chip-scroll" });
    const renderCountryChips = () => {
      const q = countryQuery.trim().toLowerCase();
      const items = q ? all.filter((c) => dx.countries.has(c) || c.toLowerCase().includes(q)) : shown;
      replace(countryChips, chip(t("common_all"), dx.countries.size === 0, () => { dx.countries.clear(); render(); }),
        items.map((c) => chip(`${c} (${counts.get(c) ?? 0})`, dx.countries.has(c), () => { if (dx.countries.has(c)) dx.countries.delete(c); else dx.countries.add(c); render(); })));
    };
    renderCountryChips();
    replace(filterHost, h("section", { class: "card pad-s col gap-m", style: { margin: "4px 16px", background: "var(--surface-variant)", width: "auto" } },
      h("div", { class: "row between" }, h("span", { class: "body-m bold" }, t("dx_filtering")),
        filterActive() ? button(t("dx_reset_filters"), () => { dx.band = "ALL"; dx.mode = "ALL"; dx.query = ""; dx.countries.clear(); render(); }, "text") : null),
      search,
      h("span", { class: "body-s semibold primary" }, t("dx_band")), chipRow(DX_BANDS, dx.band, (b) => { dx.band = b; render(); }),
      h("span", { class: "body-s semibold primary" }, t("dx_mode")), chipRow(DX_MODES, dx.mode, (m) => { dx.mode = m; render(); }),
      h("div", { class: "row" }, h("span", { class: "body-s semibold primary" }, dx.countries.size ? t("dx_country_count", dx.countries.size) : t("dx_country")), countrySearch),
      countryChips));
  }

  function renderList() {
    const filtered = filterDxSpots(dxSpots, dx);
    const shown = dx.countries.size ? filtered.filter((s) => dx.countries.has(countryOf(s) ?? "")) : filtered;
    replace(summary, dxUpdated ? h("span", { class: "label-s muted" }, t("dx_updated", dxUpdated)) : h("span"),
      h("span", { class: "label-s bold primary" }, t("dx_spots_count", shown.length)));
    filterBtn.classList.toggle("active", filterActive());
    if (loading && !shown.length) return replace(list, h("div", { class: "centered" }, h("div", { class: "spinner l" })));
    if (error && !shown.length) return replace(list, h("div", { class: "centered error-text" }, error));
    if (!shown.length) return replace(list, h("div", { class: "centered muted" }, t("dx_no_spots")));
    const logs = logbook.all();
    const worked = logbook.workedDxcc();
    const awardTags = followedAwardStations();
    replace(list, error ? h("div", { class: "body-s error-text" }, error) : null, shown.map((s) => {
      const entity = db?.resolve(s.dx_call) ?? null;
      const isNew = entity != null && logs.length > 0 && !worked.has(entity.dxcc);
      const khz = spotKhz(s.frequency);
      return h("button", { type: "button", class: `card clickable pad-s col gap-s ${isNew ? "new-country" : ""}`,
        onclick: () => logFromSpot(s.dx_call, khz, guessModeFromSpot(khz, s.info ?? ""), "", (q) => t("dx_qso_logged", q.callsign)) },
        h("div", { class: "row between" }, h("span", { class: "title-l bold primary" }, s.dx_call), h("span", { class: "title-m secondary" }, format("%.1f", s.frequency))),
        h("div", { class: "row between body-s muted" }, h("span", null, `de ${s.de_call} @ ${String(s.time ?? "").slice(-8)}`), h("span", null, entity?.name ?? s.dx_country ?? "")),
        isNew ? h("div", { class: "body-s bold tertiary", style: { textAlign: "right" } }, t("spot_new_country")) : null,
        award(awardTags, s.dx_call),
        s.info ? h("div", { class: "body-m italic clamp2" }, s.info) : null);
    }));
  }

  function render() { renderFilters(); renderList(); }

  function addSpot() {
    const v = { call: "", freq: "", info: "" };
    /** @type {HTMLButtonElement} */
    let send;
    const upd = () => { send.disabled = !v.call.trim() || !v.freq.trim(); };
    const spotter = settings.get().callsign;
    dialog({
      title: t("addspot_title"),
      body: h("div", { class: "col gap-l" },
        textField({ label: t("addspot_dx_call"), value: "", uppercase: true, oninput: (x) => { v.call = x; upd(); } }),
        textField({ label: t("addspot_freq"), value: "", inputmode: "decimal", oninput: (x) => { v.freq = x; upd(); } }),
        textField({ label: t("addspot_comment"), value: "", oninput: (x) => { v.info = x; } }),
        !Callsigns.isValidAmateur(spotter) ? h("div", { class: "body-s error-text" }, t("settings_callsign_empty")) : null),
      actions: (close) => [button(t("common_cancel"), close, "text"),
        (send = /** @type {HTMLButtonElement} */ (button(t("dx_spot_it"), async () => {
          const f = parseNum(v.freq);
          if (f == null) return toast(t("dx_invalid_freq"));
          close();
          toast(t("dx_sending_spot"));
          try {
            const res = await proxied("dxsummit", "spots", { method: "POST", headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ dx_call: v.call.trim().toUpperCase(), de_call: spotter, frequency: f, info: v.info.trim() }) });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            fetchSpots();
          } catch (e) {
            toast(t("dx_error_send", /** @type {Error} */ (e).message));
          }
        }, "filled")))],
    });
    upd();
  }

  const filterBtn = iconButton("filterList", t("dx_filters"), () => { dx.showFilters = !dx.showFilters; renderFilters(); });
  const body = h("div", { class: "screen", style: { position: "relative" } }, filterHost, summary, list,
    h("button", { type: "button", class: "fab", "aria-label": t("dx_spot_it"), onclick: addSpot }, icon("add")));
  const { el } = layout(ctx, { title: "DX Cluster", body, actions: [filterBtn, iconButton("refresh", t("common_refresh"), fetchSpots)] });

  render();
  fetchSpots();
  // Odświeżanie wg ustawień (ręcznie / co 1 / co 5 minut)
  const minutes = settings.get().refresh_interval;
  const timer = minutes > 0 ? setInterval(() => { if (!document.hidden) fetchSpots(); }, minutes * 60000) : null;
  const unsubscribe = logbook.subscribe(renderList);
  // Stacje obserwowanych akcji dyplomowych z mapy pasm radiodyplom.pl (tylko gdy coś obserwujemy)
  const pollAwards = () => { if (settings.get().followed_awards.length && !document.hidden) refreshBandmap().then(renderList); };
  pollAwards();
  const awardsTimer = setInterval(pollAwards, BANDMAP_POLL_MS);
  return { el, destroy: () => { if (timer) clearInterval(timer); clearInterval(awardsTimer); unsubscribe(); } };
}

// ---------------------------------------------------------------- aktywacje na żywo
const live = { program: "ALL", band: "ALL", mode: "ALL", nearest: false };
/** @type {LiveSpot[]} */
let liveSpots = [];

/** @param {import("../app.js").ScreenContext} ctx */
export function liveSpotsScreen(ctx) {
  const station = stationPosition();
  const host = h("div", { class: "screen" });
  const list = h("div", { class: "scroll tight" });
  let loading = false;
  /** @type {string[]} */
  let errors = [];

  async function fetchAll() {
    loading = true;
    render();
    const results = await Promise.allSettled([
      proxied("pota", "spot/activator").then((r) => r.text()).then(parsePota),
      proxied("wwff", "static/spots.json").then((r) => r.text()).then(parseWwff),
    ]);
    const spots = results.flatMap((r) => (r.status === "fulfilled" ? r.value : []));
    errors = results.map((r, i) => (r.status === "rejected" ? `${["POTA", "WWFF"][i]}: ${r.reason?.message ?? r.reason}` : null)).filter(Boolean);
    if (spots.length || !errors.length) liveSpots = spots.sort((a, b) => b.timeEpochSec - a.timeEpochSec);
    loading = false;
    render();
  }

  const dist = (s) => (station && s.latitude != null && s.longitude != null ? GreatCircle.distanceKm(station, { lat: s.latitude, lon: s.longitude }) : null);

  function render() {
    const today = new Date().toISOString().slice(0, 10);
    const workedToday = new Set(logbook.all().filter((q) => q.datetimeUtc.startsWith(today) && q.huntedRef.trim())
      .map((q) => `${Callsigns.baseCall(q.callsign)}|${q.huntedRef.toUpperCase()}`));
    let visible = liveSpots.filter((s) => (live.program === "ALL" || s.program === live.program) &&
      (live.band === "ALL" || bandForKhz(s.frequencyKhz) === live.band) && (live.mode === "ALL" || s.mode === live.mode));
    if (live.nearest && station) visible = [...visible].sort((a, b) => (dist(a) ?? Infinity) - (dist(b) ?? Infinity));
    replace(host,
      loading ? h("div", { class: "progress" }) : null,
      h("div", { class: "col gap-s", style: { padding: "4px 0" } },
        chipRow(["ALL", "POTA", "WWFF"], live.program, (x) => { live.program = x; render(); }),
        chipRow(["ALL", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "2m"], live.band, (x) => { live.band = x; render(); }),
        chipRow(["ALL", "CW", "SSB", "FT8", "FT4", "FM"], live.mode, (x) => { live.mode = x; render(); })),
      h("div", { class: "row between", style: { padding: "0 16px" } },
        h("span", { class: "body-s muted" }, t("live_count", visible.length)),
        h("label", { class: "row" }, h("span", { class: "body-m" }, t("live_nearest")),
          switchEl(live.nearest && !!station, (x) => { live.nearest = x; render(); }, t("live_nearest"), !station))),
      errors.map((e) => h("div", { class: "body-s error-text", style: { padding: "0 16px" } }, t("live_error", e))),
      list);
    const now = Date.now() / 1000;
    replace(list, visible.map((s) => {
      const worked = workedToday.has(`${Callsigns.baseCall(s.activator)}|${s.reference}`);
      const d = dist(s);
      const bearing = station && s.latitude != null && s.longitude != null ? GreatCircle.bearingDeg(station, { lat: s.latitude, lon: s.longitude }) : null;
      const meta = [t("aprs_min_ago", Math.max(0, Math.floor((now - s.timeEpochSec) / 60))), d != null ? fmt("%,.0f km", d) : null,
        bearing != null ? format("%.0f° ", bearing) + GreatCircle.compassPoint(bearing) : null].filter(Boolean).join(" • ");
      return h("button", { type: "button", class: `card clickable pad-s col gap-s ${worked ? "worked" : ""}`,
        onclick: () => logFromSpot(s.activator, s.frequencyKhz, s.mode, s.reference, (q) => t("live_logged", q.callsign, q.huntedRef)) },
        h("div", { class: "row between" },
          h("div", { class: "row" }, h("span", { class: "title-m bold primary" }, s.activator), h("span", { class: "qso-badge" }, s.program),
            s.mode ? h("span", { class: "qso-badge" }, s.mode) : null),
          h("span", { class: "body-l" }, format("%.1f", s.frequencyKhz))),
        h("div", { class: "body-m ellipsis" }, `${s.reference} – ${s.referenceName}`),
        h("div", { class: "row between" }, h("span", { class: "body-s muted" }, meta), worked ? h("span", { class: "label-s bold tertiary" }, t("live_logged_badge")) : null),
        s.comment.trim() ? h("div", { class: "body-s italic ellipsis" }, s.comment) : null);
    }), h("div", { class: "label-s muted", style: { padding: "8px" } }, t("live_sota_note")));
  }

  render();
  fetchAll();
  // Odświeżanie co minutę, dopóki ekran jest otwarty
  const timer = setInterval(() => { if (!document.hidden) fetchAll(); }, 60000);
  const { el } = layout(ctx, { title: t("live_title"), body: host, actions: [iconButton("refresh", t("common_refresh"), fetchAll)] });
  return { el, destroy: () => clearInterval(timer) };
}

// ---------------------------------------------------------------- miejsca do aktywacji
const ACT_KEY = "hrt.activations.v1";
const SYNC_RADIUS_KM = 500, AUTO_SYNC_DISTANCE_KM = 250, LIST_RADIUS_KM = 100;
const PROGRAM_COLORS = { SOTA: "#E91E63", POTA: "#FF9800", WWFF: "#4CAF50", SPFF: "#4CAF50", PGA: "#2196F3", ZAMEK: "#9C27B0" };

/** @param {import("../app.js").ScreenContext} ctx */
export function activationsScreen(ctx) {
  const host = h("div", { class: "scroll tight" });
  let syncing = false;
  /** @type {string | null} */
  let status = null;
  /** @type {{centerLat: number, centerLon: number, spots: any[]} | null} */
  let stored = null;
  try { stored = JSON.parse(localStorage.getItem(ACT_KEY) ?? "null"); } catch { /* brak */ }
  /** @type {any[]} */
  let database = stored?.spots ?? [];
  // Przykładowe obiekty z aplikacji – do pierwszej synchronizacji
  if (!stored) fetch("data/activations.json").then((r) => r.json()).then((l) => {
    // Tylko gdy synchronizacja jeszcze niczego nie przyniosła (inaczej przykład nadpisałby pobrane obiekty)
    if (stored) return;
    database = l.map((s) => ({ ...s, program: s.program === "SPFF" ? "WWFF" : s.program }));
    render();
  }).catch(() => {});

  async function sync() {
    const pos = stationPosition();
    if (!pos) { status = t("act_no_position"); return render(); }
    if (syncing) return;
    syncing = true; status = null; render();
    try {
      const res = await fetch(apiUrl(`/activations?lat=${pos.lat.toFixed(4)}&lon=${pos.lon.toFixed(4)}&radius=${SYNC_RADIUS_KM}`));
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      if (data.spots?.length) {
        database = data.spots;
        stored = { centerLat: pos.lat, centerLon: pos.lon, spots: database };
        try { localStorage.setItem(ACT_KEY, JSON.stringify(stored)); } catch { /* pełna pamięć */ }
      } else status = t("act_download_failed");
    } catch (e) {
      status = t("act_sync_error", /** @type {Error} */ (e).message);
    } finally {
      syncing = false;
      render();
    }
  }

  function render() {
    syncBtn.replaceChildren(syncing ? h("span", { class: "spinner" }) : icon("sync"));
    const programs = settings.get().active_programs;
    const pos = stationPosition();
    const list = database.filter((s) => programs.includes(s.program) || (s.program === "WWFF" && programs.includes("SPFF")))
      .map((s) => ({ ...s, distanceKm: pos ? Math.round(GreatCircle.distanceKm(pos, { lat: s.latitude, lon: s.longitude }) * 10) / 10 : 0 }))
      .filter((s) => !pos || s.distanceKm <= LIST_RADIUS_KM).sort((a, b) => a.distanceKm - b.distanceKm);
    if (!list.length) return replace(host, h("div", { class: "centered muted pre" }, status ?? (syncing ? t("act_syncing") : t("act_none", LIST_RADIUS_KM))));
    replace(host, status ? h("div", { class: "body-s error-text" }, status) : null, list.map((s) =>
      h("a", { class: "card clickable pad row between", href: `https://maps.apple.com/?ll=${s.latitude},${s.longitude}&q=${encodeURIComponent(s.name || s.reference)}`, target: "_blank", rel: "noopener" },
        h("div", { class: "col gap-s grow" },
          h("div", { class: "row" }, h("span", { class: "badge s", style: { background: PROGRAM_COLORS[s.program] ?? "#888", borderRadius: "4px" } }, s.program),
            h("span", { class: "title-m bold primary" }, s.reference)),
          h("span", { class: "body-m muted" }, s.name)),
        h("span", { class: "body-l semibold secondary" }, `${s.distanceKm} km`))));
  }

  const syncBtn = iconButton("sync", t("act_sync"), sync);
  const { el } = layout(ctx, { title: t("act_title"), body: host, actions: [syncBtn] });
  render();
  // Pierwsze uruchomienie albo wyjazd daleko od miejsca ostatniej synchronizacji – obiekty dla nowej okolicy
  const pos = stationPosition();
  if (pos && (!stored || GreatCircle.distanceKm({ lat: stored.centerLat, lon: stored.centerLon }, pos) > AUTO_SYNC_DISTANCE_KM)) sync();
  return { el };
}
