// @ts-check
// Kalkulatory antenowe, linie, SWR, moc, akumulator (CalculatorsScreen.kt) i tłumienność kabli (CoaxCalcScreen.kt)
import { h, replace, chip, textField, selectField } from "../ui.js";
import { t, fmt, format } from "../i18n.js";
import { AntennaMath, FeedlineMath, bandForKhz, parseNum, COAX_DATABASE, attenuationForFreq } from "../core/radio.js";
import { layout } from "./common.js";

const ANTENNA_TOOLS = ["DIPOLE", "EFHW", "GP", "JPOLE", "YAGI", "MOXON", "LOOP"];
const OTHER_TOOLS = ["STUB", "SWR", "POWER", "BATTERY"];
const LABELS = { DIPOLE: "calc_t_dipole", EFHW: "calc_t_efhw", GP: "calc_t_gp", JPOLE: "calc_t_jpole", YAGI: "calc_t_yagi", MOXON: "calc_t_moxon",
  LOOP: "calc_t_loop", STUB: "calc_t_stub", SWR: "calc_t_swr", POWER: "calc_t_power", BATTERY: "calc_t_battery" };
// Typowe częstotliwości robocze pasm (środek części fonicznej / popularne QRG)
const BAND_PRESETS = [["160", 1.84], ["80", 3.7], ["60", 5.355], ["40", 7.1], ["30", 10.12], ["20", 14.175], ["17", 18.12],
  ["15", 21.25], ["12", 24.94], ["10", 28.5], ["6", 50.15], ["2", 145.0], ["70 cm", 435.0]];

// Stan zostaje po powrocie na ekran (rememberSaveable w aplikacji)
const state = {
  tool: "DIPOLE", freq: "14.175", k: "0.95", wire: "2.0", vf: "0.66", z1: "50", z2: "100",
  byPower: true, fwd: "100", ref: "5", r: "75", x: "0", z0: "50", power: "100", loss: "1.5", gain: "2.15",
  capacity: "20", usable: "90", rx: "1.0", tx: "4.0", duty: "25",
};

/** Długość: ≥ 1 m w metrach, krócej w cm. @param {number} m */
export const formatLength = (m) => (m >= 1 ? fmt("%.2f m", m) : fmt("%.1f cm", m * 100));

