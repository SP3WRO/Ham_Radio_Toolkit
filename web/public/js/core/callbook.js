// @ts-check
// Wyszukiwarka znaków: parsery źródeł i łączenie wyników – port data/callbook/*.kt i HamQthClient.kt.
// Bez DOM i bez sieci – parserów używa serwer, łączenia (z logiem i bazą DXCC) telefon.
import { Maidenhead } from "./geo.js";

// Pełny znak: cyfra w środku, litera na końcu (pomijamy niedokończone wpisy typu "SP3")
const COMPLETE_CALL = /^[A-Z0-9]{1,4}[0-9][A-Z0-9]*[A-Z]$/;

/** Znak bez dodatków /P, /M, SP/… – najdłuższa część z cyfrą i literą. @param {string} call */
export function cbBaseCall(call) {
  const parts = call.split("/").filter((p) => /\d/.test(p) && /[A-Za-z]/.test(p));
  return parts.length ? parts.reduce((a, b) => (b.length > a.length ? b : a)) : call;
}

/** Czy znak wygląda na kompletny – dopiero wtedy warto pytać serwisy w sieci. @param {string} callsign */
export const isCompleteCall = (callsign) => COMPLETE_CALL.test(cbBaseCall(callsign.trim().toUpperCase()));

/** Kraje DXCC obecne w HamDB: USA z terytoriami, Kanada, Niemcy, Czechy, Australia. */
export const HAMDB_DXCC = new Set([291, 6, 110, 202, 285, 9, 103, 166, 105, 1, 230, 503, 150]);

const validGrid = (v) => { const g = String(v ?? "").trim().toUpperCase(); return g.length >= 4 && Maidenhead.isValid(g) ? g : ""; };

/** Zawartość znacznika XML (z odkodowaniem encji). @param {string} xml @param {string} name */
export function xmlTag(xml, name) {
  const m = new RegExp(`<${name}>([\\s\\S]*?)</${name}>`).exec(xml);
  if (!m) return "";
  return m[1].trim().replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, "&");
}

// ---------------------------------------------------------------- rejestr UKE (CSV z ";", Windows-1250)
/** Wiersz CSV z separatorem ";" (pola mogą być w cudzysłowach). @param {string} line */
export function splitSemicolonLine(line) {
  const fields = [];
  let sb = "", quoted = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (quoted && ch === '"' && line[i + 1] === '"') { sb += '"'; i++; }
    else if (ch === '"') quoted = !quoted;
    else if (ch === ";" && !quoted) { fields.push(sb); sb = ""; }
    else sb += ch;
  }
  fields.push(sb);
  return fields;
}
const rows = (csv) => csv.split("\n").slice(1).filter((l) => l.trim()).map((l) => splitSemicolonLine(l.replace(/\r$/, "")));

/** Kluby mają miejscowości WIELKIMI LITERAMI ("STAROGARD GDAŃSKI"). @param {string} s */
export function toTitleCase(s) {
  if (s !== s.toUpperCase()) return s;
  let out = "", start = true;
  for (const ch of s) { out += start ? ch.toUpperCase() : ch.toLowerCase(); start = ch === " " || ch === "-"; }
  return out;
}

/** number;valid_to;call_sign;category;transmitter_power;station_location @param {string} csv */
export function parseUkeIndividuals(csv) {
  return rows(csv).flatMap((f) => (f.length < 6 || !f[2].trim() ? [] : [{
    callsign: f[2].trim().toUpperCase(), number: f[0], validTo: f[1], category: f[3], powerW: /^\d+$/.test(f[4]) ? Number(f[4]) : null,
    stationCity: f[5].trim(), stationStreet: "", clubName: "", position: null, isClub: false,
  }]));
}

/** Kluby: …;station_city;station_street;station_house;station_number;lat;lng @param {string} csv */
export function parseUkeClubs(csv) {
  return rows(csv).flatMap((f) => {
    if (f.length < 19 || !f[2].trim()) return [];
    const street = [f[16], f[17]].filter((x) => x.trim()).join(" ") + (f[18].trim() ? `/${f[18]}` : "");
    const lat = Number(f[19]), lon = Number(f[20]);
    const hasPos = f[19]?.trim() && f[20]?.trim() && Number.isFinite(lat) && Number.isFinite(lon) && (lat !== 0 || lon !== 0);
    return [{
      callsign: f[2].trim().toUpperCase(), number: f[0], validTo: f[1], category: f[3], powerW: /^\d+$/.test(f[4]) ? Number(f[4]) : null,
      stationCity: toTitleCase(f[15].trim()), stationStreet: street.trim(), clubName: f[10].trim() || f[5].trim(),
      position: hasPos ? { lat, lon } : null, isClub: true,
    }];
  });
}

