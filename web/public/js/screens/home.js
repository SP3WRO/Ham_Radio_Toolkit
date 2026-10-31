// @ts-check
// Strona główna – czas UTC i lokalny, lokator QTH z GPS, współrzędne i wysokość (HomeScreen.kt)
import { h, infoCard, button, replace, icon } from "../ui.js";
import { t, format, fmt } from "../i18n.js";
import { settings } from "../store.js";
import { location } from "../services.js";
import { Maidenhead } from "../core/geo.js";
import { layout, everySecond } from "./common.js";

const isIos = () => /iP(hone|ad|od)/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
const isStandalone = () => matchMedia("(display-mode: standalone)").matches || /** @type {any} */ (navigator).standalone === true;

/** @param {import("../app.js").ScreenContext} ctx */
export function homeScreen(ctx) {
  const call = settings.get().callsign;
  const hello = !call || call === "NOCALL" ? h("div", { class: "hello" }, t("home_hello"))
    : h("div", { class: "hello" }, t("home_hello_call"), h("b", null, call));

  // Podpowiedź instalacji na iPhonie (Safari nie pokazuje własnego przycisku "Zainstaluj")
  const install = isIos() && !isStandalone() && !settings.get().install_hint_dismissed
    ? infoCard({ class: "tonal" }, h("div", { class: "install-banner" }, icon("iosShare"),
      h("div", { class: "col grow" }, h("div", { class: "title-s" }, t("web_install_title")), h("div", { class: "body-m" }, t("web_install_text")),
        h("div", null, button(t("web_install_dismiss"), () => { settings.set({ install_hint_dismissed: true }); install?.remove(); }, "text", { style: { marginLeft: "-12px" } })))))
    : null;

  const time = h("div", { class: "display-m mono" });
  const date = h("div", { class: "body-m muted" });
  const stopClock = everySecond((now) => {
    const iso = now.toISOString();
    time.textContent = iso.slice(11, 19);
    const local = `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
    date.textContent = t("home_date_local", iso.slice(0, 10), local);
  });

  const qthBody = h("div", { class: "col" });
  const renderQth = () => {
    const { fix, status } = location.get();
    if (status === "idle" || status === "denied" || status === "insecure" || (status === "unavailable" && !fix)) {
      const msg = status === "denied" ? t("web_location_denied") : status === "insecure" ? t("web_location_https") : t("home_no_location");
      replace(qthBody, h("div", { class: "body-l" }, msg),
        status === "insecure" ? null : h("div", null, button(t("home_allow_location"), () => location.start(), "text", { style: { marginLeft: "-12px" } })));
    } else if (!fix) {
      replace(qthBody, h("div", { class: "row" }, h("div", { class: "spinner" }), h("span", { class: "body-l" }, t("home_waiting_gps"))));
    } else {
      const alt = fix.altitude != null ? fmt("%,.0f m", fix.altitude) + t("web_altitude_gps") : t("home_no_altitude");
      replace(qthBody,
        h("div", { class: "row baseline" },
          h("span", { class: "display-s secondary" }, Maidenhead.fromLatLon(fix.lat, fix.lon, 6)),
          h("span", { class: `title-l ${fix.altitude == null ? "muted" : ""}` }, alt)),
        h("div", { class: "body-m muted" }, format("%.5f°, %.5f°", fix.lat, fix.lon) + (fix.accuracy ? `  ±${Math.round(fix.accuracy)} m` : "")));
    }
  };
  renderQth();
  const unsubscribe = location.subscribe(renderQth);

  const { el } = layout(ctx, { title: "Ham Radio Toolkit" },
    hello, install,
    infoCard({ title: t("home_utc_time") }, time, date),
    infoCard({ title: t("home_qth_locator") }, qthBody));
  // Karty APRS-IS i najbliższego przemiennika dojdą razem z serwerem APRS i bazami przemienników (etap 2)

  return { el, destroy: () => { stopClock(); unsubscribe(); } };
}
