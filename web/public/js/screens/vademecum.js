// @ts-check
// Ściągi: DXCC, kody Q, RST, okręgi SP, skróty, Morse, alfabet fonetyczny (VademecumScreen.kt, VademecumData.kt)
import { h, replace, textField } from "../ui.js";
import { t, currentLanguage } from "../i18n.js";
import { layout } from "./common.js";

const TOPICS = ["dxcc", "qcodes", "rst", "sp", "slang", "morse", "phonetic"];
const MORSE = [["A", ".-"], ["B", "-..."], ["C", "-.-."], ["D", "-.."], ["E", "."], ["F", "..-."], ["G", "--."], ["H", "...."], ["I", ".."],
  ["J", ".---"], ["K", "-.-"], ["L", ".-.."], ["M", "--"], ["N", "-."], ["O", "---"], ["P", ".--."], ["Q", "--.-"], ["R", ".-."], ["S", "..."],
  ["T", "-"], ["U", "..-"], ["V", "...-"], ["W", ".--"], ["X", "-..-"], ["Y", "-.--"], ["Z", "--.."], ["1", ".----"], ["2", "..---"],
  ["3", "...--"], ["4", "....-"], ["5", "....."], ["6", "-...."], ["7", "--..."], ["8", "---.."], ["9", "----."], ["0", "-----"]];

/** @type {Record<string, Promise<Record<string, string>[]>>} */
const tables = {};
/** Tabela TSV: kolumny to języki (en, pl, de, cs, sk); pusta komórka – angielski. @param {string} name */
function table(name) {
  tables[name] ??= fetch(`data/vademecum/${name}.tsv`).then((r) => r.text()).then((text) => {
    const lines = text.split(/\r?\n/).filter((l) => l.trim());
    const header = lines[0].split("\t");
    return lines.slice(1).map((l) => Object.fromEntries(l.split("\t").map((v, i) => [header[i], v])));
  });
  return tables[name];
}
const localized = (row) => (row[currentLanguage()]?.trim() ? row[currentLanguage()] : row.en);

/** @param {import("../app.js").ScreenContext} ctx */
export function vademecumScreen(ctx) {
  let page = Math.max(0, TOPICS.indexOf(ctx.params[0] ?? "dxcc"));
  const labels = ["DXCC", t("vad_tab_qcodes"), t("vad_tab_reports"), t("vad_tab_areas"), t("vad_tab_slang"), t("vad_tab_morse"), t("vad_tab_phonetic")];
  const tabs = h("div", { class: "tabs", role: "tablist" });
  const content = h("div", { class: "screen" });
  let dxccQuery = "";

  const renderTabs = () => replace(tabs, labels.map((label, i) => h("button", { type: "button", role: "tab", class: `tab ${i === page ? "active" : ""}`,
    "aria-selected": String(i === page), onclick: () => { page = i; history.replaceState(null, "", `#/vademecum/${TOPICS[i]}`); renderTabs(); renderPage(); } }, label)));

  const simpleList = (rows, keyCls = "") => h("div", { class: "scroll tight" }, rows.map(([k, v]) => h("div", { class: "card pad row" },
    h("span", { class: `vad-key ${keyCls}` }, k), h("span", { class: keyCls ? "morse-code grow" : "body-l grow" }, v))));

  async function renderPage() {
    const topic = TOPICS[page];
    tabs.querySelector(".tab.active")?.scrollIntoView({ inline: "nearest", block: "nearest" });
    if (topic === "rst") return replace(content, rstView());
    if (topic === "morse") return replace(content, simpleList(MORSE, "morse"));
    if (topic === "dxcc") {
      const rows = await table("dxcc");
      const list = h("div", { class: "scroll tight" });
      const renderList = () => {
        const q = dxccQuery.toLowerCase();
        const shown = rows.filter((r) => r.prefix.toLowerCase().includes(q) || localized(r).toLowerCase().includes(q));
        replace(list, shown.map((r) => h("div", { class: "card pad row between", style: { background: "var(--surface-variant)" } },
          h("div", { class: "col gap-s" }, h("span", { class: "title-m bold" }, localized(r)), h("span", { class: "body-s" }, t("vad_continent", r.continent))),
          h("span", { class: "title-l primary", style: { fontWeight: "800", textAlign: "right" } }, r.prefix))));
      };
      const search = textField({ label: t("vad_search_dxcc"), value: dxccQuery, oninput: (v) => { dxccQuery = v; renderList(); } });
      renderList();
      return replace(content, h("div", { style: { padding: "16px 16px 0" } }, search), list);
    }
    const name = { qcodes: "qcodes", sp: "sp_areas", slang: "slang", phonetic: "phonetic" }[topic];
    const keyCol = topic === "phonetic" ? "letter" : "code";
    const rows = await table(name);
    replace(content, simpleList(rows.map((r) => [r[keyCol], localized(r)])));
  }

  renderTabs();
  renderPage();
  const { el } = layout(ctx, { title: t("vad_title"), body: h("div", { class: "screen" }, tabs, content) });
  return { el };
}

function rstView() {
  const card = (title, ...body) => h("section", { class: "card pad col gap-m", style: { background: "var(--surface-variant)" } }, h("div", { class: "title-m bold primary" }, title), body);
  return h("div", { class: "scroll" },
    h("div", { class: "title-l bold" }, t("rst_title")),
    card(t("rst_r"), h("div", { class: "body-m pre" }, t("rst_r_scale"))),
    card(t("rst_s"), h("div", { class: "body-m pre" }, t("rst_s_scale")), h("div", { class: "body-s italic" }, t("rst_s_note"))),
    card(t("rst_t"), h("div", { class: "body-m pre" }, t("rst_t_scale"))));
}
