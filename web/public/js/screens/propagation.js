// @ts-check
// Propagacja – parametry słoneczne i warunki KF z hamqsl.com, mapy troposferyczne Tropocast (PropagationScreen.kt)
import { h, replace, iconButton } from "../ui.js";
import { t } from "../i18n.js";
import { settings } from "../store.js";
import { proxied } from "../services.js";
import { parseSolarXml } from "../core/world.js";
import { layout } from "./common.js";

const SOLAR_CACHE = "hrt.solar.v1";
let selectedTab = 0;
let tropoOffset = 0;

/** @param {import("../app.js").ScreenContext} ctx */
export function propagationScreen(ctx) {
  const content = h("div", { class: "screen" });
  /** @type {import("../core/world.js").SolarData | null} */
  let solar = null;
  /** @type {string | null} */
  let error = null;
  let loading = false;
  try {
    const cached = localStorage.getItem(SOLAR_CACHE);
    if (cached) solar = parseSolarXml(cached);
  } catch { /* brak kopii */ }

  /** @type {{run: string, frames: {hour: number, valid_at: string, run?: string, forecast_hour?: number}[]} | null} */
  let tropo = null;
  /** @type {string | null} */
  let tropoError = null;
  const area = settings.get().tropocast_area;

  async function fetchSolar() {
    loading = true;
    error = null;
    render();
    try {
      const xml = await (await proxied("hamqsl", "solarxml.php")).text();
      solar = parseSolarXml(xml);
      try { localStorage.setItem(SOLAR_CACHE, xml); } catch { /* pełna pamięć */ }
    } catch (e) {
      error = solar ? t("prop_offline_cached") : t("prop_download_error", /** @type {Error} */ (e).message);
    } finally {
      loading = false;
      render();
    }
  }

  async function fetchTropo() {
    tropoError = null;
    try {
      const res = await proxied("tropocast", `metadata?model=full&area=${encodeURIComponent(area)}&source=icon-eu`);
      tropo = await res.json();
    } catch (e) {
      tropoError = t("prop_tropocast_error", /** @type {Error} */ (e).message);
    }
    render();
  }

  function tropoUrl() {
    if (!tropo?.frames?.length) return null;
    const target = Date.now() + tropoOffset * 3600 * 1000;
    let best = tropo.frames[0];
    let min = Infinity;
    for (const f of tropo.frames) {
      const d = Math.abs(Date.parse(f.valid_at) - target);
      if (Number.isFinite(d) && d < min) { min = d; best = f; }
    }
    const base = "https://tropocast.eu/api/v1/tropo";
    const a = encodeURIComponent(area);
    if (best.run != null && best.forecast_hour != null) return `${base}/archive/frames/${best.run}/${best.forecast_hour}.png?model=full&area=${a}&source=icon-eu`;
    return `${base}/frames/${best.hour}.png?run=${tropo.run || "current"}&model=full&area=${a}&source=icon-eu`;
  }

  function render() {
    if (loading && !solar) return replace(content, h("div", { class: "centered" }, h("div", { class: "spinner l" })));
    if (error && !solar) return replace(content, h("div", { class: "centered error-text" }, error));
    if (!solar) return replace(content);
    const tabs = h("div", { class: "tabs fixed", role: "tablist" }, [t("prop_tab_hf"), t("prop_tab_vhf")].map((label, i) =>
      h("button", { type: "button", role: "tab", class: `tab ${selectedTab === i ? "active" : ""}`, "aria-selected": String(selectedTab === i),
        onclick: () => { selectedTab = i; render(); if (i === 1 && !tropo && !tropoError) fetchTropo(); } }, label)));
    replace(content,
      error ? h("div", { class: "body-s error-text", style: { padding: "4px 16px" } }, error) : null,
      selectedTab === 0 ? h("div", { style: { padding: "8px 16px" } }, solarCard(solar)) : null,
      tabs,
      selectedTab === 0 ? hfList(solar) : tropoTab());
  }

  function tropoTab() {
    const url = tropoUrl();
    const options = [[-6, "-6h"], [-3, "-3h"], [0, t("prop_now")], [3, "+3h"], [6, "+6h"]];
    return h("div", { class: "scroll", style: { alignItems: "center" } },
      h("div", { class: "body-l bold center" }, t("prop_tropocast_title")),
      h("div", { class: "seg", style: { width: "100%" } }, options.map(([o, label]) =>
        h("button", { type: "button", class: `btn ${tropoOffset === o ? "btn-filled" : "btn-tonal"}`, onclick: () => { tropoOffset = Number(o); render(); } }, label))),
      url ? h("div", { class: "card", style: { overflow: "hidden" } },
        h("img", { class: "map-img", src: url, alt: t("prop_vhf_map_desc"), loading: "lazy" }), legend())
        : tropoError ? h("div", { class: "body-m error-text", style: { padding: "32px" } }, tropoError)
          : h("div", { class: "spinner l", style: { margin: "32px" } }));
  }

  if (selectedTab === 1) fetchTropo();
  fetchSolar();

  const { el } = layout(ctx, {
    title: t("prop_title"),
    actions: [iconButton("refresh", t("common_refresh"), () => fetchSolar())],
    body: content,
  });
  return { el };
}

