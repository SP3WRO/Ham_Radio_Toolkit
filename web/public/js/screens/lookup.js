// @ts-check
// Wyszukiwarka znaków (CallsignLookupScreen.kt)
import { h, replace, textField, iconButton, chip, infoCard, button } from "../ui.js";
import { t, plural, fmt, uiLocale } from "../i18n.js";
import { stationPosition } from "../store.js";
import { logbook } from "../db.js";
import { callbookLookup } from "../services.js";
import { Maidenhead, GreatCircle } from "../core/geo.js";
import { layout } from "./common.js";

// Ostatnie wyszukiwania – w pamięci, do zamknięcia aplikacji
/** @type {string[]} */
const recent = [];

/** @param {import("../app.js").ScreenContext} ctx */
export function lookupScreen(ctx) {
  let input = (ctx.params[0] ?? "").toUpperCase();
  let searching = false;
  /** @type {string | null} */
  let error = null;
  /** @type {any} */
  let report = null;
  const recentHost = h("div", { class: "chips" });
  const errorHost = h("div", { class: "error-text" });
  const reportHost = h("div", { class: "col gap-l" });

  const field = textField({ label: t("settings_callsign"), placeholder: t("lk_hint"), value: input, uppercase: true, maxlength: 15, class: "has-trailing",
    oninput: (v) => {
      const clean = v.toUpperCase().replace(/[^A-Z0-9/]/g, "").slice(0, 15);
      if (clean !== v) field.input.value = clean;
      input = clean;
    }, onenter: () => search() });
  const searchBtn = iconButton("search", t("common_search"), () => search(), { class: "icon-btn field-trailing" });
  const searchIcon = searchBtn.firstElementChild;
  const spinner = h("span", { class: "spinner" });
  field.appendChild(searchBtn);

  async function search(text = input) {
    const call = text.trim().toUpperCase();
    if (call.length < 3) { error = t("lk_min_chars"); return render(); }
    input = call;
    field.input.value = call;
    field.input.blur();
    error = null;
    searching = true;
    const at = recent.indexOf(call);
    if (at >= 0) recent.splice(at, 1);
    recent.unshift(call);
    if (recent.length > 8) recent.pop();
    render();
    try {
      const cb = await callbookLookup(call, true);
      const previous = logbook.previousContacts(call);
      report = { cb, previous, newCountry: cb.entity != null && !logbook.workedDxcc().has(cb.entity.dxcc) };
      if (!cb.entity && !cb.sources.length) error = t("lk_unknown_prefix");
    } catch (e) {
      error = t("lk_failed", /** @type {Error} */ (e).message);
    } finally {
      searching = false;
      render();
    }
  }

  const row = (label, value) => h("div", { class: "kv" }, h("span", { class: "body-m muted" }, label), h("span", { class: "body-m semibold", style: { textAlign: "right" } }, value));

  function render() {
    searchBtn.replaceChildren(searching ? spinner : searchIcon ?? "");
    replace(recentHost, recent.map((c) => chip(c, false, () => search(c))));
    errorHost.textContent = error ?? "";
    if (!report) return replace(reportHost, searching ? null : h("div", { class: "body-s muted" }, t("lk_info")));
    const { cb, previous, newCountry } = report;
    const e = cb.entity;
    const info = cb.hamQth;
    const subtitle = [cb.name?.trim() ? cb.name : null, e?.name ?? info?.country ?? null].filter(Boolean).join(" • ");
    const station = stationPosition();
    const target = cb.grid ? Maidenhead.toLatLon(cb.grid.locator) : null;
    const path = station && target ? GreatCircle.path(station, target) : null;
    const base = cb.callsign.split("/").reduce((a, b) => (b.length > a.length ? b : a), "");
    const yesNo = (label, v) => (v?.toUpperCase() === "Y" ? [label, true] : v?.toUpperCase() === "N" ? [label, false] : null);
    const qsl = info ? [yesNo(t("lk_bureau"), info.qslBureau), yesNo("Direct", info.qslDirect), yesNo("LoTW", info.lotw), yesNo("eQSL", info.eqsl)].filter(Boolean) : [];
    replace(reportHost,
      h("div", { class: "col gap-s" },
        h("div", { class: "row" }, h("span", { class: "headline-m bold primary" }, cb.callsign), newCountry ? h("span", { class: "tag neutral bold" }, t("spot_new_country")) : null),
        subtitle ? h("div", { class: "title-m" }, subtitle) : null),
      infoCard({ title: t("lk_station") },
        !cb.sources.length ? h("div", { class: "body-m" }, t("lk_not_found")) : null,
        cb.name?.trim() ? row(cb.uke?.clubName ? t("lk_club") : t("lk_name"), cb.name) : null,
        cb.qth?.trim() ? row("QTH", cb.qth) : null,
        cb.uke ? [row(t("lk_uke_licence"), cb.uke.category + (cb.uke.powerW != null ? ` • ${cb.uke.powerW} W` : "")), row(t("lk_valid_to"), cb.uke.validTo)] : null,
        cb.hamDb ? [cb.hamDb.licenceClass ? row(t("lk_licence_class"), cb.hamDb.licenceClass) : null, cb.hamDb.expires ? row(t("lk_valid_to"), cb.hamDb.expires) : null] : null,
        cb.sources.length ? h("div", { class: "body-s muted" }, t("lk_sources", cb.sources.join(", "))) : null,
        cb.notes.map((n) => h("div", { class: "body-s tertiary" }, n))),
      e ? infoCard({ title: t("lk_dxcc_country") }, row(t("lk_country"), e.name), row(t("lk_main_prefix"), e.primaryPrefix), row(t("lk_dxcc_number"), String(e.dxcc)),
        row(t("lk_continent"), e.continent), row(t("lk_zones"), `${e.cqZone} / ${e.ituZone}`)) : null,
      // Odległość i kierunek wyłącznie z lokatora stacji
      infoCard({ title: t("lk_locator_bearing") },
        !cb.grid || !target ? [h("div", { class: "body-m" }, t("lk_no_locator")), h("div", { class: "body-s muted" }, t("lk_ask_locator"))]
          : [row(t("qso_locator"), cb.grid.locator), h("div", { class: "body-s muted" }, cb.grid.source),
            path ? [row(t("lk_short_path"), fmt("%.0f° ", path.shortPathBearing) + GreatCircle.compassPoint(path.shortPathBearing) + fmt(" • %,.0f km", path.shortPathKm)),
              row(t("lk_long_path"), fmt("%.0f° ", path.longPathBearing) + GreatCircle.compassPoint(path.longPathBearing) + fmt(" • %,.0f km", path.longPathKm))]
              : h("div", { class: "body-m muted" }, t("lk_no_my_position"))]),
      e ? infoCard({ title: t("lk_local_time") }, localTimeRow(e.utcOffsetHours, row), h("div", { class: "body-s muted" }, t("lk_tz_note"))) : null,
      infoCard({ title: "QSL (HamQTH)" },
        !cb.hamQthConfigured ? [h("div", { class: "body-m" }, t("lk_hamqth_hint")), h("div", null, button(t("lk_open_settings"), () => ctx.navigate("settings"), "text", { style: { marginLeft: "-12px" } }))]
          : info ? [info.qslVia?.trim() ? row("QSL via", info.qslVia) : null,
            qsl.length ? h("div", { class: "chips s" }, qsl.map(([l, yes]) => chip(`${l} ${yes ? "✓" : "✗"}`, !!yes, () => {}))) : null]
            : h("div", { class: "body-m" }, t("lk_not_in_hamqth"))),
      infoCard({ title: t("lk_your_log") },
        !previous.length ? h("div", { class: "body-m" }, t("lk_no_qso_yet"))
          : [h("div", { class: "title-s" }, plural("lk_qso_count", previous.length, previous.length)),
            h("div", { class: "body-m" }, t("lk_bands_modes", [...new Set(previous.map((q) => `${q.band} ${q.mode}`))].join(", "))),
            previous.slice(0, 5).map((q) => h("div", { class: "body-s muted" }, `${q.datetimeUtc} UTC • ${q.band} ${q.mode} • ${q.rstSent}/${q.rstRcvd}`))]),
      h("div", { class: "fields" },
        h("a", { class: "btn btn-outlined grow", href: `https://www.qrz.com/db/${encodeURIComponent(base)}`, target: "_blank", rel: "noopener" }, "QRZ.com"),
        h("a", { class: "btn btn-outlined grow", href: `https://www.hamqth.com/${encodeURIComponent(base)}`, target: "_blank", rel: "noopener" }, "HamQTH")));
  }

  const { el } = layout(ctx, { title: t("lk_title") }, field, recentHost, errorHost, reportHost);
  render();
  if (input.length >= 3) search(input);
  return { el };
}

function localTimeRow(offset, row) {
  const local = new Date(Date.now() + offset * 3600000);
  const text = new Intl.DateTimeFormat(uiLocale(), { hour: "2-digit", minute: "2-digit", weekday: "short", timeZone: "UTC", hour12: false }).format(local);
  const hrs = Math.abs(offset);
  const label = offset === 0 ? "UTC" : `UTC${offset > 0 ? "+" : "−"}${hrs % 1 === 0 ? hrs.toFixed(0) : hrs.toFixed(1)}`;
  return row(label, text);
}
