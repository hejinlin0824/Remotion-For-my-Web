// pick_frames.mjs — 复算时间轴输出审核帧号
// 用法: node scripts/pick_frames.mjs [--scene s03]  （打印 totalFrames + 推荐帧清单）
import fs from "node:fs";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("..", import.meta.url));
const content = JSON.parse(fs.readFileSync(root + "src/data/content.json", "utf-8"));
const meta = JSON.parse(fs.readFileSync(root + "src/data/audio_meta.json", "utf-8"));
const FPS = meta.fps ?? 30;
const BREATH = Math.round((meta.breathSec ?? 0.18) * FPS);
const argIdx = process.argv.indexOf("--scene");
const want = argIdx > -1 ? process.argv[argIdx + 1] : null;

let t = Math.round(meta.fixed.act1.durationSec * FPS);
const act1Dur = Math.round(meta.fixed.act1.durationSec * FPS);
const linesBy = new Map(meta.lines.map((l) => [l.sceneId, l]));
const wins = [];
for (const sc of content.scenes) {
  const line = linesBy.get(sc.id);
  if (!line) throw new Error(`scene ${sc.id} 无音频行`);
  const dur = Math.round(line.durationSec * FPS);
  wins.push({ id: sc.id, act: sc.act, component: sc.component, start: t, dur });
  t += dur + BREATH;
}
const act5Start = t - BREATH;
const total = act5Start + Math.round((meta.fixed.act5.durationSec + (meta.act5TailSec ?? 2.0)) * FPS);

const rows = [["act1-中段", Math.round(act1Dur * 0.5)]];
const act2K = wins.find((w) => w.act === 2 && w.component === "knowledge-card");
rows.push(["act2-章节字", act2K.start + 12]);
for (const act of [3, 4]) rows.push([`act${act}-章节字`, wins.find((w) => w.act === act).start + 12]);
for (const w of wins) rows.push([`s-${w.id}-${w.component}`, w.start + Math.round(w.dur * 0.55)]);
rows.push(["act5-打字机", act5Start + Math.round(meta.fixed.act5.durationSec * FPS * 0.6)]);
rows.push(["act5-定格末尾", total - 8]);

console.log("totalFrames =", total);
for (const [name, f] of rows) {
  if (want && !name.includes(want)) continue;
  console.log(`${name}\t${f}`);
}
