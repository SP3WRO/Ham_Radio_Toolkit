// @ts-check
// Mapa azymutalna równoodległościowa wycentrowana na QTH (components/AzimuthalMap.kt) i mapa świata ze szarówką
import { h, icon, iconButton, dialog } from "../ui.js";
import { t } from "../i18n.js";
import { GreatCircle, parseWorldOutlines } from "../core/geo.js";

/** @typedef {import("../core/geo.js").LatLon} LatLon */

const MAP_RADIUS_KM = Math.PI * GreatCircle.EARTH_RADIUS_KM;
const MAX_ZOOM = 25;

/** @type {Promise<{svg: string, outlines: LatLon[][]}> | null} */
let worldPromise = null;
export function world() {
  worldPromise ??= fetch("data/world_path.txt").then((r) => r.text()).then((svg) => ({ svg, outlines: parseWorldOutlines(svg) }));
  return worldPromise;
}

/**
 * Przy antypodach rzut rozciąga lądy na całą krawędź mapy – takie fragmenty rysujemy tylko konturem,
 * a skoki między kolejnymi punktami przerywają linię.
 * @param {LatLon} center @param {LatLon[][]} outlines
 */
function project(center, outlines) {
  const jump = 4000, nearAntipode = MAP_RADIUS_KM * 0.85;
  return outlines.map((poly) => {
    let fillable = true;
    /** @type {([number, number] | null)[]} */
    const pts = [];
    /** @type {[number, number] | null} */
    let prev = null;
    for (const v of poly) {
      const p = GreatCircle.azimuthalProject(center, v);
      if (!p || Math.hypot(p[0], p[1]) > nearAntipode) fillable = false;
      if (p && prev && Math.hypot(p[0] - prev[0], p[1] - prev[1]) > jump) { fillable = false; pts.push(null); }
      pts.push(p);
      prev = p;
    }
    return { pts, fillable };
  });
}

/**
 * Canvas z mapą. Zwraca element i funkcję ustawiającą cel. [interactive] – szczypanie, przeciąganie, podwójne stuknięcie.
 * @param {{center: LatLon, target?: LatLon | null, interactive?: boolean}} o
 */
