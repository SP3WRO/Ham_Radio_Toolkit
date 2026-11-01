// @ts-check
// Trener CW metodą Kocha i dekoder CW z mikrofonu (CwScreens.kt)
import { h, replace, button, switchEl, toast } from "../ui.js";
import { t, format } from "../i18n.js";
import { settings } from "../store.js";
import { MORSE, KOCH_LESSONS, kochCharacters, cwTiming, renderCw, randomGroups, cwAccuracy, CwDecoder } from "../core/cw.js";
import { playSamples, startMic, audioRate } from "../audio.js";
import { layout } from "./common.js";

/**
 * Suwak z etykietą. @param {(v: number) => string} label @param {number} min @param {number} max @param {number} step
 * @param {number} value @param {(v: number) => void} oninput @param {() => void} [onchange]
 */
function slider(label, min, max, step, value, oninput, onchange) {
  const text = h("div", { class: "body-m" }, label(value));
  const input = /** @type {HTMLInputElement} */ (h("input", { type: "range", min, max, step, value, "aria-label": label(value),
    oninput: () => { oninput(Number(input.value)); text.textContent = label(Number(input.value)); }, onchange: () => onchange?.() }));
  return Object.assign(h("div", { class: "col" }, text, input), { input, text });
}

/** @param {unknown} e */
function micError(e) {
  const name = /** @type {any} */ (e)?.name;
  toast(name === "NotAllowedError" ? t("dec_mic_denied") : `${t("dec_mic_failed")}: ${/** @type {Error} */ (e)?.message ?? e}`);
}

// ---------------------------------------------------------------- trener
/** @param {import("../app.js").ScreenContext} ctx */
export function cwTrainerScreen(ctx) {
  const s = settings.get();
  let lesson = s.cw_lesson, charWpm = s.cw_char_wpm, effWpm = Math.min(s.cw_effective_wpm, s.cw_char_wpm), tone = s.cw_tone_hz, groups = 10;
  let expected = "", typed = "";
  /** @type {number | null} */
  let result = null;
  /** @type {{stop: () => void} | null} */
  let player = null;
  const save = () => settings.set({ cw_lesson: lesson, cw_char_wpm: charWpm, cw_effective_wpm: effWpm, cw_tone_hz: tone });

  const lessonCard = h("div", { class: "card pad col gap-s" });
  const buttons = h("div", { class: "row wrap", style: { gap: "8px" } });
  const resultHost = h("div");
  const input = /** @type {HTMLTextAreaElement} */ (h("textarea", { class: "textarea code", rows: 4, placeholder: t("cwt_type_hint"), autocapitalize: "characters",
    autocomplete: "off", spellcheck: "false", oninput: () => { typed = input.value.toUpperCase(); } }));
  const checkBtn = /** @type {HTMLButtonElement} */ (button(t("cwt_check"), () => { result = cwAccuracy(expected, typed); renderResult(); }));

  /** @param {string} text */
  async function play(text) {
    player?.stop();
    const rate = audioRate();
    const p = playSamples(renderCw(text, cwTiming(charWpm, Math.min(effWpm, charWpm)), { sampleRate: rate, toneHz: tone }), rate);
    player = p;
    renderButtons();
    await p.done;
    if (player === p) { player = null; renderButtons(); }
  }

  function renderLesson() {
    const chars = kochCharacters(lesson);
    const ch = chars[chars.length - 1];
    replace(lessonCard,
      h("div", { class: "title-m bold" }, t("cwt_lesson", lesson, KOCH_LESSONS)),
      h("div", { class: "code" }, t("cwt_chars", [...chars].join(" "))),
      h("div", { class: "row", style: { gap: "8px" } }, h("span", { class: "body-l semibold primary grow" }, t("cwt_new_char", ch, MORSE[ch])),
        button(t("cwt_listen"), () => play(`${ch} ${ch} ${ch}`), "outlined", { disabled: !!player })),
      h("input", { type: "range", min: 1, max: KOCH_LESSONS, step: 1, value: lesson, "aria-label": t("cwt_lesson", lesson, KOCH_LESSONS),
        oninput: (/** @type {any} */ e) => { lesson = Number(e.target.value); /** @type {HTMLElement} */ (lessonCard.firstChild).textContent = t("cwt_lesson", lesson, KOCH_LESSONS); },
        onchange: () => { save(); renderLesson(); } }));
  }

  function renderButtons() {
    replace(buttons,
      button(t("cwt_new_round"), () => { expected = randomGroups(kochCharacters(lesson), groups); typed = ""; input.value = ""; result = null; renderResult(); play(expected); },
        "filled", { icon: "playArrow", disabled: !!player }),
      button(t("cwt_repeat"), () => play(expected), "outlined", { disabled: !!player || !expected || result != null }),
      player ? button(t("common_stop"), () => { player?.stop(); player = null; renderButtons(); }, "outlined", { icon: "stop" }) : null);
    checkBtn.disabled = !expected || !!player;
    lessonCard.querySelectorAll("button").forEach((b) => { /** @type {HTMLButtonElement} */ (b).disabled = !!player; });
  }

  function renderResult() {
    renderButtons();
    if (result == null) return replace(resultHost);
    const acc = result;
    replace(resultHost, h("div", { class: "card pad col gap-s" },
      h("div", { class: `title-l bold ${acc >= 0.9 ? "primary" : "error-text"}` }, t("cwt_accuracy", format("%.0f", acc * 100)).replace("%%", "%")),
      h("div", { class: "code body-m" }, t("cwt_sent", expected)),
      h("div", { class: "code body-m" }, t("cwt_received", typed.trim())),
      acc >= 0.9 && lesson < KOCH_LESSONS ? [h("div", null, t("cwt_pass")),
        h("div", null, button(t("cwt_next_lesson", lesson + 1, kochCharacters(lesson + 1).slice(-1)), () => {
          lesson++; save(); expected = ""; typed = ""; input.value = ""; result = null; renderLesson(); renderResult();
        }))]
        : acc < 0.9 ? h("div", { class: "body-m" }, t("cwt_stay")) : null));
  }

  const speeds = h("div", { class: "card pad col gap-s" });
  const charSlider = slider((v) => t("cwt_char_speed", v), 10, 40, 1, charWpm, (v) => {
    charWpm = v;
    if (effWpm > charWpm) { effWpm = charWpm; effSlider.input.value = String(effWpm); effSlider.text.textContent = t("cwt_eff_speed", effWpm); }
  }, save);
  const effSlider = slider((v) => t("cwt_eff_speed", v), 5, 40, 1, effWpm, (v) => {
    effWpm = Math.min(v, charWpm);
    if (v > charWpm) effSlider.input.value = String(effWpm);
  }, save);
  speeds.append(charSlider, effSlider,
    slider((v) => t("cwt_tone", v), 400, 1000, 50, tone, (v) => { tone = v; }, save),
    slider((v) => t("cwt_groups", v), 5, 30, 1, groups, (v) => { groups = v; }));

  renderLesson();
  renderResult();
  const { el } = layout(ctx, { title: t("cwt_title") }, lessonCard, speeds, buttons, input, h("div", null, checkBtn), resultHost);
  return { el, destroy: () => player?.stop() };
}