/** @param {import("../core/world.js").SolarData} d */
function solarCard(d) {
  const item = (label, value) => h("div", { class: "col", style: { alignItems: "center" } },
    h("span", { class: "body-m secondary" }, label), h("span", { class: "title-l bold" }, value));
  return h("section", { class: "card pad" },
    h("div", { class: "label-l primary" }, t("prop_solar_params")),
    h("div", { class: "body-s muted" }, t("prop_updated", d.updated)),
    h("div", { class: "row between", style: { marginTop: "12px" } },
      item("SFI", d.solarFlux), item(t("prop_sunspots"), d.sunspots), item(t("prop_a_index"), d.aIndex), item(t("prop_k_index"), d.kIndex)));
}

/** @param {import("../core/world.js").SolarData} d */
function hfList(d) {
  /** @type {Map<string, {day?: string, night?: string}>} */
  const bands = new Map();
  for (const c of d.hfConditions) {
    const e = bands.get(c.name) ?? {};
    if (c.time.toLowerCase() === "day") e.day = c.condition;
    if (c.time.toLowerCase() === "night") e.night = c.condition;
    bands.set(c.name, e);
  }
  const cell = (c) => h("div", null, c ? conditionBadge(c) : h("span", { class: "muted" }, "-"));
  return h("div", { class: "scroll no-pad" }, h("div", { style: { padding: "16px" } },
    h("div", { class: "list-row body-m bold muted" }, h("span"), h("span", null, t("prop_day")), h("span", null, t("prop_night"))),
    [...bands].map(([name, e]) => h("div", { class: "list-row" }, h("span", { class: "body-l bold" }, name), cell(e.day), cell(e.night)))));
}

/** @param {string} condition */
function conditionBadge(condition) {
  const c = condition.toLowerCase();
  const color = { good: "#4CAF50", fair: "#FF9800", poor: "#F44336", "band closed": "#444444" }[c] ?? "#888888";
  const label = { good: "prop_cond_good", fair: "prop_cond_fair", poor: "prop_cond_poor", "band closed": "prop_cond_closed" }[c];
  return h("span", { class: "badge", style: { background: color } }, label ? t(label) : condition);
}

function legend() {
  const items = [["#191C20", "prop_legend_background"], ["#8822CC", "prop_legend_marginal"], ["#4488EE", "prop_legend_weak"],
    ["#22BBEE", "prop_legend_moderate"], ["#33CC44", "prop_legend_good"], ["#EEDD22", "prop_legend_very_good"],
    ["#FF9922", "prop_legend_high"], ["#EE4444", "prop_legend_strong"], ["#FFBBCC", "prop_legend_very_strong"]];
  return h("div", { class: "legend" }, items.map(([color, key]) =>
    h("div", { class: "row" }, h("span", { class: "sw", style: { background: color } }), h("span", null, t(key)))));
}
