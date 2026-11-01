// @ts-check
// Logger, okno QSO i statystyki – LoggerScreen.kt, LoggerViewModel.kt, components/QsoDialog.kt, LogStatsScreen.kt
import { h, replace, iconButton, icon, textField, selectField, switchEl, button, dialog, toast, saveTextFile, pickFile } from "../ui.js";
import { t, fmt } from "../i18n.js";
import { settings, stationPosition } from "../store.js";
import { logbook } from "../db.js";
import { cty, callbookLookup } from "../services.js";
import { isCompleteCall } from "../core/callbook.js";
import { Adif, LogStatistics, makeQso, filterLogs } from "../core/logbook.js";
import { calculateBandFromFreq } from "../core/radio.js";
import { Maidenhead, GreatCircle } from "../core/geo.js";
import { layout } from "./common.js";

/** @typedef {import("../core/logbook.js").QsoEntry} QsoEntry */

let searchQuery = "";
let searchOpen = false;

/** @param {import("../app.js").ScreenContext} ctx */
export function loggerScreen(ctx) {
  const listHost = h("div", { class: "scroll tight", style: { paddingBottom: "96px" } });
  const busy = h("div");
  const searchHost = h("div");
  const subtitle = h("div", { class: "top-bar-sub" });

  const searchField = textField({
    label: t("log_search_hint"), value: searchQuery, class: "has-trailing",
    oninput: (v) => { searchQuery = v; renderList(); },
  });
  const clearBtn = iconButton("clear", t("common_clear"), () => { searchQuery = ""; searchField.input.value = ""; renderList(); }, { class: "icon-btn field-trailing" });
  searchField.appendChild(clearBtn);

  const searchToggle = iconButton("search", t("log_search"), () => {
    searchOpen = !searchOpen;
    if (!searchOpen) { searchQuery = ""; searchField.input.value = ""; }
    renderSearch();
    renderList();
    if (searchOpen) searchField.input.focus();
  });

  function renderSearch() {
    replace(searchHost, searchOpen ? h("div", { style: { padding: "4px 16px" } }, searchField) : null);
    searchToggle.classList.toggle("active", !!searchQuery.trim());
  }

  function renderList() {
    const all = logbook.all();
    const shown = filterLogs(all, searchQuery);
    subtitle.textContent = searchQuery.trim() ? t("log_count_filtered", shown.length, all.length) : t("log_count", all.length);
    clearBtn.classList.toggle("hidden", !searchQuery);
    searchToggle.classList.toggle("active", !!searchQuery.trim());
    if (!all.length) return replace(listHost, h("div", { class: "centered muted pre" }, t("log_empty")));
    if (!shown.length) return replace(listHost, h("div", { class: "centered muted" }, t("log_no_match", searchQuery)));
    replace(listHost, shown.slice(0, 500).map(qsoItem), shown.length > 500 ? h("div", { class: "body-s muted center" }, `… +${shown.length - 500}`) : null,
      h("div", { class: "body-s muted", style: { padding: "8px 4px" } }, t("web_storage_note")));
  }

  /** @param {QsoEntry} q */
  function qsoItem(q) {
    const details = [q.name, q.country, q.gridSquare, q.huntedRef].filter((s) => s.trim());
    const uploaded = [q.qrzUploaded ? "QRZ ✓" : null, q.eqslUploaded ? "eQSL ✓" : null].filter(Boolean);
    return h("div", { class: "card clickable qso", role: "button", tabindex: "0", onclick: () => openQsoDialog(q) },
      h("div", { class: "col grow" },
        h("div", { class: "row wrap" }, h("span", { class: "title-l bold primary" }, q.callsign),
          h("span", { class: "qso-badge" }, q.mode), h("span", { class: "qso-badge" }, `${q.frequency} kHz (${q.band})`)),
        h("div", { class: "row body-s muted", style: { gap: "12px" } }, h("span", null, q.datetimeUtc), h("span", null, `RST: ${q.rstSent}/${q.rstRcvd}`)),
        details.length ? h("div", { class: "body-s muted" }, details.join(" • ")) : null,
        q.comment ? h("div", { class: "body-s italic" }, q.comment) : null,
        uploaded.length ? h("div", { class: "label-s tertiary" }, uploaded.join("  ")) : null),
      iconButton("delete", t("common_delete"), (e) => { e.stopPropagation(); confirmDelete(q); }, { style: { color: "var(--error)" } }));
  }

  /** @param {QsoEntry} q */
  function confirmDelete(q) {
    dialog({
      title: t("log_delete_title"),
      body: h("div", { class: "body-m" }, `${q.callsign}, ${q.datetimeUtc} UTC, ${q.band} ${q.mode}`),
      actions: (close) => [button(t("common_cancel"), close, "text"),
        button(t("common_delete"), async () => { close(); await logbook.remove(q.id); }, "text", { class: "btn btn-text danger" })],
    });
  }

  /** @param {QsoEntry} [initial] */
  function openQsoDialog(initial) {
    const last = logbook.all()[0];
    qsoDialog({
      initial,
      defaults: { frequency: last?.frequency ?? "14195", mode: last?.mode ?? "SSB", rstSent: last?.rstSent ?? "59", rstRcvd: last?.rstRcvd ?? "59" },
      onsave: async (entry) => {
        await logbook.put(entry);
        toast(initial ? t("log_qso_updated") : t("dx_qso_logged", entry.callsign));
      },
    });
  }

  function setBusy(msg) {
    replace(busy, msg ? [h("div", { class: "progress" }), h("div", { class: "body-s", style: { padding: "4px 16px" } }, msg)] : null);
  }

  async function exportAdif() {
    const entries = logbook.all();
    if (!entries.length) return toast(t("log_nothing_to_export"));
    setBusy(t("log_busy_export"));
    try {
      const text = Adif.generate(entries, settings.get().callsign);
      await saveTextFile("qso_log.adi", text, "application/octet-stream");
      toast(t("log_exported", entries.length));
    } catch (e) {
      toast(t("log_file_write_error", /** @type {Error} */ (e).message));
    } finally {
      setBusy(null);
    }
  }

  async function importAdif() {
    const file = await pickFile(".adi,.adif,.txt,application/octet-stream,text/plain");
    if (!file) return;
    setBusy(t("log_busy_import"));
    try {
      const text = await file.text();
      const parsed = Adif.parse(text);
      const db = await cty().catch(() => null);
      const withDxcc = parsed.entries.map((q) => {
        if (q.dxcc != null || !db) return q;
        const e = db.resolve(q.callsign);
        return e ? { ...q, dxcc: e.dxcc, country: q.country || e.name } : q;
      });
      const added = await logbook.importAll(withDxcc);
      const dupes = withDxcc.length - added;
      toast(t("log_imported", added) + (dupes > 0 ? t("log_imported_dupes", dupes) : "") + (parsed.skipped > 0 ? t("log_imported_incomplete", parsed.skipped) : ""), 6000);
    } catch (e) {
      toast(t("log_import_error", /** @type {Error} */ (e).message));
    } finally {
      setBusy(null);
    }
  }

  function openMenu(anchor) {
    const rect = anchor.getBoundingClientRect();
    const close = () => backdrop.remove();
    const item = (ic, label, fn) => h("button", { type: "button", class: "menu-item", onclick: () => { close(); fn(); } }, icon(ic), h("span", null, label));
    const backdrop = h("div", { class: "menu-backdrop", onclick: (e) => { if (e.target === backdrop) close(); } },
      h("div", { class: "menu", role: "menu", style: { top: `${rect.bottom}px`, right: `${Math.max(8, window.innerWidth - rect.right)}px` } },
        item("fileOpen", t("log_import_adif"), importAdif),
        item("download", t("log_export_adif"), exportAdif),
        item("settings", t("common_settings"), () => ctx.navigate("settings"))));
    document.body.appendChild(backdrop);
  }

  const menuBtn = iconButton("moreVert", t("log_more"), () => openMenu(menuBtn));
  const body = h("div", { class: "screen", style: { position: "relative" } }, busy, searchHost, listHost,
    h("button", { type: "button", class: "fab", "aria-label": t("log_add_qso"), onclick: () => openQsoDialog() }, icon("add")));

  const { el } = layout(ctx, {
    title: t("log_title"), settings: false, body,
    actions: [searchToggle, iconButton("barChart", t("log_stats"), () => ctx.navigate("log_stats")), menuBtn],
  });
  el.querySelector(".top-bar-title")?.appendChild(subtitle);

  renderSearch();
  renderList();
  const unsubscribe = logbook.subscribe(renderList);
  return { el, destroy: unsubscribe };
}