// ---------------------------------------------------------------- dekoder
/** @param {import("../app.js").ScreenContext} ctx */
export function cwDecoderScreen(ctx) {
  let text = "";
  let autoTone = true;
  /** @type {{stop: () => void} | null} */
  let mic = null;
  /** @type {CwDecoder | null} */
  let decoder = null;
  let toneHz = 600;
  const out = h("div", { class: "decoder-text code" });
  const info = h("div", { class: "row between bold" });
  const meter = h("div", { class: "meter" }, h("div"));
  const controls = h("div", { class: "col gap-s" });

  const append = (/** @type {string} */ piece) => { text = (text + piece).slice(-2000); renderText(); };
  const renderText = () => {
    out.textContent = text || (mic ? t("cwd_listening") : t("cwd_press_listen"));
    out.classList.toggle("muted", !text);
    out.scrollTop = out.scrollHeight;
  };
  const renderInfo = () => {
    const wpm = mic && decoder ? decoder.wpm : 0;
    replace(info, h("span", null, wpm > 0 ? `≈ ${wpm} WPM` : "– WPM"), h("span", null, t("cwd_tone", format("%.0f", decoder?.toneHz ?? toneHz))));
    /** @type {HTMLElement} */ (meter.firstChild).style.width = `${Math.round((mic && decoder ? decoder.signalLevel : 0) * 100)}%`;
  };

  async function toggle() {
    if (mic) { mic.stop(); mic = null; renderControls(); renderText(); return; }
    try {
      mic = await startMic((samples) => decoder?.process(samples));
      decoder = new CwDecoder(audioRate(), toneHz, 20, append);
      decoder.autoTone = autoTone;
    } catch (e) {
      mic = null;
      micError(e);
    }
    renderControls();
    renderText();
  }

  function renderControls() {
    const toneSlider = slider((v) => t("cwd_tone", String(v)), 300, 1200, 10, Math.round(decoder?.toneHz ?? toneHz), (v) => { toneHz = v; decoder?.setTone(v); });
    replace(controls,
      h("label", { class: "row between" }, h("span", null, t("cwd_auto_tone")),
        switchEl(autoTone, (v) => { autoTone = v; if (decoder) decoder.autoTone = v; renderControls(); }, t("cwd_auto_tone"))),
      autoTone ? null : toneSlider,
      h("div", { class: "row", style: { gap: "8px" } },
        button(mic ? t("common_stop") : t("cwd_listen"), toggle, "filled", { icon: mic ? "stop" : "mic" }),
        button(t("common_clear"), () => { text = ""; renderText(); }, "outlined")),
      h("div", { class: "body-s muted" }, t("cwd_info")));
  }

  const timer = setInterval(renderInfo, 100);
  renderText();
  renderInfo();
  renderControls();
  const body = h("div", { class: "scroll decoder" }, out, info, meter, controls);
  const { el } = layout(ctx, { title: t("cwd_title"), body });
  return { el, destroy: () => { clearInterval(timer); mic?.stop(); } };
}
