// @ts-check
import { h, topBar, iconButton, icon, button } from "../ui.js";
import { t } from "../i18n.js";

/** @typedef {import("../app.js").ScreenContext} ScreenContext */

/**
 * Typowy ekran: górny pasek z tytułem, przyciskiem ustawień i (na pod-ekranach) strzałką wstecz.
 * @param {ScreenContext} ctx @param {{title: any, subtitle?: string, actions?: any[], settings?: boolean, scrollClass?: string, body?: HTMLElement}} o
 * @param {...any} content
 */
export function layout(ctx, o, ...content) {
  const actions = [...(o.actions ?? [])];
  if (o.settings !== false) actions.push(iconButton("settings", t("common_settings"), () => ctx.navigate("settings")));
  const body = o.body ?? h("div", { class: `scroll ${o.scrollClass ?? ""}` }, content);
  const el = h("div", { class: "screen" }, topBar({ title: o.title, subtitle: o.subtitle, back: ctx.isTab ? undefined : ctx.back, actions }), body);
  return { el, body };
}

/** Narzędzia jeszcze nieprzeniesione z Androida. @param {ScreenContext} ctx */
export function soonScreen(ctx) {
  const titleKey = SOON_TITLES[ctx.params[0]] ?? "web_soon_title";
  const { el } = layout(ctx, { title: t(titleKey) },
    h("div", { class: "centered" },
      h("div", { class: "col gap-l", style: { alignItems: "center", maxWidth: "420px" } },
        icon("construction", "icon large primary"),
        h("div", { class: "title-l" }, t("web_soon_title")),
        h("div", { class: "body-m muted" }, t("web_soon_text")),
        button(t("tab_toolbox"), () => ctx.navigate("toolbox"), "tonal"))));
  return { el };
}

/** Tytuły ekranów (te same klucze co w Przyborniku). */
export const SOON_TITLES = {
  dx_cluster: "tab_dx_cluster", callsign_lookup: "tool_callsign_lookup", live_spots: "tool_live_spots", aprs: "tool_aprs",
  repeaters: "tool_repeaters", satellites: "tool_satellites", ft8_decoder: "tool_ft8", rtty_decoder: "tool_rtty",
  horus_decoder: "tool_horus", sstv_decoder: "tool_sstv", dsstv_decoder: "tool_dsstv", cw_decoder: "tool_cw_decoder",
  cw_trainer: "tool_cw_trainer", contest_calendar: "tool_contests", awards: "tool_awards", abroad: "tool_abroad",
  activations: "tool_activations", about: "tool_about",
};

/** Odświeżanie co pełną sekundę (zegary); zwraca funkcję zatrzymującą. @param {(now: Date) => void} fn */
export function everySecond(fn) {
  /** @type {any} */
  let timer;
  const tick = () => {
    fn(new Date());
    timer = setTimeout(tick, 1000 - (Date.now() % 1000));
  };
  tick();
  return () => clearTimeout(timer);
}
