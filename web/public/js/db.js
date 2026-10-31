// @ts-check
// Dziennik łączności w IndexedDB (odpowiednik LogbookRepository/SQLite). Lista w pamięci posortowana od najnowszego.
import { Callsigns } from "./core/radio.js";

/** @typedef {import("./core/logbook.js").QsoEntry} QsoEntry */

const DB_NAME = "hrt-logbook";
const STORE = "qso";

/** @type {Promise<IDBDatabase> | null} */
let dbPromise = null;

function open() {
  dbPromise ??= new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      // Kolejne wersje schematu dodawać jako migracje, nigdy przez kasowanie magazynu z logiem
      const store = req.result.createObjectStore(STORE, { keyPath: "id" });
      store.createIndex("callsign", "callsign");
      store.createIndex("datetimeUtc", "datetimeUtc");
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

/** @param {IDBTransactionMode} mode @param {(s: IDBObjectStore) => void} fn */
async function tx(mode, fn) {
  const db = await open();
  return new Promise((resolve, reject) => {
    const t = db.transaction(STORE, mode);
    fn(t.objectStore(STORE));
    t.oncomplete = () => resolve(undefined);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  });
}

const byNewest = (a, b) => (a.datetimeUtc < b.datetimeUtc ? 1 : a.datetimeUtc > b.datetimeUtc ? -1 : 0);

/** @type {QsoEntry[]} */
let logs = [];
/** @type {Set<(l: QsoEntry[]) => void>} */
const listeners = new Set();
const emit = () => listeners.forEach((l) => l(logs));

export const logbook = {
  /** @returns {QsoEntry[]} */
  all: () => logs,
  /** @param {(l: QsoEntry[]) => void} l */
  subscribe(l) { listeners.add(l); return () => listeners.delete(l); },

  async load() {
    const db = await open();
    logs = await new Promise((resolve, reject) => {
      const req = db.transaction(STORE, "readonly").objectStore(STORE).getAll();
      req.onsuccess = () => resolve(/** @type {QsoEntry[]} */ (req.result).sort(byNewest));
      req.onerror = () => reject(req.error);
    });
    emit();
    return logs;
  },

  /** @param {QsoEntry} entry */
  async put(entry) {
    await tx("readwrite", (s) => s.put(entry));
    logs = [...logs.filter((q) => q.id !== entry.id), entry].sort(byNewest);
    emit();
  },

  /** @param {string} id */
  async remove(id) {
    await tx("readwrite", (s) => s.delete(id));
    logs = logs.filter((q) => q.id !== id);
    emit();
  },

  /** Import wielu QSO w jednej transakcji; pomija duble (ten sam znak, czas, pasmo i emisja). @param {QsoEntry[]} entries */
  async importAll(entries) {
    const seen = new Set(logs.map((q) => `${q.callsign}|${q.datetimeUtc}|${q.band}|${q.mode}`));
    const fresh = [];
    for (const e of entries) {
      const key = `${e.callsign}|${e.datetimeUtc}|${e.band}|${e.mode}`;
      if (seen.has(key)) continue;
      seen.add(key);
      fresh.push(e);
    }
    if (fresh.length) await tx("readwrite", (s) => fresh.forEach((e) => s.put(e)));
    logs = [...logs, ...fresh].sort(byNewest);
    emit();
    return fresh.length;
  },

  /** Uzupełnia kraj DXCC w QSO, które go nie mają. @param {{resolve: (c: string) => any}} cty */
  async backfillDxcc(cty) {
    const changed = [];
    for (const q of logs) {
      if (q.dxcc != null) continue;
      const e = cty.resolve(q.callsign);
      if (e) changed.push({ ...q, dxcc: e.dxcc, country: q.country || e.name });
    }
    if (!changed.length) return 0;
    await tx("readwrite", (s) => changed.forEach((e) => s.put(e)));
    const map = new Map(changed.map((e) => [e.id, e]));
    logs = logs.map((q) => map.get(q.id) ?? q);
    emit();
    return changed.length;
  },

  /** Wcześniejsze łączności z tym znakiem (bez dopisków /P, /M), najnowsze najpierw. @param {string} callsign */
  previousContacts(callsign) {
    const base = Callsigns.baseCall(callsign);
    return base ? logs.filter((q) => Callsigns.baseCall(q.callsign) === base) : [];
  },

  workedDxcc() {
    return new Set(logs.filter((q) => q.dxcc != null).map((q) => q.dxcc));
  },
};

/** Prośba o trwałe przechowywanie (iOS/Safari może inaczej usuwać dane nieużywanych stron). */
export async function requestPersistentStorage() {
  try {
    if (navigator.storage?.persist && !(await navigator.storage.persisted())) await navigator.storage.persist();
  } catch { /* nieobsługiwane */ }
}
