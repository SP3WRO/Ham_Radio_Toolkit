// @ts-check
// Rejestr ekranów: trasa -> fabryka ekranu
import { homeScreen } from "./home.js";
import { propagationScreen } from "./propagation.js";
import { loggerScreen, logStatsScreen } from "./logger.js";
import { toolboxScreen, ALL_TOOL_ROUTES } from "./toolbox.js";
import { calculatorsScreen, coaxScreen } from "./calculators.js";
import { locatorScreen } from "./locator.js";
import { grayLineScreen, beaconsScreen, bandplanScreen } from "./planning.js";
import { vademecumScreen } from "./vademecum.js";
import { settingsScreen } from "./settings.js";
import { soonScreen } from "./common.js";
import { dxClusterScreen, liveSpotsScreen, activationsScreen } from "./spots.js";
import { repeatersScreen } from "./repeaters.js";
import { lookupScreen } from "./lookup.js";
import { contestsScreen } from "./contests.js";
import { awardsScreen } from "./awards.js";
import { abroadScreen } from "./abroad.js";
import { satellitesScreen } from "./satellites.js";
import { cwTrainerScreen, cwDecoderScreen } from "./cw.js";
import { aprsScreen } from "./aprs.js";
import { rttyScreen } from "./rtty.js";
import { ft8Screen } from "./ft8.js";
import { sstvScreen } from "./sstv.js";

/** @type {Record<string, (ctx: import("../app.js").ScreenContext) => import("../app.js").ScreenInstance>} */
export const SCREENS = {
  home: homeScreen,
  propagation: propagationScreen,
  dx_cluster: dxClusterScreen,
  live_spots: liveSpotsScreen,
  activations: activationsScreen,
  repeaters: repeatersScreen,
  callsign_lookup: lookupScreen,
  contest_calendar: contestsScreen,
  awards: awardsScreen,
  abroad: abroadScreen,
  satellites: satellitesScreen,
  cw_trainer: cwTrainerScreen,
  cw_decoder: cwDecoderScreen,
  aprs: aprsScreen,
  rtty_decoder: rttyScreen,
  ft8_decoder: ft8Screen,
  sstv_decoder: sstvScreen,
  logger: loggerScreen,
  log_stats: logStatsScreen,
  toolbox: toolboxScreen,
  calculators: calculatorsScreen,
  coax_calc: coaxScreen,
  locator: locatorScreen,
  gray_line_calc: grayLineScreen,
  beacons: beaconsScreen,
  bandplan: bandplanScreen,
  vademecum: vademecumScreen,
  settings: settingsScreen,
  soon: soonScreen,
};

/** Trasy z Przybornika, które nie mają jeszcze ekranu (pokazują "Wkrótce"). */
export const TOOL_ROUTES = new Set([...ALL_TOOL_ROUTES].filter((r) => !(r in SCREENS)));
