// @ts-check
// Kalendarz zawodów (ContestCalendarScreen.kt): SP9CXN, PK-UKF / SP1CNV i QRZ.RU przez serwer aplikacji, zapasowo kalendarz wbudowany
import { h, replace, iconButton, chip, switchEl, icon } from "../ui.js";
import { t, plural, currentLanguage, uiLocale } from "../i18n.js";
import { settings } from "../store.js";
import { apiUrl } from "../services.js";
import { PhraseLocalizer } from "../core/world.js";
import { builtinOccurrences, cyclicNames, contestKey, contestIcs, CONTEST_SOURCES, DAY_MS, HOUR_MS } from "../core/contests.js";
import { layout } from "./common.js";

/** @typedef {import("../core/contests.js").ContestEntry} ContestEntry */

const CACHE_KEY = "hrt.contests.v1";
const QRP = /\bQRP\b/i;
const FILTERS = [["all", "common_all"], ["polish", "ct_filter_polish"], ["international", "ct_filter_international"], ["vhf", "ct_vhf"],
  ["cw", "ct_mode_cw"], ["ssb", "ct_mode_ssb"], ["digi", "ct_filter_digi"], ["qrp", "ct_qrp"]];

/** @type {Promise<[string, string][]> | null} */
let phrases = null;
const loadPhrases = () => (phrases ??= fetch("data/bandplan.json").then((r) => r.json()).then((d) => d.contestPhrases ?? []).catch(() => []));

/** @returns {{entries: ContestEntry[], statuses: any[], time: number} | null} */
function readCache() {
  try { return JSON.parse(localStorage.getItem(CACHE_KEY) ?? "null"); } catch { return null; }
}

/** @param {number} ms */
export function durationText(ms) {
  const total = Math.max(0, Math.floor(ms / 60_000));
  const days = Math.floor(total / 1440), hours = Math.floor(total / 60) % 24, minutes = total % 60;
  if (days > 0) return `${plural("dur_days", days, days)} ${t("dur_hours", hours)}`;
  if (hours > 0) return t("dur_hours_minutes", hours, minutes);
  return t("dur_minutes", minutes);
}

/** @param {number} startMs @param {number} endMs @param {boolean} utc */
function rangeText(startMs, endMs, utc) {
  const f = new Intl.DateTimeFormat(uiLocale(), { weekday: "short", day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false, timeZone: utc ? "UTC" : undefined });
  // Koniec jak w kalendarzach zawodów: 23:59 zamiast 00:00 dnia następnego
  const end = endMs % HOUR_MS === 0 ? endMs - 60_000 : endMs;
  return `${f.format(startMs)} – ${f.format(end)}${utc ? " UTC" : ""}`;
}

/** @param {ContestEntry} c */
const modesText = (c) => ["CW", "SSB", "RTTY", "DIGI"].filter((m) => c.modes.includes(m)).map((m) => (m === "DIGI" ? t("ct_filter_digi") : m)).join(" • ");