// ---------------------------------------------------------------- serwisy online
/** RadioID.net (dmr/user). @param {string} text @param {string} callsign */
export function parseRadioIdUser(text, callsign) {
  const results = JSON.parse(text)?.results;
  if (!Array.isArray(results)) return null;
  const s = (o, k) => (o[k] == null || typeof o[k] === "object" ? "" : String(o[k]).trim());
  const row = results.find((o) => s(o, "callsign").toUpperCase() === callsign.trim().toUpperCase());
  if (!row) return null;
  const name = [s(row, "fname"), s(row, "surname")].filter(Boolean).join(" ") || s(row, "name");
  return { callsign: s(row, "callsign").toUpperCase(), name, city: s(row, "city"), state: s(row, "state"), country: s(row, "country") };
}

/** HamDB.org. @param {string} text */
export function parseHamDb(text) {
  const root = JSON.parse(text)?.hamdb;
  if (!root || root.messages?.status !== "OK" || !root.callsign) return null;
  const c = root.callsign;
  const field = (k) => { const v = c[k] == null ? "" : String(c[k]).trim(); return v === "NOT_FOUND" ? "" : v; };
  const call = field("call").toUpperCase();
  if (!call) return null;
  return {
    callsign: call, name: [field("fname"), field("mi"), field("name"), field("suffix")].filter(Boolean).join(" "),
    qth: [field("addr2"), field("state")].filter(Boolean).join(", "), gridSquare: validGrid(field("grid")),
    licenceClass: field("class"), expires: field("expires"), country: field("country"),
  };
}

/** QRZCQ.com (XML zgodny z QRZ.com). @param {string} xml */
export function parseQrzcq(xml) {
  const block = /<Callsign>([\s\S]*?)<\/Callsign>/.exec(xml)?.[1];
  if (block == null) return null;
  const call = xmlTag(block, "call").toUpperCase();
  if (!call) return null;
  return { callsign: call, name: xmlTag(block, "name"), qth: xmlTag(block, "qth") || xmlTag(block, "city"), gridSquare: validGrid(xmlTag(block, "locator") || xmlTag(block, "grid")) };
}

/** HamQTH (xml.php). @param {string} xml */
export function parseHamQth(xml) {
  const search = /<search>([\s\S]*?)<\/search>/.exec(xml)?.[1];
  if (search == null) return null;
  const tg = (n) => xmlTag(search, n);
  const numOrNull = (v) => (v.trim() && Number.isFinite(Number(v)) ? Number(v) : null);
  return {
    callsign: tg("callsign").toUpperCase(), name: tg("adr_name") || tg("nick"), qth: tg("qth") || tg("adr_city"), gridSquare: tg("grid").toUpperCase(),
    country: tg("country"), dxcc: numOrNull(tg("adif")), qslVia: tg("qsl_via"), qslBureau: tg("qsl"), qslDirect: tg("qsldirect"), lotw: tg("lotw"),
    eqsl: tg("eqsl"), web: tg("web"), cqZone: tg("cq"), ituZone: tg("itu"), continent: tg("continent"),
    latitude: numOrNull(tg("latitude")), longitude: numOrNull(tg("longitude")),
  };
}

/**
 * PSK Reporter: najnowszy lokator nadawany przez [call]. Gdy najnowszy raport ma lokator 4-znakowy, a inny podaje
 * 6-znakowy w tym samym kwadracie – bierzemy dokładniejszy (z czasem najnowszego).
 * @param {string} xml @param {string} call
 */
