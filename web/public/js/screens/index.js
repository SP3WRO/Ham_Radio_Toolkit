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

/** @type {Record<string, (ctx: import("../app.js").ScreenContext) => import("../app.js").ScreenInstance>} */
export const SCREENS = {
  home: homeScreen,
  propagation: propagationScreen,
  dx_cluster: (ctx) => soonScreen({ ...ctx, params: ["dx_cluster"] }),
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