/** @param {import("../app.js").ScreenContext} ctx */
export function contestsScreen(ctx) {
  let filter = "all";
  let showWeekly = false;
  let loading = false;
  let offline = false;
  /** @type {ContestEntry[]} */
  let entries = [];
  /** @type {any[]} */
  let statuses = [];
  /** @type {string | null} */
  let expanded = null;
  /** @type {(s: string) => string} */
  let L = (s) => s;
  const host = h("div", { class: "scroll" });
  const refreshBtn = /** @type {HTMLButtonElement} */ (iconButton("refresh", t("common_refresh"), () => load(true)));

  async function load(force = false) {
    const cached = readCache();
    if (cached && !force && Date.now() - cached.time < 12 * HOUR_MS) return apply(cached.entries, cached.statuses, false);
    if (cached) apply(cached.entries, cached.statuses, false);
    loading = true;
    refreshBtn.disabled = true;
    render();
    try {
      const res = await fetch(apiUrl(`/contests${force ? "?refresh=1" : ""}`), { signal: AbortSignal.timeout(45000) });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      if (data.entries?.length) {
        try { localStorage.setItem(CACHE_KEY, JSON.stringify({ entries: data.entries, statuses: data.statuses, time: Date.now() })); } catch { /* pełna pamięć */ }
      }
      apply(data.entries ?? [], data.statuses ?? [], false);
    } catch {
      // Bez sieci: zapisana kopia, a gdy jej brak – kalendarz wbudowany
      if (!cached) apply([], [], true);
    } finally {
      loading = false;
      refreshBtn.disabled = false;
      render();
    }
  }

  /** @param {ContestEntry[]} list @param {any[]} st @param {boolean} off */
  function apply(list, st, off) {
    offline = off || !list.length;
    entries = offline ? builtin(Date.now()) : list;
    statuses = st;
    render();
  }

  /** @param {number} now */
  const builtin = (now) => builtinOccurrences(now - HOUR_MS, now + 365 * DAY_MS)
    .filter((o) => !o.id.startsWith("cwt") || o.startMs <= now + 7 * DAY_MS);

  function statusText() {
    if (offline) return t("ct_offline");
    const f = new Intl.DateTimeFormat(uiLocale(), { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false });
    return statuses.map((s) => {
      const label = CONTEST_SOURCES[/** @type {keyof typeof CONTEST_SOURCES} */ (s.source)]?.label ?? s.source;
      if (s.fetchedAt == null) return t("ct_unavailable", label);
      if (s.fromCache) return t("ct_copy_from", label, f.format(s.fetchedAt));
      return `${label}: ${f.format(s.fetchedAt)}`;
    }).join(" • ");
  }

  function render() {
    const now = Date.now();
    const showRussian = settings.get().contests_show_russian;
    const cyclic = cyclicNames(entries);
    // Cotygodniowe CWT z kalendarza wbudowanego – źródła ich nie obejmują
    const weekly = showWeekly && !offline ? builtin(now).filter((c) => c.name.includes("CWT") && c.startMs < now + 7 * DAY_MS) : [];
    const seen = new Set();
    const visible = [...entries, ...weekly].sort((a, b) => a.startMs - b.startMs).filter((c) => {
      const key = contestKey(c);
      if (seen.has(key)) return false;
      seen.add(key);
      const isCyclic = cyclic.has(c.name.toLowerCase()) || c.name.includes("CWT");
      if (c.endMs <= now || (!showWeekly && isCyclic)) return false;
      if (!showRussian && c.originalName != null && c.sources.length < 2) return false;
      switch (filter) {
        case "polish": return c.polish;
        case "international": return !c.polish && c.originalName == null;
        case "vhf": return c.vhf;
        case "cw": return c.modes.includes("CW");
        case "ssb": return c.modes.includes("SSB");
        case "digi": return c.modes.includes("DIGI") || c.modes.includes("RTTY");
        case "qrp": return QRP.test(c.name);
        default: return true;
      }
    });
    const running = visible.filter((c) => now >= c.startMs && now < c.endMs);
    const upcoming = visible.filter((c) => !(now >= c.startMs && now < c.endMs));
    const monthFmt = new Intl.DateTimeFormat(uiLocale(), { month: "long", year: "numeric", timeZone: "UTC" });
    /** @type {any[]} */
    const items = [];
    if (running.length) items.push(h("div", { class: "section-title" }, t("ct_running_now")), running.map((c) => card(c, now)));
    let lastMonth = "";
    for (const c of upcoming) {
      const raw = monthFmt.format(c.startMs);
      const month = raw.charAt(0).toUpperCase() + raw.slice(1);
      if (month !== lastMonth) { lastMonth = month; items.push(h("div", { class: "section-title" }, month)); }
      items.push(card(c, now));
    }
    const toggle = (label, checked, onchange) => h("label", { class: "row", style: { gap: "12px" } }, switchEl(checked, onchange, label), h("span", { class: "body-m" }, label));
    replace(host,
      h("div", { class: "col gap-s" },
        h("div", { class: "chips" }, FILTERS.map(([f, key]) => chip(t(key), filter === f, () => { filter = f; render(); }))),
        toggle(t("ct_show_weekly"), showWeekly, (v) => { showWeekly = v; render(); }),
        toggle(t("ct_show_russian"), showRussian, (v) => { settings.set({ contests_show_russian: v }); render(); }),
        h("div", { class: "label-s muted" }, statusText()),
        loading ? h("div", { class: "progress" }) : null),
      items,
      !loading && !visible.length ? h("div", { class: "body-m muted" }, t("ct_none")) : null,
      h("div", { class: "col gap-s", style: { paddingTop: "8px" } },
        h("div", { class: "body-s muted" }, t("ct_footer")),
        ["SP9CXN", "PK_UKF", "QRZ_RU"].map((s) => {
          const src = CONTEST_SOURCES[/** @type {"SP9CXN"} */ (s)];
          return link(t("ct_calendar_of", src.label), src.pageUrl);
        })));
  }

  /** @param {string} label @param {string} href @param {string} [ic] */
  const link = (label, href, ic = "openInNew") => h("a", { class: "btn btn-text", href, target: "_blank", rel: "noopener", style: { alignSelf: "flex-start", marginLeft: "-12px" } }, icon(ic), label);

  /** @param {ContestEntry} c @param {number} now */
  function card(c, now) {
    const key = contestKey(c);
    const open = expanded === key;
    const runningNow = now >= c.startMs && now < c.endMs;
    const modes = modesText(c);
    const bands = L(c.bands);
    const sources = c.sources.filter((s) => s !== "BUILTIN").map((s) => CONTEST_SOURCES[/** @type {"SP9CXN"} */ (s)]?.label ?? s);
    const details = [[t("ct_organizer"), c.organizer], [t("ct_exchange"), L(c.exchange)], [t("ct_log_deadline"), c.logDeadline], [t("common_source"), sources.join(", ")]]
      .filter(([, v]) => v?.trim());
    return h("div", { class: `card pad col gap-s ${runningNow ? "tonal running" : ""}` },
      h("button", { type: "button", class: "row plain-btn", style: { width: "100%", textAlign: "left" }, onclick: () => { expanded = open ? null : key; render(); } },
        h("span", { class: "title-m bold grow" }, c.name),
        c.polish ? h("span", { class: "label-l bold error-text" }, "SP") : null,
        c.vhf ? h("span", { class: "label-l bold tertiary" }, t("ct_vhf")) : null,
        icon(open ? "expandLess" : "expandMore")),
      c.originalName ? h("div", { class: "body-s muted", style: { fontStyle: "italic" } }, c.originalName) : null,
      h("div", { class: `body-m semibold ${runningNow ? "" : "primary"}` },
        runningNow ? t("ct_running_left", durationText(c.endMs - now)) : t("ct_starts_in", durationText(c.startMs - now))),
      h("div", { class: "body-m" }, rangeText(c.startMs, c.endMs, true)),
      h("div", { class: "body-s muted" }, t("common_local_time", rangeText(c.startMs, c.endMs, false))),
      modes || bands ? h("div", { class: "body-s" }, [modes, bands].filter(Boolean).join("  •  ")) : null,
      c.notes.map((n) => h("div", { class: "body-s error-text" }, `⚠ ${n.startsWith("ct_") ? t(n) : n}`)),
      open ? [h("div", { class: "hr" }), details.map(([label, value]) => h("div", null, h("div", { class: "label-m primary" }, label), h("div", { class: "body-s" }, value))),
        c.homepage ? link(t("ct_organizer_page"), c.homepage) : null] : null,
      h("div", { class: "row wrap", style: { gap: "4px" } },
        link(c.rulesUrl ? t("ct_rules") : t("ct_search_rules"), c.rulesUrl ?? `https://www.google.com/search?q=${encodeURIComponent(`${c.name} rules`)}`),
        c.rulesPlUrl && c.rulesPlUrl !== c.rulesUrl ? link(t("ct_rules_pl"), c.rulesPlUrl) : null,
        c.sourcePageUrl && c.sourcePageUrl !== c.rulesUrl ? link(t("ct_source_page", CONTEST_SOURCES.QRZ_RU.label), c.sourcePageUrl) : null,
        h("button", { type: "button", class: "btn btn-text", style: { marginLeft: "-12px" }, onclick: () => addToCalendar(c, modes, bands, sources) }, icon("eventAvailable"), t("common_add_to_calendar"))));
  }

  /** Plik .ics – Safari na iPhonie od razu proponuje dodanie do Kalendarza. @param {ContestEntry} c @param {string} modes @param {string} bands @param {string[]} sources */
  function addToCalendar(c, modes, bands, sources) {
    const description = [c.originalName, [modes, bands].filter(Boolean).join(", ") || null, L(c.exchange) || null,
      c.logDeadline ? t("ct_logs_by", c.logDeadline) : null, c.rulesUrl,
      c.rulesPlUrl && c.rulesPlUrl !== c.rulesUrl ? t("ct_rules_pl_value", c.rulesPlUrl) : null,
      c.sourcePageUrl && c.sourcePageUrl !== c.rulesUrl ? c.sourcePageUrl : null,
      sources.length ? `${t("common_source")}: ${sources.join(", ")}` : null].filter(Boolean).join("\n");
    const url = URL.createObjectURL(new Blob([contestIcs(c, description)], { type: "text/calendar" }));
    // Na iPhonie bez "download" – Safari pokazuje wtedy okno dodawania do Kalendarza zamiast zapisu do Plików
    const ios = /iP(hone|ad|od)/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
    const a = h("a", { href: url, download: ios ? null : `${c.name.replace(/[^\p{L}\p{N}]+/gu, "_").slice(0, 40)}.ics` });
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  }

  loadPhrases().then((p) => {
    const localizer = new PhraseLocalizer(p, (k) => t(k), currentLanguage());
    L = (s) => localizer.localize(s);
    render();
  });
  const timer = setInterval(render, 30_000);
  load();
  const { el } = layout(ctx, { title: t("ct_title"), body: host, actions: [refreshBtn] });
  return { el, destroy: () => clearInterval(timer) };
}
