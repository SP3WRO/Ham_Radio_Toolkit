// Jednorazowo: wyciąga ścieżki ikon Material (Icons.Filled.* z aplikacji na Androida) z pakietu react-icons/md
// do public/js/icons.js, żeby PWA działała bez sieci i bez fontu z ikonami.
// Uruchomienie: node web/tools/extract-icons.mjs <ścieżka do node_modules/react-icons>
// Ikony: Google Material Design Icons, licencja Apache 2.0.
import { readFileSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const WEB = join(dirname(fileURLToPath(import.meta.url)), "..");
const pkg = process.argv[2];
if (!pkg) throw new Error("Podaj katalog react-icons");
const source = readFileSync(join(pkg, "md", "index.mjs"), "utf8");

// Nazwa w aplikacji -> nazwa w react-icons
const ICONS = {
  home: "MdHome", networkCell: "MdNetworkCell", list: "MdList", book: "MdBook", handyman: "MdHandyman",
  settings: "MdSettings", refresh: "MdRefresh", search: "MdSearch", barChart: "MdBarChart", cloudUpload: "MdCloudUpload",
  moreVert: "MdMoreVert", fileOpen: "MdFileOpen", download: "MdDownload", add: "MdAdd", delete: "MdDelete",
  clear: "MdClear", close: "MdClose", arrowBack: "MdArrowBack", expandMore: "MdExpandMore", expandLess: "MdExpandLess",
  myLocation: "MdMyLocation", fullscreen: "MdFullscreen", remove: "MdRemove", centerFocusStrong: "MdCenterFocusStrong",
  personSearch: "MdPersonSearch", park: "MdPark", radar: "MdRadar", cellTower: "MdCellTower", satelliteAlt: "MdSatelliteAlt",
  wifiTethering: "MdWifiTethering", waves: "MdWaves", keyboard: "MdKeyboard", air: "MdAir", imageSearch: "MdImageSearch",
  image: "MdImage", graphicEq: "MdGraphicEq", school: "MdSchool", emojiEvents: "MdEmojiEvents",
  workspacePremium: "MdWorkspacePremium", flight: "MdFlight", explore: "MdExplore", wbSunny: "MdWbSunny",
  tableChart: "MdTableChart", locationOn: "MdLocationOn", calculate: "MdCalculate", cable: "MdCable", public: "MdPublic",
  menuBook: "MdMenuBook", signalCellularAlt: "MdSignalCellularAlt", map: "MdMap", forum: "MdForum", moreHoriz: "MdMoreHoriz",
  translate: "MdTranslate", info: "MdInfo", language: "MdLanguage", palette: "MdPalette", person: "MdPerson",
  hiking: "MdHiking", notifications: "MdNotifications", key: "MdKey", iosShare: "MdIosShare", share: "MdShare",
  addBox: "MdAddBox", cloudOff: "MdCloudOff", construction: "MdConstruction", filterList: "MdFilterList", sync: "MdSync",
  openInNew: "MdOpenInNew", eventAvailable: "MdEventAvailable", checkCircle: "MdCheckCircle", navigation: "MdNavigation",
  radioButtonUnchecked: "MdRadioButtonUnchecked", warning: "MdWarning", star: "MdStar", starBorder: "MdStarBorder", playArrow: "MdPlayArrow", fullscreenExit: "MdFullscreenExit", stop: "MdStop", mic: "MdMic", upload: "MdUploadFile", send: "MdSend", save: "MdSave", folderOpen: "MdFolderOpen",
};

function toSvg(node) {
  const attrs = Object.entries(node.attr ?? {}).map(([k, v]) => `${k === "fillRule" ? "fill-rule" : k}="${v}"`).join(" ");
  const kids = (node.child ?? []).map(toSvg).join("");
  return `<${node.tag}${attrs ? " " + attrs : ""}>${kids}</${node.tag}>`;
}

const out = {};
for (const [name, rid] of Object.entries(ICONS)) {
  const m = new RegExp(`export function ${rid} \\(props\\) \\{\\s*return GenIcon\\((\\{.*?\\})\\)\\(props\\);`, "s").exec(source);
  if (!m) throw new Error(`Brak ikony ${rid}`);
  const tree = JSON.parse(m[1]);
  out[name] = (tree.child ?? []).map(toSvg).join("");
}

writeFileSync(
  join(WEB, "public", "js", "icons.js"),
  "// @ts-check\n// Wygenerowane przez tools/extract-icons.mjs – Google Material Design Icons (Apache 2.0)\n" +
    "/** @type {Record<string, string>} */\nexport const ICON_PATHS = " + JSON.stringify(out, null, 0).replace(/","/g, '",\n"') + ";\n"
);
console.log(`Zapisano ${Object.keys(out).length} ikon`);