export function parsePskReporter(xml, call) {
  const spots = [];
  for (const m of xml.matchAll(/<receptionReport\b([^>]*)\/?>/g)) {
    /** @type {Record<string, string>} */
    const a = {};
    for (const x of m[1].matchAll(/(\w+)="([^"]*)"/g)) a[x[1]] = x[2];
    const locator = (a.senderLocator ?? "").trim().toUpperCase();
    if ((a.senderCallsign ?? "").toUpperCase() !== call.toUpperCase() || locator.length < 4 || !Maidenhead.isValid(locator)) continue;
    spots.push({ locator, timeSeconds: Number(a.flowStartSeconds) || 0 });
  }
  if (!spots.length) return null;
  const latest = spots.reduce((a, b) => (b.timeSeconds > a.timeSeconds ? b : a));
  const finer = spots.filter((s) => s.locator.length > latest.locator.length && s.locator.startsWith(latest.locator.slice(0, 4)))
    .reduce((a, b) => (!a || b.timeSeconds > a.timeSeconds ? b : a), /** @type {{locator: string, timeSeconds: number} | null} */ (null));
  return finer ? { ...finer, timeSeconds: latest.timeSeconds } : latest;
}

// ---------------------------------------------------------------- łączenie wyników (Callbook.fetch)
/**
 * @param {{call: string, entity: any, homeEntity: any, data: any, lastLogged: any, hamQthConfigured: boolean, now?: number}} o
 * @param {(key: string, ...args: any[]) => string} t tłumaczenia
 */
export function combineCallbook(o, t) {
  const { call, entity, homeEntity, data, lastLogged } = o;
  const now = o.now ?? Date.now();
  const base = cbBaseCall(call);
  // SP/DL1ABC, DL1ABC/SP: stacja pracuje poza krajem pozwolenia – jej domowy lokator nie pasuje
  const abroad = entity != null && homeEntity != null && entity.dxcc !== homeEntity.dxcc;
  const ago = (sec) => {
    const min = Math.max(0, Math.floor((now / 1000 - sec) / 60));
    return min < 1 ? t("cb_just_now") : min < 60 ? t("cb_minutes_ago", min) : t("cb_hours_ago", Math.floor(min / 60));
  };
  const { uke = null, hamDb = null, radioId = null, hamQth = null, qrzcq = null, psk = null } = data ?? {};
  const notes = [...(data?.notes ?? [])];
  /** @type {{locator: string, source: string, fromPsk?: boolean} | null} */
  let grid = null;
  if (psk?.status === "found") grid = { locator: psk.spot.locator, source: t("cb_src_psk", ago(psk.spot.timeSeconds)), fromPsk: true };
  else if (lastLogged) grid = { locator: lastLogged.gridSquare.toUpperCase(), source: t("cb_src_log", lastLogged.datetimeUtc) };
  else if (!abroad && qrzcq?.gridSquare) grid = { locator: qrzcq.gridSquare, source: t("cb_src_operator", "QRZCQ") };
  else if (!abroad && hamQth && hamQth.gridSquare.length >= 4 && Maidenhead.isValid(hamQth.gridSquare)) grid = { locator: hamQth.gridSquare.toUpperCase(), source: t("cb_src_operator", "HamQTH") };
  else if (!abroad && hamDb?.gridSquare) grid = { locator: hamDb.gridSquare, source: t("cb_src_hamdb") };
  if (!grid) {
    if (psk?.status === "rate") notes.push(t("cb_psk_wait", psk.waitMinutes));
    else if (psk?.status === "failed") notes.push(`PSK Reporter: ${psk.message}`);
  }
  if (abroad) notes.push(t("cb_abroad"));
  else if (call !== base) notes.push(t("cb_suffix", call.slice(call.lastIndexOf("/") + 1)));
  const name = [hamQth?.name, qrzcq?.name, hamDb?.name, radioId?.name, uke?.clubName].find((s) => s?.trim()) ?? "";
  const qth = [hamQth?.qth, qrzcq?.qth, hamDb?.qth, uke ? [uke.stationStreet, uke.stationCity].filter((s) => s.trim()).join(", ") : "",
    radioId ? [radioId.city, radioId.state].filter((s) => s.trim()).join(", ") : ""].find((s) => s?.trim()) ?? "";
  const sources = [uke ? "rejestr UKE" : null, hamDb ? "HamDB" : null, qrzcq ? "QRZCQ" : null, hamQth ? "HamQTH" : null, radioId ? "RadioID.net" : null].filter(Boolean);
  return { callsign: call, entity, name, qth, grid, uke, hamDb, qrzcq, radioId, hamQth, hamQthConfigured: o.hamQthConfigured, notes, sources };
}
