// @ts-check
// Ustawienia (SettingsScreen.kt) – te same sekcje; powiadomienia i konta dojdą z funkcjami online
import { h, settingsSection, settingsChoice, settingsText, settingsSwitch, settingsSlider, settingsItem, divider, replace } from "../ui.js";
import { t, LANGUAGES } from "../i18n.js";
import { settings } from "../store.js";
import { serverStatus, cty, ctyInfo } from "../services.js";
import { APP_VERSION } from "../core/logbook.js";
import { Callsigns } from "../core/radio.js";
import { Maidenhead } from "../core/geo.js";
import { layout } from "./common.js";

const APRS_SSID = /^-?(\d|1[0-5])$/;

/** @param {import("../app.js").ScreenContext} ctx */
export function settingsScreen(ctx) {
  const host = h("div", { class: "scroll settings" });
  const serverRow = h("div");
  let serverOk = /** @type {boolean | null} */ (null);

  const render = () => {
    const s = settings.get();
    const km = (v) => t("common_km", String(v));
    replace(host,
      settingsSection(t("settings_language"), "language",
        settingsChoice(t("settings_language"), LANGUAGES.map((l) => [l.tag, l.tag ? l.nativeName : t("settings_language_system")]), s.language,
          (v) => settings.set({ language: v }))),
      settingsSection(t("settings_section_appearance"), "palette",
        settingsChoice(t("settings_theme"), [["system", t("settings_theme_system")], ["light", t("settings_theme_light")], ["dark", t("settings_theme_dark")]],
          s.app_theme, (v) => settings.set({ app_theme: v }))),
      settingsSection(t("settings_section_station"), "person",
        settingsText({ title: t("settings_callsign"), value: s.callsign === "NOCALL" ? "" : s.callsign, emptySummary: t("settings_callsign_empty"),
          placeholder: t("settings_callsign_placeholder"), uppercase: true,
          // Puste pole jest dozwolone: bez znaku aplikacja tylko odbiera
          validate: (v) => (!v.trim() || Callsigns.isValidAmateur(v) ? null : t("settings_callsign_invalid")),
          onsave: (v) => settings.set({ callsign: v.trim().toUpperCase() || "NOCALL" }) }),
        divider(),
        settingsText({ title: t("settings_locator"), value: s.user_locator, emptySummary: t("settings_locator_empty"), placeholder: t("settings_locator_placeholder"),
          uppercase: true, validate: (v) => (!v || Maidenhead.isValid(v) ? null : t("settings_locator_invalid")), onsave: (v) => settings.set({ user_locator: v }) })),
      settingsSection(t("settings_section_dx"), "public",
        settingsChoice(t("settings_dx_refresh"), [[0, t("settings_dx_refresh_manual")], [1, t("settings_dx_refresh_1")], [5, t("settings_dx_refresh_5")]],
          s.refresh_interval, (v) => settings.set({ refresh_interval: v })),
        divider(),
        settingsChoice(t("settings_tropocast_area"), [["europe", "settings_area_europe"], ["poland", "settings_area_poland"], ["united-kingdom", "settings_area_uk"],
          ["germany", "settings_area_germany"], ["france", "settings_area_france"], ["spain", "settings_area_spain"], ["italy", "settings_area_italy"],
          ["czechia", "settings_area_czechia"], ["croatia", "settings_area_croatia"]].map(([v, k]) => [v, t(k)]), s.tropocast_area,
          (v) => settings.set({ tropocast_area: v }))),
      settingsSection(t("settings_section_repeaters"), "cellTower",
        settingsChoice(t("settings_repeater_source"), [
          ["przemienniki_net", t("settings_source_poland", "przemienniki.net")], ["przemienniki_eu", t("settings_source_poland", "przemienniki.eu")],
          ["world", t("settings_source_world", "HearHam + repeatermap.de")], ["hearham", t("settings_source_world", "HearHam")],
          ["repeatermap", t("settings_source_europe", "repeatermap.de")], ["repeaterbook", t("settings_source_token", "RepeaterBook")],
        ], s.repeater_data_source, (v) => settings.set({ repeater_data_source: v })),
        h("div", { class: "settings-note" }, t("settings_repeater_note")),
        divider(),
        settingsSlider({ title: t("settings_search_radius"), value: s.repeater_radius_km, min: 10, max: 200, step: 10, format: km,
          onsave: (v) => settings.set({ repeater_radius_km: v }) })),
      settingsSection("APRS-IS", "radar",
        settingsText({ title: "SSID", value: s.aprs_ssid, placeholder: t("settings_aprs_ssid_placeholder"), inputmode: "numeric",
          validate: (v) => (!v || APRS_SSID.test(v) ? null : t("settings_aprs_ssid_invalid")),
          onsave: (v) => settings.set({ aprs_ssid: !v || v.startsWith("-") ? v : `-${v}` }) }),
        divider(),
        settingsChoice(t("settings_aprs_icon"), [["/>", "settings_aprs_icon_car"], ["/<", "settings_aprs_icon_motorcycle"], ["/v", "settings_aprs_icon_van"],
          ["/[", "settings_aprs_icon_walker"], ["/-", "settings_aprs_icon_home"], ["/;", "settings_aprs_icon_tent"]].map(([v, k]) => [v, t(k)]),
          s.aprs_icon, (v) => settings.set({ aprs_icon: v })),
        divider(),
        settingsText({ title: t("settings_aprs_comment"), value: s.aprs_comment, emptySummary: t("settings_none"), onsave: (v) => settings.set({ aprs_comment: v }) }),
        divider(),
        settingsSlider({ title: t("settings_aprs_radius"), value: s.aprs_radius_km, min: 10, max: 200, step: 10, format: km, onsave: (v) => settings.set({ aprs_radius_km: v }) })),
      settingsSection(t("settings_section_programs"), "hiking",
        [["POTA", "Parks on the Air"], ["SOTA", "Summits on the Air"], ["WWFF", t("settings_wwff_desc")]].map(([p, desc], i) => [
          i > 0 ? divider() : null,
          settingsSwitch(p, desc, s.active_programs.includes(p), (on) => settings.set({
            active_programs: on ? [...new Set([...s.active_programs, p])] : s.active_programs.filter((x) => x !== p) })),
        ])),
      settingsSection(t("settings_section_accounts"), "key",
        h("div", { class: "settings-note" }, t("web_accounts_note"))),
      settingsSection(t("web_section_web"), "info",
        settingsItem(t("web_version"), APP_VERSION),
        divider(),
        serverRow,
        divider(),
        settingsItem("GitHub", "SP3WRO/Ham_Radio_Toolkit", () => window.open("https://github.com/SP3WRO/Ham_Radio_Toolkit", "_blank", "noopener"))));
    renderServer();
  };

  const renderServer = () => {
    const status = serverOk == null ? "…" : serverOk ? t("web_server_ok") : t("web_server_none");
    const source = ctyInfo();
    replace(serverRow, settingsItem(t("web_server"), `${status}\n${t("web_cty_status", source === "bundled" ? t("web_cty_bundled") : source)}`));
    serverRow.querySelector(".settings-item-summary")?.classList.add("pre");
  };

  serverStatus().then((ok) => { serverOk = ok; renderServer(); });
  cty().then(renderServer).catch(() => {});
  render();
  const unsubscribe = settings.subscribe(render);
  const { el } = layout(ctx, { title: t("common_settings"), settings: false, body: host });
  return { el, destroy: unsubscribe };
}
