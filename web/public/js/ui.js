// @ts-check
// Małe komponenty interfejsu w stylu Material 3 (jak w aplikacji na Androida), bez frameworka
import { ICON_PATHS } from "./icons.js";
import { t } from "./i18n.js";

/**
 * Element DOM: h("div", {class: "x", onclick: fn}, dziecko, ...). Dzieci: węzły, teksty, tablice, null/false (pomijane).
 * @param {string} tag @param {Record<string, any> | null} [props] @param {...any} children @returns {HTMLElement}
 */
export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props ?? {})) {
    if (v == null || v === false) continue;
    if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className = v;
    else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
    else if (k === "html") el.innerHTML = v;
    else if (k in el && typeof v !== "string") /** @type {any} */ (el)[k] = v;
    else el.setAttribute(k, v === true ? "" : String(v));
  }
  append(el, children);
  return el;
}

/** @param {Node} el @param {any[]} children */
function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false) continue;
    el.appendChild(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

/** Zamienia zawartość elementu. @param {HTMLElement} el @param {...any} children */
export function replace(el, ...children) {
  el.replaceChildren();
  append(el, children);
  return el;
}

/** Ikona Material jako SVG. @param {string} name @param {string} [cls] */
export function icon(name, cls = "icon") {
  const span = document.createElement("span");
  span.className = cls;
  span.setAttribute("aria-hidden", "true");
  span.innerHTML = `<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor">${ICON_PATHS[name] ?? ""}</svg>`;
  return span;
}

/** Przycisk z ikoną. @param {string} name @param {string} label @param {(e: MouseEvent) => void} onclick @param {Record<string, any>} [extra] */
export function iconButton(name, label, onclick, extra = {}) {
  return h("button", { class: "icon-btn", type: "button", "aria-label": label, title: label, onclick, ...extra }, icon(name));
}

/**
 * Górny pasek ekranu. @param {{title: any, subtitle?: string, back?: () => void, actions?: any[]}} o
 */
export function topBar({ title, subtitle, back, actions = [] }) {
  return h("header", { class: "top-bar" },
    back ? iconButton("arrowBack", t("common_back"), back) : null,
    h("div", { class: "top-bar-title" }, h("h1", null, title), subtitle ? h("div", { class: "top-bar-sub" }, subtitle) : null),
    h("div", { class: "top-bar-actions" }, actions));
}

/** Karta z nagłówkiem (InfoCard). @param {{title?: string, onclick?: () => void, trailing?: any, class?: string}} o @param {...any} body */
export function infoCard({ title, onclick, trailing, class: cls = "" }, ...body) {
  const head = title || trailing ? h("div", { class: "info-card-head" }, title ? h("span", { class: "info-card-title" }, title) : null, trailing ?? null) : null;
  return h(onclick ? "button" : "section", { class: `card info-card ${onclick ? "clickable" : ""} ${cls}`, type: onclick ? "button" : null, onclick }, head, body);
}

/** Chip filtra. @param {string} label @param {boolean} selected @param {() => void} onclick */
export function chip(label, selected, onclick) {
  return h("button", { type: "button", class: `chip ${selected ? "selected" : ""}`, "aria-pressed": String(selected), onclick }, label);
}

/**
 * Pole tekstowe z etykietą (OutlinedTextField).
 * @param {{label: string, value: string, oninput?: (v: string) => void, onenter?: () => void, type?: string, inputmode?: string,
 *   placeholder?: string, uppercase?: boolean, error?: boolean, suffix?: string, support?: string, maxlength?: number, class?: string, trailing?: any}} o
 */
export function textField(o) {
  const input = /** @type {HTMLInputElement} */ (h("input", {
    type: o.type ?? "text", value: o.value, placeholder: o.placeholder ?? " ", inputmode: o.inputmode,
    autocapitalize: o.uppercase ? "characters" : "off", autocomplete: "off", spellcheck: "false", maxlength: o.maxlength,
    enterkeyhint: o.onenter ? "search" : null,
  }));
  input.addEventListener("input", () => {
    if (o.uppercase) {
      const pos = input.selectionStart;
      input.value = input.value.toUpperCase();
      input.setSelectionRange(pos, pos);
    }
    o.oninput?.(input.value);
  });
  if (o.onenter) input.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); o.onenter?.(); } });
  const support = h("div", { class: "field-support" }, o.support ?? "");
  const wrap = h("label", { class: `field ${o.error ? "error" : ""} ${o.class ?? ""}` },
    input, h("span", { class: "field-label" }, o.label), o.suffix ? h("span", { class: "field-suffix" }, o.suffix) : null, o.trailing ?? null, support);
  return Object.assign(wrap, { input, setError: (/** @type {boolean} */ e) => wrap.classList.toggle("error", e), setSupport: (s) => { support.textContent = s; } });
}

