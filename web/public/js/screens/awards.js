// @ts-check
// Akcje dyplomowe radiodyplom.pl (AwardsScreen.kt): lista akcji, obserwowane (gwiazdka), mapa pasm z ostatnich 30 minut.
// Tylko pliki public_events.json i public_bandmap.json (przez serwer aplikacji) – tak zezwala serwis.
import { h, replace, iconButton, chip, icon } from "../ui.js";
import { t, uiLocale, currentLanguage, format } from "../i18n.js";
import { settings } from "../store.js";
import { apiUrl } from "../services.js";
import { Callsigns } from "../core/radio.js";
import { RADIODYPLOM_PAGE, activeSpots, followedTags, awardTitle, awardOrganizers } from "../core/awards.js";
import { contestIcs } from "../core/contests.js";
import { layout } from "./common.js";
import { durationText } from "./contests.js";

/** @typedef {import("../core/awards.js").AwardEvent} AwardEvent */
/** @typedef {import("../core/awards.js").BandmapSpot} BandmapSpot */

const LIST_KEY = "hrt.awards.v2";
const MAX_AGE = 12 * 3_600_000;
const BANDMAP_MAX_AGE = 5 * 60_000;
/** Co ile pytamy o mapę pasm – chwilę dłużej niż ważność kopii na serwerze. */
export const BANDMAP_POLL_MS = BANDMAP_MAX_AGE + 10_000;

// Dawne kopie stron akcji (wersja z rankingiem) – usuwamy
try {
  for (const k of Object.keys(localStorage)) if (k === "hrt.awards.v1" || k.startsWith("hrt.award.")) localStorage.removeItem(k);
} catch { /* brak dostępu */ }

/** @param {string} key */
function readJson(key) {
  try { return JSON.parse(localStorage.getItem(key) ?? "null"); } catch { return null; }
}
/** @param {string} key @param {any} value */
function writeJson(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* pełna pamięć */ }
}

// ---------------------------------------------------------------- mapa pasm (wspólna z DX Clusterem)
/** @type {{spots: BandmapSpot[], fetchedAt: number} | null} */
let bandmap = null;
/** @type {Promise<void> | null} */
let bandmapLoading = null;
let bandmapFailed = false;

/** Pobiera mapę pasm, jeśli kopia jest starsza niż 5 minut. */
export function refreshBandmap() {
  if (bandmap && Date.now() - bandmap.fetchedAt < BANDMAP_MAX_AGE) return Promise.resolve();
  bandmapLoading ??= (async () => {
    try {
      const res = await fetch(apiUrl("/awards/bandmap"), { signal: AbortSignal.timeout(30000) });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      bandmap = { spots: data.spots ?? [], fetchedAt: data.fetchedAt ?? Date.now() };
      bandmapFailed = false;
    } catch {
      bandmapFailed = true;
    }
  })().finally(() => { bandmapLoading = null; });
  return bandmapLoading;
}

/** Stacje aktywne w ostatnich 30 minutach; null – nie wiadomo (brak sieci i aktualnej kopii). */
function currentSpots() {
  if (!bandmap) return null;
  const spots = activeSpots(bandmap.spots, Date.now() - bandmap.fetchedAt);
  return bandmapFailed && !spots.length ? null : spots;
}

/** Stacje obserwowanych akcji z mapy pasm – do oznaczania spotów w DX Clusterze. */
export function followedAwardStations() {
  const followed = settings.get().followed_awards;
  if (!followed.length) return new Map();
  return followedTags(currentSpots() ?? [], new Set(followed));
}

/** Tekst dla spotu, np. "Akcja X (+1 p.)". @param {Map<string, string[]>} tags @param {string} call */
export function awardTextFor(tags, call) {
  const c = call.trim().toUpperCase();
  const list = tags.get(c) ?? tags.get(Callsigns.baseCall(c) ?? "");
  return list?.join(" • ") ?? null;
}

