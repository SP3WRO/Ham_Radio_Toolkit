// Ikony PWA z ikony aplikacji na Androida (mipmap-xxxhdpi/ic_launcher_foreground.webp).
// Uruchomienie: node web/tools/make-icons.mjs <ścieżka do modułu sharp>   (jednorazowo, wyniki są w repozytorium)
import { createRequire } from "node:module";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const WEB = join(dirname(fileURLToPath(import.meta.url)), "..");
const sharp = createRequire(import.meta.url)(process.argv[2] ?? "sharp");
const src = join(WEB, "..", "app", "src", "main", "res", "mipmap-xxxhdpi", "ic_launcher_foreground.webp");
const out = (n) => join(WEB, "public", "icons", n);
const BLUE = { r: 56, g: 111, b: 166, alpha: 1 }; // kolor tła ikony

// Granatowy kwadrat ikony zajmuje piksele 54..377 z 432 (strefa bezpieczna ikony adaptacyjnej)
const square = { left: 54, top: 54, width: 324, height: 324 };
const inner = { left: 62, top: 62, width: 308, height: 308 };

await sharp(src).extract(square).resize(192, 192).png().toFile(out("icon-192.png"));
await sharp(src).extract(square).resize(512, 512).png().toFile(out("icon-512.png"));
// iOS sam zaokrągla rogi i nie lubi przezroczystości – pełny kwadrat bez marginesu
await sharp(src).extract(inner).flatten({ background: BLUE }).resize(180, 180).png().toFile(out("apple-touch-icon.png"));
// Ikona "maskable" (Android/Chrome): treść w 80% środka, reszta tła wypełniona kolorem
await sharp(src).flatten({ background: BLUE }).resize(512, 512).png().toFile(out("icon-maskable-512.png"));
console.log("Ikony zapisane w public/icons");
