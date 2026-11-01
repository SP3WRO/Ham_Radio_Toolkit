// @ts-check
// Lokator: moja stacja, odległość i azymut do lokatora/współrzędnych/znaku, mapa azymutalna, przelicznik (LocatorScreen.kt)
import { h, replace, textField, button, iconButton, icon } from "../ui.js";
import { t, fmt, format } from "../i18n.js";
import { settings, stationPosition } from "../store.js";
import { location, callbookLookup } from "../services.js";
import { Maidenhead, GreatCircle } from "../core/geo.js";
import { parseNum } from "../core/radio.js";
import { layout } from "./common.js";
import { azimuthalMap, fullScreenMap } from "./map.js";

const COORDINATES = /^\s*(-?\d+(?:[.,]\d+)?)\s*[;,\s]\s*(-?\d+(?:[.,]\d+)?)\s*$/;
/** @type {{description: string, position: {lat: number, lon: number}, note: string} | null} */
let target = null;
let targetInput = "";

/** @param {import("../app.js").ScreenContext} ctx */
export function locatorScreen(ctx) {
  const myStation = h("div", { class: "col gap-m" });
  const targetResult = h("div", { class: "col gap-m" });
  const mapHost = h("div", { class: "col gap-m" });
  /** @type {ReturnType<typeof azimuthalMap> | null} */
  let map = null;
  let mapCenterKey = "";
  let manual = settings.get().user_locator;

  function renderStation() {
    const pos = stationPosition();
    const locField = textField({
      label: t("loc_my_locator"), value: manual, uppercase: true, maxlength: 8, error: !!manual && !Maidenhead.isValid(manual),
      oninput: (v) => { manual = v; locField.setError(!!v && !Maidenhead.isValid(v)); saveBtn.textContent = v ? t("common_save") : t("loc_use_gps"); saveBtn.disabled = !!v && !Maidenhead.isValid(v); },
    });
    const saveBtn = /** @type {HTMLButtonElement} */ (button(manual ? t("common_save") : t("loc_use_gps"), () => settings.set({ user_locator: manual.trim().toUpperCase() })));
    saveBtn.disabled = !!manual && !Maidenhead.isValid(manual);
    replace(myStation,
      h("div", { class: "body-l bold" }, t("loc_my_station")),
      pos ? [h("div", { class: "headline-m bold primary" }, Maidenhead.fromLatLon(pos.lat, pos.lon, 6)),
        h("div", { class: "body-s muted" }, format("%.4f°, %.4f°  •  ", pos.lat, pos.lon) + (Maidenhead.isValid(settings.get().user_locator) ? t("loc_manual") : t("loc_from_gps")))]
        : h("div", { class: "error-text" }, t("loc_no_position")),
      h("div", { class: "row" }, locField, saveBtn),
      h("div", null, button(t("loc_refresh_gps"), () => location.start(), "text", { icon: "myLocation", style: { marginLeft: "-12px" } })));
  }

  async function searchTarget() {
    const text = targetInput.trim();
    if (!text) return;
    targetErr.textContent = "";
    if (Maidenhead.isValid(text)) {
      const p = Maidenhead.toLatLon(text);
      if (p) target = { description: t("loc_locator_value", text.toUpperCase()), position: p, note: "" };
    } else if (COORDINATES.test(text)) {
      const m = /** @type {RegExpExecArray} */ (COORDINATES.exec(text));
      const lat = Number(m[1].replace(",", ".")), lon = Number(m[2].replace(",", "."));
      if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) target = { description: t("loc_point", format("%.4f", lat), format("%.4f", lon)), position: { lat, lon }, note: "" };
      else { target = null; targetErr.textContent = t("loc_not_recognized"); }
    } else {
      // Znak: lokator z wyszukiwarki (PSK Reporter, log, QRZCQ, HamQTH, HamDB) – bez zgadywania środka kraju
      const call = text.toUpperCase();
      const r = await callbookLookup(call, true);
      const position = r.grid ? Maidenhead.toLatLon(r.grid.locator) : null;
      if (!r.entity && !r.sources.length && !r.grid) {
        target = null;
        targetErr.textContent = t("loc_not_recognized");
      } else if (!r.grid || !position) {
        target = null;
        targetErr.textContent = [t("loc_no_locator_for", call), ...r.notes].join("\n");
      } else {
        const details = [r.name ?? "", r.qth ?? "", r.entity?.name ?? ""].filter((x) => x.trim()).join(", ");
        target = { description: details ? `${call} – ${details}` : call, position, note: r.grid.source };
      }
    }
    renderTarget();
  }

  const targetField = textField({ label: t("loc_target_label"), placeholder: t("loc_target_hint"), value: targetInput, uppercase: true, class: "has-trailing",
    oninput: (v) => { targetInput = v; }, onenter: searchTarget });
  targetField.appendChild(iconButton("search", t("common_search"), searchTarget, { class: "icon-btn field-trailing" }));
  const targetErr = h("div", { class: "body-m error-text pre" });

  function renderTarget() {
    const pos = stationPosition();
    const row = (label, km, bearing, color) => h("div", { class: "kv" }, h("span", { class: "semibold", style: { color } }, label),
      h("span", { class: "bold" }, fmt("%,.0f km  •  %.0f° ", km, bearing) + GreatCircle.compassPoint(bearing)));
    if (!target) replace(targetResult);
    else {
      const path = pos ? GreatCircle.path(pos, target.position) : null;
      replace(targetResult,
        h("div", { class: "semibold" }, target.description),
        h("div", { class: "body-m muted pre" }, t("loc_target_locator", Maidenhead.fromLatLon(target.position.lat, target.position.lon, 6)) + (target.note ? `\n${target.note}` : "")),
        path ? [row(t("loc_short_path"), path.shortPathKm, path.shortPathBearing, "#B8860B"), row(t("loc_long_path"), path.longPathKm, path.longPathBearing, "#C2185B")] : null);
    }
    renderMap();
  }

  function renderMap() {
    const pos = stationPosition();
    if (!pos) { map?.destroy(); map = null; replace(mapHost); return; }
    const key = `${pos.lat},${pos.lon}`;
    if (!map) {
      map = azimuthalMap({ center: pos, target: target?.position });
      mapCenterKey = key;
      const open = () => {
        const path = target ? GreatCircle.path(pos, target.position) : null;
        fullScreenMap(pos, target?.position ?? null, [
          h("div", { class: "title-m bold" }, t("loc_my_station_value", Maidenhead.fromLatLon(pos.lat, pos.lon, 6))),
          target ? h("div", { class: "body-m" }, target.description) : null,
          path ? h("div", { class: "body-m", style: { color: "#FFD166" } },
            fmt("SP: %,.0f km • %.0f°   LP: %,.0f km • %.0f°", path.shortPathKm, path.shortPathBearing, path.longPathKm, path.longPathBearing)) : null]);
      };
      map.canvas.addEventListener("click", open);
      replace(mapHost, h("div", { class: "map-box" }, map.canvas, iconButton("fullscreen", t("loc_enlarge_map"), open, { class: "icon-btn overlay" })),
        h("div", { class: "body-s muted" }, t("loc_map_note")));
    } else {
      if (key !== mapCenterKey) { mapCenterKey = key; map.setCenter(pos); }
      map.setTarget(target?.position ?? null);
    }
  }

  // Przelicznik współrzędne -> lokator
  let convLat = "", convLon = "";
  const convOut = h("div", { class: "title-l bold primary" });
  const updateConv = () => {
    const lat = parseNum(convLat), lon = parseNum(convLon);
    convOut.textContent = lat != null && lon != null && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 ? Maidenhead.fromLatLon(lat, lon, 8) : "";
  };

  const { el } = layout(ctx, { title: t("loc_title") },
    h("section", { class: "card pad-s" }, myStation),
    h("section", { class: "card pad-s col gap-m" }, h("div", { class: "body-l bold" }, t("loc_distance_bearing")), targetField, targetErr, targetResult),
    mapHost,
    h("section", { class: "card pad-s col gap-m" }, h("div", { class: "body-l bold" }, t("loc_coords_to_locator")),
      h("div", { class: "fields" },
        // Bez inputmode=decimal: klawiatura iPhone'a nie ma wtedy minusa (szerokości S, długości W)
        textField({ label: t("loc_lat"), value: "", oninput: (v) => { convLat = v; updateConv(); } }),
        textField({ label: t("loc_lon"), value: "", oninput: (v) => { convLon = v; updateConv(); } })),
      convOut));

  renderStation();
  renderTarget();
  const unsubscribe = settings.subscribe(() => { renderStation(); renderTarget(); });
  return { el, destroy: () => { unsubscribe(); map?.destroy(); } };
}
