// @ts-check
// Ustawienia (odpowiednik UserPreferencesRepository) w localStorage, z tymi samymi kluczami i wartościami domyślnymi
import { Maidenhead, GreatCircle } from "./core/geo.js";

const KEY = "hrt.settings.v1";

export const DEFAULTS = {
  callsign: "NOCALL",
  user_locator: "",
  app_theme: "system",
  language: "",
  refresh_interval: 0,
  tropocast_area: "poland",
  active_programs: ["POTA", "SOTA", "WWFF"],
  aprs_ssid: "-7",
  aprs_icon: "/>",
  aprs_comment: "HamRadioToolkit",
  aprs_radius_km: 50,
  repeater_radius_km: 50,
  repeater_data_source: "przemienniki_net",
  last_known_lat: /** @type {number | null} */ (null),
  last_known_lon: /** @type {number | null} */ (null),
  install_hint_dismissed: false,
  contests_show_russian: false,
  followed_awards: /** @type {string[]} */ ([]),
};

/** @typedef {typeof DEFAULTS} Settings */

/** @type {Settings} */
let state = { ...DEFAULTS, ...read() };
// Etap 1 zapisywał źródło "świat" jako "world" – w aplikacji to "international"
if (/** @type {string} */ (state.repeater_data_source) === "world") state.repeater_data_source = "international";
/** @type {Set<(s: Settings) => void>} */
const listeners = new Set();

function read() {
  try {
    return JSON.parse(localStorage.getItem(KEY) ?? "{}");
  } catch {
    return {};
  }
}

export const settings = {
  get: () => state,
  /** @param {Partial<Settings>} patch */
  set(patch) {
    state = { ...state, ...patch };
    try { localStorage.setItem(KEY, JSON.stringify(state)); } catch { /* prywatne okno – tylko w pamięci */ }
    listeners.forEach((l) => l(state));
  },
  /** @param {(s: Settings) => void} l */
  subscribe(l) { listeners.add(l); return () => listeners.delete(l); },
};

/** Pozycja stacji: lokator wpisany ręcznie albo ostatnia pozycja z GPS (jak stationPosition w aplikacji). */
export function stationPosition() {
  const loc = Maidenhead.toLatLon(state.user_locator);
  if (loc) return loc;
  if (state.last_known_lat != null && state.last_known_lon != null) return { lat: state.last_known_lat, lon: state.last_known_lon };
  return null;
}

/** Zapis ostatniej pozycji co ≥ 200 m. @param {{lat: number, lon: number}} p */
export function saveLastKnownPosition(p) {
  const last = state.last_known_lat != null && state.last_known_lon != null ? { lat: state.last_known_lat, lon: state.last_known_lon } : null;
  if (!last || GreatCircle.distanceKm(last, p) > 0.2) settings.set({ last_known_lat: p.lat, last_known_lon: p.lon });
}

// ---------------------------------------------------------------- konta w serwisach (CredentialsRepository)
// Osobno od ustawień; zostają tylko w tej przeglądarce. Serwer dostaje je wyłącznie na czas zapytania.
const CRED_KEY = "hrt.credentials.v1";
export const CREDENTIAL_DEFAULTS = {
  hamQthUser: "", hamQthPassword: "", qrzcqUser: "", qrzcqPassword: "", qrzLogbookKey: "", eqslUser: "", eqslPassword: "",
  repeaterBookToken: "", repeaterBookContact: "",
};
/** @typedef {typeof CREDENTIAL_DEFAULTS} Credentials */

export const credentials = {
  /** @returns {Credentials} */
  get() {
    try { return { ...CREDENTIAL_DEFAULTS, ...JSON.parse(localStorage.getItem(CRED_KEY) ?? "{}") }; } catch { return { ...CREDENTIAL_DEFAULTS }; }
  },
  /** @param {Partial<Credentials>} patch */
  set(patch) {
    const next = { ...this.get(), ...patch };
    try { localStorage.setItem(CRED_KEY, JSON.stringify(next)); } catch { /* prywatne okno */ }
  },
  hasHamQth() { const c = this.get(); return !!(c.hamQthUser.trim() && c.hamQthPassword); },
  hasQrzcq() { const c = this.get(); return !!(c.qrzcqUser.trim() && c.qrzcqPassword); },
};