/** @param {import("../app.js").ScreenContext} ctx */
export function calculatorsScreen(ctx) {
  const host = h("div", { class: "scroll" });
  const toolHost = h("div", { class: "col gap-l" });

  const render = () => {
    replace(host,
      chipGroup(t("calc_antennas"), ANTENNA_TOOLS), chipGroup(t("calc_matching"), OTHER_TOOLS), h("div", { class: "hr" }),
      ANTENNA_TOOLS.includes(state.tool) || state.tool === "STUB" ? frequencyInput() : null,
      toolHost);
    renderTool();
  };

  const chipGroup = (title, tools) => h("div", { class: "col gap-m" }, h("span", { class: "label-l primary" }, title),
    h("div", { class: "chips" }, tools.map((x) => chip(t(LABELS[x]), state.tool === x, () => { state.tool = x; render(); }))));

  const freqValue = () => { const f = parseNum(state.freq); return f != null && f >= 0.1 && f <= 10000 ? f : null; };

  function frequencyInput() {
    const support = () => {
      const f = freqValue();
      if (f == null) return t("calc_enter_freq");
      const band = bandForKhz(f * 1000);
      return band ? t("calc_band", band) : t("calc_outside_bands");
    };
    const field = textField({ label: t("calc_freq"), value: state.freq, inputmode: "decimal", support: support(), error: freqValue() == null,
      oninput: (v) => { state.freq = v; field.setError(freqValue() == null); field.setSupport(support()); updatePresets(); renderTool(); } });
    const presets = h("div", { class: "chips s" });
    const updatePresets = () => replace(presets, BAND_PRESETS.map(([label, mhz]) =>
      chip(String(label).endsWith("cm") ? String(label) : `${label} m`, freqValue() === mhz,
        () => { state.freq = format("%.3f", mhz); field.input.value = state.freq; field.setError(false); field.setSupport(support()); updatePresets(); renderTool(); })));
    updatePresets();
    return h("div", { class: "col gap-m" }, field, presets);
  }

  /** Pole liczbowe, które przelicza wynik przy każdej zmianie. */
  const num = (label, key, suffix, extra = {}) => {
    const f = textField({ label, value: state[key], inputmode: "decimal", suffix, error: parseNum(state[key]) == null,
      oninput: (v) => { state[key] = v; f.setError(parseNum(v) == null); results(); }, ...extra });
    return f;
  };
  const resultHost = h("div", { class: "col gap-l" });
  /** @type {() => any} */
  let compute = () => null;
  const results = () => replace(resultHost, compute());

  function renderTool() {
    const f = freqValue();
    const tool = state.tool;
    /** @type {any[]} */
    const inputs = [];
    const shortening = () => [num(t("calc_k_factor"), "k"), h("div", { class: "body-s muted" }, t("calc_k_note"))];
    const k = () => { const v = parseNum(state.k); return v != null && v >= 0.5 && v <= 1 ? v : null; };

    if (tool === "DIPOLE") {
      inputs.push(shortening());
      compute = () => {
        const kk = k(); if (f == null || kk == null) return null;
        const d = AntennaMath.dipole(f, kk);
        return [diagram("dipole"), resultCard(t("calc_dipole_title"),
          [[t("calc_total_length"), formatLength(d.total)], [t("calc_leg"), formatLength(d.leg)], [t("calc_wavelength"), formatLength(d.wavelength)]],
          ["calc_dipole_tip1", "calc_dipole_tip2", "calc_dipole_tip3"].map((x) => t(x)))];
      };
    } else if (tool === "EFHW") {
      inputs.push(shortening());
      compute = () => {
        const kk = k(); if (f == null || kk == null) return null;
        const e = AntennaMath.efhw(f, kk);
        const harmonics = e.harmonics.map(([mhz, band]) => `${band} (${fmt("%.3f", mhz)})`).join(", ");
        return [diagram("efhw"), resultCard(t("calc_efhw_title"),
          [[t("calc_radiator"), formatLength(e.radiator)], [t("calc_counterpoise"), formatLength(e.counterpoise)], [t("calc_transformer"), "49:1 (2,5–3 kΩ)"]],
          [harmonics ? t("calc_efhw_harmonics", harmonics) : null, t("calc_efhw_tip1"), t("calc_efhw_tip2")])];
      };
    } else if (tool === "GP") {
      inputs.push(shortening());
      compute = () => {
        const kk = k(); if (f == null || kk == null) return null;
        const g = AntennaMath.groundPlane(f, kk);
        return [diagram("gp"), resultCard(t("calc_gp_title"), [[t("calc_radiator"), formatLength(g.radiator)], [t("calc_radials"), formatLength(g.radials)]],
          ["calc_gp_tip1", "calc_gp_tip2", "calc_gp_tip3"].map((x) => t(x)))];
      };
    } else if (tool === "JPOLE") {
      inputs.push(shortening());
      compute = () => {
        const kk = k(); if (f == null || kk == null) return null;
        const j = AntennaMath.jPole(f, kk);
        return [diagram("jpole"), resultCard(t("calc_jpole_title"),
          [[t("calc_jpole_long"), formatLength(j.longElement)], [t("calc_jpole_short"), formatLength(j.stub)],
            [t("calc_jpole_feed"), `≈ ${formatLength(j.feedPoint)}`], [t("calc_jpole_spacing"), `${formatLength(j.spacingMin)} – ${formatLength(j.spacingMax)}`]],
          ["calc_jpole_tip1", "calc_jpole_tip2", "calc_jpole_tip3"].map((x) => t(x)))];
      };
    } else if (tool === "YAGI") {
      compute = () => {
        if (f == null) return null;
        const y = AntennaMath.yagi3(f);
        return [diagram("yagi", y), resultCard(t("calc_yagi_title"),
          [[t("calc_reflector"), formatLength(y.reflector)], [t("calc_driven"), formatLength(y.driven)], [t("calc_director"), formatLength(y.director)],
            [t("calc_spacing_rw"), formatLength(y.spacingReflector)], [t("calc_spacing_wd"), formatLength(y.spacingDirector)], [t("calc_boom"), formatLength(y.boom)]],
          ["calc_yagi_tip1", "calc_yagi_tip2", "calc_yagi_tip3"].map((x) => t(x)))];
      };
    } else if (tool === "MOXON") {
      inputs.push(num(t("calc_wire_diameter"), "wire", "mm"));
      compute = () => {
        const d = parseNum(state.wire);
        if (f == null || d == null || d <= 0 || d >= 200) return null;
        const m = AntennaMath.moxon(f, d / 1000);
        return [diagram("moxon"), resultCard(t("calc_moxon_title"),
          [["calc_moxon_a", m.a], ["calc_moxon_b", m.b], ["calc_moxon_c", m.c], ["calc_moxon_d", m.d], ["calc_moxon_e", m.e]].map(([key, val]) => [t(String(key)), formatLength(Number(val))]),
          ["calc_moxon_tip1", "calc_moxon_tip2", "calc_moxon_tip3"].map((x) => t(x)))];
      };
    } else if (tool === "LOOP") {
      compute = () => {
        if (f == null) return null;
        const l = AntennaMath.loop(f);
        return [diagram("loop"), resultCard(t("calc_loop_title"),
          [[t("calc_loop_perimeter"), formatLength(l.perimeter)], [t("calc_loop_quad"), formatLength(l.quadSide)],
            [t("calc_loop_delta"), formatLength(l.deltaSide)], [t("calc_loop_reflector"), formatLength(l.reflectorPerimeter)]],
          ["calc_loop_tip1", "calc_loop_tip2", "calc_loop_tip3"].map((x) => t(x)))];
      };
    } else if (tool === "STUB") {
      const vfChips = h("div", { class: "chips s" });
      const vfField = num("VF", "vf");
      const updateVf = () => replace(vfChips, [["0.66", "RG-58/213 (PE)"], ["0.78", t("calc_vf_foam")], ["0.83", "H-155"], ["0.85", "LMR-400 / Aircell 7"], ["0.95", t("calc_vf_ladder")]]
        .map(([val, label]) => chip(`${label} ${val}`, state.vf === val, () => { state.vf = val; vfField.input.value = val; vfField.setError(false); updateVf(); results(); })));
      vfField.input.addEventListener("input", updateVf);
      updateVf();
      // Wyniki w dwóch miejscach (odcinki linii, potem transformator λ/4) – pola impedancji między nimi nie są przebudowywane
      const stubHost = h("div");
      const qHost = h("div");
      inputs.push(h("span", { class: "label-l" }, t("calc_vf")), vfChips, vfField, stubHost,
        h("div", { class: "title-s primary" }, t("calc_qmatch")),
        h("div", { class: "fields" }, num(t("calc_z_source"), "z1", "Ω"), num(t("calc_z_load"), "z2", "Ω")), qHost);
      compute = () => {
        const vf = parseNum(state.vf);
        if (f == null || vf == null || vf < 0.4 || vf > 1) { replace(stubHost); replace(qHost); return null; }
        const s = FeedlineMath.stubs(f, vf);
        const a = parseNum(state.z1), b = parseNum(state.z2);
        replace(stubHost, resultCard(t("calc_stub_title"), [[t("calc_stub_quarter"), formatLength(s.quarter)], [t("calc_stub_half"), formatLength(s.half)], [t("calc_stub_full"), formatLength(s.full)]],
          [t("calc_stub_tip1"), t("calc_stub_tip2")]));
        replace(qHost, a != null && b != null && a > 0 && b > 0 ? resultCard(t("calc_q_title"),
          [[t("calc_q_required"), fmt("%.1f Ω", FeedlineMath.quarterWaveZ0(a, b))], [t("calc_q_length"), formatLength(s.quarter)]], [t("calc_q_tip")]) : null);
        return null;
      };
    } else if (tool === "SWR") {
      const mode = h("div", { class: "chips" },
        chip(t("calc_by_power"), state.byPower, () => { state.byPower = true; renderTool(); }),
        chip(t("calc_by_impedance"), !state.byPower, () => { state.byPower = false; renderTool(); }));
      inputs.push(mode, state.byPower
        ? h("div", { class: "fields" }, num(t("calc_forward"), "fwd", "W"), num(t("calc_reflected"), "ref", "W"))
        : h("div", { class: "fields" }, num("R", "r", "Ω"), num("X", "x", "Ω"), num("Z₀", "z0", "Ω")));
      compute = () => {
        let gamma = null;
        if (state.byPower) {
          const fw = parseNum(state.fwd), rr = parseNum(state.ref);
          if (fw != null && rr != null && fw > 0 && rr >= 0 && rr < fw) gamma = FeedlineMath.gammaFromPower(fw, rr);
        } else {
          const r = parseNum(state.r), x = parseNum(state.x), z = parseNum(state.z0);
          if (r != null && x != null && z != null && r >= 0 && z > 0) gamma = FeedlineMath.gammaFromImpedance(r, x, z);
        }
        if (gamma == null) return null;
        const swr = FeedlineMath.swr(gamma);
        return resultCard(t("calc_match_title"), [
          [t("calc_t_swr"), !Number.isFinite(swr) || swr > 99 ? "> 99" : fmt("%.2f : 1", swr)],
          [t("calc_gamma"), fmt("%.3f", gamma)],
          [t("calc_reflected"), fmt("%.1f %%", gamma * gamma * 100)],
          [t("calc_return_loss"), gamma <= 1e-6 ? "∞" : fmt("%.1f dB", FeedlineMath.returnLossDb(gamma))],
          [t("calc_mismatch_loss"), fmt("%.2f dB", FeedlineMath.mismatchLossDb(gamma))],
        ], [t("calc_swr_tip1"), t("calc_swr_tip2")]);
      };
    } else if (tool === "POWER") {
      inputs.push(h("div", { class: "fields" }, num(t("calc_tx_power"), "power", "W"), num(t("calc_cable_loss"), "loss", "dB")),
        num(t("calc_antenna_gain"), "gain", "dBi"), h("div", { class: "body-s muted" }, t("calc_gain_note")));
      compute = () => {
        const p = parseNum(state.power), l = parseNum(state.loss), g = parseNum(state.gain);
        if (p == null || p <= 0 || l == null || g == null) return null;
        const r = FeedlineMath.radiated(p, l, g);
        return resultCard(t("calc_radiated"), [
          [t("calc_tx_power"), fmt("%.1f dBm / %.1f dBW", r.txDbm, r.txDbm - 30)], [t("calc_at_antenna"), fmt("%.1f W", r.atAntennaW)],
          [t("calc_eirp"), fmt("%.1f W", r.eirpW)], [t("calc_erp"), fmt("%.1f W", r.erpW)],
        ], [t("calc_power_tip1"), t("calc_power_tip2")]);
      };
    } else if (tool === "BATTERY") {
      const usableField = num(t("calc_usable"), "usable", "%");
      const dutyField = num(t("calc_tx_share_field"), "duty", "%");
      const presetChips = (key, options, fieldEl, label) => {
        const box = h("div", { class: "chips s" });
        const upd = () => replace(box, options.map(([val, name]) => chip(label(name, val), state[key] === val,
          () => { state[key] = val; fieldEl.input.value = val; fieldEl.setError(false); upd(); results(); })));
        fieldEl.input.addEventListener("input", upd);
        upd();
        return box;
      };
      inputs.push(h("span", { class: "label-l" }, t("calc_battery")),
        presetChips("usable", [["90", "LiFePO4"], ["85", "Li-ion"], ["50", t("calc_agm")]], usableField, (n, v) => `${n} (${v} %)`),
        h("div", { class: "fields" }, num(t("calc_capacity"), "capacity", "Ah"), usableField),
        h("div", { class: "fields" }, num(t("calc_rx_current"), "rx", "A"), num(t("calc_tx_current"), "tx", "A")),
        h("span", { class: "label-l" }, t("calc_tx_share")),
        presetChips("duty", [["20", "SSB"], ["40", "CW"], ["50", "FT8"], ["10", t("calc_duty_quiet")]], dutyField, (n, v) => `${n} ${v} %`),
        dutyField);
      compute = () => {
        const c = parseNum(state.capacity), u = parseNum(state.usable), rx = parseNum(state.rx), tx = parseNum(state.tx), d = parseNum(state.duty);
        if (c == null || c <= 0 || u == null || u < 1 || u > 100 || rx == null || rx < 0 || tx == null || tx < 0 || d == null || d < 0 || d > 100) return null;
        const b = FeedlineMath.battery(c, u, rx, tx, d);
        const hrs = b.hours;
        return resultCard(t("calc_runtime"), [
          [t("calc_avg_current"), fmt("%.2f A", b.averageA)], [t("calc_avail_capacity"), fmt("%.1f Ah", b.usableAh)],
          [t("calc_est_runtime"), !Number.isFinite(hrs) ? "∞" : `${Math.trunc(hrs)} h ${String(Math.trunc((hrs % 1) * 60)).padStart(2, "0")} min`],
        ], [t("calc_battery_tip1"), t("calc_battery_tip2")]);
      };
    }
    replace(toolHost, inputs, resultHost);
    results();
  }

  render();
  const { el } = layout(ctx, { title: t("calc_title"), body: host });
  return { el };
}