/** Lista rozwijana (ExposedDropdownMenu). @param {{label: string, value: string, options: [string, string][], onchange: (v: string) => void}} o */
export function selectField(o) {
  const select = h("select", { onchange: (e) => o.onchange(e.target.value) },
    o.options.map(([v, label]) => h("option", { value: v, selected: v === o.value }, label)));
  return h("label", { class: "field select" }, select, h("span", { class: "field-label" }, o.label), icon("expandMore", "icon field-caret"));
}

/** Przełącznik. @param {boolean} checked @param {(v: boolean) => void} onchange @param {string} label @param {boolean} [disabled] */
export function switchEl(checked, onchange, label, disabled = false) {
  return h("label", { class: `switch ${disabled ? "disabled" : ""}` },
    h("input", { type: "checkbox", role: "switch", checked, disabled, "aria-label": label, onchange: (e) => onchange(e.target.checked) }),
    h("span", { class: "switch-track" }));
}

/** Przycisk. @param {string} label @param {() => void} onclick @param {"filled"|"tonal"|"text"|"outlined"} [variant] @param {Record<string, any>} [extra] */
export function button(label, onclick, variant = "filled", extra = {}) {
  return h("button", { type: "button", class: `btn btn-${variant}`, onclick, ...extra }, extra.icon ? icon(extra.icon) : null, label);
}

// ---------------------------------------------------------------- okna dialogowe i komunikaty
/**
 * Okno dialogowe (AlertDialog). Zwraca funkcję zamykającą.
 * @param {{title: string, body: any, actions?: (close: () => void) => any[], onclose?: () => void, wide?: boolean, fullscreen?: boolean}} o
 */
export function dialog(o) {
  const close = () => {
    dlg.close();
    dlg.remove();
    o.onclose?.();
  };
  const dlg = /** @type {HTMLDialogElement} */ (h("dialog", { class: `dialog ${o.wide ? "wide" : ""} ${o.fullscreen ? "fullscreen" : ""}` },
    h("h2", { class: "dialog-title" }, o.title),
    h("div", { class: "dialog-body" }, o.body),
    o.actions ? h("div", { class: "dialog-actions" }, o.actions(close)) : null));
  dlg.addEventListener("cancel", (e) => { e.preventDefault(); close(); });
  dlg.addEventListener("click", (e) => { if (e.target === dlg) close(); });
  document.body.appendChild(dlg);
  dlg.showModal();
  return close;
}

/** Krótki komunikat na dole ekranu (Toast). @param {string} text @param {number} [ms] @param {{label: string, onclick: () => void}} [action] */
export function toast(text, ms = 3500, action) {
  const host = document.getElementById("toasts") ?? document.body;
  const el = h("div", { class: "toast", role: "status" }, h("span", null, text),
    action ? h("button", { class: "toast-action", type: "button", onclick: () => { action.onclick(); el.remove(); } }, action.label) : null);
  host.appendChild(el);
  requestAnimationFrame(() => el.classList.add("show"));
  setTimeout(() => { el.classList.remove("show"); setTimeout(() => el.remove(), 300); }, ms);
}

// ---------------------------------------------------------------- elementy ekranu ustawień
/** @param {string} title @param {string} iconName @param {...any} rows */
export function settingsSection(title, iconName, ...rows) {
  return h("section", { class: "settings-section" },
    h("div", { class: "settings-section-head" }, icon(iconName, "icon small"), h("span", null, title)),
    h("div", { class: "card settings-card" }, rows));
}

/** @param {string} title @param {string | null} [summary] @param {(() => void) | null} [onclick] @param {any} [trailing] */
export function settingsItem(title, summary, onclick, trailing) {
  return h(onclick ? "button" : "div", { class: `settings-item ${onclick ? "clickable" : ""}`, type: onclick ? "button" : null, onclick },
    h("div", { class: "settings-item-text" }, h("div", { class: "settings-item-title" }, title), summary ? h("div", { class: "settings-item-summary" }, summary) : null),
    trailing ?? null);
}

/**
 * Wybór jednej opcji z listy w okienku. @template T @param {string} title @param {[T, string][]} options @param {T} selected
 * @param {(v: T) => void} onselect @param {string | null} [summaryOverride] opis, gdy wartość jest spoza listy
 */
export function settingsChoice(title, options, selected, onselect, summaryOverride) {
  const label = summaryOverride ?? options.find(([v]) => v === selected)?.[1] ?? "—";
  return settingsItem(title, label, () => {
    /** @type {() => void} */
    let close = () => {};
    close = dialog({
      title,
      body: h("div", { class: "radio-list", role: "radiogroup" }, options.map(([v, l]) =>
        h("button", { type: "button", class: "radio-row", role: "radio", "aria-checked": String(v === selected), onclick: () => { close(); onselect(v); } },
          h("span", { class: `radio ${v === selected ? "on" : ""}` }), h("span", null, l)))),
      actions: (c) => [button(t("common_cancel"), c, "text")],
    });
  });
}

