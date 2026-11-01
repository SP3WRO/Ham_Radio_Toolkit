// Dekodowanie slotów FT8/FT4 w osobnym wątku – ekran nie zamarza na czas obliczeń (setki ms na telefonie)
import { Ft8SlotDecoder } from "../core/ft8.js";

/** Osobny dekoder na emisję (każdy ma własny wodospad i tablicę haszy znaków) */
const decoders = { FT8: new Ft8SlotDecoder(12000, "FT8"), FT4: new Ft8SlotDecoder(12000, "FT4") };

self.onmessage = (e) => {
  const { id, mode, samples, slotStartMs, fromFile } = e.data;
  try {
    const result = decoders[mode].decode(samples, samples.length, slotStartMs, fromFile);
    self.postMessage({ id, result });
  } catch (err) {
    self.postMessage({ id, error: String(err?.message ?? err) });
  }
};
