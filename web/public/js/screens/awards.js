// @ts-check
// Akcje dyplomowe radiodyplom.pl (AwardsScreen.kt): kalendarz, obserwowane akcje, ranking i szacunek punktów z logu
import { h, replace, iconButton, chip, icon } from "../ui.js";
import { t, uiLocale } from "../i18n.js";
import { settings } from "../store.js";
import { apiUrl } from "../services.js";
import { logbook } from "../db.js";
import { Callsigns } from "../core/radio.js";
import { computeAwardProgress, RADIODYPLOM_PAGE } from "../core/awards.js";
import { contestIcs } from "../core/contests.js";
import { layout } from "./common.js";
import { durationText } from "./contests.js";

/** @typedef {import("../core/awards.js").AwardEvent} AwardEvent */
/** @typedef {import("../core/awards.js").AwardDetails} AwardDetails */

const LIST_KEY = "hrt.awards.v1";
const DETAILS_PREFIX = "hrt.award.";
const MAX_AGE = 12 * 3_600_000;
const STATIONS_PREVIEW = 24;

/** @param {string} key */
function readJson(key) {
  try { return JSON.parse(localStorage.getItem(key) ?? "null"); } catch { return null; }
}
/** @param {string} key @param {any} value */
function writeJson(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* pełna pamięć */ }
}

/** @param {string} id @returns {AwardDetails | null} */
export const cachedAwardDetails = (id) => readJson(DETAILS_PREFIX + id);

/** Stacje trwających obserwowanych akcji (z zapisanych kopii) – do oznaczania spotów w DX Clusterze. */
export function followedAwardStations() {
  const now = Date.now();
  /** @type {Map<string, {eventName: string, points: number}[]>} */
  const out = new Map();
  for (const id of settings.get().followed_awards) {
    const d = cachedAwardDetails(id);
    if (!d || (d.endMs != null && d.endMs <= now)) continue;
    for (const s of d.stations) {
      const k = s.callsign.toUpperCase();
      out.set(k, [...(out.get(k) ?? []), { eventName: d.name || `#${id}`, points: s.points }]);
    }
  }
  return out;
}

/** Tekst dla spotu, np. "Akcja X · 10 pkt". @param {Map<string, {eventName: string, points: number}[]>} tags @param {string} call */
export function awardTextFor(tags, call) {
  const c = call.trim().toUpperCase();
  const list = tags.get(c) ?? tags.get(Callsigns.baseCall(c) ?? "");
  return list?.map((x) => t("dx_award_points", x.eventName, x.points)).join(" • ") ?? null;
}

/** @param {string} id @param {boolean} force */
async function fetchDetails(id, force) {
  const call = settings.get().callsign.trim().toUpperCase();
  const sign = call === "NOCALL" ? "" : call;
  const cached = cachedAwardDetails(id);
  if (!force && cached && Date.now() - cached.fetchedAtMs < MAX_AGE && cached.rankingCallsign === sign) return cached;
  try {
    const res = await fetch(apiUrl(`/awards/${id}?call=${encodeURIComponent(sign)}${force ? "&refresh=1" : ""}`), { signal: AbortSignal.timeout(90000) });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error ?? `HTTP ${res.status}`);
    writeJson(DETAILS_PREFIX + id, data);
    return /** @type {AwardDetails} */ (data);
  } catch (e) {
    if (cached) return cached;
    throw e;
  }
}

