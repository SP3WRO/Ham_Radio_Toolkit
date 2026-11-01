// @ts-check
// O programie (AboutScreen.kt): wersja, autor (z niespodzianką po 5 dotknięciach), licencja MIT, kod innych autorów, źródła danych.
import { h, replace, icon, button, dialog } from "../ui.js";
import { t } from "../i18n.js";
import { layout } from "./common.js";

const GITHUB_URL = "https://github.com/SP3WRO/Ham_Radio_Toolkit";
const LICENSE_URL = `${GITHUB_URL}/blob/HEAD/LICENSE`;

/** @typedef {{name: string, usage: string, url?: string, nameKey?: string}} Credit */

/** @type {Credit[]} */
const THIRD_PARTY = [
  { name: "ft8_lib – Kārlis Goba", usage: "src_ft8", url: "https://github.com/kgoba/ft8_lib" },
  { name: "Robot36 – Ahmet Inan", usage: "src_robot36", url: "https://github.com/xdsopl/robot36" },
  { name: "horusdemodlib – Project Horus (David Rowe, Brady O'Brien, Mark Jessop)", usage: "src_horus", url: "https://github.com/projecthorus/horusdemodlib" },
  { name: "pdf.js – Mozilla Foundation", usage: "src_pdfjs", url: "https://github.com/mozilla/pdf.js" },
  { name: "hessu/aprs-symbols – Heikki Hannikainen OH7LZB", usage: "src_aprs_symbols", url: "https://github.com/hessu/aprs-symbols" },
];

/** @type {[string, Credit[]][]} */
const SOURCES = [
  ["src_g_propagation", [
    { name: "HamQSL – Paul N0NBH", usage: "src_hamqsl", url: "https://www.hamqsl.com" },
    { name: "NOAA SWPC", usage: "src_noaa", url: "https://www.swpc.noaa.gov" },
    { name: "Tropocast", usage: "src_tropocast", url: "https://tropocast.eu" }]],
  ["src_g_dx", [
    { name: "DX Summit", usage: "src_dxsummit", url: "https://www.dxsummit.fi" },
    { name: "AD1C – country-files.com", usage: "src_cty", url: "https://www.country-files.com" }]],
  ["src_g_activations", [
    { name: "POTA", usage: "src_pota", url: "https://pota.app" },
    { name: "SOTA", usage: "src_sota", url: "https://www.sotadata.org.uk" },
    { name: "WWFF", usage: "src_wwff", url: "https://wwff.co" }]],
  ["tool_aprs", [{ name: "APRS-IS (aprs2.net)", usage: "src_aprsis", url: "https://www.aprs2.net" }]],
  ["src_g_repeaters", [
    { name: "przemienniki.eu", usage: "src_rep_pl_eu", url: "https://przemienniki.eu" },
    { name: "przemienniki.net", usage: "src_rep_pl_eu", url: "https://przemienniki.net" },
    { name: "HearHam.com", usage: "src_rep_world", url: "https://hearham.com" },
    { name: "repeatermap.de", usage: "src_rep_world", url: "https://www.repeatermap.de" },
    { name: "RadioID.net", usage: "src_dmr_details", url: "https://radioid.net" }]],
  ["src_g_lookup", [
    { name: "UKE", nameKey: "src_uke_name", usage: "src_uke", url: "https://amator.uke.gov.pl" },
    { name: "HamQTH", usage: "src_callbook", url: "https://www.hamqth.com" },
    { name: "HamDB.org", usage: "src_hamdb", url: "https://hamdb.org" },
    { name: "QRZCQ", usage: "src_callbook", url: "https://www.qrzcq.com" },
    { name: "RadioID.net", usage: "src_dmr_ids", url: "https://radioid.net" },
    { name: "PSK Reporter", usage: "src_pskreporter", url: "https://pskreporter.info" }]],
  ["src_g_satellites", [
    { name: "AMSAT", usage: "src_tle", url: "https://www.amsat.org" },
    { name: "CelesTrak", usage: "src_tle_backup", url: "https://celestrak.org" },
    { name: "SatNOGS DB", usage: "src_satnogs", url: "https://db.satnogs.org" },
    { name: "amateur-satellite-database (palewire)", usage: "src_satdb", url: "https://github.com/palewire/amateur-satellite-database" }]],
  ["src_g_contests", [
    { name: "SP9CXN", nameKey: "src_sp9cxn_name", usage: "src_sp9cxn", url: "https://sp9cxn.pzk.pl" },
    { name: "PK-UKF / SP1CNV", usage: "src_pkukf", url: "https://pk-ukf.pl" },
    { name: "QRZ.RU", usage: "src_qrzru", url: "https://www.qrz.ru/contest/" },
    { name: "radiodyplom.pl – SP8TZ", usage: "src_radiodyplom", url: "https://radiodyplom.pl" }]],
  ["src_g_log", [
    { name: "QRZ.com Logbook", usage: "src_upload", url: "https://logbook.qrz.com" },
    { name: "eQSL.cc", usage: "src_upload", url: "https://www.eqsl.cc" }]],
  ["src_g_bandplans", [
    { name: "IARU", nameKey: "src_iaru_name", usage: "src_iaru", url: "https://www.iaru.org" },
    { name: "CEPT/ECO", nameKey: "src_cept_name", usage: "src_cept", url: "https://docdb.cept.org" },
    { name: "NCDXF / IARU", usage: "src_ncdxf", url: "https://www.ncdxf.org/beacon/" }]],
  ["src_g_algorithms", [
    { name: "Olgierd SQ3SWF – Coax Calculator", usage: "src_coax" },
    { name: "L.B. Cebik W4RNL", usage: "src_moxon" },
    { name: "ETSI ES 201 980 (DRM) + HamDRM – Francesco Lanza HB9TLK", usage: "src_drm" }]],
];

