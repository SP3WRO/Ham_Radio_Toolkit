// @ts-check
// Za granicą (AbroadScreen.kt): znak wg CEPT T/R 61-01, region IARU z częstotliwościami, strefy i czas
import { h, replace, infoCard, selectField, button, icon } from "../ui.js";
import { t, array, currentLanguage, uiLocale } from "../i18n.js";
import { settings } from "../store.js";
import { location, resolveCountry, cachedCountry, cty } from "../services.js";
import { IaruRegion, regionalInfo, PhraseLocalizer } from "../core/world.js";
import { ceptAdvice, ceptCountryCodes, flagOf, localizedLicences, CEPT_SOURCE } from "../core/cept.js";
import { layout } from "./common.js";

/** @param {string} iso */
export const countryName = (iso) => {
  try { return new Intl.DisplayNames([uiLocale()], { type: "region" }).of(iso) ?? iso; } catch { return iso; }
};

/** @type {Promise<[string, string][]> | null} */
let phrases = null;

/** Karta na ekran główny, gdy jesteśmy za granicą. @param {(r: string) => void} navigate */
export function abroadBanner(navigate) {
  const c = cachedCountry();
  if (!c || c.iso === "PL" || !location.get().fix) return null;
  const advice = ceptAdvice({ iso: c.iso, adminArea: c.adminArea, position: c.position }, settings.get().callsign);
  const text = !advice.cept ? t("ab_outside_cept") : advice.callsign ? t("ab_transmit_as_value", advice.callsign) : t("ab_guest_prefix", advice.prefix ?? "");
  return infoCard({ title: t("ab_title"), onclick: () => navigate("abroad") },
    h("div", { class: "row" }, h("span", { style: { fontSize: "28px" } }, flagOf(c.iso)),
      h("div", { class: "col" }, h("span", { class: "title-m" }, countryName(c.iso)),
        h("span", { class: `body-m ${advice.cept ? "primary" : "error-text"}` }, text))));
}