/** @param {string} title @param {[string, string][]} rows @param {(string | null)[]} [tips] */
function resultCard(title, rows, tips = []) {
  const list = tips.filter(Boolean);
  return h("section", { class: "card pad col gap-m" },
    h("div", { class: "title-m bold primary" }, title),
    rows.map(([k, v]) => h("div", { class: "kv" }, h("span", { class: "body-m grow" }, k), h("span", { class: "body-l v" }, v))),
    list.length ? h("div", { class: "tips" }, list.map((x) => h("div", { class: "body-s muted" }, `• ${x}`))) : null);
}

// ---------------------------------------------------------------- schematy anten (jak Canvas w aplikacji)
const svg = (height, inner) => h("div", { class: "diagram", html:
  `<svg viewBox="0 0 320 ${height}" role="img" aria-hidden="true" style="--el:var(--primary);--ac:var(--tertiary);--mu:var(--outline)">${inner}</svg>` });
const line = (x1, y1, x2, y2, c = "var(--el)", w = 4) => `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${c}" stroke-width="${w}" stroke-linecap="round"/>`;
const dot = (x, y, r = 5) => `<circle cx="${x}" cy="${y}" r="${r}" fill="var(--ac)"/>`;
const label = (x, y, s) => `<text x="${x}" y="${y}" fill="var(--on-surface)" font-size="13" font-weight="700" text-anchor="middle" dominant-baseline="middle">${s}</text>`;

