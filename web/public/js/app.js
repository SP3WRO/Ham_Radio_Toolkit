// @ts-check
// Start aplikacji: język, motyw, dolne menu i nawigacja (odpowiednik MainActivity/HamRadioToolkitApp)
import "./compat.js";
import { h, icon, toast, replace } from "./ui.js";
import { loadLanguage, t } from "./i18n.js";
import { settings } from "./store.js";
import { logbook, requestPersistentStorage } from "./db.js";
import { cty, location } from "./services.js";
import { SCREENS, TOOL_ROUTES } from "./screens/index.js";

/** @typedef {{navigate: (route: string) => void, back: () => void, params: string[], isTab: boolean}} ScreenContext */
/** @typedef {{el: HTMLElement, destroy?: () => void}} ScreenInstance */

const TABS = [
  { route: "home", label: "tab_home", icon: "home" },
  { route: "propagation", label: "tab_propagation", icon: "networkCell" },
  { route: "dx_cluster", label: "tab_dx_cluster", icon: "list" },
  { route: "logger", label: "tab_logger", icon: "book" },
  { route: "toolbox", label: "tab_toolbox", icon: "handyman" },
];
// Ekrany otwierane z zakładek – podświetlają swoją zakładkę na dolnym pasku
const LOGGER_SUBROUTES = new Set(["log_stats"]);
const parentTab = (route) => (TABS.some((x) => x.route === route) ? route : LOGGER_SUBROUTES.has(route) ? "logger" : route === "settings" ? null : "toolbox");

const screenHost = /** @type {HTMLElement} */ (document.getElementById("screen"));
const nav = /** @type {HTMLElement} */ (document.getElementById("nav"));
/** @type {ScreenInstance | null} */
let current = null;
let currentRoute = "";

function parseHash() {
  const parts = location_hash().replace(/^#\/?/, "").split("/").filter(Boolean).map(decodeURIComponent);
  return { route: parts[0] || "home", params: parts.slice(1) };
}
const location_hash = () => window.location.hash;

/** @param {string} route np. "vademecum/qcodes" */
function navigate(route) {
  window.location.hash = `#/${route}`;
}

function back() {
  // Pierwszy ekran po otwarciu (np. z zakładki) – wracamy do zakładki-rodzica zamiast wychodzić z aplikacji
  if (history.length > 1 && sessionStorage.getItem("hrt.navigated")) history.back();
  else navigate(parentTab(currentRoute) ?? "home");
}

function renderNav(active) {
  replace(nav, TABS.map((tab) =>
    h("button", { type: "button", class: `nav-item ${tab.route === active ? "active" : ""}`, "aria-current": tab.route === active ? "page" : null,
      onclick: () => {
        // Dotknięcie zakładki w jej pod-ekranie wraca do jej głównego widoku (jak openTab w aplikacji)
        if (currentRoute !== tab.route) navigate(tab.route);
        else screenHost.querySelector(".scroll")?.scrollTo({ top: 0, behavior: "smooth" });
      } },
      h("span", { class: "nav-pill" }, icon(tab.icon)), h("span", { class: "label" }, t(tab.label)))));
}

function show() {
  const { route, params } = parseHash();
  const factory = SCREENS[route] ?? (TOOL_ROUTES.has(route) ? SCREENS.soon : SCREENS.home);
  current?.destroy?.();
  currentRoute = route;
  const isTab = TABS.some((x) => x.route === route);
  current = factory({ navigate, back, params: route in SCREENS ? params : [route], isTab });
  screenHost.replaceChildren(current.el);
  renderNav(parentTab(route));
}

function applyTheme() {
  const theme = settings.get().app_theme;
  if (theme === "light" || theme === "dark") document.documentElement.dataset.theme = theme;
  else delete document.documentElement.dataset.theme;
  const dark = theme === "dark" || (theme !== "light" && matchMedia("(prefers-color-scheme: dark)").matches);
  document.querySelectorAll('meta[name="theme-color"]').forEach((m) => m.setAttribute("content", dark ? "#111318" : "#f8f9ff"));
}

async function start() {
  applyTheme();
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", applyTheme);
  await loadLanguage(settings.get().language);
  document.title = "Ham Radio Toolkit";

  let lastLanguage = settings.get().language;
  let lastTheme = settings.get().app_theme;
  settings.subscribe(async (s) => {
    if (s.app_theme !== lastTheme) { lastTheme = s.app_theme; applyTheme(); }
    if (s.language !== lastLanguage) {
      lastLanguage = s.language;
      await loadLanguage(s.language);
      show();
    }
  });

  window.addEventListener("hashchange", () => {
    sessionStorage.setItem("hrt.navigated", "1");
    // Okna dialogowe należą do poprzedniego ekranu (np. gest „wstecz” w Safari)
    document.querySelectorAll("dialog.dialog").forEach((d) => d.dispatchEvent(new Event("cancel")));
    show();
  });
  show();

  // Dziennik i baza DXCC w tle; stare wpisy dostają kraj, gdy tylko baza będzie dostępna
  logbook.load().then(() => cty()).then((db) => logbook.backfillDxcc(db)).catch((e) => console.warn("Log/DXCC", e));
  requestPersistentStorage();
  // Safari na iPhonie przewija stronę, żeby pokazać pole nad klawiaturą, i po jej schowaniu zostawia przesunięty układ
  document.addEventListener("focusout", () => setTimeout(() => {
    const a = document.activeElement;
    if ((!a || a === document.body) && (window.scrollY || document.documentElement.scrollTop)) window.scrollTo(0, 0);
  }, 100));
  // Pozycja: pytamy dopiero po geście użytkownika (Safari), chyba że zgoda już jest
  location.alreadyGranted().then((granted) => { if (granted) location.start(); });

  registerServiceWorker();
}

function registerServiceWorker() {
  if (!navigator.serviceWorker || !window.isSecureContext) return;
  let reloading = false;
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    if (!reloading) { reloading = true; window.location.reload(); }
  });
  navigator.serviceWorker.register("sw.js").then((reg) => {
    // Sprawdzanie aktualizacji przy każdym powrocie do aplikacji (na iPhonie PWA rzadko jest zamykana)
    document.addEventListener("visibilitychange", () => { if (!document.hidden) reg.update().catch(() => {}); });
    reg.addEventListener("updatefound", () => {
      const worker = reg.installing;
      worker?.addEventListener("statechange", () => {
        if (worker.state === "installed" && navigator.serviceWorker.controller) {
          toast(t("web_update_ready"), 15000, { label: t("web_reload"), onclick: () => worker.postMessage("skipWaiting") });
        }
      });
    });
  }).catch((e) => console.warn("Service worker", e));
}

start().catch((e) => {
  console.error(e);
  screenHost.replaceChildren(h("div", { class: "centered" }, String(e)));
});
