// Converts chosen Phosphor (MIT) regular/fill SVGs into Android vector drawables.
// usage: node icons.mjs <phosphor assets dir> <res/drawable out dir>
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const [src, out] = process.argv.slice(2);
// android name -> [weight, phosphor name]
const icons = {
  ph_microphone: ["regular", "microphone"],
  ph_microphone_fill: ["fill", "microphone"],
  ph_sparkle: ["regular", "sparkle"],
  ph_caret_down: ["regular", "caret-down"],
  ph_caret_right: ["regular", "caret-right"],
  ph_clock_counter_clockwise: ["regular", "clock-counter-clockwise"],
  ph_dots_three: ["regular", "dots-three"],
  ph_x: ["regular", "x"],
  ph_stop_fill: ["fill", "stop"],
  ph_check_circle_fill: ["fill", "check-circle"],
  ph_check: ["regular", "check"],
  ph_arrow_counter_clockwise: ["regular", "arrow-counter-clockwise"],
  ph_quotes: ["regular", "quotes"],
  ph_chat_circle: ["regular", "chat-circle"],
  ph_info_fill: ["fill", "info"],
  ph_arrow_fat_up: ["regular", "arrow-fat-up"],
  ph_arrow_fat_up_fill: ["fill", "arrow-fat-up"],
  ph_arrow_fat_line_up_fill: ["fill", "arrow-fat-line-up"],
  ph_backspace: ["regular", "backspace"],
  ph_arrow_elbow_down_left: ["regular", "arrow-elbow-down-left"],
  ph_arrow_right: ["regular", "arrow-right"],
  ph_paper_plane_right: ["regular", "paper-plane-right"],
  ph_magnifying_glass: ["regular", "magnifying-glass"],
  ph_smiley: ["regular", "smiley"],
  ph_translate: ["regular", "translate"],
  ph_pencil_simple_line: ["regular", "pencil-simple-line"],
  ph_magic_wand: ["regular", "magic-wand"],
  ph_text_aa: ["regular", "text-aa"],
  ph_gear_six: ["regular", "gear-six"],
  ph_key: ["regular", "key"],
  ph_keyboard: ["regular", "keyboard"],
  ph_sun: ["regular", "sun"],
  ph_book_open_text: ["regular", "book-open-text"],
  ph_sliders_horizontal: ["regular", "sliders-horizontal"],
  ph_copy: ["regular", "copy"],
  ph_waveform: ["regular", "waveform"],
  ph_arrow_left: ["regular", "arrow-left"],
  ph_plus: ["regular", "plus"],
  ph_push_pin: ["regular", "push-pin"],
  ph_push_pin_fill: ["fill", "push-pin"],
  ph_arrows_out_simple: ["regular", "arrows-out-simple"],
  ph_clipboard_text: ["regular", "clipboard-text"],
  ph_arrows_clockwise: ["regular", "arrows-clockwise"],
  ph_circle_notch: ["regular", "circle-notch"],
  ph_globe_simple: ["regular", "globe-simple"],
  ph_warning_circle: ["regular", "warning-circle"],
  ph_download_simple: ["regular", "download-simple"],
  ph_cloud_check: ["regular", "cloud-check"],
};

for (const [name, [weight, ph]] of Object.entries(icons)) {
  const file = weight === "regular" ? `${ph}.svg` : `${ph}-${weight}.svg`;
  const svg = readFileSync(join(src, weight, file), "utf8");
  const vb = /viewBox="0 0 (\d+) (\d+)"/.exec(svg);
  if (!vb) throw new Error(`no viewBox in ${file}`);
  const paths = [...svg.matchAll(/<path[^>]*\sd="([^"]+)"/g)].map((m) => m[1]);
  if (paths.length === 0) throw new Error(`no path in ${file}`);
  if (/<(circle|rect|line|polyline|polygon|ellipse)\b/.test(svg)) throw new Error(`non-path shape in ${file}`);
  const body = paths
    .map((d) => `    <path\n        android:fillColor="#FF000000"\n        android:pathData="${d}" />`)
    .join("\n");
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<!-- Phosphor Icons "${ph}" (${weight}), MIT licence: assets/licenses/Phosphor-MIT.txt -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="${vb[1]}"
    android:viewportHeight="${vb[2]}">
${body}
</vector>
`;
  writeFileSync(join(out, `${name}.xml`), xml);
}
console.log(`wrote ${Object.keys(icons).length} icons`);
