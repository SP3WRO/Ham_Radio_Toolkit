// @ts-check
// Przemienniki (RepeatersScreen.kt, RepeatersViewModel.kt) – jedna baza dla ekranu i karty na stronie głównej
import { h, replace, iconButton, chip, icon } from "../ui.js";
import { t, format } from "../i18n.js";
import { settings, stationPosition } from "../store.js";
import { apiUrl, proxied } from "../services.js";
import { GreatCircle } from "../core/geo.js";
import { RepeaterSources, buildRepeaterList, parseRadioId, pickRadioId } from "../core/repeaters.js";
import { layout } from "./common.js";

/** @typedef {import("../core/repeaters.js").Repeater} Repeater */
/** @typedef {import("../core/repeaters.js").RepeaterGroup} RepeaterGroup */

const CACHE_KEY = "hrt.repeaters.v1";

// ---------------------------------------------------------------- wspólna baza
export const repeaterStore = {
  /** @type {Repeater[]} */
  database: [],
  source: "",
  /** @type {{lat: number, lon: number} | null} */
  fetchedAround: null,
  loading: false,
  /** @type {string | null} */
  error: null,
  /** @type {Set<() => void>} */
  listeners: new Set(),
  emit() { this.listeners.forEach((l) => l()); },

  /** Pobranie wg zasad aplikacji: zmiana źródła, pusta baza albo (źródła zagraniczne) wyjazd > 100 km. */
  ensure(force = false) {
    const source = settings.get().repeater_data_source;
    const pos = stationPosition();
    if (this.source !== source) {
      this.source = source;
      this.database = [];
      this.fetchedAround = null;
      this.restore(source);
    }
    const movedFar = !RepeaterSources.isPolish(source) && pos && (!this.fetchedAround || GreatCircle.distanceKm(this.fetchedAround, pos) > 100);
    if ((force || !this.database.length || movedFar) && !this.loading) this.fetch(source, pos);
  },

  restore(source) {
    try {
      const c = JSON.parse(localStorage.getItem(CACHE_KEY) ?? "null");
      if (c?.source === source) { this.database = c.repeaters; this.fetchedAround = c.around; }
    } catch { /* brak kopii */ }
  },

  async fetch(source, pos) {
    // Bez pozycji zagranicznych źródeł nie da się zapytać – poczekamy na GPS
    if (!RepeaterSources.isPolish(source) && !pos) return;
    this.loading = true;
    this.error = null;
    this.emit();
    try {
      const q = new URLSearchParams({ source });
      if (pos) { q.set("lat", pos.lat.toFixed(4)); q.set("lon", pos.lon.toFixed(4)); }
      const res = await fetch(apiUrl(`/repeaters?${q}`));
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error ?? `HTTP ${res.status}`);
      this.database = data.repeaters ?? [];
      this.fetchedAround = pos;
      try { localStorage.setItem(CACHE_KEY, JSON.stringify({ source, around: pos, repeaters: this.database })); } catch { /* pełna pamięć */ }
    } catch (e) {
      this.error = t("rep_download_error", RepeaterSources.label(source), /** @type {Error} */ (e).message);
    } finally {
      this.loading = false;
      this.emit();
    }
  },
};

/** Najbliższy przemiennik (dla strony głównej), niezależnie od filtrów ekranu. */
export function nearestRepeater() {
  return buildRepeaterList(repeaterStore.database, stationPosition(), { radiusKm: Infinity, band: "ALL", mode: "ALL", onlyWorking: false }).nearest;
}

// ---------------------------------------------------------------- ekran
const filters = { band: "ALL", mode: "ALL", onlyWorking: false, show: true };
/** @type {Map<string, any>} */
const dmrCache = new Map();