/** @param {string} kind @param {any} [data] */
function diagram(kind, data) {
  if (kind === "dipole") {
    const y = 60, cx = 160;
    return svg(130, line(8, y, cx - 8, y) + line(cx + 8, y, 312, y) + line(cx, y, cx, y + 50, "var(--mu)", 3) + dot(cx, y) +
      label(cx / 2, y - 18, "A") + label(cx * 1.5, y - 18, "A") + label(cx + 34, y + 38, "50 Ω"));
  }
  if (kind === "efhw") {
    return svg(130, `<rect x="4" y="22" width="30" height="26" fill="var(--ac)" rx="3"/>` + line(34, 35, 312, 15) + line(19, 48, 96, 122, "var(--mu)", 3) +
      label(176, 8, "A") + label(64, 96, "B") + label(19, 14, "49:1"));
  }
  if (kind === "gp") {
    const bx = 160, by = 95;
    let s = line(bx, by, bx, 8);
    for (const a of [200, 340, 245, 295]) {
      const r = (a * Math.PI) / 180;
      s += line(bx, by, bx + 105 * Math.cos(r), by - 38 * Math.sin(r), "var(--mu)", 3);
    }
    return svg(145, s + line(bx, by, bx, by + 45, "var(--mu)", 3) + dot(bx, by) + label(bx + 18, 45, "A") + label(bx - 84, by + 8, "B"));
  }
  if (kind === "jpole") {
    const bottom = 150, xl = 140, xs = 180, top = 8, shortTop = bottom - (bottom - top) / 3, feed = bottom - (bottom - shortTop) * 0.12;
    return svg(160, line(xl, bottom, xl, top) + line(xs, bottom, xs, shortTop) + line(xl, bottom, xs, bottom) + dot(xl, feed) + dot(xs, feed) +
      label(xl - 18, (top + bottom) / 2, "A") + label(xs + 18, (shortTop + bottom) / 2, "B") + label(xs + 20, feed, "C") + label((xl + xs) / 2, bottom - 16, "D"));
  }
  if (kind === "yagi") {
    const y = /** @type {{reflector: number, driven: number, director: number}} */ (data);
    const cx = 160, scale = 256 / y.reflector, ys = [20, 20 + 120 * 0.57, 140];
    const lens = [y.reflector, y.driven, y.director];
    return svg(160, line(cx, 8, cx, 152, "var(--mu)", 6) +
      lens.map((len, i) => line(cx - (len * scale) / 2, ys[i], cx + (len * scale) / 2, ys[i], i === 1 ? "var(--ac)" : "var(--el)", 5)).join("") +
      label(12, ys[0], "R") + label(12, ys[1], "W") + label(12, ys[2], "D") + label(cx + 20, (ys[0] + ys[1]) / 2, "S1") + label(cx + 20, (ys[1] + ys[2]) / 2, "S2"));
  }
  if (kind === "moxon") {
    const l = 40, r = 280, top = 14, bottom = 136, gt = top + (bottom - top) * 0.4, gb = top + (bottom - top) * 0.52;
    return svg(150, `<path d="M${l} ${gt}V${top}H${r}V${gt}" fill="none" stroke="var(--el)" stroke-width="4"/>` +
      `<path d="M${l} ${gb}V${bottom}H${r}V${gb}" fill="none" stroke="var(--mu)" stroke-width="4"/>` + dot(160, top) +
      label(160, top + 18, "A") + label(l - 18, (top + gt) / 2, "B") + label(l - 18, (gt + gb) / 2, "C") + label(l - 18, (gb + bottom) / 2, "D") + label(r + 18, (top + bottom) / 2, "E"));
  }
  // pętla: kwadrat (quad) i trójkąt (delta)
  const hh = 110, x0 = 40, tx = 190;
  return svg(130, `<rect x="${x0}" y="10" width="${hh}" height="${hh}" fill="none" stroke="var(--el)" stroke-width="4"/>` + dot(x0 + hh / 2, 10 + hh) + label(x0 - 16, 10 + hh / 2, "A") +
    `<path d="M${tx} ${10 + hh}H${tx + hh * 1.15}L${tx + hh * 0.575} 10Z" fill="none" stroke="var(--el)" stroke-width="4"/>` + dot(tx + hh * 0.575, 10 + hh) + label(tx + hh * 0.22, 10 + hh * 0.45, "B"));
}

