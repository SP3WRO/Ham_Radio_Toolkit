// @ts-check
// Pasma, znaki, wzory antenowe, kable i beacony NCDXF – port QsoEntry.kt (bandForKhz), utils/Callsigns.kt,
// calc/AntennaMath.kt, CoaxCalcScreen.kt i data/beacons/NcdxfBeacons.kt

/** Pasmo amatorskie dla częstotliwości w kHz albo null poza pasmami. @param {number} khz */
export function bandForKhz(khz) {
  /** @type {[number, number, string][]} */
  const r = [
    [1800, 2000, "160m"], [3500, 4000, "80m"], [5350, 5450, "60m"], [7000, 7300, "40m"],
    [10100, 10150, "30m"], [14000, 14350, "20m"], [18068, 18168, "17m"], [21000, 21450, "15m"],
    [24890, 24990, "12m"], [28000, 29700, "10m"], [50000, 54000, "6m"], [70000, 71000, "4m"],
    [144000, 148000, "2m"], [430000, 440000, "70cm"], [1240000, 1300000, "23cm"],
  ];
  for (const [lo, hi, name] of r) if (khz >= lo && khz <= hi) return /** @type {string} */ (name);
  return null;
}

/** Jak calculateBandFromFreq w Kotlinie: domyślnie "20m". @param {string} freqStr kHz */
export function calculateBandFromFreq(freqStr) {
  const v = parseNum(freqStr);
  return (v != null && bandForKhz(v)) || "20m";
}

/** Liczba z pola tekstowego (przecinek albo kropka) albo null. @param {string | null | undefined} s */
export function parseNum(s) {
  const t = String(s ?? "").trim().replace(",", ".");
  if (t === "" || !/^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/.test(t)) return null;
  const v = Number(t);
  return Number.isFinite(v) ? v : null;
}

// ---------------------------------------------------------------- znaki
const AMATEUR = /^(?:[A-Z]{1,2}|[A-Z][0-9]|[0-9][A-Z]{1,2})[0-9]{1,4}[A-Z]{1,4}$/;
const EXTRA_PART = /^[A-Z0-9]{1,4}$/;
const PLACEHOLDERS = new Set(["N0CALL", "N0CAL", "MYCALL"]);

export const Callsigns = {
  /** Znak bez dopisków portable/mobile: SP3ABC/P -> SP3ABC, DL/SP3ABC -> SP3ABC. @param {string} callsign */
  baseCall(callsign) {
    const parts = String(callsign).trim().toUpperCase().split("/").filter((p) => p.length > 0);
    let best = "";
    for (const p of parts) if (p.length > best.length) best = p;
    return best;
  },

  /** Czy to wygląda na prawdziwy znak krótkofalarski (z ewentualnym /P, /M, DL/…). @param {string} callsign */
  isValidAmateur(callsign) {
    const call = String(callsign ?? "").trim().toUpperCase();
    if (call.length === 0 || call.length > 16) return false;
    const parts = call.split("/");
    if (parts.some((p) => p.length === 0)) return false;
    let baseIndex = 0;
    parts.forEach((p, i) => { if (p.length > parts[baseIndex].length) baseIndex = i; });
    const base = parts[baseIndex];
    if (PLACEHOLDERS.has(base) || !AMATEUR.test(base)) return false;
    return parts.every((p, i) => i === baseIndex || EXTRA_PART.test(p));
  },
};

// ---------------------------------------------------------------- anteny (długości w m, częstotliwość w MHz)
const C = 299.792458;
const FEET = 0.3048;

