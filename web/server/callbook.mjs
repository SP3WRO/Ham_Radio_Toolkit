// Wyszukiwarka znaków po stronie serwera: rejestr UKE (raz w tygodniu), HamDB, RadioID, HamQTH i QRZCQ (sesje
// logowania w pamięci), PSK Reporter (najwyżej jedno zapytanie na 5 minut dla całego serwera – prośba autora serwisu).
// Hasła przychodzą w treści zapytania POST i nie są nigdzie zapisywane ani logowane.
import {
  parseUkeIndividuals, parseUkeClubs, parseRadioIdUser, parseHamDb, parseQrzcq, parseHamQth, parsePskReporter, xmlTag,
} from "../public/js/core/callbook.js";

const SOURCE_TIMEOUT = 12000;
const UKE_MAX_AGE = 7 * 24 * 3600 * 1000;
const UKE_RETRY = 10 * 60 * 1000;
const PSK_INTERVAL = 5 * 60 * 1000;
const PSK_CACHE = 60 * 60 * 1000;

async function get(url, ua, accept = "*/*") {
  const res = await fetch(url, { headers: { "User-Agent": ua, Accept: accept }, signal: AbortSignal.timeout(10000) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res;
}

// ---------------------------------------------------------------- rejestr UKE
/** @type {Map<string, any> | null} */
let uke = null;
let ukeLoaded = 0, ukeFailed = 0;
/** @type {Promise<void> | null} */
let ukeLoading = null;

async function loadUke(ua) {
  // Eksport UKE jest w Windows-1250, nie w UTF-8
  const decode = async (url) => {
    const buf = await (await get(url, ua)).arrayBuffer();
    const text = new TextDecoder("windows-1250").decode(buf);
    if (!text.startsWith("number;")) throw new Error("Nieoczekiwany format");
    return text;
  };
  const [ind, clubs] = await Promise.all([
    decode("https://amator.uke.gov.pl/pl/individuals/export.csv"),
    decode("https://amator.uke.gov.pl/pl/clubs/export.csv").catch(() => ""),
  ]);
  const map = new Map();
  // Ten sam znak może mieć kilka pozwoleń (np. po przedłużeniu) – liczy się najdłużej ważne
  for (const l of [...parseUkeIndividuals(ind), ...(clubs ? parseUkeClubs(clubs) : [])]) {
    const e = map.get(l.callsign);
    if (!e || l.validTo > e.validTo) map.set(l.callsign, l);
  }
  if (!map.size) throw new Error("Pusty rejestr");
  uke = map;
  ukeLoaded = Date.now();
  console.log(`UKE: ${map.size} pozwoleń`);
}

/** Pozwolenie dla znaku; przy pierwszym pobieraniu czekamy najwyżej 4 s (następne wyszukiwanie już je dostanie). */
async function ukeFind(call, ua) {
  if ((!uke || Date.now() - ukeLoaded > UKE_MAX_AGE) && !ukeLoading && Date.now() - ukeFailed > UKE_RETRY) {
    ukeLoading = loadUke(ua).catch((e) => { ukeFailed = Date.now(); console.warn(`UKE: ${e.message}`); }).finally(() => { ukeLoading = null; });
  }
  if (!uke && ukeLoading) await Promise.race([ukeLoading, new Promise((r) => setTimeout(r, 4000))]);
  return uke?.get(call) ?? null;
}

// ---------------------------------------------------------------- HamQTH i QRZCQ (sesje)
/** @type {Map<string, string>} */
const hamQthSessions = new Map();
async function hamQth(user, password, call, ua) {
  const base = "https://www.hamqth.com/xml.php";
  const login = async () => {
    const xml = await (await get(`${base}?u=${encodeURIComponent(user)}&p=${encodeURIComponent(password)}`, ua)).text();
    const id = xmlTag(xml, "session_id");
    if (!id) throw new Error(xmlTag(xml, "error") || "logowanie nieudane");
    hamQthSessions.set(user, id);
    return id;
  };
  const query = async (id) => (await get(`${base}?id=${encodeURIComponent(id)}&callsign=${encodeURIComponent(call)}&prg=HamRadioToolkit`, ua)).text();
  let xml = await query(hamQthSessions.get(user) ?? (await login()));
  let error = xmlTag(xml, "error");
  if (/expired|does not exist/i.test(error)) { xml = await query(await login()); error = xmlTag(xml, "error"); }
  if (/not found/i.test(error)) return null;
  if (error) throw new Error(error);
  return parseHamQth(xml);
}

/** @type {Map<string, string>} */
const qrzcqSessions = new Map();
/** @type {Map<string, [number, string]>} */
const qrzcqLoginErrors = new Map();
async function qrzcq(user, password, call, ua) {
  const base = "https://ssl.qrzcq.com/xml";
  const err = qrzcqLoginErrors.get(user);
  // Po błędnym loginie albo braku Premium nie próbujemy przez godzinę
  if (err && Date.now() - err[0] < 3600 * 1000) throw new Error(err[1]);
  const login = async () => {
    const xml = await (await get(`${base}?username=${encodeURIComponent(user)}&password=${encodeURIComponent(password)}&agent=HamRadioToolkit`, ua)).text();
    const key = xmlTag(xml, "Key");
    if (!key) {
      const e = xmlTag(xml, "Error");
      const message = !e ? "logowanie nieudane" : /premium/i.test(e) ? "konto wymaga pakietu Premium" : e;
      qrzcqSessions.delete(user);
      qrzcqLoginErrors.set(user, [Date.now(), message]);
      throw new Error(message);
    }
    qrzcqSessions.set(user, key);
    qrzcqLoginErrors.delete(user);
    return key;
  };
  const query = async (key) => (await get(`${base}?s=${encodeURIComponent(key)}&callsign=${encodeURIComponent(call)}&agent=HamRadioToolkit`, ua)).text();
  let xml = await query(qrzcqSessions.get(user) ?? (await login()));
  let error = xmlTag(xml, "Error");
  if (!error) return parseQrzcq(xml);
  if (/^not found/i.test(error)) return null;
  if (/session|key/i.test(error)) {
    xml = await query(await login());
    error = xmlTag(xml, "Error");
    if (!error) return parseQrzcq(xml);
    if (/^not found/i.test(error)) return null;
  }
  throw new Error(error);
}

// ---------------------------------------------------------------- PSK Reporter
let pskLast = 0;
/** @type {Map<string, [number, any]>} */
const pskCache = new Map();
async function psk(call, ua) {
  const now = Date.now();
  const hit = pskCache.get(call);
  if (hit && now - hit[0] < PSK_CACHE) return hit[1] ? { status: "found", spot: hit[1] } : { status: "notHeard" };
  const wait = PSK_INTERVAL - (now - pskLast);
  if (wait > 0) return { status: "rate", waitMinutes: Math.ceil(wait / 60000) };
  pskLast = now;
  try {
    const url = `https://retrieve.pskreporter.info/query?senderCallsign=${encodeURIComponent(call)}&flowStartSeconds=-86400&rronly=1&noactive=1&rptlimit=50`;
    const spot = parsePskReporter(await (await get(url, ua)).text(), call);
    pskCache.set(call, [now, spot]);
    return spot ? { status: "found", spot } : { status: "notHeard" };
  } catch (e) {
    return { status: "failed", message: String(e?.message ?? e) };
  }
}

// ---------------------------------------------------------------- całe wyszukiwanie
/** Źródło z limitem czasu; błąd zamienia na null (i notatkę, jeśli [notes]). */
async function source(name, notes, fn) {
  try {
    return await Promise.race([fn(), new Promise((_, rej) => setTimeout(() => rej(new Error("brak odpowiedzi")), SOURCE_TIMEOUT))]);
  } catch (e) {
    notes?.push(`${name}: ${e?.message ?? "błąd połączenia"}`);
    return null;
  }
}

/**
 * @param {{call: string, base: string, online: boolean, uke: boolean, hamDb: boolean, psk: boolean,
 *   hamQthUser?: string, hamQthPassword?: string, qrzcqUser?: string, qrzcqPassword?: string}} q
 */
export async function lookup(q, ua) {
  const call = String(q.call ?? "").trim().toUpperCase();
  const base = String(q.base ?? call).trim().toUpperCase();
  if (!/^[A-Z0-9/]{3,15}$/.test(call) || !/^[A-Z0-9]{3,10}$/.test(base)) throw new Error("Zły znak");
  const notes = [];
  const hasHamQth = !!(q.hamQthUser?.trim() && q.hamQthPassword);
  const hasQrzcq = !!(q.qrzcqUser?.trim() && q.qrzcqPassword);
  const [ukeR, hamDbR, radioIdR, hamQthR, qrzcqR, pskR] = await Promise.all([
    q.uke ? source("Rejestr UKE", null, () => ukeFind(base, ua)) : null,
    q.online && q.hamDb ? source("HamDB", null, async () => parseHamDb(await (await get(`https://api.hamdb.org/${base}/json/HamRadioToolkit`, ua)).text())) : null,
    q.online ? source("RadioID.net", null, async () => parseRadioIdUser(await (await get(`https://radioid.net/api/dmr/user/?callsign=${base}`, ua)).text(), base)) : null,
    q.online && hasHamQth ? source("HamQTH", notes, () => hamQth(q.hamQthUser.trim(), q.hamQthPassword, base, ua)) : null,
    q.online && hasQrzcq ? source("QRZCQ", notes, () => qrzcq(q.qrzcqUser.trim(), q.qrzcqPassword, base, ua)) : null,
    q.online && q.psk ? psk(call, ua) : null,
  ]);
  return { uke: ukeR, hamDb: hamDbR, radioId: radioIdR, hamQth: hamQthR, qrzcq: qrzcqR, psk: pskR, notes };
}