// ---------------------------------------------------------------- kable koncentryczne
const coax = { cable: COAX_DATABASE[0].name, freq: "145", length: "10", power: "50" };

/** @param {import("../app.js").ScreenContext} ctx */
export function coaxScreen(ctx) {
  const result = h("div");
  const compute = () => {
    const cable = COAX_DATABASE.find((c) => c.name === coax.cable) ?? COAX_DATABASE[0];
    const freq = parseNum(coax.freq) ?? 0, length = parseNum(coax.length) ?? 0, pwr = parseNum(coax.power) ?? 0;
    const att = attenuationForFreq(cable, freq);
    if (att >= 10000) return replace(result, h("div", { class: "card error pad" }, t("coax_no_data")));
    if (!(freq > 0 && length > 0 && pwr > 0)) return replace(result);
    const total = att * (length / 100);
    const out = 10 ** (Math.log10(pwr) - total / 10);
    const lossPct = 100 - 10 ** (2 - total / 10);
    const col = (labelText, value, cls = "") => h("div", { class: "col", style: { alignItems: "center" } },
      h("span", { class: "body-s muted" }, labelText), h("span", { class: `headline-s bold ${cls}` }, value));
    replace(result, h("section", { class: "card pad col gap-l", style: { alignItems: "center", background: "var(--surface-variant)" } },
      h("div", { class: "title-m bold primary center" }, t("coax_result", cable.name)),
      h("div", { class: "row", style: { justifyContent: "space-evenly", width: "100%" } },
        col(t("coax_attenuation"), format("%.2f dB", total)), col(t("coax_power_at_antenna"), format("%.1f W", out), "primary")),
      h("div", { class: "hr", style: { width: "100%" } }),
      h("div", { class: "col center" }, h("span", { class: "semibold" }, t("coax_loss_percent", format("%.1f", lossPct))), h("span", null, t("coax_power_lost", format("%.1f", pwr - out))))));
  };
  const field = (labelText, key) => textField({ label: labelText, value: coax[key], inputmode: "decimal", oninput: (v) => { coax[key] = v; compute(); } });
  const { el } = layout(ctx, { title: t("coax_title") },
    selectField({ label: t("coax_choose"), value: coax.cable, options: COAX_DATABASE.map((c) => [c.name, c.name]), onchange: (v) => { coax.cable = v; compute(); } }),
    h("div", { class: "fields" }, field(t("coax_freq"), "freq"), field(t("coax_length"), "length")),
    field(t("coax_power"), "power"),
    result,
    h("div", { class: "body-s muted center" }, t("coax_credit")));
  compute();
  return { el };
}
