// qa_stills.mjs — QA 审帧截图（单进程批量，Task 13b）
// 根因：逐帧 `npx remotion still` 每帧各付一次 bundler 冷启动 ~35s，17-25 帧 QA 单轮
// 17.6-57.2min；本脚本 bundle/composition/浏览器只取一次，循环 renderStill 复用。
// 用法: node scripts/qa_stills.mjs <compositionId> <manifest.json> <outDir>
//   manifest = [{"name":"s-s01-problem-card","frame":313}, ...]（QaFrameCheck 按 pick_frames 解析结果落盘）
// stdout: 每帧一行 `名\t帧\tok|fail`（Java 侧解析）；任一帧失败 → stderr 列失败行、exit 1。
// 渲染条件与整片渲染同源：浏览器可执行文件取 remotion.config.ts 的 Config.setBrowserExecutable
// （Edge）。config 是 TS、脚本不便解析，此处镜像同一路径——改 config 必须同步改这里（QA 工具资产）。
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { bundle } from "@remotion/bundler";
import { openBrowser, renderStill, selectComposition } from "@remotion/renderer";

const root = fileURLToPath(new URL("..", import.meta.url));
const [compositionId, manifestPath, outDir] = process.argv.slice(2);
if (!compositionId || !manifestPath || !outDir) {
  fail("用法: node scripts/qa_stills.mjs <compositionId> <manifest.json> <outDir>");
}

// manifest 校验：name 非空串（去空白/路径分隔符，与 Java 侧同防）、frame 非负整数。
const rows = JSON.parse(fs.readFileSync(path.resolve(manifestPath), "utf-8"));
if (!Array.isArray(rows) || rows.length === 0) fail("manifest 为空或非数组: " + manifestPath);
const frames = rows.map((row, i) => {
  const name = String(row?.name ?? "").replace(/\s+/g, "").replace(/[\\/]/g, "");
  const frame = row?.frame;
  if (!name || !Number.isInteger(frame) || frame < 0) {
    fail(`manifest 第 ${i} 行非法: ${JSON.stringify(row)}`);
  }
  return { name, frame };
});

// remotion.config.ts 的浏览器真源（渲染管线同款）；缺失时回退 remotion 默认下载。
const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const browserExecutable = fs.existsSync(EDGE) ? EDGE : undefined;

const entry = path.join(root, "src", "index.ts");
const outAbs = path.resolve(outDir);
fs.mkdirSync(outAbs, { recursive: true });

let bundled;
let browser;
const results = [];
const failures = [];
try {
  console.error(`[qa_stills] bundling ${entry} …`);
  bundled = await bundle(entry, () => {});
  browser = await openBrowser("chrome", { browserExecutable });
  const composition = await selectComposition({
    serveUrl: bundled,
    id: compositionId,
    puppeteerInstance: browser,
  });
  for (const { name, frame } of frames) {
    const output = path.join(outAbs, `${name}.png`);
    try {
      await renderStill({
        composition,
        serveUrl: bundled,
        output,
        frame,
        overwrite: true,
        puppeteerInstance: browser,
      });
      results.push(`${name}\t${frame}\tok`);
    } catch (err) {
      results.push(`${name}\t${frame}\tfail`);
      failures.push(`${name}\t${frame}\t${String(err?.message ?? err).split("\n")[0]}`);
    }
  }
} finally {
  if (browser) await browser.close({ silent: true });
  if (bundled) fs.rmSync(bundled, { recursive: true, force: true });
}

process.stdout.write(results.join("\n") + "\n", () => {
  if (failures.length > 0) {
    fs.writeSync(2, `[qa_stills] ${failures.length}/${frames.length} 帧失败\n` + failures.join("\n") + "\n");
    process.exit(1);
  }
  console.error(`[qa_stills] ${frames.length} 帧完成 → ${outAbs}`);
  process.exit(0);
});

/** 致命错误（参数/manifest 非法等）：同步写 stderr 后退出（管道不丢行）。 */
function fail(message) {
  fs.writeSync(2, `[qa_stills] ${message}\n`);
  process.exit(1);
}