/** @param {import("../app.js").ScreenContext} ctx */
export function repeatersScreen(ctx) {
  const panel = h("div");
  const list = h("div", { class: "scroll tight" });
  /** @type {Set<string>} */
  const expanded = new Set();
  const radius = settings.get().repeater_radius_km;
  const source = settings.get().repeater_data_source;

  const filterBtn = iconButton("filterList", t("dx_filters"), () => { filters.show = !filters.show; render(); });
  const syncBtn = iconButton("sync", t("common_refresh"), () => repeaterStore.ensure(true));

  function render() {
    const { groups } = buildRepeaterList(repeaterStore.database, stationPosition(), { radiusKm: radius, ...filters });
    filterBtn.classList.toggle("active", filters.band !== "ALL" || filters.mode !== "ALL" || filters.onlyWorking);
    syncBtn.replaceChildren(repeaterStore.loading ? h("span", { class: "spinner" }) : icon("sync"));
    const row = (label, options, key) => h("div", { class: "row wrap", style: { gap: "6px" } },
      h("span", { class: "body-s bold primary" }, label),
      options.map(([code, l]) => chip(l, filters[key] === code, () => { filters[key] = code; render(); })));
    replace(panel, filters.show ? h("div", { class: "col gap-m", style: { padding: "8px 12px", background: "var(--surface-container)" } },
      row(t("dx_band"), [["ALL", t("common_all")], ["2M", "2m"], ["70CM", "70cm"], ["10M", "10m"], ["6M", "6m"], ["23CM", "23cm"]], "band"),
      row(t("dx_mode"), [["ALL", t("common_all")], ["FM", "FM"], ["DMR", "DMR"], ["C4FM", "C4FM"], ["DSTAR", "D-STAR"]], "mode"),
      h("div", { class: "row between" }, chip(t("rep_only_working"), filters.onlyWorking, () => { filters.onlyWorking = !filters.onlyWorking; render(); }),
        h("span", { class: "body-s semibold secondary" }, t("rep_found", groups.length)))) : null);
    if (!groups.length && !repeaterStore.loading) return replace(list, h("div", { class: "centered muted" }, repeaterStore.error ?? t("rep_none")));
    if (!groups.length) return replace(list, h("div", { class: "centered" }, h("div", { class: "spinner l" })));
    replace(list, repeaterStore.error ? h("div", { class: "body-s error-text" }, repeaterStore.error) : null, groups.map(card));
  }

  /** @param {RepeaterGroup} g */
  function card(g) {
    const open = expanded.has(g.listKey);
    const modes = [...new Set(g.channels.flatMap((c) => c.modes).map((m) => m.toUpperCase()))];
    const notes = [...new Set(g.channels.map((c) => c.note).filter((n) => n.trim()))];
    const sources = [...new Set(g.channels.flatMap((c) => c.source.split(" + ")).filter((s) => s.trim()))];
    const dmrChannel = g.channels.find((c) => c.modes.some((m) => m.toUpperCase().includes("DMR")));
    const dmrKey = dmrChannel ? `${dmrChannel.callsign}@${Math.round(dmrChannel.userRx * 1000)}` : "";
    const dmr = dmrCache.get(dmrKey);
    if (open && dmrChannel && !dmrCache.has(dmrKey)) {
      dmrCache.set(dmrKey, undefined);
      proxied("radioid", `repeater/?callsign=${encodeURIComponent(dmrChannel.callsign.toUpperCase())}`)
        .then((r) => r.text()).then((txt) => pickRadioId(parseRadioId(txt), dmrChannel.userRx)).catch(() => null)
        .then((d) => { dmrCache.set(dmrKey, d); render(); });
    }
    const loadingDmr = dmrChannel && dmrCache.has(dmrKey) && dmr === undefined;
    const cc = dmr?.colorCode?.trim() || dmrChannel?.dmrColorCode || "";
    const freqCol = (label, value, cls) => h("div", { class: "col", style: { alignItems: "center" } },
      h("span", { class: "body-s muted" }, label), h("span", { class: `headline-s bold ${cls}` }, value));
    return h("section", { class: `card pad clickable ${g.isWorking ? "" : "worked"}`, role: "button", tabindex: "0",
      onclick: () => { if (open) expanded.delete(g.listKey); else expanded.add(g.listKey); render(); } },
      h("div", { class: "row between top" },
        h("div", { class: "col gap-s grow" }, h("span", { class: `title-l bold ${g.isWorking ? "primary" : "error-text"}` }, g.callsign),
          h("span", { class: "body-m muted" }, g.qth)),
        h("div", { class: "col", style: { alignItems: "flex-end", gap: "4px" } }, h("span", { class: "body-l semibold secondary" }, `${g.distanceKm} km`),
          modes.map((m) => h("span", { class: "qso-badge" }, m)))),
      open ? h("div", { class: "col gap-m", style: { paddingTop: "16px" } }, h("div", { class: "hr" }),
        g.channels.map((c) => h("div", { class: "col gap-m" },
          h("div", { class: "row", style: { justifyContent: "space-evenly" } },
            freqCol(t("rep_tx"), format("%.4f", c.userRx), "primary"), freqCol(t("rep_rx"), format("%.4f", c.userTx), "error-text")),
          h("div", { class: "col", style: { alignItems: "center" } }, h("span", { class: "body-s muted" }, t("rep_ctcss")),
            h("span", { class: "title-m bold" }, c.requiredCtcss !== "-" ? `${c.requiredCtcss} Hz` : t("rep_none_short"))))),
        dmrChannel ? h("div", { class: "col gap-s" },
          h("span", { class: "body-m semibold" }, ["DMR", cc ? `CC ${cc}` : null, dmr?.network?.trim() || null].filter(Boolean).join(" • ")),
          loadingDmr ? h("span", { class: "body-s muted" }, t("rep_checking_radioid")) : null,
          dmr ? [dmr.talkgroups.slice(0, 12).map((tg) => h("span", { class: "body-s muted" }, tg)),
            dmr.trustee.trim() ? h("span", { class: "body-s muted" }, t("rep_trustee", dmr.trustee)) : null] : null) : null,
        notes.map((n) => h("div", { class: "body-s muted" }, n)),
        sources.length ? h("div", { class: "label-s muted" }, t("common_source_value", sources.join(" + ")) + (dmr ? " • DMR: RadioID.net" : "")) : null) : null);
  }

  const unsubscribe = (() => { const l = () => render(); repeaterStore.listeners.add(l); return () => repeaterStore.listeners.delete(l); })();
  const titleEl = h("div", { class: "col" }, h("span", { class: "title-m" }, t("rep_title", radius)),
    h("span", { class: "body-s muted" }, t("common_source_value", RepeaterSources.label(source))));
  const { el } = layout(ctx, { title: titleEl, body: h("div", { class: "screen" }, panel, list), actions: [filterBtn, syncBtn] });
  repeaterStore.ensure();
  render();
  return { el, destroy: unsubscribe };
}