// ---------------------------------------------------------------- ekran
/** @param {import("../app.js").ScreenContext} ctx */
export function awardsScreen(ctx) {
  let filter = "all";
  let loading = false;
  /** @type {AwardEvent[]} */
  let events = [];
  let status = "";
  const host = h("div", { class: "scroll" });
  const refreshBtn = /** @type {HTMLButtonElement} */ (iconButton("refresh", t("common_refresh"), () => loadList(true)));
  const fmt = (/** @type {number} */ ms, /** @type {Intl.DateTimeFormatOptions} */ o) => new Intl.DateTimeFormat(uiLocale(), { hour12: false, ...o }).format(ms);
  const polish = currentLanguage() === "pl";

  async function loadList(force = false) {
    const cached = readJson(LIST_KEY);
    const apply = (/** @type {any} */ c, /** @type {boolean} */ fromCache) => {
      events = c.events;
      const time = fmt(c.time, { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" });
      status = fromCache ? t("aw_cached", time) : `radiodyplom.pl: ${time}`;
    };
    if (cached) apply(cached, false);
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
    }
    render();
  }

  /** @param {string} id @param {boolean} follow */
  function setFollowed(id, follow) {
    const cur = settings.get().followed_awards;
    settings.set({ followed_awards: follow ? [...new Set([...cur, id])] : cur.filter((x) => x !== id) });
    render();
  }

  function render() {
    const now = Date.now();
    const followed = new Set(settings.get().followed_awards);
    const visible = events.filter((e) => e.endMs > now && (filter === "running" ? now >= e.startMs : filter === "upcoming" ? e.startMs > now
      : filter === "followed" ? followed.has(e.id) : true));
    const running = visible.filter((e) => now >= e.startMs).sort((a, b) => a.endMs - b.endMs);
    const upcoming = visible.filter((e) => e.startMs > now).sort((a, b) => a.startMs - b.startMs);
    const spots = currentSpots()?.filter((s) => filter === "upcoming" ? false : filter === "followed" ? s.events.some((e) => followed.has(e.id)) : true);
    replace(host,
      h("div", { class: "col gap-s" },
        h("div", { class: "chips" }, [["all", "common_all"], ["running", "aw_running"], ["upcoming", "aw_upcoming"], ["followed", "aw_followed"]]
          .map(([f, key]) => chip(t(key), filter === f, () => { filter = f; render(); }))),
        h("div", { class: "label-s muted" }, status),
        loading ? h("div", { class: "progress" }) : null),
      filter !== "upcoming" && spots ? [
        h("div", { class: "section-title" }, t("aw_on_air_header", spots.length)),
        spots.length ? spots.map(spotCard) : h("div", { class: "body-s muted" }, t("aw_on_air_none")),
      ] : null,
      running.length ? [h("div", { class: "section-title" }, t("aw_running_header", running.length)), running.map((e) => card(e, now, followed.has(e.id)))] : null,
      upcoming.length ? [h("div", { class: "section-title" }, t("aw_upcoming_header", upcoming.length)), upcoming.map((e) => card(e, now, followed.has(e.id)))] : null,
      !loading && !visible.length ? h("div", { class: "body-m muted" }, filter === "followed" ? t("aw_none_followed") : t("aw_none")) : null,
      h("div", { class: "col gap-s", style: { paddingTop: "8px" } },
        h("div", { class: "body-s muted" }, t("aw_footer")),
        link("radiodyplom.pl", RADIODYPLOM_PAGE)));
  }

  /** @param {string} label @param {string} href */
  const link = (label, href) => h("a", { class: "btn btn-text", href, target: "_blank", rel: "noopener", style: { alignSelf: "flex-start", marginLeft: "-12px" } }, icon("openInNew"), label);
  /** @param {number} s @param {number} e */
  const utcRange = (s, e) => {
    const o = /** @type {Intl.DateTimeFormatOptions} */ ({ day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", timeZone: "UTC" });
    return `${fmt(s, o)} – ${fmt(e - 60_000, o)} UTC`;
  };

  /** @param {BandmapSpot} s */
  const spotCard = (s) => h("div", { class: "card pad-s col", style: { gap: "2px" } },
    h("div", { class: "row" }, h("span", { class: "title-m bold code grow" }, s.callsign),
      s.frequencyKhz != null ? h("span", { class: "body-m code" }, format("%.3f MHz", s.frequencyKhz / 1000)) : null),
    h("div", { class: "body-s muted" }, [s.band, s.mode, s.timeUtc ? `${s.timeUtc} UTC` : ""].filter(Boolean).join(" • ")),
    s.events.map((e) => h("div", { class: "body-s" }, `${e.title} ${e.pointsInfo}`.trim())));

  /** @param {AwardEvent} e @param {number} now @param {boolean} isFollowed */
  function card(e, now, isFollowed) {
    const runningNow = now >= e.startMs;
    const organizers = awardOrganizers(e, polish);
    return h("div", { class: `card pad col gap-s ${runningNow ? "running" : ""}` },
      h("div", { class: "row" },
        h("span", { class: "title-m bold grow" }, awardTitle(e, polish)),
        iconButton(isFollowed ? "star" : "starBorder", isFollowed ? t("aw_unfollow") : t("aw_follow"), () => setFollowed(e.id, !isFollowed),
          { class: `icon-btn ${isFollowed ? "tertiary" : "muted"}` })),
      h("div", { class: `body-m semibold ${runningNow ? "" : "primary"}` },
        runningNow ? t("aw_running_left", durationText(e.endMs - now)) : t("ct_starts_in", durationText(e.startMs - now))),
      h("div", { class: "body-m" }, utcRange(e.startMs, e.endMs)),
      organizers ? h("div", { class: "body-s" }, t("aw_organizers_value", organizers)) : null,
      h("div", { class: "row wrap", style: { gap: "4px" } },
        link(t("aw_event_page"), e.url),
        h("button", { type: "button", class: "btn btn-text", style: { marginLeft: "-12px" }, onclick: () => addToCalendar(e) }, icon("eventAvailable"), t("common_add_to_calendar"))));
  }

  /** @param {AwardEvent} e */
  function addToCalendar(e) {
    const description = [awardOrganizers(e, polish) || null, e.url, t("common_source_value", "radiodyplom.pl")].filter(Boolean).join("\n");
    const ics = contestIcs(/** @type {any} */ ({ name: awardTitle(e, polish), startMs: e.startMs, endMs: e.endMs, rulesUrl: e.url }), description);
    const url = URL.createObjectURL(new Blob([ics], { type: "text/calendar" }));
    const ios = /iP(hone|ad|od)/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
    const a = h("a", { href: url, download: ios ? null : `radiodyplom_${e.id}.ics` });
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  }

  const pollBandmap = () => refreshBandmap().then(render);
  const timer = setInterval(render, 60_000);
  const bandmapTimer = setInterval(pollBandmap, BANDMAP_POLL_MS);
  loadList();
  pollBandmap();
  const { el } = layout(ctx, { title: t("tool_awards"), body: host, actions: [refreshBtn] });
  return { el, destroy: () => { clearInterval(timer); clearInterval(bandmapTimer); } };
}