/** @param {import("../app.js").ScreenContext} ctx */
export function awardsScreen(ctx) {
  let filter = "all";
  let loading = false;
  /** @type {AwardEvent[]} */
  let events = [];
  let status = "";
  /** @type {string | null} */
  let expanded = null;
  /** @type {Map<string, {state: "loading"} | {state: "loaded", details: AwardDetails} | {state: "failed", message: string}>} */
  const details = new Map();
  /** @type {Set<string>} */
  const showAll = new Set();
  /** @type {Set<string>} */
  const showDescription = new Set();
  const host = h("div", { class: "scroll" });
  const refreshBtn = /** @type {HTMLButtonElement} */ (iconButton("refresh", t("common_refresh"), () => loadList(true)));
  const fmt = (/** @type {number} */ ms, /** @type {Intl.DateTimeFormatOptions} */ o) => new Intl.DateTimeFormat(uiLocale(), { hour12: false, ...o }).format(ms);

  /** @param {string} id @param {boolean} [force] */
  async function loadDetails(id, force = false) {
    const cur = details.get(id);
    if (!force && cur?.state === "loading") return;
    details.set(id, { state: "loading" });
    render();
    try {
      details.set(id, { state: "loaded", details: await fetchDetails(id, force) });
    } catch (e) {
      details.set(id, cur?.state === "loaded" ? cur : { state: "failed", message: /** @type {Error} */ (e).message });
    }
    render();
  }

  async function loadList(force = false) {
    const cached = readJson(LIST_KEY);
    const apply = (/** @type {any} */ c, /** @type {boolean} */ fromCache) => {
      events = c.events;
      status = fromCache ? t("aw_cached", fmt(c.time, { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }))
        : `radiodyplom.pl: ${fmt(c.time, { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" })}`;
    };
    if (cached) apply(cached, false);
    const followed = settings.get().followed_awards;
    for (const id of followed) {
      const d = cachedAwardDetails(id);
      if (d && !details.has(id)) details.set(id, { state: "loaded", details: d });
    }
    if (!cached || force || Date.now() - cached.time > MAX_AGE) {
      loading = true;
      refreshBtn.disabled = true;
      render();
      try {
        const res = await fetch(apiUrl(`/awards${force ? "?refresh=1" : ""}`), { signal: AbortSignal.timeout(45000) });
        const data = await res.json();
        if (data.fetchedAt == null) throw new Error(data.error ?? t("common_no_data"));
        const c = { events: data.events, time: data.fetchedAt };
        writeJson(LIST_KEY, c);
        apply(c, data.fromCache);
      } catch (e) {
        if (cached) apply(cached, true);
        else status = t("aw_unavailable", /** @type {Error} */ (e).message);
      } finally {
        loading = false;
        refreshBtn.disabled = false;
      }
      // Kopie akcji spoza kalendarza i nieobserwowanych są usuwane
      const keep = new Set([...events.map((e) => e.id), ...followed]);
      try {
        for (const k of Object.keys(localStorage)) if (k.startsWith(DETAILS_PREFIX) && !keep.has(k.slice(DETAILS_PREFIX.length))) localStorage.removeItem(k);
      } catch { /* brak dostępu */ }
    }
    render();
    for (const id of followed) loadDetails(id, force);
  }

  /** @param {string} id @param {boolean} follow */
  function setFollowed(id, follow) {
    const cur = settings.get().followed_awards;
    settings.set({ followed_awards: follow ? [...new Set([...cur, id])] : cur.filter((x) => x !== id) });
    if (follow && details.get(id)?.state !== "loaded") loadDetails(id);
    else render();
  }

  function render() {
    const now = Date.now();
    const followed = new Set(settings.get().followed_awards);
    const visible = events.filter((e) => e.endMs > now && (filter === "running" ? now >= e.startMs : filter === "upcoming" ? e.startMs > now
      : filter === "followed" ? followed.has(e.id) : true));
    const running = visible.filter((e) => now >= e.startMs).sort((a, b) => a.endMs - b.endMs);
    const upcoming = visible.filter((e) => e.startMs > now).sort((a, b) => a.startMs - b.startMs);
    replace(host,
      h("div", { class: "col gap-s" },
        h("div", { class: "chips" }, [["all", "common_all"], ["running", "aw_running"], ["upcoming", "aw_upcoming"], ["followed", "aw_followed"]]
          .map(([f, key]) => chip(t(key), filter === f, () => { filter = f; render(); }))),
        h("div", { class: "label-s muted" }, status),
        loading ? h("div", { class: "progress" }) : null),
      running.length ? [h("div", { class: "section-title" }, t("aw_running_header", running.length)), running.map((e) => card(e, now, followed.has(e.id)))] : null,
      upcoming.length ? [h("div", { class: "section-title" }, t("aw_upcoming_header", upcoming.length)), upcoming.map((e) => card(e, now, followed.has(e.id)))] : null,
      !loading && !visible.length ? h("div", { class: "body-m muted" }, filter === "followed" ? t("aw_none_followed") : t("aw_none")) : null,
      h("div", { class: "col gap-s", style: { paddingTop: "8px" } },
        h("div", { class: "body-s muted" }, t("aw_footer")),
        link("radiodyplom.pl", RADIODYPLOM_PAGE)));
  }

  /** @param {string} label @param {string} href */
  const link = (label, href) => h("a", { class: "btn btn-text", href, target: "_blank", rel: "noopener", style: { alignSelf: "flex-start", marginLeft: "-12px" } }, icon("openInNew"), label);
  const utcRange = (/** @type {number} */ s, /** @type {number} */ e) => {
    const o = /** @type {Intl.DateTimeFormatOptions} */ ({ day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", timeZone: "UTC" });
    return `${fmt(s, o)} – ${fmt(e - 60_000, o)} UTC`;
  };
  const dayRange = (/** @type {number} */ s, /** @type {number} */ e) => {
    const o = /** @type {Intl.DateTimeFormatOptions} */ ({ day: "2-digit", month: "2-digit", year: "numeric", timeZone: "UTC" });
    return `${fmt(s, o)} – ${fmt(e - 1, o)}`;
  };

  /** @param {AwardEvent} e @param {number} now @param {boolean} isFollowed */
  function card(e, now, isFollowed) {
    const open = expanded === e.id;
    const runningNow = now >= e.startMs && now < e.endMs;
    const st = details.get(e.id);
    const loaded = st?.state === "loaded" ? st.details : null;
    const toggle = () => {
      expanded = open ? null : e.id;
      if (expanded && details.get(e.id)?.state !== "loaded") loadDetails(e.id);
      else render();
    };
    return h("div", { class: `card pad col gap-s ${runningNow ? "running" : ""}` },
      h("div", { class: "row" },
        h("button", { type: "button", class: "plain-btn title-m bold grow", style: { textAlign: "left" }, onclick: toggle }, e.name),
        iconButton(isFollowed ? "star" : "starBorder", isFollowed ? t("aw_unfollow") : t("aw_follow"), () => setFollowed(e.id, !isFollowed), { class: `icon-btn ${isFollowed ? "tertiary" : "muted"}` }),
        iconButton(open ? "expandLess" : "expandMore", t("common_details"), toggle)),
      h("div", { class: `body-m semibold ${runningNow ? "" : "primary"}` },
        runningNow ? t("aw_running_left", durationText(e.endMs - now)) : t("ct_starts_in", durationText(e.startMs - now))),
      h("div", { class: "body-m" }, loaded?.startMs != null && loaded.endMs != null ? utcRange(loaded.startMs, loaded.endMs) : dayRange(e.startMs, e.endMs)),
      !open && loaded?.myRanking && isFollowed ? h("div", { class: "body-s semibold" }, t("aw_ranking_short", loaded.myRanking.points, loaded.myRanking.qsos, loaded.myRanking.rank)) : null,
      !open && e.description ? h("div", { class: "body-s muted clamp2" }, e.description) : null,
      open ? [h("div", { class: "hr" }),
        !st || st.state === "loading" ? h("div", { class: "progress" })
          : st.state === "failed" ? [h("div", { class: "body-s error-text" }, t("aw_details_failed", st.message)),
            h("button", { type: "button", class: "btn btn-text", style: { alignSelf: "flex-start", marginLeft: "-12px" }, onclick: () => loadDetails(e.id, true) }, t("common_retry"))]
            : detailsSection(e, st.details)] : null,
      h("div", { class: "row wrap", style: { gap: "4px" } },
        link(t("aw_event_page"), e.url),
        h("button", { type: "button", class: "btn btn-text", style: { marginLeft: "-12px" }, onclick: () => addToCalendar(e, loaded) }, icon("eventAvailable"), t("common_add_to_calendar"))));
  }

  /** @param {string} label @param {string} value */
  const labeled = (label, value) => h("div", null, h("div", { class: "label-m primary" }, label), h("div", { class: "body-s pre" }, value));

  /** @param {AwardEvent} e @param {AwardDetails} d */
  function detailsSection(e, d) {
    const progress = computeAwardProgress(d, logbook.all(), e.startMs, e.endMs);
    const worked = progress.workedStations;
    const call = d.rankingCallsign;
    const result = !call ? t("aw_set_callsign")
      : d.myRanking ? t("aw_ranking_full", call, d.myRanking.points, d.myRanking.qsos, d.myRanking.rank) + (d.participants > 0 ? t("aw_of_participants", d.participants) : "")
        : t("aw_not_ranked", call) + (d.participants > 0 ? t("aw_participants", d.participants) : "");
    const sorted = [...d.stations].sort((a, b) => Number(b.callsign in worked) - Number(a.callsign in worked) || b.points - a.points || a.callsign.localeCompare(b.callsign));
    const all = showAll.has(e.id);
    const desc = showDescription.has(e.id);
    const flip = (/** @type {Set<string>} */ set) => { if (set.has(e.id)) set.delete(e.id); else set.add(e.id); render(); };
    return h("div", { class: "col gap-m" },
      d.organizers ? labeled(t("aw_organizers"), d.organizers) : null,
      d.repeatRule ? labeled(t("aw_repeats"), d.repeatRule) : null,
      d.digiGroup.length ? labeled(t("aw_digi_group"), d.digiGroup.join(", ")) : null,
      h("div", null, h("div", { class: "label-m primary" }, t("aw_your_result")), h("div", { class: "body-m semibold" }, result),
        d.rankingUpdated ? h("div", { class: "label-s muted" }, t("aw_ranking_from", d.rankingUpdated)) : null),
      d.awards.length ? h("div", { class: "col gap-m" },
        h("div", { class: "label-m primary" }, t("aw_awards_estimate")),
        progress.rules.map((p) => {
          const min = p.rule.minPoints;
          const regions = [p.rule.allowedRegions.length ? t("aw_for") + p.rule.allowedRegions.join(", ") : null,
            p.rule.excludedRegions.length ? t("aw_without") + p.rule.excludedRegions.join(", ") : null].filter(Boolean);
          const bandsModes = [p.rule.bands.join(" "), p.rule.modes.join(" ")].filter(Boolean).join("  •  ");
          return h("div", { class: "col gap-s" },
            h("div", { class: "row" }, h("span", { class: "body-m semibold grow" }, p.rule.title),
              h("span", { class: `body-m ${p.achieved ? "bold tertiary" : ""}` }, min != null ? `${p.points} / ${min} ${p.rule.unit}` : t("aw_points", p.points))),
            min != null && min > 0 ? h("div", { class: "meter" }, h("div", { style: { width: `${Math.min(100, (p.points / min) * 100)}%` } })) : null,
            regions.length ? h("div", { class: "body-s muted" }, regions.join(" • ")) : null,
            bandsModes ? h("div", { class: "body-s muted clamp2" }, bandsModes) : null);
        }),
        d.hasTimeMultipliers ? h("div", { class: "body-s error-text" }, `⚠ ${t("aw_no_multipliers")}`) : null,
        d.repeatLimit === "BAND_MODE_DAILY" ? h("div", { class: "body-s error-text" }, `⚠ ${t("aw_daily_utc")}`) : null) : null,
      d.stations.length ? h("div", { class: "col gap-s" },
        h("div", { class: "label-m primary" }, t("aw_stations", d.stations.length, Object.keys(worked).length)),
        h("div", { class: "chips s" }, (all ? sorted : sorted.slice(0, STATIONS_PREVIEW)).map((s) => {
          const done = s.callsign in worked;
          return h("span", { class: `station-chip ${done ? "done" : ""}` }, `${done ? "✓ " : ""}${s.callsign} · ${s.points}`);
        })),
        sorted.length > STATIONS_PREVIEW ? h("button", { type: "button", class: "btn btn-text", style: { alignSelf: "flex-start", marginLeft: "-12px" }, onclick: () => flip(showAll) },
          all ? t("common_show_less") : t("aw_show_all", sorted.length)) : null) : null,
      d.description ? [h("button", { type: "button", class: "btn btn-text", style: { alignSelf: "flex-start", marginLeft: "-12px" }, onclick: () => flip(showDescription) },
        desc ? t("aw_hide_description") : t("aw_description")), desc ? h("div", { class: "body-s pre" }, d.description) : null] : null,
      h("div", { class: "label-s muted" }, t("aw_fetched", fmt(d.fetchedAtMs, { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }))));
  }

  /** @param {AwardEvent} e @param {AwardDetails | null} d */
  function addToCalendar(e, d) {
    const description = [e.description || null, e.url, t("common_source_value", "radiodyplom.pl")].filter(Boolean).join("\n");
    const ics = contestIcs(/** @type {any} */ ({ name: e.name, startMs: d?.startMs ?? e.startMs, endMs: d?.endMs ?? e.endMs, rulesUrl: e.url }), description);
    const url = URL.createObjectURL(new Blob([ics], { type: "text/calendar" }));
    const ios = /iP(hone|ad|od)/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
    const a = h("a", { href: url, download: ios ? null : `radiodyplom_${e.id}.ics` });
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  }

  const timer = setInterval(render, 60_000);
  const unsub = logbook.subscribe(() => render());
  loadList();
  const { el } = layout(ctx, { title: t("tool_awards"), body: host, actions: [refreshBtn] });
  return { el, destroy: () => { clearInterval(timer); unsub?.(); } };
}