// ---------------------------------------------------------------- okno QSO
const MODES = ["SSB", "CW", "FT8", "FT4", "FM", "AM", "RTTY", "PSK31", "JS8", "SSTV", "DMR", "C4FM"];

/**
 * @param {{initial?: QsoEntry, defaults: {frequency: string, mode: string, rstSent: string, rstRcvd: string},
 *   prefillCallsign?: string, prefillHuntedRef?: string, onsave: (e: QsoEntry) => void}} o
 */
export function qsoDialog(o) {
  const init = o.initial;
  const now = new Date().toISOString().replace("T", " ").slice(0, 16);
  const v = {
    callsign: init?.callsign ?? (o.prefillCallsign ?? "").toUpperCase(), frequency: init?.frequency ?? o.defaults.frequency,
    mode: init?.mode ?? o.defaults.mode, rstSent: init?.rstSent ?? o.defaults.rstSent, rstRcvd: init?.rstRcvd ?? o.defaults.rstRcvd,
    comment: init?.comment ?? "", name: init?.name ?? "", qth: init?.qth ?? "", gridSquare: init?.gridSquare ?? "",
    huntedRef: init?.huntedRef ?? (o.prefillHuntedRef ?? "").toUpperCase(), datetime: init?.datetimeUtc ?? now,
  };
  let realTime = !init;
  /** @type {import("../core/logbook.js").DxccEntity | null} */
  let entity = null;
  let newCountry = false;
  /** @type {QsoEntry[]} */
  let previous = [];
  /** @type {any} */
  let lookupTimer = null;
  // Pola uzupełnione automatycznie z wyszukiwarki – czyścimy je przy zmianie znaku, jeśli nikt ich nie poprawił
  /** @type {Record<"name"|"qth"|"gridSquare", string>} */
  const auto = { name: "", qth: "", gridSquare: "" };
  /** @type {Record<string, any>} */
  const inputs = {};
  let lookupSeq = 0;
  const status = h("div", { class: "body-s muted pre" });
  const setField = (key, value) => { v[key] = value; if (inputs[key]) inputs[key].input.value = value; };

  const info = h("div", { class: "col gap-s" });
  const renderInfo = () => {
    const band = calculateBandFromFreq(v.frequency);
    const station = stationPosition();
    const target = v.gridSquare.length >= 4 ? Maidenhead.toLatLon(v.gridSquare) : null;
    const path = station && target ? GreatCircle.path(station, target) : null;
    const dupe = previous.some((q) => q.band.toLowerCase() === band.toLowerCase() && q.mode.toLowerCase() === v.mode.toLowerCase());
    const lastQso = previous[0];
    replace(info,
      entity ? h("div", { class: "row wrap" }, h("span", { class: "body-m" }, `${entity.name} • ${entity.continent} • CQ ${entity.cqZone}`),
        newCountry ? h("span", { class: "body-s bold tertiary" }, t("spot_new_country")) : null) : null,
      path ? h("div", { class: "body-m muted" }, t("qso_distance", fmt("%,.0f", path.shortPathKm), fmt("%.0f", path.shortPathBearing), GreatCircle.compassPoint(path.shortPathBearing))) : null,
      previous.length ? h("div", { class: `body-m ${dupe ? "bold error-text" : "primary"}` },
        dupe ? t("qso_dupe", band, v.mode) : t("qso_worked_before", previous.length, lastQso.datetimeUtc, lastQso.band, lastQso.mode)) : null);
  };

  // Sprawdzenie znaku po chwili bez pisania: kraj DXCC i wcześniejsze QSO
  const scheduleLookup = () => {
    clearTimeout(lookupTimer);
    const call = v.callsign.trim();
    const seq = ++lookupSeq;
    for (const k of /** @type {const} */ (["name", "qth", "gridSquare"])) {
      if (auto[k] && v[k] === auto[k]) setField(k, "");
      auto[k] = "";
    }
    gridField.setError(false);
    status.textContent = "";
    if (call.length < 3) { entity = null; previous = []; newCountry = false; renderInfo(); return; }
    lookupTimer = setTimeout(async () => {
      const db = await cty().catch(() => null);
      entity = db?.resolve(call) ?? null;
      previous = logbook.previousContacts(call).filter((q) => q.id !== init?.id);
      newCountry = entity != null && !logbook.workedDxcc().has(entity.dxcc);
      renderInfo();
      if (!isCompleteCall(call)) return;
      status.textContent = t("qso_looking_up");
      const cb = await callbookLookup(call);
      if (seq !== lookupSeq) return;
      const fill = (/** @type {"name"|"qth"|"gridSquare"} */ k, value) => {
        if (!value || v[k].trim()) return;
        auto[k] = value;
        setField(k, value);
      };
      fill("name", cb.name ?? "");
      fill("qth", cb.qth ?? "");
      fill("gridSquare", cb.grid?.locator ?? "");
      status.textContent = [cb.grid ? t("qso_locator_from", cb.grid.locator, cb.grid.source) : t("qso_no_locator"), ...cb.notes].join("\n");
      renderInfo();
    }, 600);
  };

  const field = (label, key, extra = {}) => textField({ label, value: v[key], oninput: (x) => { v[key] = x; extra.after?.(); }, ...extra });
  const callField = field(t("qso_callsign"), "callsign", { uppercase: true, after: () => { v.callsign = v.callsign.trim(); scheduleLookup(); updateSave(); } });
  const freqField = field(t("qso_frequency"), "frequency", { inputmode: "decimal", after: () => { renderInfo(); updateSave(); } });
  const gridField = field(t("qso_locator"), "gridSquare", {
    uppercase: true, maxlength: 8,
    after: () => { gridField.setError(!!v.gridSquare && !Maidenhead.isValid(v.gridSquare)); renderInfo(); },
  });
  gridField.setError(!!v.gridSquare && !Maidenhead.isValid(v.gridSquare));
  const dtField = field(t("qso_datetime"), "datetime");
  const nameField = field(t("qso_name"), "name");
  const qthField = field("QTH", "qth");
  Object.assign(inputs, { name: nameField, qth: qthField, gridSquare: gridField });
  dtField.classList.toggle("hidden", realTime);

  /** @type {HTMLButtonElement} */
  let saveBtn;
  const updateSave = () => { if (saveBtn) saveBtn.disabled = !v.callsign.trim() || !v.frequency.trim(); };

  const body = h("div", { class: "col gap-l" },
    callField, info, status,
    h("div", { class: "fields" }, h("div", { style: { flex: "1.2" }, class: "field-wrap" }, freqField),
      h("div", { style: { flex: "0.8" }, class: "field-wrap" }, selectField({ label: t("qso_mode"), value: v.mode, options: /** @type {[string, string][]} */ (MODES.map((m) => [m, m]).concat(MODES.includes(v.mode) ? [] : [[v.mode, v.mode]])),
        onchange: (m) => { v.mode = m; renderInfo(); } }))),
    h("div", { class: "fields" }, field(t("qso_rst_sent"), "rstSent"), field(t("qso_rst_rcvd"), "rstRcvd")),
    h("div", { class: "fields" }, nameField, qthField),
    h("div", { class: "fields" }, gridField, field("POTA/WWFF", "huntedRef", { uppercase: true, placeholder: "SP-0012" })),
    h("div", { class: "row between" }, h("span", null, t("qso_utc_now")),
      switchEl(realTime, (x) => { realTime = x; if (x) v.datetime = now; dtField.input.value = v.datetime; dtField.classList.toggle("hidden", x); }, t("qso_utc_now"))),
    dtField,
    field(t("qso_comment"), "comment"));

  dialog({
    title: init ? t("qso_edit") : t("qso_new"),
    body,
    wide: true,
    onclose: () => clearTimeout(lookupTimer),
    actions: (close) => [
      button(t("common_cancel"), close, "text"),
      (saveBtn = /** @type {HTMLButtonElement} */ (button(t("common_save"), () => {
        const datetime = realTime ? now : v.datetime;
        const entry = makeQso({
          id: init?.id, callsign: v.callsign.trim(), datetimeUtc: datetime.trim(), frequency: v.frequency.trim().replace(",", "."),
          band: calculateBandFromFreq(v.frequency), mode: v.mode.trim(), rstSent: v.rstSent.trim(), rstRcvd: v.rstRcvd.trim(),
          comment: v.comment.trim(), name: v.name.trim(), qth: v.qth.trim(),
          gridSquare: Maidenhead.isValid(v.gridSquare.trim()) ? v.gridSquare.trim() : "",
          dxcc: entity?.dxcc ?? init?.dxcc ?? null, country: entity?.name ?? init?.country ?? "", huntedRef: v.huntedRef.trim(),
          qrzUploaded: init?.qrzUploaded ?? false, eqslUploaded: init?.eqslUploaded ?? false,
        });
        close();
        o.onsave(entry);
      }))),
    ],
  });
  updateSave();
  renderInfo();
  if (v.callsign) scheduleLookup();
  if (!init) setTimeout(() => callField.input.focus(), 50);
}