const MIT_LICENSE_TEXT = `MIT License

Copyright (c) 2026 Mateusz SP3WRO

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.`;

/** @param {string} href @param {any[]} children @param {string} [cls] */
const link = (href, children, cls = "") => h("a", { href, target: "_blank", rel: "noopener", class: cls }, ...children);

/** @param {Credit} c */
const creditRow = (c) => {
  const content = [h("div", { class: "col grow" },
    h("span", { class: "body-m medium" }, c.nameKey ? t(c.nameKey) : c.name),
    h("span", { class: "body-s muted" }, t(c.usage))),
  c.url ? icon("openInNew", "icon small muted") : null];
  return c.url ? link(c.url, content, "credit-row row") : h("div", { class: "credit-row row" }, ...content);
};

/** Niespodzianka: papużka KUKI odbijająca się od krawędzi ekranu. Dotknięcie zamyka. */
function easterEgg() {
  const colors = ["#00FF00", "#00FFFF", "#FF00FF", "#FFFF00", "#FF0000", "#FFFFFF"];
  let color = colors[0];
  const name = h("span", { class: "kuki-name" }, "KUKI");
  name.style.color = color;
  const bird = h("div", { class: "kuki" }, h("span", { class: "kuki-bird", "aria-hidden": "true" }, "🦜"), name);
  const overlay = h("div", { class: "kuki-overlay", role: "button", "aria-label": t("common_close") }, bird);
  let x = 100, y = 100, vx = 250, vy = 200, last = 0, alive = true;
  const W = 180, H = 120;
  const step = (/** @type {number} */ now) => {
    if (!alive) return;
    const dt = last ? (now - last) / 1000 : 0;
    last = now;
    if (dt > 0 && dt < 0.1) { x += vx * dt; y += vy * dt; }
    const sw = overlay.clientWidth, sh = overlay.clientHeight;
    let hit = false;
    if (x <= 0) { x = 0; vx = -vx; hit = true; } else if (x >= sw - W) { x = sw - W; vx = -vx; hit = true; }
    if (y <= 0) { y = 0; vy = -vy; hit = true; } else if (y >= sh - H) { y = sh - H; vy = -vy; hit = true; }
    if (hit) { const others = colors.filter((c) => c !== color); color = others[Math.floor(Math.random() * others.length)]; name.style.color = color; }
    bird.style.transform = `translate(${x}px, ${y}px)`;
    requestAnimationFrame(step);
  };
  overlay.addEventListener("click", () => { alive = false; overlay.remove(); });
  document.body.appendChild(overlay);
  requestAnimationFrame(step);
}

