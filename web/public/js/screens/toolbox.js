// @ts-check
// Przybornik – te same grupy i kafelki co ToolboxScreen.kt; nieprzeniesione jeszcze narzędzia mają oznaczenie "Wkrótce"
import { h, icon } from "../ui.js";
import { t } from "../i18n.js";
import { layout } from "./common.js";

/** Narzędzia działające już w wersji WWW. */
export const READY = new Set(["beacons", "locator", "gray_line_calc", "bandplan", "calculators", "coax_calc", "vademecum", "live_spots", "activations", "repeaters", "callsign_lookup", "contest_calendar", "awards", "abroad"]);

/** @type {[string, string[][]][]} */
const GROUPS = [
  ["toolbox_group_on_air", [
    ["tool_callsign_lookup", "personSearch", "callsign_lookup"], ["tool_live_spots", "park", "live_spots"], ["tool_aprs", "radar", "aprs"],
    ["tool_repeaters", "cellTower", "repeaters"], ["tool_satellites", "satelliteAlt", "satellites"], ["tool_beacons", "wifiTethering", "beacons"],
  ]],
  ["toolbox_group_digital", [
    ["tool_ft8", "waves", "ft8_decoder"], ["tool_rtty", "keyboard", "rtty_decoder"], ["tool_horus", "air", "horus_decoder"],
    ["tool_sstv", "imageSearch", "sstv_decoder"], ["tool_dsstv", "image", "dsstv_decoder"], ["tool_cw_decoder", "graphicEq", "cw_decoder"],
    ["tool_cw_trainer", "school", "cw_trainer"],
  ]],
  ["toolbox_group_planning", [
    ["tool_contests", "emojiEvents", "contest_calendar"], ["tool_awards", "workspacePremium", "awards"], ["tool_abroad", "flight", "abroad"],
    ["tool_locator", "explore", "locator"], ["tool_grayline", "wbSunny", "gray_line_calc"], ["tool_bandplan", "tableChart", "bandplan"],
    ["tool_activations", "locationOn", "activations"],
  ]],
  ["toolbox_group_calculators", [["tool_calculators", "calculate", "calculators"], ["tool_coax", "cable", "coax_calc"]]],
  ["toolbox_group_cheatsheets", [
    ["tool_dxcc", "public", "vademecum/dxcc"], ["tool_qcodes", "menuBook", "vademecum/qcodes"], ["tool_rst", "signalCellularAlt", "vademecum/rst"],
    ["tool_sp_districts", "map", "vademecum/sp"], ["tool_slang", "forum", "vademecum/slang"], ["tool_morse", "moreHoriz", "vademecum/morse"],
    ["tool_phonetic", "translate", "vademecum/phonetic"],
  ]],
  ["toolbox_group_app", [["tool_about", "info", "about"]]],
];

/** Wszystkie trasy z Przybornika (do rozpoznania ekranów "wkrótce"). */
export const ALL_TOOL_ROUTES = new Set(GROUPS.flatMap(([, items]) => items.map((i) => String(i[2]).split("/")[0])));

/** @param {import("../app.js").ScreenContext} ctx */
export function toolboxScreen(ctx) {
  const { el } = layout(ctx, { title: t("toolbox_title") },
    GROUPS.map(([title, items]) => [
      h("div", { class: "section-title" }, t(String(title))),
      h("div", { class: "tool-grid" }, /** @type {string[][]} */ (items).map(([label, ic, route]) => {
        const ready = READY.has(route.split("/")[0]);
        return h("button", { type: "button", class: `card clickable tool ${ready ? "" : "soon"}`, onclick: () => ctx.navigate(route) },
          ready ? null : h("span", { class: "soon-tag" }, t("web_soon")),
          icon(ic), h("span", { class: "tool-label" }, t(label)));
      })),
    ]));
  return { el };
}