export const AntennaMath = {
  /** @param {number} f */
  wavelength: (f) => C / f,

  /** Dipol półfalowy: λ/2·K (K = 0,95 odpowiada popularnemu 142,5/f). */
  dipole(f, k = 0.95) {
    const lambda = this.wavelength(f);
    const total = 0.5 * lambda * k;
    return { total, leg: total / 2, wavelength: lambda };
  },

  /** Półfalówka zasilana na końcu: promiennik λ/2·K, przeciwwaga ok. 0,05 λ; pasma harmoniczne ×2…×4. */
  efhw(f, k = 0.95) {
    const lambda = this.wavelength(f);
    /** @type {[number, string][]} */
    const harmonics = [];
    for (let n = 2; n <= 4; n++) {
      const band = bandForKhz(f * n * 1000);
      if (band) harmonics.push([f * n, band]);
    }
    return { radiator: 0.5 * lambda * k, counterpoise: 0.05 * lambda, harmonics };
  },

  groundPlane(f, k = 0.95) {
    const radiator = 0.25 * this.wavelength(f) * k;
    // Radiale podniesione – nieco dłuższe od promiennika
    return { radiator, radials: radiator * 1.05 };
  },

  jPole(f, k = 0.95) {
    const lambda = this.wavelength(f);
    return {
      longElement: 0.75 * lambda * k,
      stub: 0.25 * lambda * k,
      feedPoint: 0.025 * lambda,
      spacingMin: 0.01 * lambda,
      spacingMax: 0.02 * lambda,
    };
  },

  /** Klasyczne wzory 492/f, 468/f, 447/f (stopy) i odstępy 0,2 λ / 0,15 λ. */
  yagi3(f) {
    const lambda = this.wavelength(f);
    const sr = 0.2 * lambda, sd = 0.15 * lambda;
    return {
      reflector: (492 * FEET) / f, driven: (468 * FEET) / f, director: (447 * FEET) / f,
      spacingReflector: sr, spacingDirector: sd, boom: sr + sd,
    };
  },

  /** Prostokąt Moxona – równania L.B. Cebika (W4RNL); średnica przewodu w m. */
  moxon(f, wireDiameterM) {
    const lambda = this.wavelength(f);
    const d1 = Math.log10(wireDiameterM / lambda);
    const A = -0.0008571428571 * d1 ** 2 - 0.009571428571 * d1 + 0.3398571429;
    const B = -0.002142857143 * d1 ** 2 - 0.02035714286 * d1 + 0.008285714286;
    const Cc = 0.001809523381 * d1 ** 2 + 0.01780952381 * d1 + 0.05164285714;
    const D = 0.001 * d1 + 0.07178571429;
    return { a: A * lambda, b: B * lambda, c: Cc * lambda, d: D * lambda, e: (B + Cc + D) * lambda };
  },

  /** Pętla pełnofalowa: 1005/f ft, reflektor quada 1030/f ft. */
  loop(f) {
    const perimeter = (1005 * FEET) / f;
    return { perimeter, quadSide: perimeter / 4, deltaSide: perimeter / 3, reflectorPerimeter: (1030 * FEET) / f };
  },
};

/** Linie zasilające, dopasowanie, moc i zasilanie. */
export const FeedlineMath = {
  stubs(f, velocityFactor) {
    const full = AntennaMath.wavelength(f) * velocityFactor;
    return { full, half: full / 2, quarter: full / 4 };
  },
  quarterWaveZ0: (z1, z2) => Math.sqrt(z1 * z2),
  gammaFromPower: (fwd, ref) => Math.sqrt(ref / fwd),
  gammaFromImpedance: (r, x, z0) => Math.sqrt(((r - z0) ** 2 + x * x) / ((r + z0) ** 2 + x * x)),
  swr: (g) => (g >= 1 ? Infinity : (1 + g) / (1 - g)),
  returnLossDb: (g) => -20 * Math.log10(g),
  mismatchLossDb: (g) => (g >= 1 ? Infinity : -10 * Math.log10(1 - g * g)),

  radiated(powerW, cableLossDb, gainDbi) {
    const atAntenna = powerW * 10 ** (-cableLossDb / 10);
    const eirp = atAntenna * 10 ** (gainDbi / 10);
    // Dipol półfalowy ma 2,15 dBi
    return { txDbm: 10 * Math.log10(powerW * 1000), atAntennaW: atAntenna, eirpW: eirp, erpW: eirp / 10 ** (2.15 / 10) };
  },

  battery(capacityAh, usablePercent, rxA, txA, txDutyPercent) {
    const duty = txDutyPercent / 100;
    const average = rxA * (1 - duty) + txA * duty;
    const usable = (capacityAh * usablePercent) / 100;
    return { averageA: average, usableAh: usable, hours: average <= 0 ? Infinity : usable / average };
  },
};