// ---------------------------------------------------------------- statystyki
/** @param {import("../app.js").ScreenContext} ctx */
export function logStatsScreen(ctx) {
  const host = h("div", { class: "scroll" });
  const render = () => {
    const s = LogStatistics.compute(logbook.all());
    const tile = (label, value) => h("div", { class: "card stat-tile" }, h("div", { class: "headline-m bold primary" }, String(value)), h("div", { class: "body-s muted" }, label));
    const bands = [...LogStatistics.BAND_ORDER.filter((b) => b in s.bandModeCounts), ...Object.keys(s.bandModeCounts).filter((b) => !LogStatistics.BAND_ORDER.includes(b)).sort()];
    const table = bands.length
      ? h("div", { class: "table-scroll" }, h("table", { class: "grid" },
        h("tr", null, h("th", null, t("stats_band")), [...LogStatistics.MODE_GROUPS, "DXCC"].map((c) => h("th", null, c === "PHONE" ? t("stats_phone") : c))),
        bands.map((b) => h("tr", null, h("td", null, b),
          LogStatistics.MODE_GROUPS.map((g) => h("td", null, s.bandModeCounts[b]?.[g] ? String(s.bandModeCounts[b][g]) : "–")),
          h("td", null, String(s.dxccPerBand[b] ?? 0))))))
      : h("div", { class: "muted" }, t("stats_no_data"));
    replace(host,
      h("div", { class: "stat-tiles" }, tile("QSO", s.totalQso), tile(t("stats_calls"), s.uniqueCalls), tile("DXCC", s.dxccCount)),
      h("div", { class: "stat-tiles" }, tile(t("stats_pota_parks"), s.potaRefs.size), tile(t("stats_wwff_refs"), s.wwffRefs.size)),
      s.withoutDxcc > 0 ? h("div", { class: "body-s muted" }, t("stats_without_dxcc", s.withoutDxcc)) : null,
      h("div", { class: "body-s muted" }, t("stats_worked_note")),
      h("div", { class: "body-l bold" }, t("stats_by_band_mode")), table,
      h("div", { class: "body-l bold" }, t("stats_dxcc_count", s.dxccCount)),
      s.entities.map((e) => h("div", { class: "card pad-s row between" },
        h("div", { class: "col grow" }, h("span", { class: "semibold" }, e.name),
          h("span", { class: "body-s muted" }, LogStatistics.BAND_ORDER.filter((b) => e.bands.has(b)).join(" ") + "  •  " + [...e.modes].sort().join("/"))),
        h("span", { class: "body-m" }, `${e.qsoCount} QSO`))));
  };
  render();
  const unsubscribe = logbook.subscribe(render);
  const { el } = layout(ctx, { title: t("stats_title"), body: host });
  return { el, destroy: unsubscribe };
}