export function azimuthalMap(o) {
  const canvas = /** @type {HTMLCanvasElement} */ (h("canvas", { class: "map", role: "img", "aria-label": t("tool_locator") }));
  let center = o.center;
  /** @type {LatLon | null} */
  let target = o.target ?? null;
  /** @type {{pts: ([number, number] | null)[], fillable: boolean}[]} */
  let projected = [];
  let zoom = 1, panX = 0, panY = 0;

  world().then((w) => { projected = project(center, w.outlines); draw(); });

  function draw() {
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth, hgt = canvas.clientHeight;
    if (!w || !hgt) return;
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(hgt * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(hgt * dpr); }
    const g = /** @type {CanvasRenderingContext2D} */ (canvas.getContext("2d"));
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, hgt);
    g.save();
    g.beginPath(); g.rect(0, 0, w, hgt); g.clip();
    const radius = (Math.min(w, hgt) / 2) * zoom;
    const cx = w / 2 + panX, cy = hgt / 2 + panY;
    const scale = radius / MAP_RADIUS_KM;
    const sx = (x) => cx + x * scale, sy = (y) => cy - y * scale;

    g.fillStyle = "#0D3B66";
    g.beginPath(); g.arc(cx, cy, radius, 0, Math.PI * 2); g.fill();

    g.fillStyle = "#4C8C4A"; g.strokeStyle = "#4C8C4A"; g.lineWidth = 1.5;
    for (const poly of projected) {
      g.beginPath();
      let started = false;
      for (const p of poly.pts) {
        if (!p) { started = false; continue; }
        if (!started) g.moveTo(sx(p[0]), sy(p[1])); else g.lineTo(sx(p[0]), sy(p[1]));
        started = true;
      }
      if (poly.fillable) { g.closePath(); g.fill(); } else g.stroke();
    }

    // Okręgi odległości (gęstsze po powiększeniu) i promienie co 30°
    const step = zoom >= 12 ? 250 : zoom >= 6 ? 500 : zoom >= 3 ? 1000 : zoom >= 1.6 ? 2500 : 5000;
    g.strokeStyle = "rgba(255,255,255,.35)"; g.lineWidth = 1; g.setLineDash([4, 4]);
    g.fillStyle = "#fff"; g.font = "11px -apple-system, system-ui, sans-serif"; g.textBaseline = "top";
    for (let km = step; km < MAP_RADIUS_KM; km += step) {
      g.beginPath(); g.arc(cx, cy, km * scale, 0, Math.PI * 2); g.stroke();
      g.fillText(km % 1000 === 0 ? t("web_km_k", km / 1000) : `${km} km`, cx + 4, cy - km * scale + 2);
    }
    g.setLineDash([]);
    for (let d = 0; d < 360; d += 30) {
      const r = (d * Math.PI) / 180;
      g.beginPath(); g.moveTo(cx, cy); g.lineTo(cx + radius * Math.sin(r), cy - radius * Math.cos(r)); g.stroke();
    }
    g.strokeStyle = "rgba(255,255,255,.8)"; g.lineWidth = 2;
    g.beginPath(); g.arc(cx, cy, radius, 0, Math.PI * 2); g.stroke();
    g.font = "14px -apple-system, system-ui, sans-serif"; g.textAlign = "center"; g.textBaseline = "middle";
    for (const [lbl, d] of [["N", 0], ["E", 90], ["S", 180], ["W", 270]]) {
      const r = (Number(d) * Math.PI) / 180;
      g.fillText(String(lbl), cx + (radius - 14) * Math.sin(r), cy - (radius - 14) * Math.cos(r));
    }
    g.textAlign = "start";

    if (target) {
      const path = GreatCircle.path(center, target);
      const sp = (path.shortPathBearing * Math.PI) / 180, lp = (path.longPathBearing * Math.PI) / 180;
      const end = GreatCircle.azimuthalProject(center, target);
      g.strokeStyle = "#FFD166"; g.fillStyle = "#FFD166"; g.lineWidth = 3;
      if (end) {
        g.beginPath(); g.moveTo(cx, cy); g.lineTo(sx(end[0]), sy(end[1])); g.stroke();
        g.beginPath(); g.arc(sx(end[0]), sy(end[1]), 5, 0, Math.PI * 2); g.fill();
      }
      g.beginPath(); g.arc(cx + radius * Math.sin(sp), cy - radius * Math.cos(sp), 4, 0, Math.PI * 2); g.fill();
      // Dłuższa droga: przeciwny kierunek aż do krawędzi mapy (antypody)
      g.strokeStyle = "#EF476F"; g.lineWidth = 1.5; g.setLineDash([8, 5]);
      g.beginPath(); g.moveTo(cx, cy); g.lineTo(cx + radius * Math.sin(lp), cy - radius * Math.cos(lp)); g.stroke();
      g.setLineDash([]);
    }
    const primary = getComputedStyle(document.documentElement).getPropertyValue("--primary").trim() || "#1b5e9e";
    g.fillStyle = primary; g.strokeStyle = "#fff"; g.lineWidth = 2;
    g.beginPath(); g.arc(cx, cy, 5, 0, Math.PI * 2); g.fill(); g.stroke();
    g.restore();
  }

  const ro = new ResizeObserver(() => draw());
  ro.observe(canvas);

  if (o.interactive) attachGestures();

  function zoomAround(fx, fy, newZoom, dx = 0, dy = 0) {
    const z = Math.min(Math.max(newZoom, 1), MAX_ZOOM);
    const relX = fx - canvas.clientWidth / 2, relY = fy - canvas.clientHeight / 2;
    // Punkt mapy pod palcami zostaje w tym samym miejscu ekranu
    let nx = (panX - relX) * (z / zoom) + relX + dx, ny = (panY - relY) * (z / zoom) + relY + dy;
    const limit = (Math.min(canvas.clientWidth, canvas.clientHeight) / 2) * (z - 1);
    panX = Math.min(Math.max(nx, -limit), limit); panY = Math.min(Math.max(ny, -limit), limit);
    zoom = z;
    draw();
  }

  function attachGestures() {
    /** @type {Map<number, {x: number, y: number}>} */
    const pointers = new Map();
    let lastTap = 0;
    canvas.addEventListener("pointerdown", (e) => {
      canvas.setPointerCapture(e.pointerId);
      pointers.set(e.pointerId, { x: e.offsetX, y: e.offsetY });
      if (pointers.size === 1) {
        const now = Date.now();
        if (now - lastTap < 300) { if (zoom > 1.05) { zoom = 1; panX = panY = 0; draw(); } else zoomAround(e.offsetX, e.offsetY, 3); lastTap = 0; }
        else lastTap = now;
      }
    });
    canvas.addEventListener("pointermove", (e) => {
      const prev = pointers.get(e.pointerId);
      if (!prev) return;
      const pts = [...pointers.values()];
      if (pointers.size === 2) {
        const other = pts.find((p) => p !== prev);
        if (!other) return;
        const d0 = Math.hypot(prev.x - other.x, prev.y - other.y);
        const d1 = Math.hypot(e.offsetX - other.x, e.offsetY - other.y);
        const mx = (e.offsetX + other.x) / 2, my = (e.offsetY + other.y) / 2;
        if (d0 > 0) zoomAround(mx, my, zoom * (d1 / d0), (e.offsetX - prev.x) / 2, (e.offsetY - prev.y) / 2);
      } else if (zoom > 1) {
        zoomAround(canvas.clientWidth / 2, canvas.clientHeight / 2, zoom, e.offsetX - prev.x, e.offsetY - prev.y);
      }
      pointers.set(e.pointerId, { x: e.offsetX, y: e.offsetY });
    });
    const up = (e) => pointers.delete(e.pointerId);
    canvas.addEventListener("pointerup", up);
    canvas.addEventListener("pointercancel", up);
    canvas.addEventListener("wheel", (e) => { e.preventDefault(); zoomAround(e.offsetX, e.offsetY, zoom * (e.deltaY < 0 ? 1.2 : 1 / 1.2)); }, { passive: false });
  }

  return {
    canvas,
    /** @param {LatLon | null} tg */
    setTarget(tg) { target = tg; draw(); },
    /** @param {LatLon} c */
    setCenter(c) { center = c; world().then((w) => { projected = project(center, w.outlines); draw(); }); },
    zoomBy(f) { zoomAround(canvas.clientWidth / 2, canvas.clientHeight / 2, zoom * f); },
    reset() { zoom = 1; panX = panY = 0; draw(); },
    destroy() { ro.disconnect(); },
  };
}

/** Mapa na cały ekran z powiększaniem. @param {LatLon} center @param {LatLon | null} target @param {any} header */
export function fullScreenMap(center, target, header) {
  const map = azimuthalMap({ center, target, interactive: true });
  const close = dialog({
    title: t("tool_locator"), fullscreen: true, onclose: () => map.destroy(),
    body: h("div", { class: "map-full" },
      h("div", { class: "map-full-head" }, h("div", { class: "col grow" }, header), iconButton("close", t("common_close"), () => close())),
      h("div", { class: "map-full-canvas" }, map.canvas,
        h("div", { class: "map-zoom" },
          h("button", { type: "button", "aria-label": t("map_zoom_in"), onclick: () => map.zoomBy(2) }, icon("add")),
          h("button", { type: "button", "aria-label": t("map_zoom_out"), onclick: () => map.zoomBy(0.5) }, icon("remove")),
          h("button", { type: "button", "aria-label": t("map_whole"), onclick: () => map.reset() }, icon("centerFocusStrong")))),
      h("div", { class: "body-s", style: { color: "rgba(255,255,255,.7)", padding: "8px 16px" } }, t("map_hint"))),
  });
}