// ---------------------------------------------------------------- kable (tłumienność dB/100 m dla częstotliwości w MHz)
/** @type {{name: string, att100m: [number, number][]}[]} */
export const COAX_DATABASE = [
  ["H155 (Belden)", [[5, 2.5], [50, 6.9], [100, 9.1], [145, 11.0], [230, 13.4], [400, 18.0], [430, 18.7], [800, 26.1], [1000, 29.6]]],
  ["RG58 Premium", [[10, 4.2], [50, 10.5], [100, 15.1], [145, 18.2], [230, 22.4], [430, 32.5], [470, 35.6], [860, 49.4], [1000, 54.0]]],
  ["RG213 (Satec)", [[10, 1.8], [50, 4.8], [100, 6.8], [145, 8.2], [200, 9.0], [400, 14.4], [430, 15.0], [1000, 24.7]]],
  ["Trilan 400 (Dipol)", [[30, 2.3], [50, 3.0], [150, 4.9], [220, 6.0], [450, 8.7], [900, 12.8], [1500, 17.0]]],
  ["Aircell 5 (SSB)", [[10, 2.78], [50, 6.28], [100, 8.93], [144, 10.76], [432, 18.99], [1000, 29.54]]],
  ["Aircell 7 (SSB)", [[10, 2.09], [50, 4.29], [100, 5.97], [144, 7.22], [200, 8.59], [432, 12.92], [500, 13.98], [800, 18.05]]],
  ["Ecoflex 10 (SSB)", [[10, 1.14], [50, 2.66], [100, 3.8], [144, 4.66], [200, 5.51], [432, 8.46], [500, 9.12], [1000, 15.49]]],
  ["Ecoflex 15 (SSB)", [[10, 0.86], [50, 1.96], [100, 2.81], [144, 3.40], [432, 6.10], [1000, 9.80]]],
  ["HyperFlex 5 (M&P)", [[10, 2.6], [50, 5.5], [100, 8.0], [144, 9.6], [430, 17.0], [800, 23.4], [1000, 26.4]]],
  ["HyperFlex 10 (M&P)", [[10, 1.3], [50, 2.7], [100, 3.9], [144, 4.7], [430, 8.6], [800, 11.9], [1000, 13.4]]],
  ["LMR-240 / CNT-240", [[30, 4.4], [50, 5.4], [150, 9.9], [220, 12.0], [450, 17.3], [900, 24.8], [1500, 35.0]]],
  ["LMR-400 / CNT-400", [[30, 2.5], [50, 3.2], [150, 4.9], [220, 6.2], [450, 8.9], [900, 12.8], [1500, 16.7]]],
  ["H1000 (Belden)", [[10, 1.4], [50, 2.8], [100, 4.0], [200, 5.7], [400, 8.4], [800, 12.3], [1000, 14.0]]],
  ["RG-174 (Pigtail)", [[10, 9.5], [50, 18.2], [100, 31.0], [145, 38.0], [200, 51.0], [430, 77.0], [1000, 120.0]]],
  ["RG-214", [[50, 4.5], [100, 6.8], [200, 10.3], [400, 15.0], [600, 20.0], [1000, 27.0]]],
  ['LDF4-50A (1/2")', [[10, 0.67], [50, 1.52], [100, 2.17], [150, 2.67], [450, 4.75], [1000, 7.28]]],
].map(([name, att100m]) => ({ name: /** @type {string} */ (name), att100m: /** @type {[number, number][]} */ (att100m) }));

