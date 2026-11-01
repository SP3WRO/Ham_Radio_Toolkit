// @ts-check
// Zgodność ze starszym Safari na iPhonie (iOS 15–16). Ładowane jako pierwszy moduł aplikacji.

// AbortSignal.timeout – Safari 16+
if (typeof AbortSignal !== "undefined" && !("timeout" in AbortSignal)) {
  /** @param {number} ms */
  /** @type {any} */ (AbortSignal).timeout = (ms) => {
    const c = new AbortController();
    setTimeout(() => c.abort(new DOMException("Przekroczony czas oczekiwania", "TimeoutError")), ms);
    return c.signal;
  };
}

// <dialog> – Safari 15.4+. Starsze: prosty zamiennik (okno nad przyciemnionym tłem).
if (typeof HTMLDialogElement === "undefined" || !("showModal" in HTMLDialogElement.prototype)) {
  const proto = /** @type {any} */ (typeof HTMLDialogElement !== "undefined" ? HTMLDialogElement.prototype : HTMLElement.prototype);
  proto.showModal = function () {
    this.classList.add("polyfill");
    this.setAttribute("open", "");
    const backdrop = document.createElement("div");
    backdrop.className = "dialog-backdrop-polyfill";
    backdrop.addEventListener("click", () => this.dispatchEvent(new Event("cancel")));
    this.before(backdrop);
    this._backdrop = backdrop;
  };
  proto.close = function () {
    this.removeAttribute("open");
    this._backdrop?.remove();
  };
}

// Array.prototype.at – Safari 15.4+
if (!Array.prototype.at) {
  Object.defineProperty(Array.prototype, "at", {
    value(/** @type {number} */ i) { const n = Math.trunc(i) || 0; return this[n < 0 ? this.length + n : n]; },
    writable: true, configurable: true,
  });
}