/** @param {import("../app.js").ScreenContext} ctx */
export function abroadScreen(ctx) {
  /** @type {string | null} */
  let chosen = null;
  /** @type {import("../services.js").ResolvedCountry | null} */
  let detected = cachedCountry();
  let entityText = /** @type {any} */ (null);
  let L = (/** @type {string} */ s) => s;
  const host = h("div", { class: "scroll" });
  const codes = ceptCountryCodes().sort((a, b) => countryName(a).localeCompare(countryName(b), uiLocale()));

  async function detect() {
    const fix = location.get().fix;
    if (!fix) return;
    const c = await resolveCountry(fix);
    if (c && (c.iso !== detected?.iso || c.adminArea !== detected?.adminArea)) { detected = c; render(); }
  }

  function render() {
    const country = chosen ? { iso: chosen, adminArea: "", position: null } : detected;
    const label = (/** @type {string} */ iso) => `${flagOf(iso)} ${countryName(iso)}`;
    const picker = selectField({
      label: t("lk_country"), value: chosen ?? (detected ? "" : "-"),
      options: /** @type {[string, string][]} */ ([
        ...(detected ? [["", t("ab_current_location", flagOf(detected.iso), countryName(detected.iso))]] : [["-", t("ab_choose_country")]]),
        ...codes.map((c) => [c, label(c)]),
      ]),
      onchange: (v) => { chosen = v && v !== "-" ? v : null; entityText = null; render(); },
    });
    if (!country) {
      return replace(host, picker, h("div", { class: "body-m muted" }, t("ab_detecting")),
        location.get().status !== "ok" ? h("div", null, button(t("home_allow_location"), () => location.start(), "text", { style: { marginLeft: "-12px" } })) : null);
    }
    const advice = ceptAdvice(country, settings.get().callsign);
    const cept = advice.cept;
    const isPoland = country.iso === "PL";
    const region = IaruRegion.forCountry(country.iso) ?? 1;
    const freqs = regionalInfo(region, country.iso);
    replace(host, picker,
      h("div", { class: `card pad col gap-s ${cept ? "running" : "error"}` },
        h("div", { class: "title-m bold" }, label(country.iso)),
        isPoland ? h("div", null, t("ab_in_poland"))
          : !cept ? [h("div", { class: "semibold" }, t("ab_no_cept")), h("div", null, t("ab_need_permit"))]
            : [h("div", { class: "label-l" }, t("ab_transmit_as")),
              h("div", { class: "headline-m bold primary", style: { wordBreak: "break-all" } }, advice.callsign ?? `${advice.prefix}/${t("ab_your_call")}`),
              h("div", { class: "body-m" }, cept.cept ? t("ab_cept_member") : t("ab_cept_nonmember")),
              cept.licences ? h("div", { class: "body-s" }, t("ab_equivalent", localizedLicences(cept.licences, t))) : null,
              cept.prefixes.length > 1 ? h("div", { class: "body-s" }, t("ab_guest_prefixes", cept.prefixes.join(", "))) : null,
              cept.note ? h("div", { class: "body-s semibold" }, t(cept.note)) : null,
              country.iso === "CA" && advice.prefix === "VE" ? h("div", { class: "body-s" }, t("ab_canada")) : null,
              h("div", { class: "body-s" }, t("ab_how_to_sign"))]),
      !isPoland && cept ? infoCard({ title: t("ab_remember") }, array("ab_remember_items").map((x) => h("div", { class: "body-m" }, `• ${x}`))) : null,
      infoCard({ title: t("ab_iaru_region", region) },
        h("div", { class: "body-m" }, t(`iaru_r${region}`)),
        h("div", { class: "body-m" }, `APRS: ${L(freqs.aprs)}`),
        h("div", { class: "body-m" }, t("bp_fm_calling", L(freqs.fmCalling))),
        h("div", { class: "body-m" }, t("bp_emergency", L(freqs.emergency))),
        region !== 1 ? h("div", { class: "body-s semibold" }, t("ab_different_plan")) : null,
        h("div", { class: "row wrap", style: { gap: "4px" } },
          h("button", { type: "button", class: "btn btn-text", style: { marginLeft: "-12px" }, onclick: () => ctx.navigate("bandplan") }, icon("tableChart"), t("tool_bandplan")),
          !chosen ? h("button", { type: "button", class: "btn btn-text", onclick: () => ctx.navigate("repeaters") }, icon("cellTower"), t("ab_repeaters_nearby")) : null)),
      entityText ? infoCard({ title: t("ab_zones_time") }, entityText) : null,
      h("div", { class: "label-s muted" }, t("ab_source", CEPT_SOURCE)));
    if (!entityText && advice.prefix) loadEntity(advice.prefix, country.iso);
  }

  /** Strefy i czas z bazy DXCC – po przykładowym znaku z prefiksem gościa. @param {string} prefix @param {string} iso */
  async function loadEntity(prefix, iso) {
    const sample = /\d$/.test(prefix) ? `${prefix}AA` : `${prefix}1AA`;
    const e = (await cty().catch(() => null))?.resolve(sample);
    const current = chosen ?? detected?.iso;
    if (!e || current !== iso) return;
    const off = e.utcOffsetHours;
    const local = new Date(Date.now() + off * 3_600_000).toISOString().slice(11, 16);
    const hours = Math.abs(off) % 1 === 0 ? String(Math.abs(off)) : String(Math.abs(off));
    entityText = [h("div", { class: "body-m" }, `DXCC: ${e.name} (${e.primaryPrefix}) • ${e.continent}`),
      h("div", { class: "body-m" }, t("ab_zones", String(e.cqZone), String(e.ituZone))),
      h("div", { class: "body-m" }, t("ab_standard_time", local, `${off >= 0 ? "+" : "−"}${hours}`))];
    render();
  }

  phrases ??= fetch("data/bandplan.json").then((r) => r.json()).then((d) => d.phrases ?? []).catch(() => []);
  phrases.then((p) => {
    const loc = new PhraseLocalizer(p, (k) => t(k), currentLanguage());
    L = (s) => loc.localize(s);
    render();
  });
  const unsub = location.subscribe(() => detect());
  location.alreadyGranted().then((g) => { if (g) location.start(); });
  render();
  detect();
  const { el } = layout(ctx, { title: t("ab_title"), body: host });
  return { el, destroy: () => { unsub(); } };
}