/** Interpolacja liniowa tłumienności; 10000 oznacza brak danych powyżej ostatniego punktu (jak w aplikacji). */
export function attenuationForFreq(cable, freqMhz) {
  const e = [...cable.att100m].sort((x, y) => x[0] - y[0]);
  if (freqMhz <= e[0][0]) return (freqMhz / e[0][0]) * e[0][1];
  if (freqMhz >= e[e.length - 1][0]) return 10000;
  for (let i = 1; i < e.length; i++) {
    if (e[i][0] === freqMhz) return e[i][1];
    if (e[i][0] > freqMhz) {
      const [f0, a0] = e[i - 1], [f1, a1] = e[i];
      return ((freqMhz - f0) / (f1 - f0)) * (a1 - a0) + a0;
    }
  }
  return 10000;
}

// ---------------------------------------------------------------- beacony NCDXF/IARU
/**
 * 18 beaconów nadaje po kolei po 10 s na 5 pasmach, cykl 3 minuty liczony od pełnej godziny UTC.
 * Beacon nr i (od 0) nadaje na paśmie nr b w szczelinie (i + b) mod 18.
 */
export const NcdxfBeacons = {
  SLOT_SECONDS: 10,
  CYCLE_SECONDS: 180,
  BANDS_KHZ: [14100, 18110, 21150, 24930, 28200],
  // Kolejność i lokatory według ncdxf.org/beacon/beaconlocations.html
  BEACONS: [
    ["4U1UN", "ONZ", "Nowy Jork", "FN30as"], ["VE8AT", "Kanada", "Inuvik, NT", "CP38gh"],
    ["W6WX", "USA", "Mt. Umunhum", "CM97bd"], ["KH6RS", "Hawaje", "Maui", "BL10ts"],
    ["ZL6B", "Nowa Zelandia", "Masterton", "RE78tw"], ["VK6RBP", "Australia", "Rolystone", "OF87av"],
    ["JA2IGY", "Japonia", "Mt. Asama", "PM84jk"], ["RR9O", "Rosja", "Nowosybirsk", "NO14kx"],
    ["VR2B", "Hongkong", "Hongkong", "OL72bg"], ["4S7B", "Sri Lanka", "Kolombo", "MJ96wv"],
    ["ZS6DN", "RPA", "Pretoria", "KG33xi"], ["5Z4B", "Kenia", "Kikuyu", "KI88hr"],
    ["4X6TU", "Izrael", "Tel Awiw", "KM72jb"], ["OH2B", "Finlandia", "Lohja", "KP20eh"],
    ["CS3B", "Madera", "São Jorge", "IM12jt"], ["LU4AA", "Argentyna", "Buenos Aires", "GF05tj"],
    ["OA4B", "Peru", "Lima", "FH17mw"], ["YV5B", "Wenezuela", "Caracas", "FK60nd"],
  ].map(([call, entity, location, gridSquare]) => ({ call, entity, location, gridSquare })),

  /** Który beacon nadaje teraz na każdym paśmie. @param {number} utcSecondOfDay */
  nowTransmitting(utcSecondOfDay) {
    const mod = (x, m) => ((x % m) + m) % m;
    const inCycle = mod(utcSecondOfDay, this.CYCLE_SECONDS);
    const slot = Math.floor(inCycle / this.SLOT_SECONDS);
    const secondsLeft = this.SLOT_SECONDS - (inCycle % this.SLOT_SECONDS);
    return this.BANDS_KHZ.map((khz, band) => {
      const index = mod(slot - band, this.BEACONS.length);
      return { bandKhz: khz, beacon: this.BEACONS[index], next: this.BEACONS[(index + 1) % this.BEACONS.length], secondsLeft };
    });
  },

  /** Za ile sekund beacon zacznie nadawać na paśmie [bandIndex] (0, jeśli nadaje teraz). */
  secondsUntil(beacon, bandIndex, utcSecondOfDay) {
    const mod = (x, m) => ((x % m) + m) % m;
    const start = mod(this.BEACONS.indexOf(beacon) + bandIndex, this.BEACONS.length) * this.SLOT_SECONDS;
    const inCycle = mod(utcSecondOfDay, this.CYCLE_SECONDS);
    if (inCycle >= start && inCycle < start + this.SLOT_SECONDS) return 0;
    return mod(start - inCycle, this.CYCLE_SECONDS);
  },
};