/** @param {import("../app.js").ScreenContext} ctx */
export function aboutScreen(ctx) {
  let showSources = false;
  let authorClicks = 0;
  const version = h("div", { class: "body-l medium muted" }, t("about_version", "…"));
  Promise.all([
    fetch("data/app_version.json").then((r) => r.json()).catch(() => ({ versionName: "?" })),
    fetch("sw-assets.js").then((r) => r.text()).then((s) => /HRT_VERSION = "([^"]+)"/.exec(s)?.[1] ?? null).catch(() => null),
  ]).then(([v, web]) => { version.textContent = t("about_version", v.versionName) + (web ? ` • WWW ${web.slice(0, 7)}` : " • WWW"); });

  const sourcesBody = h("div");
  const sourcesCount = SOURCES.reduce((a, [, items]) => a + items.length, 0);
  const toggleSources = () => { showSources = !showSources; renderSources(); };
  const sourcesHead = h("button", { type: "button", class: "plain-btn row", style: { width: "100%" }, onclick: toggleSources });
  function renderSources() {
    replace(sourcesHead, h("span", { class: "title-m bold primary grow", style: { textAlign: "left" } }, t("about_sources")),
      icon(showSources ? "expandLess" : "expandMore"));
    sourcesHead.setAttribute("aria-label", showSources ? t("common_collapse") : t("common_expand"));
    replace(sourcesBody, showSources
      ? SOURCES.map(([title, items]) => h("div", { class: "col", style: { marginTop: "10px" } },
        h("span", { class: "title-s bold secondary" }, t(title)), ...items.map(creditRow)))
      : button(t("about_show_sources"), toggleSources, "text"));
  }
  renderSources();

  const showLicense = () => dialog({
    title: t("about_mit_title"),
    body: h("pre", { class: "license-text code" }, MIT_LICENSE_TEXT),
    actions: (close) => [link(LICENSE_URL, [t("about_on_github")], "btn btn-text"), button(t("common_close"), close, "text")],
  });

  const body = h("div", { class: "scroll about" },
    h("div", { class: "col", style: { alignItems: "center", gap: "4px" } },
      h("h1", { class: "about-title" }, "Ham Radio Toolkit"),
      version,
      link(GITHUB_URL, [icon("code"), t("about_github")], "btn btn-tonal")),
    h("button", { type: "button", class: "card clickable pad col gap-s", style: { textAlign: "left" },
      onclick: () => { authorClicks++; if (authorClicks >= 5) { authorClicks = 0; easterEgg(); } } },
      h("span", { class: "title-m bold primary" }, t("about_author")),
      h("span", { class: "body-m" }, t("about_author_text")),
      h("span", { class: "body-m", style: { fontStyle: "italic" } }, t("about_author_joke"))),
    h("div", { class: "card pad col gap-s" },
      h("span", { class: "title-m bold primary" }, t("about_license_title")),
      h("span", { class: "body-m" }, t("about_license_text")),
      h("span", { class: "body-s muted" }, t("about_as_is")),
      h("div", { class: "row", style: { gap: "4px", flexWrap: "wrap" } },
        button(t("about_full_license"), showLicense, "text"),
        link(GITHUB_URL, [icon("openInNew", "icon small"), t("about_repository")], "btn btn-text")),
      h("div", { class: "hr" }),
      h("span", { class: "title-s bold" }, t("about_third_party")),
      h("span", { class: "body-s muted" }, t("about_third_party_note")),
      ...THIRD_PARTY.map(creditRow)),
    h("div", { class: "card pad col gap-s" }, sourcesHead,
      h("span", { class: "body-s muted" }, t("about_sources_note", sourcesCount, SOURCES.length)), sourcesBody),
    h("p", { class: "body-m medium secondary about-thanks" }, t("about_thanks")));
  const { el } = layout(ctx, { title: t("tool_about"), body });
  return { el };
}