/**
 * Pole tekstowe edytowane w okienku; zapis po "Zapisz".
 * @param {{title: string, value: string, emptySummary?: string, placeholder?: string, uppercase?: boolean, secret?: boolean,
 *   inputmode?: string, validate?: (v: string) => string | null, onsave: (v: string) => void,
 *   trailing?: any, preview?: (v: string) => any}} o  preview – podgląd wpisywanej wartości w okienku
 */
export function settingsText(o) {
  const summary = !o.value.trim() ? o.emptySummary ?? t("settings_not_set") : o.secret ? t("settings_set") : o.value;
  return settingsItem(o.title, summary, () => {
    const previewHost = h("div", { class: "settings-preview" });
    let value = o.value;
    /** @type {HTMLButtonElement} */
    let save;
    const field = textField({
      label: o.title, value, placeholder: o.placeholder, uppercase: o.uppercase, type: o.secret ? "password" : "text", inputmode: o.inputmode,
      oninput: (v) => { value = v; check(); }, onenter: () => { if (!save.disabled) save.click(); },
    });
    const check = () => {
      const err = o.validate?.(value.trim()) ?? null;
      field.setError(err != null);
      field.setSupport(err ?? "");
      save.disabled = err != null;
      if (o.preview) replace(previewHost, o.preview(value.trim()));
    };
    dialog({
      title: o.title, body: o.preview ? h("div", { class: "col gap-s" }, field, previewHost) : field,
      actions: (close) => [
        button(t("common_cancel"), close, "text"),
        (save = /** @type {HTMLButtonElement} */ (button(t("common_save"), () => { o.onsave(value.trim()); close(); }, "text"))),
      ],
    });
    check();
    setTimeout(() => field.input.focus(), 50);
  }, o.trailing);
}

/** @param {string} title @param {string | null} summary @param {boolean} checked @param {(v: boolean) => void} onchange */
export function settingsSwitch(title, summary, checked, onchange) {
  const sw = switchEl(checked, onchange, title);
  const row = settingsItem(title, summary, null, sw);
  row.classList.add("clickable");
  row.addEventListener("click", (e) => { if (!(/** @type {HTMLElement} */ (e.target).closest(".switch"))) sw.querySelector("input")?.click(); });
  return row;
}

/** Suwak z wartością w tytule; zapis po puszczeniu. @param {{title: string, value: number, min: number, max: number, step: number, format: (v: number) => string, onsave: (v: number) => void}} o */
export function settingsSlider(o) {
  const val = h("span", { class: "slider-value" }, o.format(o.value));
  const input = h("input", { type: "range", min: o.min, max: o.max, step: o.step, value: o.value, "aria-label": o.title,
    oninput: (e) => { val.textContent = o.format(Number(e.target.value)); }, onchange: (e) => o.onsave(Number(e.target.value)) });
  return h("div", { class: "settings-slider" }, h("div", { class: "settings-slider-head" }, h("span", null, o.title), val), input);
}

export const divider = () => h("div", { class: "divider" });

/** Plakietka z emisją (kolory jak ModeBadge/ConditionBadge). @param {string} text @param {string} color */
export function badge(text, color) {
  return h("span", { class: "badge", style: { background: color } }, text);
}

/** Pobranie pliku (na iOS – arkusz udostępniania, z którego można zapisać do Plików). @param {string} name @param {string} text @param {string} mime */
export async function saveTextFile(name, text, mime) {
  const file = new File([text], name, { type: mime });
  const nav = /** @type {any} */ (navigator);
  const isIos = /iP(hone|ad|od)/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  if (isIos && nav.canShare?.({ files: [file] })) {
    try {
      await nav.share({ files: [file], title: name });
      return;
    } catch (e) {
      if (/** @type {Error} */ (e).name === "AbortError") return;
    }
  }
  const url = URL.createObjectURL(file);
  const a = h("a", { href: url, download: name });
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

/** Wybór pliku z urządzenia (na iOS – aplikacja Pliki). @param {string} accept @returns {Promise<File | null>} */
export function pickFile(accept) {
  return new Promise((resolve) => {
    const input = /** @type {HTMLInputElement} */ (h("input", { type: "file", accept, style: { display: "none" } }));
    input.addEventListener("change", () => { resolve(input.files?.[0] ?? null); input.remove(); });
    input.addEventListener("cancel", () => { resolve(null); input.remove(); });
    document.body.appendChild(input);
    input.click();
  });
}
