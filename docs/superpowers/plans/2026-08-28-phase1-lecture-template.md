# Phase 1：五幕讲题模板项目 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `E:\codebase\remotion_java\template\` 构建封版 Remotion 模板项目：读入 content.json + audio_meta.json，渲染五幕固定风格讲题视频，示例题全片（16:9 与 9:16）渲染通过并全部审帧 PASS。

**Architecture:** 纯数据驱动——engine 把 content.json（scenes[] 白名单组件）+ audio_meta.json（每句 TTS 实测时长）编译成帧级时间轴；acts 按五幕规格渲染，每个镜头时长永远等于对应音频时长。模板代码封版后不再改动，Phase 2 的 Java 服务只复制模板、覆写两个 JSON + 音频、调 `npx remotion render`。

**Tech Stack:** Remotion ^4.0.220 / React 18 / TypeScript 5 / KaTeX（数学式）/ vitest（时间轴单测）/ Node v24.18.0 / Python 3.14 + dashscope 1.27.1（一次性生成示例音频）/ 渲染浏览器 Edge。

**Spec:** `docs/superpowers/specs/2026-08-28-remotion-java-design.md`（§5 结构、§6-7 契约与白名单、§8 五幕规格、§16 Phase 1 验收）。本计划把 spec 未定死的实现细节（组件 props、布局分区、配色、时间轴公式）全部钉死；**Phase 2 计划以此为契约另行编写，不在本文件内**。

## Global Constraints（每个任务隐含遵守）

- 项目根 `E:\codebase\remotion_java`（git 仓库，main 分支）；模板代码全部在 `template\` 下。
- `FPS=30`；气口 `BREATH_SEC=0.18`；片尾定格 `ACT5_TAIL_SEC=2.0`；章节大字 `CHAPTER_SEC=1.8`；语速恒 1.0，禁止任何 playbackRate/变速。
- 双画幅：`16:9` = 1920×1080（默认），`9:16` = 1080×1920；同一份 content.json 渲染两个 composition。
- **所有颜色显式声明**（继承色黑字坑 spec §17-4）：根节点显式 `color`，每个组件显式设色。
- 所有数学式 KaTeX 渲染；**所有元素必须有进入/退出动画**，禁止瞬现瞬失（spec §8）。
- LLM 枚举白名单只有 7 个：`problem-card / knowledge-card / step-card / derivation-popup / pitfall-card / checklist-card / general-list`；幕 1/5 的 `intro-title`、`typewriter-end` 归模板所有，不在 content.json 中。
- `remotion.config.ts` 用本机 Edge：`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`，绝不下载 Chrome Headless Shell。
- npm 走代理：`HTTPS_PROXY=http://127.0.0.1:7890 npm install`。
- API key 不入库：TTS key 走环境变量 `DASHSCOPE_API_KEY`（脚本回落解析旧配置文件）；GLM key 走 `ZHIPU_API_KEY`/`GLM_API_KEY` 环境变量（回落 `~/.claude/settings.json`）。
- 每个任务结束：`git add` + `git commit`（conventional messages），在仓库根执行。
- 视觉任务的"测试"= `npx remotion still` 抽帧 + 人工对照本任务验收描述；纯逻辑任务走 vitest TDD。
- 所有 `.tsx` 文件用到 `React.FC` 类型时，顶部补 `import React from "react";`（代码块为省篇幅未全部写出，tsc 报 "Cannot find namespace React" 时即补此行）。

## 数据契约（全部任务共同依赖，钉死不改）

### content.json（`template/src/data/content.json`，JSON import 进 bundle）

```jsonc
{
  "meta": { "aspect": "16:9", "problemType": "计算题" },
  "problem": { "lines": [
    { "id": "L1", "segments": [ { "type": "text", "value": "…" }, { "type": "math", "value": "KaTeX" } ] }
  ]},
  "knowledge":    [ { "claim": "…", "formula": "KaTeX", "premise": "…", "trap": "…" } ],   // ×2-4
  "steps":        [ { "usesAnchor": "L1", "statement": "…", "derivation": "KaTeX", "note": "…" } ], // ×3-10
  "pitfalls":     [ { "claim": "…", "why": "…" } ],                                        // ×1-3
  "generalMethod":[ { "step": "…", "trick": "…" } ],                                       // ×3-6
  "scenes": [
    { "id": "s01", "act": 2, "component": "problem-card", "ttsText": "口语讲解词", "props": {} }
  ]
}
```

**props 按组件钉死（Phase 2 的 V1/V3 校验对象）：**

| component | 允许 act | props |
|---|---|---|
| `problem-card` | 2 | `{}`，且必须是 act2 第一场 |
| `knowledge-card` | 2 | `{ "knowledgeRef": N }` 1-based → knowledge[N-1] |
| `step-card` | 3 | `{ "stepRef": N }` 1-based → steps[N-1] |
| `derivation-popup` | 3 | `{ "stepRef": N, "formula": "KaTeX" }` |
| `pitfall-card` | 3 | `{ "pitfallRef": N }` 1-based → pitfalls[N-1] |
| `checklist-card` | 3 | `{ "pitfallRefs": [N…] }`（幕3收尾清单，至多一场） |
| `general-list` | 4 | `{ "itemRef": N }` 1-based → generalMethod[N-1] |

- scenes 顺序 = 播放顺序，且必须 act2 全部 → act3 全部 → act4 全部。
- `ttsText` 是口语讲解词（不是题干朗读，题干在画面上）；一句 TTS = 一个镜头。

### audio_meta.json（`template/src/data/audio_meta.json`，TTS 脚本生成）

```jsonc
{
  "voice": "Cherry", "model": "qwen-tts", "rate": 1.0, "fps": 30,
  "breathSec": 0.18, "act5TailSec": 2.0,
  "fixed": {
    "act1": { "file": "audio/fixed/act1.wav", "durationSec": 0.0 },
    "act5": { "file": "audio/fixed/act5.wav", "durationSec": 0.0 }
  },
  "lines": [ { "index": 1, "sceneId": "s01", "file": "audio/lines/line_01.wav", "durationSec": 0.0, "text": "…" } ]
}
```

（`durationSec` 由脚本按 `文件字节数 ÷ byte_rate` 实测填入，示例里的 0.0 是占位示意。）

### 时间轴公式（engine/timeline.ts 实现，QA 脚本必须镜像）

```
frame(sec) = Math.round(sec * 30)
BREATH_F = frame(0.18) = 5
t = 0
act1:  start=0,              dur=frame(act1.durationSec);                      t += dur
每场:  start=t,              dur=frame(line.durationSec);                      t += dur + BREATH_F
act5:  start=t-BREATH_F,     dur=frame(act5.durationSec + 2.0)
totalFrames = act5.start + act5.dur
```

- 章节大字挂靠：act3/act4 = 该幕第一场 startFrame；act2 = 第一场 `knowledge-card` 的 startFrame（act2 第一场是 problem-card 全屏，不挂章节字）。章节字 overlay 局部帧 `[0, 54)`。
- **ProblemStage（常驻题干）跨幕生命周期**：全屏展示于 problem 场 `[pStart, pEnd-18)`；`[pEnd-18, pEnd)` 平滑缩移到左上角；`[pEnd, act4Start)` 左上角定格常驻；`[act4Start, act4Start+18)` 淡出。act3 内 `stepRef` 场景驱动对应 `usesAnchor` 题干行高亮。
- **核心结论保留**：act3 最后一场若是 `step-card`，其卡片常驻到 act4Start（其余步骤卡用完即消）。

### 布局分区（engine/layout.ts）

```ts
"16:9": width 1920 height 1080, problemFull {260,220,1400,640}, corner {48,44,600,300}, main {720,120,1140,860}
"9:16": width 1080 height 1920, problemFull {70,420,940,760},  corner {44,44,992,380}, main {60,500,960,1280}
```

### 配色与字体（engine/constants.ts，深色科技风 + 幕5 纯白）

```
bg #0E1220  bg2 #171C30  card #1A2138  cardBorder #2A3352  accent #5B8DFF
text #F2F5FF  sub #9AA3C0  hl #FFD54A  ok #4ADE80  warn #F87171  white #FFFFFF  ink #0E1220
font: 'Microsoft YaHei', 'PingFang SC', 'Noto Sans SC', sans-serif
```

## 任务总览

1. 模板项目脚手架（可渲染的占位 composition + vitest）
2. engine 类型/常量/布局
3. 时间轴引擎 + 契约校验（TDD）
4. 示例题 content.json（golden 解答全量）
5. TTS 生成脚本 + 全量示例音频（fixed 2 句 + 正文 17 句）
6. 动画工具 + KaTeX 组件
7. ProblemStage / ChapterTitle / 白名单组件·幕2 组（problem/knowledge）
8. Act1 + Act5（固定幕，立即可渲染验证）
9. Act2 组装
10. 白名单组件·幕3 组 + Act3
11. general-list + Act4 + LectureVideo 总装 + Root 双 composition
12. 双画幅全片渲染 + pick_frames + GLM 审帧 + 缺陷迭代
13. 模板契约 README + 封版 tag

---

### Task 1: 模板项目脚手架

**Files:**
- Create: `template/package.json`, `template/tsconfig.json`, `template/remotion.config.ts`, `template/src/index.ts`, `template/src/Root.tsx`, `template/src/acts/LectureVideo.tsx`（占位）, `template/src/data/content.json`（占位 `{}` 后续任务覆写）, `template/src/data/audio_meta.json`（占位）

**Interfaces:**
- Produces: 可 `npm run studio` / `npx remotion still Lecture169 --frame=0` 的空项目；`LectureVideo` 组件签名 `React.FC<{aspect: "16:9"|"9:16"}>`（后续任务填充内部）。

- [ ] **Step 1: 写 package.json**

```json
{
  "name": "lecture-template",
  "private": true,
  "version": "1.0.0",
  "description": "五幕考研数学讲题视频封版模板（remotion_java Phase 1）",
  "scripts": {
    "studio": "remotion studio --no-open",
    "render:169": "remotion render Lecture169 out/preview_169.mp4",
    "render:916": "remotion render Lecture916 out/preview_916.mp4",
    "test": "vitest run"
  },
  "dependencies": {
    "@remotion/cli": "^4.0.220",
    "katex": "^0.16.11",
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "remotion": "^4.0.220"
  },
  "devDependencies": {
    "@types/katex": "^0.16.7",
    "@types/react": "^18.3.3",
    "typescript": "^5.5.4",
    "vitest": "^3.0.0"
  }
}
```

- [ ] **Step 2: 写 tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "skipLibCheck": true,
    "esModuleInterop": true,
    "resolveJsonModule": true,
    "noEmit": true,
    "lib": ["DOM", "ES2022"]
  },
  "include": ["src", "vitest.config.ts"]
}
```

- [ ] **Step 3: 写 remotion.config.ts（Edge，禁止下载 Chrome Headless Shell）**

```ts
import { Config } from "@remotion/cli/config";

Config.setBrowserExecutable("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");
Config.setVideoImageFormat("jpeg");
Config.setOverwriteOutput(true);
```

- [ ] **Step 4: 写占位 src/index.ts、src/Root.tsx、src/acts/LectureVideo.tsx、data 占位 JSON**

```ts
// src/index.ts
import { registerRoot } from "remotion";
import { RemotionRoot } from "./Root";
registerRoot(RemotionRoot);
```

```tsx
// src/Root.tsx — Task 1 占位版（durationInFrames 先写死，Task 11 换成 timeline）
import { Composition } from "remotion";
import { LectureVideo } from "./acts/LectureVideo";

export const RemotionRoot: React.FC = () => (
  <>
    <Composition id="Lecture169" component={LectureVideo} width={1920} height={1080} fps={30}
      durationInFrames={90} defaultProps={{ aspect: "16:9" }} />
    <Composition id="Lecture916" component={LectureVideo} width={1080} height={1920} fps={30}
      durationInFrames={90} defaultProps={{ aspect: "9:16" }} />
  </>
);
```

```tsx
// src/acts/LectureVideo.tsx — 占位，仅验证渲染链路
import { AbsoluteFill } from "remotion";
import { COLORS, FONT_FAMILY } from "../engine/constants";

export const LectureVideo: React.FC<{ aspect: "16:9" | "9:16" }> = ({ aspect }) => (
  <AbsoluteFill style={{ backgroundColor: COLORS.bg, color: COLORS.text, fontFamily: FONT_FAMILY,
    justifyContent: "center", alignItems: "center", fontSize: 60 }}>
    scaffold ok {aspect}
  </AbsoluteFill>
);
```

`src/engine/constants.ts`（本任务先建最小版，Task 2 补全）：

```ts
export const COLORS = {
  bg: "#0E1220", bg2: "#171C30", card: "#1A2138", cardBorder: "#2A3352",
  accent: "#5B8DFF", text: "#F2F5FF", sub: "#9AA3C0", hl: "#FFD54A",
  ok: "#4ADE80", warn: "#F87171", white: "#FFFFFF", ink: "#0E1220",
} as const;
export const FONT_FAMILY = "'Microsoft YaHei', 'PingFang SC', 'Noto Sans SC', sans-serif";
```

两个 data JSON 占位：`content.json` 写 `{}`，`audio_meta.json` 写 `{}`。

- [ ] **Step 5: 安装依赖并验证**

```bash
cd /e/codebase/remotion_java/template
HTTPS_PROXY=http://127.0.0.1:7890 npm install
npx tsc --noEmit
npx remotion still Lecture169 out/scaffold.png --frame=0
```

Expected: tsc 无错误；still 生成深色底、居中 "scaffold ok 16:9" 的 PNG。

- [ ] **Step 6: Commit**

```bash
cd /e/codebase/remotion_java
git add template && git commit -m "feat(template): Remotion 模板项目脚手架（Edge 渲染链路走通）"
```

---

### Task 2: engine 类型 / 常量 / 布局

**Files:**
- Modify: `template/src/engine/constants.ts`（补全）
- Create: `template/src/engine/types.ts`, `template/src/engine/layout.ts`

**Interfaces:**
- Produces: `FPS=30`, `BREATH_SEC=0.18`, `ACT5_TAIL_SEC=2.0`, `CHAPTER_SEC=1.8`, `CHAPTER_F=54`, `TRANSITION_F=18`, `EXIT_F=10`, `COLORS`, `FONT_FAMILY`, `CHAPTER_TITLES`；类型 `AspectKey/ComponentKey/Segment/ProblemLine/Knowledge/Step/Pitfall/MethodItem/SceneProps/Scene/ContentJson/AudioLine/AudioMeta`；`LAYOUTS: Record<AspectKey, Layout>`、`Layout{width,height,full,problemFull,corner,main: Rect}`、`Rect{x,y,w,h}`。

- [ ] **Step 1: 补全 constants.ts**

```ts
export const FPS = 30;
export const BREATH_SEC = 0.18;
export const ACT5_TAIL_SEC = 2.0;
export const CHAPTER_SEC = 1.8;
export const CHAPTER_F = Math.round(CHAPTER_SEC * FPS);      // 54
export const TRANSITION_F = 18;                              // 题干全屏→左上角过渡
export const EXIT_F = 10;                                    // 组件退场帧数
export const COLORS = {
  bg: "#0E1220", bg2: "#171C30", card: "#1A2138", cardBorder: "#2A3352",
  accent: "#5B8DFF", text: "#F2F5FF", sub: "#9AA3C0", hl: "#FFD54A",
  ok: "#4ADE80", warn: "#F87171", white: "#FFFFFF", ink: "#0E1220",
} as const;
export const FONT_FAMILY = "'Microsoft YaHei', 'PingFang SC', 'Noto Sans SC', sans-serif";
export const CHAPTER_TITLES: Record<2 | 3 | 4, string> = { 2: "知识点回顾", 3: "本题解法", 4: "以后怎么做" };
```

- [ ] **Step 2: 写 types.ts（契约里全部类型，照"数据契约"节的字段逐字落型）**

```ts
export type AspectKey = "16:9" | "9:16";
export type ComponentKey =
  | "problem-card" | "knowledge-card" | "step-card" | "derivation-popup"
  | "pitfall-card" | "checklist-card" | "general-list";
export type ActKey = 2 | 3 | 4;

export interface Segment { type: "text" | "math"; value: string }
export interface ProblemLine { id: string; segments: Segment[] }
export interface Knowledge { claim: string; formula: string; premise: string; trap: string }
export interface Step { usesAnchor: string; statement: string; derivation: string; note: string }
export interface Pitfall { claim: string; why: string }
export interface MethodItem { step: string; trick: string }

export interface SceneProps {
  stepRef?: number; formula?: string; knowledgeRef?: number;
  pitfallRef?: number; pitfallRefs?: number[]; itemRef?: number;
}
export interface Scene { id: string; act: ActKey; component: ComponentKey; ttsText: string; props?: SceneProps }
export interface ContentJson {
  meta: { aspect: AspectKey; problemType: string };
  problem: { lines: ProblemLine[] };
  knowledge: Knowledge[]; steps: Step[]; pitfalls: Pitfall[]; generalMethod: MethodItem[];
  scenes: Scene[];
}
export interface AudioLine { index: number; sceneId: string; file: string; durationSec: number; text: string }
export interface AudioMeta {
  voice: string; model: string; rate: number; fps: number;
  breathSec: number; act5TailSec: number;
  fixed: { act1: { file: string; durationSec: number }; act5: { file: string; durationSec: number } };
  lines: AudioLine[];
}
```

- [ ] **Step 3: 写 layout.ts**

```ts
import type { AspectKey } from "./types";

export interface Rect { x: number; y: number; w: number; h: number }
export interface Layout {
  width: number; height: number;
  full: Rect;         // 全屏
  problemFull: Rect;  // 题干全屏展示区
  corner: Rect;       // 题干左上角常驻区
  main: Rect;         // 幕2/3/4 卡片主区
}
export const LAYOUTS: Record<AspectKey, Layout> = {
  "16:9": { width: 1920, height: 1080,
    full: { x: 0, y: 0, w: 1920, h: 1080 },
    problemFull: { x: 260, y: 220, w: 1400, h: 640 },
    corner: { x: 48, y: 44, w: 600, h: 300 },
    main: { x: 720, y: 120, w: 1140, h: 860 } },
  "9:16": { width: 1080, height: 1920,
    full: { x: 0, y: 0, w: 1080, h: 1920 },
    problemFull: { x: 70, y: 420, w: 940, h: 760 },
    corner: { x: 44, y: 44, w: 992, h: 380 },
    main: { x: 60, y: 500, w: 960, h: 1280 } },
};
```

- [ ] **Step 4: 验证 + Commit**

```bash
npx tsc --noEmit && cd /e/codebase/remotion_java && git add template && git commit -m "feat(template): engine 类型/常量/双画幅布局分区"
```

Expected: tsc 通过（LectureVideo 引用不变仍编译）。

---

### Task 3: 时间轴引擎 + 契约校验（TDD）

**Files:**
- Create: `template/src/engine/timeline.ts`, `template/src/engine/timeline.test.ts`, `template/src/engine/contract.ts`, `template/src/engine/contract.test.ts`
- Constraint: **这两个文件不得 import remotion**（保持纯 TS 可在 vitest/node 中运行）。

**Interfaces:**
- Consumes: Task 2 的类型与常量。
- Produces:
  - `buildTimeline(content: ContentJson, audio: AudioMeta): Timeline`
  - `Timeline { acts: ActWindow[]; scenes: SceneWindow[]; chapterSlots: ChapterSlot[]; problemScene: SceneWindow; act4StartFrame: number; totalFrames: number }`
  - `ActWindow { act: 1|2|3|4|5; startFrame: number; durFrames: number }`
  - `SceneWindow { index: number; sceneId: string; act: ActKey; component: ComponentKey; props: SceneProps; startFrame: number; durFrames: number; lineFile: string; stepAnchor?: string }`（`stepAnchor` = props.stepRef 对应 step 的 usesAnchor，供题干行高亮）
  - `ChapterSlot { act: ActKey; fromFrame: number; title: string }`
  - `validateContract(content: ContentJson, audio: AudioMeta): string[]`（返回错误清单，空数组 = 合法）

- [ ] **Step 1: 写失败的 timeline 测试**

```ts
// src/engine/timeline.test.ts
import { describe, expect, it } from "vitest";
import { buildTimeline } from "./timeline";
import type { AudioMeta, ContentJson } from "./types";

const content = (scenes: ContentJson["scenes"]): ContentJson => ({
  meta: { aspect: "16:9", problemType: "计算题" },
  problem: { lines: [{ id: "L1", segments: [{ type: "text", value: "题" }] }] },
  knowledge: [{ claim: "c", formula: "f", premise: "p", trap: "t" }],
  steps: [{ usesAnchor: "L1", statement: "s", derivation: "d", note: "n" }],
  pitfalls: [{ claim: "c", why: "w" }],
  generalMethod: [{ step: "s", trick: "t" }],
  scenes,
});
const audio = (durs: number[]): AudioMeta => ({
  voice: "Cherry", model: "qwen-tts", rate: 1.0, fps: 30, breathSec: 0.18, act5TailSec: 2.0,
  fixed: { act1: { file: "audio/fixed/act1.wav", durationSec: 2.0 },
           act5: { file: "audio/fixed/act5.wav", durationSec: 3.0 } },
  lines: durs.map((durationSec, i) => ({ index: i + 1, sceneId: `s${String(i + 1).padStart(2, "0")}`,
    file: `audio/lines/line_${String(i + 1).padStart(2, "0")}.wav`, durationSec, text: "t" })),
});

describe("buildTimeline", () => {
  const scenes = content([
    { id: "s01", act: 2, component: "problem-card", ttsText: "a", props: {} },
    { id: "s02", act: 2, component: "knowledge-card", ttsText: "b", props: { knowledgeRef: 1 } },
    { id: "s03", act: 3, component: "step-card", ttsText: "c", props: { stepRef: 1 } },
    { id: "s04", act: 4, component: "general-list", ttsText: "d", props: { itemRef: 1 } },
  ]);
  const tl = buildTimeline(scenes, audio([1.0, 0.5, 1.0, 1.0]));

  it("act1 窗口 = fixed.act1 时长", () => {
    expect(tl.acts[0]).toEqual({ act: 1, startFrame: 0, durFrames: 60 });
  });
  it("场景窗口 = 音频帧长，场间气口 5 帧", () => {
    expect(tl.scenes[0].startFrame).toBe(60);
    expect(tl.scenes[0].durFrames).toBe(30);
    expect(tl.scenes[1].startFrame).toBe(60 + 30 + 5);
    expect(tl.scenes[1].durFrames).toBe(15);
  });
  it("act2 章节字挂第一场 knowledge-card；act3/4 挂各幕第一场", () => {
    expect(tl.chapterSlots.map((c) => [c.act, c.fromFrame])).toEqual([
      [2, 60 + 30 + 5], [3, 60 + 30 + 5 + 15 + 5], [4, 60 + 30 + 5 + 15 + 5 + 30 + 5],
    ]);
  });
  it("act5 = 气口后开始，时长含 2s 定格；totalFrames 收口", () => {
    const act4End = 60 + 30 + 5 + 15 + 5 + 30 + 5 + 30 + 5;
    expect(tl.acts[4]).toEqual({ act: 5, startFrame: act4End - 5, durFrames: Math.round((3.0 + 2.0) * 30) });
    expect(tl.totalFrames).toBe(act4End - 5 + 150);
  });
  it("stepAnchor 解析 stepRef → usesAnchor", () => {
    expect(tl.scenes[2].stepAnchor).toBe("L1");
  });
  it("act 乱序抛错", () => {
    const bad = content([
      { id: "s01", act: 3, component: "step-card", ttsText: "x", props: { stepRef: 1 } },
      { id: "s02", act: 2, component: "knowledge-card", ttsText: "y", props: { knowledgeRef: 1 } },
    ]);
    expect(() => buildTimeline(bad, audio([1, 1]))).toThrow(/act 顺序/);
  });
  it("act2 第一场不是 problem-card 抛错", () => {
    const bad = content([
      { id: "s01", act: 2, component: "knowledge-card", ttsText: "y", props: { knowledgeRef: 1 } },
    ]);
    expect(() => buildTimeline(bad, audio([1]))).toThrow(/problem-card/);
  });
  it("场景无对应音频行抛错", () => {
    expect(() => buildTimeline(scenes, audio([1, 1]))).toThrow(/s03/);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd template && npx vitest run src/engine/timeline.test.ts
```

Expected: FAIL（buildTimeline 未定义）。

- [ ] **Step 3: 实现 timeline.ts**

```ts
import { ACT5_TAIL_SEC, BREATH_SEC, CHAPTER_F, CHAPTER_TITLES, FPS } from "./constants";
import type { ActKey, AudioMeta, ComponentKey, ContentJson, SceneProps } from "./types";

export interface ActWindow { act: 1 | 2 | 3 | 4 | 5; startFrame: number; durFrames: number }
export interface SceneWindow {
  index: number; sceneId: string; act: ActKey; component: ComponentKey; props: SceneProps;
  startFrame: number; durFrames: number; lineFile: string; stepAnchor?: string;
}
export interface ChapterSlot { act: ActKey; fromFrame: number; title: string }
export interface Timeline {
  acts: ActWindow[]; scenes: SceneWindow[]; chapterSlots: ChapterSlot[];
  problemScene: SceneWindow; act4StartFrame: number; totalFrames: number;
}
const frame = (sec: number) => Math.round(sec * FPS);
const BREATH_F = frame(BREATH_SEC);

export function buildTimeline(content: ContentJson, audio: AudioMeta): Timeline {
  const scenes = content.scenes;
  const actOrder = scenes.map((s) => s.act);
  const sorted = [...actOrder].sort((a, b) => a - b);
  if (JSON.stringify(actOrder) !== JSON.stringify(sorted)) {
    throw new Error("scenes 必须按 act 顺序排列（act2 → act3 → act4）");
  }
  const firstAct2 = scenes.find((s) => s.act === 2);
  if (!firstAct2 || firstAct2.component !== "problem-card") {
    throw new Error("act2 第一场必须是 problem-card");
  }
  const lineBy = new Map(audio.lines.map((l) => [l.sceneId, l]));

  const acts: ActWindow[] = [];
  const windows: SceneWindow[] = [];
  let t = 0;
  acts.push({ act: 1, startFrame: 0, durFrames: frame(audio.fixed.act1.durationSec) });
  t = acts[0].durFrames;

  scenes.forEach((s, i) => {
    const line = lineBy.get(s.id);
    if (!line) throw new Error(`场景 ${s.id} 没有对应音频行（audio_meta.lines.sceneId）`);
    const start = t;
    const dur = frame(line.durationSec);
    windows.push({
      index: i, sceneId: s.id, act: s.act, component: s.component,
      props: s.props ?? {}, startFrame: start, durFrames: dur, lineFile: line.file,
      stepAnchor: s.props?.stepRef != null ? content.steps[s.props.stepRef - 1]?.usesAnchor : undefined,
    });
    t = start + dur + BREATH_F;
  });

  const act5Start = t - BREATH_F;
  acts.push({ act: 5, startFrame: act5Start, durFrames: frame(audio.fixed.act5.durationSec + ACT5_TAIL_SEC) });

  const acts234: ActWindow[] = [];
  for (const act of [2, 3, 4] as ActKey[]) {
    const group = windows.filter((w) => w.act === act);
    acts234.push({ act, startFrame: group[0].startFrame, durFrames: group[group.length - 1].startFrame + group[group.length - 1].durFrames - group[0].startFrame });
  }
  const act2FirstKnowledge = windows.find((w) => w.act === 2 && w.component === "knowledge-card");
  if (!act2FirstKnowledge) throw new Error("act2 至少需要一场 knowledge-card（章节字挂靠点）");
  const chapterSlots: ChapterSlot[] = [
    { act: 2, fromFrame: act2FirstKnowledge.startFrame, title: CHAPTER_TITLES[2] },
    { act: 3, fromFrame: acts234[1].startFrame, title: CHAPTER_TITLES[3] },
    { act: 4, fromFrame: acts234[2].startFrame, title: CHAPTER_TITLES[4] },
  ];

  return {
    acts: [acts[0], ...acts234, acts[1]],
    scenes: windows,
    chapterSlots,
    problemScene: windows[0],
    act4StartFrame: acts234[2].startFrame,
    totalFrames: act5Start + acts[1].durFrames,
  };
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
npx vitest run src/engine/timeline.test.ts
```

Expected: 7 个用例全 PASS。

- [ ] **Step 5: 写失败的 contract 校验测试**

```ts
// src/engine/contract.test.ts
import { describe, expect, it } from "vitest";
import { validateContract } from "./contract";
// content/audio fixture 同 timeline.test.ts（复制 helper，勿 import 测试文件）
```

用例：①合法 fixture 返回 `[]`；②`knowledgeRef: 5`（越界）返回含 `knowledgeRef` 的错误；③`derivation-popup` 缺 `formula` 返回含 `formula` 的错误；④`checklist-card` 的 `pitfallRefs` 越界报错；⑤`problem-card` 不在 act2 第一场报错；⑥`act: 1` 出现在 scenes 报错（白名单只允许 2/3/4）。

- [ ] **Step 6: 实现 contract.ts（渲染前的轻校验，与 Phase 2 的 V1 分工：这里只挡"渲染会崩"的错误）**

```ts
import type { AudioMeta, ContentJson, Scene } from "./types";

const ACT_COMPONENTS: Record<number, string[]> = {
  2: ["problem-card", "knowledge-card"],
  3: ["step-card", "derivation-popup", "pitfall-card", "checklist-card"],
  4: ["general-list"],
};
const ref = (p: unknown) => typeof p === "number" && Number.isInteger(p) && p >= 1;

export function validateContract(content: ContentJson, audio: AudioMeta): string[] {
  const errs: string[] = [];
  const lineIds = new Set(content.problem.lines.map((l) => l.id));
  content.scenes.forEach((s: Scene, i) => {
    const tag = `scenes[${i}](${s.id})`;
    if (![2, 3, 4].includes(s.act)) errs.push(`${tag}: act 只允许 2/3/4`);
    if (!(ACT_COMPONENTS[s.act] ?? []).includes(s.component)) {
      errs.push(`${tag}: 组件 ${s.component} 不允许出现在 act${s.act}`);
    }
    if (!s.ttsText?.trim()) errs.push(`${tag}: ttsText 为空`);
    if (!audio.lines.some((l) => l.sceneId === s.id)) errs.push(`${tag}: 无对应音频行`);
    const p = s.props ?? {};
    if (s.component === "problem-card" && (i !== 0 || s.act !== 2)) errs.push(`${tag}: problem-card 必须是 act2 第一场`);
    if (s.component === "knowledge-card" && (!ref(p.knowledgeRef) || p.knowledgeRef! > content.knowledge.length))
      errs.push(`${tag}: knowledgeRef 越界`);
    if ((s.component === "step-card" || s.component === "derivation-popup")) {
      if (!ref(p.stepRef) || p.stepRef! > content.steps.length) errs.push(`${tag}: stepRef 越界`);
    }
    if (s.component === "derivation-popup" && !p.formula?.trim()) errs.push(`${tag}: 缺 formula`);
    if (s.component === "pitfall-card" && (!ref(p.pitfallRef) || p.pitfallRef! > content.pitfalls.length))
      errs.push(`${tag}: pitfallRef 越界`);
    if (s.component === "checklist-card") {
      const ok = Array.isArray(p.pitfallRefs) && p.pitfallRefs.length > 0
        && p.pitfallRefs!.every((r) => ref(r) && r <= content.pitfalls.length);
      if (!ok) errs.push(`${tag}: pitfallRefs 非法`);
    }
    if (s.component === "general-list" && (!ref(p.itemRef) || p.itemRef! > content.generalMethod.length))
      errs.push(`${tag}: itemRef 越界`);
  });
  content.steps.forEach((st, i) => {
    if (!lineIds.has(st.usesAnchor)) errs.push(`steps[${i}].usesAnchor "${st.usesAnchor}" 不存在于 problem.lines`);
  });
  return errs;
}
```

- [ ] **Step 7: 跑全部测试 + Commit**

```bash
npx vitest run && cd /e/codebase/remotion_java && git add template && git commit -m "feat(template): 帧级时间轴引擎 + 契约轻校验（vitest TDD）"
```

---

### Task 4: 示例题 content.json（golden 全量）

**Files:**
- Create: `template/src/data/content.json`（覆写 Task 1 占位）

**Interfaces:**
- Produces: 17 场示例（act2×4 / act3×10 / act4×3），供 Task 5 TTS、Task 7-11 渲染、Task 12 全片验收使用。**题目已验算**：f(x)=x³+ax²+x 在 R 上单调递增 ⇔ f'(x)=3x²+2ax+1≥0 恒成立 ⇔ 开口向上且 Δ=4a²−12≤0 ⇔ a∈[−√3,√3]（允许导数个别零点，端点取得到，闭区间）。

- [ ] **Step 1: 写 content.json（完整内容如下）**

```json
{
  "meta": { "aspect": "16:9", "problemType": "计算题" },
  "problem": {
    "lines": [
      { "id": "L1", "segments": [
        { "type": "text", "value": "已知函数 " },
        { "type": "math", "value": "f(x)=x^{3}+ax^{2}+x" }, { "type": "text", "value": "，" }
      ]},
      { "id": "L2", "segments": [
        { "type": "text", "value": "若 " },
        { "type": "math", "value": "f(x)" }, { "type": "text", "value": " 在 " },
        { "type": "math", "value": "\\mathbb{R}" }, { "type": "text", "value": " 上单调递增，" }
      ]},
      { "id": "L3", "segments": [
        { "type": "text", "value": "求实数 " },
        { "type": "math", "value": "a" }, { "type": "text", "value": " 的取值范围。" }
      ]}
    ]
  },
  "knowledge": [
    { "claim": "可导函数在区间上单调递增，等价于导数在该区间恒非负",
      "formula": "f(x)\\text{ 在 }I\\text{ 单调递增}\\iff f'(x)\\ge 0",
      "premise": "f(x) 在区间内可导；考纲默认单调递增允许导数个别零点",
      "trap": "写成 f'(x)>0 会漏掉临界情形（本题 a=±√3）" },
    { "claim": "二次不等式在全体实数上恒成立的判别法",
      "formula": "ax^{2}+bx+c\\ge 0\\iff a>0\\text{ 且 }\\Delta=b^{2}-4ac\\le 0",
      "premise": "二次项系数符号必须先确认",
      "trap": "忽略开口方向，只看判别式" },
    { "claim": "恒成立问题两条常用路径：判别式法、分离参数法",
      "formula": "\\Delta\\le 0\\quad\\text{或}\\quad a\\ge g(x)_{\\max}",
      "premise": "含二次结构优先判别式；参数能干净分离时用分离参数",
      "trap": "端点开闭要单独检验取等情形" }
  ],
  "steps": [
    { "usesAnchor": "L1", "statement": "对 f(x) 求导",
      "derivation": "f'(x)=3x^{2}+2ax+1", "note": "三次函数的导数是二次函数" },
    { "usesAnchor": "L2", "statement": "把单调递增翻译成导数恒非负",
      "derivation": "f'(x)\\ge 0\\ \\text{在}\\ \\mathbb{R}\\ \\text{上恒成立}", "note": "这是整道题的关键转化" },
    { "usesAnchor": "L2", "statement": "二次函数恒非负的条件",
      "derivation": "3>0\\ \\text{且}\\ \\Delta=(2a)^{2}-12\\le 0", "note": "开口向上由二次项系数 3 保证" },
    { "usesAnchor": "L3", "statement": "解判别式不等式",
      "derivation": "4a^{2}-12\\le 0\\iff a^{2}\\le 3\\iff -\\sqrt{3}\\le a\\le \\sqrt{3}",
      "note": "开方要写全正负两支" },
    { "usesAnchor": "L3", "statement": "写出最终结论",
      "derivation": "a\\in[-\\sqrt{3},\\ \\sqrt{3}]", "note": "取得到等号，端点是闭的" }
  ],
  "pitfalls": [
    { "claim": "把条件写成 f'(x)>0 严格大于", "why": "漏掉判别式等于零的临界情形，丢掉 a=±√3 两个端点" },
    { "claim": "不确认开口方向就直接用判别式", "why": "开口向下时恒非负无解；口诀：先看开口，再看判别式" }
  ],
  "generalMethod": [
    { "step": "识别：可导函数 + 区间上单调", "trick": "立刻联想「导数恒定号」翻译" },
    { "step": "转化：单调 ⇔ 导数恒 ≥0（或恒 ≤0）", "trick": "含参二次上判别式；能分离参数就分离" },
    { "step": "求解并回验：解不等式 + 端点开闭检验", "trick": "取等情形代回验证单调性" }
  ],
  "scenes": [
    { "id": "s01", "act": 2, "component": "problem-card", "ttsText": "我们先看这道题。三行信息：一个三次函数，一个在 R 上单调递增的条件，最后要 a 的取值范围。", "props": {} },
    { "id": "s02", "act": 2, "component": "knowledge-card", "ttsText": "第一件事，回顾考点。可导函数单调递增，等价于导数恒大于等于零。注意，是大于等于，不是严格大于。", "props": { "knowledgeRef": 1 } },
    { "id": "s03", "act": 2, "component": "knowledge-card", "ttsText": "那什么时候一个二次式恒非负？开口向上，判别式小于等于零，两个条件缺一不可。", "props": { "knowledgeRef": 2 } },
    { "id": "s04", "act": 2, "component": "knowledge-card", "ttsText": "这类恒成立问题，二次结构优先用判别式法，参数能分离就用分离参数法，两条路都要会。", "props": { "knowledgeRef": 3 } },
    { "id": "s05", "act": 3, "component": "step-card", "ttsText": "进入解法。第一步，对 f(x) 求导，导数是一个二次函数，3x 方加 2ax 加 1。", "props": { "stepRef": 1 } },
    { "id": "s06", "act": 3, "component": "derivation-popup", "ttsText": "求导这里用幂函数法则，x 的三次方下来变成平方，一次项照抄，常数项求导直接归零。", "props": { "stepRef": 1, "formula": "f'(x)=3x^{2}+2ax+1" } },
    { "id": "s07", "act": 3, "component": "step-card", "ttsText": "第二步，最关键的翻译：在 R 上单调递增，就是说导数在全体实数上恒大于等于零。", "props": { "stepRef": 2 } },
    { "id": "s08", "act": 3, "component": "step-card", "ttsText": "第三步，把恒非负落到二次函数上。开口向上已经满足，剩下判别式要小于等于零，也就是 4a 方减 12 小于等于零。", "props": { "stepRef": 3 } },
    { "id": "s09", "act": 3, "component": "derivation-popup", "ttsText": "判别式自己算一遍：2a 的平方就是 4a 方，再减去 4 乘 3 乘 1，也就是减 12。", "props": { "stepRef": 3, "formula": "\\Delta=4a^{2}-12\\le 0" } },
    { "id": "s10", "act": 3, "component": "step-card", "ttsText": "第四步，解这个不等式。4a 方小于等于 12，a 方小于等于 3，开方得到负根号 3 小于等于 a 小于等于根号 3。", "props": { "stepRef": 4 } },
    { "id": "s11", "act": 3, "component": "step-card", "ttsText": "最后下结论：a 属于闭区间，负根号 3 到根号 3。两个端点都取得到，千万别写成开的。", "props": { "stepRef": 5 } },
    { "id": "s12", "act": 3, "component": "pitfall-card", "ttsText": "第一个易错点：把条件写成导数严格大于零。判别式等于零的临界情形就被你丢了，两个端点直接没了。", "props": { "pitfallRef": 1 } },
    { "id": "s13", "act": 3, "component": "pitfall-card", "ttsText": "第二个易错点：开口方向不确认就直接上判别式。记住口诀，先看开口，再看判别式。", "props": { "pitfallRef": 2 } },
    { "id": "s14", "act": 3, "component": "checklist-card", "ttsText": "做完对一下清单：条件是不是恒成立？端点开闭验过了吗？两秒检查，避免会而不对。", "props": { "pitfallRefs": [1, 2] } },
    { "id": "s15", "act": 4, "component": "general-list", "ttsText": "以后怎么做？第一步，看到可导函数加上区间单调，马上反应：导数恒定号。", "props": { "itemRef": 1 } },
    { "id": "s16", "act": 4, "component": "general-list", "ttsText": "第二步，翻译成恒成立。含参二次就上判别式，能分离参数就分离参数。", "props": { "itemRef": 2 } },
    { "id": "s17", "act": 4, "component": "general-list", "ttsText": "第三步，解完回验端点开闭。记住这套三步流程，这类题就是送分题。", "props": { "itemRef": 3 } }
  ]
}
```

- [ ] **Step 2: 验证 + Commit**

```bash
cd template && node -e "const c=require('./src/data/content.json'); console.log(c.scenes.length)"
npx tsc --noEmit
cd /e/codebase/remotion_java && git add template && git commit -m "feat(template): 示例题 content.json（golden 全量 17 场，已验算）"
```

Expected: 输出 17；tsc 通过。

---

### Task 5: TTS 生成脚本 + 全量示例音频

**Files:**
- Create: `template/scripts/gen_tts_template.py`
- Create（脚本产物，gitignore 音频不忽略——固定台词与示例音频是模板资产，入库）: `template/public/audio/fixed/act1.wav`, `act5.wav`, `template/public/audio/lines/line_01.wav … line_17.wav`, `template/src/data/audio_meta.json`

**Interfaces:**
- Consumes: `src/data/content.json` 的 scenes[].ttsText；固定台词（脚本内常量，模板资产，不进 content.json）。
- Produces: `audio_meta.json`（契约见"数据契约"节）；wav 命名 `line_{scene 顺序号:02d}.wav`（scenes 数组顺序，与 sceneId 无关）。

**TTS 铁律（从抖音项目移植，dashscope-tts-pitfalls 全套）：**
- `qwen-tts` + `voice="Cherry"`（大小写敏感），语速恒 1.0 不传变速参数。
- 请求间隔 ≥3s；异常退避 3×2^n 秒。
- WAV 头不可信（data-size=INT32_MAX）：时长 = 文件字节数 ÷ byte_rate（byte_rate = ch@22 × sampleRate@24 × bits@34 ÷ 8）。
- 完整性判据：`last80 < 100`（数字静音）OR（`last80 < 0.35×prev240` 且 `last80 < 0.35×prev480`，气音自然衰减）。
- 择优：先按时长过滤（≥ 本句全部 take 最长值的 92%），再取尾部最静（last80 最小）。
- 每句 ≤3 take；某句 3 take 全败 → **exit 1 让人重跑整批**（Phase 1 不做整批自动重录，那是 Phase 2 Java 的活；但脚本必须先删旧 wav 再整批重跑，避免跨批次音色漂移混批）。

- [ ] **Step 1: 写 scripts/gen_tts_template.py**

```python
# -*- coding: utf-8 -*-
"""
gen_tts_template.py — 模板音频一次性生成（fixed 2 句 + content.json 全部场次）
产出: public/audio/fixed/act1.wav act5.wav, public/audio/lines/line_NN.wav, src/data/audio_meta.json
重跑: 删除 public/audio/lines 与 fixed 后整批重跑（禁止混批，防跨批次音色漂移）
Key:  环境变量 DASHSCOPE_API_KEY 优先，否则解析旧项目 TTS 配置文件的 ApiKey 行
"""
import json, math, os, pathlib, struct, sys, time
import requests
from dashscope.audio.qwen_tts import SpeechSynthesizer

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONTENT = ROOT / "src" / "data" / "content.json"
META_OUT = ROOT / "src" / "data" / "audio_meta.json"
AUDIO_DIR = ROOT / "public" / "audio"
FALLBACK_KEY_FILE = pathlib.Path(r"C:\Users\jinlin.he\Desktop\remotion参考(1)\remotion参考\TTS就选它.txt")

VOICE, MODEL, THROTTLE, MAX_TAKES, DUR_RATIO = "Cherry", "qwen-tts", 3.0, 3, 0.92
FIXED_LINES = {
    "act1": "你好啊同学，我来帮你解决这道题，坐好发车！",
    "act5": "这就是本道题的解法，希望能够帮到你，祝你考研一战上岸！",
}

def load_key() -> str:
    if os.environ.get("DASHSCOPE_API_KEY"):
        return os.environ["DASHSCOPE_API_KEY"].strip()
    for line in FALLBACK_KEY_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
        if line.strip().lower().startswith("apikey:"):
            return line.split(":", 1)[1].strip()
    sys.exit("[fatal] 未找到 DashScope Key（设 DASHSCOPE_API_KEY）")

def wav_duration(path: pathlib.Path) -> float:
    raw = path.read_bytes()
    if len(raw) < 44 or raw[0:4] != b"RIFF" or raw[8:12] != b"WAVE":
        raise ValueError(f"{path.name} 不是合法 WAV")
    ch, sr = struct.unpack("<H", raw[22:24])[0], struct.unpack("<I", raw[24:28])[0]
    bits = struct.unpack("<H", raw[34:36])[0]
    byte_rate = ch * sr * bits // 8
    if byte_rate <= 0:
        raise ValueError(f"{path.name} byte_rate 异常")
    return len(raw) / byte_rate

def parse_pcm(path: pathlib.Path):
    raw = path.read_bytes()
    pos = 12
    while pos + 8 <= len(raw):
        cid, size = raw[pos:pos+4], struct.unpack("<I", raw[pos+4:pos+8])[0]
        if cid == b"data":
            pcm = raw[pos+8:]
            n = len(pcm) // 2
            return struct.unpack(f"<{n}h", pcm[:n*2]), struct.unpack("<I", raw[24:28])[0]
        pos += 8 + size + (size & 1)
    return None, None

def rms(seg) -> float:
    return math.sqrt(sum(x*x for x in seg) / max(1, len(seg)))

def tail_profile(path: pathlib.Path):
    samples, sr = parse_pcm(path)
    if not samples:
        return 1e9, 1e9, 1e9
    w, w3 = int(sr*0.08), int(sr*0.24)
    return (rms(samples[-w:]), rms(samples[-w-w3:-w]), rms(samples[-w-2*w3:-w-w3]))

def is_complete(t: dict) -> bool:
    if t["last80"] < 100.0:
        return True
    return t["last80"] < 0.35*t["prev240"] and t["last80"] < 0.35*t["prev480"]

def find_audio_url(obj):
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k.lower() in ("url", "audio_url") and isinstance(v, str) and v.startswith("http"):
                return v
            found = find_audio_url(v)
            if found:
                return found
    elif isinstance(obj, list):
        for item in obj:
            found = find_audio_url(item)
            if found:
                return found
    return None

def synth(key: str, text: str, dest: pathlib.Path) -> bool:
    resp = SpeechSynthesizer.call(model=MODEL, api_key=key, text=text, voice=VOICE)
    if resp.status_code != 200:
        print(f"    [tts] status={resp.status_code} {getattr(resp, 'message', '')}")
        return False
    url = find_audio_url(resp.output)
    if not url:
        return False
    r = requests.get(url, timeout=180)
    r.raise_for_status()
    dest.write_bytes(r.content)
    return True

def synthesize_line(key: str, text: str, dest: pathlib.Path) -> dict:
    """≤3 take，完整性+时长过滤后取尾部最静。全败 sys.exit(1)。"""
    takes = []
    for take in range(MAX_TAKES):
        tmp = dest.with_suffix(f".take{take+1}.wav")
        try:
            ok = synth(key, text, tmp)
        except Exception as e:
            print(f"    [net] {e}")
            ok = False
        if ok:
            l80, p240, p480 = tail_profile(tmp)
            dur = wav_duration(tmp)
            takes.append({"dur": dur, "last80": l80, "prev240": p240, "prev480": p480, "path": tmp})
            print(f"    take{take+1}: {dur:.2f}s last80={l80:.0f} {'完整' if is_complete(takes[-1]) else '截断'}")
        time.sleep(THROTTLE)  # 3 take 烧满再择优（先时长过滤再取尾部最静），与 spec §12 一致
    maxd = max((t["dur"] for t in takes), default=0)
    cands = [t for t in takes if t["dur"] >= DUR_RATIO*maxd and is_complete(t)]
    if not cands:
        sys.exit(f"[fatal] 「{text[:18]}…」{MAX_TAKES} take 全败。删除 public/audio 整批重跑（禁单句补录）。")
    best = min(cands, key=lambda t: t["last80"])
    dest.write_bytes(best["path"].read_bytes())
    for t in takes:
        t["path"].unlink()
    return {"durationSec": round(best["dur"], 3)}

def main() -> None:
    key = load_key()
    content = json.loads(CONTENT.read_text(encoding="utf-8"))
    fixed_dir, lines_dir = AUDIO_DIR/"fixed", AUDIO_DIR/"lines"
    fixed_dir.mkdir(parents=True, exist_ok=True)
    lines_dir.mkdir(parents=True, exist_ok=True)
    meta = {
        "voice": VOICE, "model": MODEL, "rate": 1.0, "fps": 30,
        "breathSec": 0.18, "act5TailSec": 2.0, "fixed": {}, "lines": [],
    }
    for name, text in FIXED_LINES.items():
        dest = fixed_dir/f"{name}.wav"
        print(f"[fixed:{name}] {text}")
        r = synthesize_line(key, text, dest)
        meta["fixed"][name] = {"file": f"audio/fixed/{name}.wav", **r}
        time.sleep(THROTTLE)
    for i, sc in enumerate(content["scenes"], start=1):
        dest = lines_dir/f"line_{i:02d}.wav"
        print(f"[line_{i:02d}] ({sc['id']}) {sc['ttsText'][:24]}…")
        r = synthesize_line(key, sc["ttsText"], dest)
        meta["lines"].append({"index": i, "sceneId": sc["id"], "file": f"audio/lines/line_{i:02d}.wav",
                              "durationSec": r["durationSec"], "text": sc["ttsText"]})
        time.sleep(THROTTLE)
    META_OUT.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    total = sum(m["durationSec"] for m in meta["lines"])
    print(f"[done] 正文 {len(meta['lines'])} 句净长 {total:.1f}s → {META_OUT}")

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 设置 key 并运行**

```bash
cd template
export DASHSCOPE_API_KEY="<从旧项目 TTS 配置文件复制>"   # 或脚本自动回落解析
python scripts/gen_tts_template.py
```

Expected: 19 句全部合成成功，`audio_meta.json` 生成；无 `[fatal]`。若某句 3 take 全败：按提示删除 `public/audio/` 整批重跑。

- [ ] **Step 3: 人工抽听** — 试听 `fixed/act1.wav`、`fixed/act5.wav`、`lines/line_01.wav`、`lines/line_17.wav`：语音完整、音色一致。

- [ ] **Step 4: Commit**

```bash
cd /e/codebase/remotion_java && git add template && git commit -m "feat(template): TTS 生成脚本 + 示例题全量音频（Cherry，完整性验证择优）"
```

---

### Task 6: 动画工具 + KaTeX 组件

**Files:**
- Create: `template/src/acts/components/anim.tsx`, `template/src/acts/components/Katex.tsx`
- Modify: `template/src/acts/LectureVideo.tsx`（顶部 `import "katex/dist/katex.min.css";`）

**Interfaces:**
- Produces:
  - `<SceneCard durFrames={n} delay={n?} className?/style?>{children}` — 卡片容器：spring 进入（局部帧 delay 起 16 帧）+ 最后 `EXIT_F` 帧淡出上移。所有白名单组件的进出动画**必须**经它或 ChapterTitle 同款曲线。
  - `useEnter(delay?)` → `{opacity, y}`（只进入不退出的容器用）
  - `<Katex tex fontSize? color? />` — KaTeX 行内渲染，`throwOnError:false`，禁止公式崩坏时抛错中断渲染。

- [ ] **Step 1: 写 anim.tsx**

```tsx
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import type { CSSProperties, ReactNode } from "react";
import { EXIT_F } from "../../engine/constants";

export const useEnter = (delay = 0) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f - delay, fps, config: { damping: 16, stiffness: 110 }, durationInFrames: 16 });
  return { opacity: s, y: (1 - s) * 36 };
};

export const SceneCard: React.FC<{ durFrames: number; delay?: number; style?: CSSProperties; children: ReactNode }> =
({ durFrames, delay = 8, style, children }) => {
  const f = useCurrentFrame();
  const { opacity, y } = useEnter(delay);
  const exit = interpolate(f, [durFrames - EXIT_F, durFrames], [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ opacity: opacity * exit,
      transform: `translateY(${y + (1 - exit) * -24}px)`, ...style }}>
      {children}
    </AbsoluteFill>
  );
};
```

（`AbsoluteFill` 从 remotion import。）

- [ ] **Step 2: 写 Katex.tsx**

```tsx
import katex from "katex";
import { useMemo } from "react";
import { COLORS } from "../../engine/constants";

export const Katex: React.FC<{ tex: string; fontSize?: number; color?: string }> =
({ tex, fontSize = 44, color = COLORS.text }) => {
  const html = useMemo(() =>
    katex.renderToString(tex, { throwOnError: false, output: "html" }), [tex]);
  return (
    <div style={{ fontSize, color, lineHeight: 1.35, maxWidth: "100%", overflow: "hidden" }}
      dangerouslySetInnerHTML={{ __html: html }} />
  );
};
```

- [ ] **Step 3: 验证 + Commit**

```bash
cd template && npx tsc --noEmit
cd /e/codebase/remotion_java && git add template && git commit -m "feat(template): SceneCard 进出动画工具 + KaTeX 渲染组件"
```

（渲染验证在 Task 8/9 的 still 抽帧中一并完成。）

---

### Task 7: ProblemStage / ChapterTitle / 卡片壳 / 幕2 白名单组件

**Files:**
- Create: `template/src/acts/components/CardShell.tsx`, `template/src/acts/components/ProblemPanel.tsx`, `template/src/acts/ProblemStage.tsx`, `template/src/acts/components/ChapterTitle.tsx`, `template/src/acts/components/KnowledgeCard.tsx`

**Interfaces:**
- Consumes: Task 2 常量/布局、Task 3 `Timeline` 类型、Task 6 `SceneCard/useEnter/Katex`。
- Produces:
  - `CardShell {chip, accent?, children}` 统一卡片壳（圆角/边框/标题角标）；`Row {label, text, color}` 行。
  - `ProblemPanel {content, highlightId?, compact?}` — 题干卡片本体（ProblemStage 全屏与左上角共用同一内容，缩放渲染）。
  - `ProblemStage {content, timeline, layout}` — 跨幕常驻题干（全屏→缩移→常驻→淡出，生命周期见"数据契约"节；`stepAnchor` 驱动行高亮）。
  - `ChapterTitle {title, durFrames}` — 章节大字 overlay（scrim + 文字 + 下划线动画）。
  - `KnowledgeCard {k, durFrames}`。

- [ ] **Step 1: CardShell.tsx**

```tsx
import type { ReactNode } from "react";
import { COLORS } from "../../engine/constants";

export const CardShell: React.FC<{ chip: string; accent?: string; children: ReactNode }> =
({ chip, accent = COLORS.accent, children }) => (
  <div style={{ backgroundColor: COLORS.card, border: `2px solid ${COLORS.cardBorder}`,
    borderRadius: 24, padding: "40px 48px", width: "100%", boxShadow: "0 12px 40px rgba(0,0,0,0.45)" }}>
    <div style={{ display: "inline-block", fontSize: 26, fontWeight: 700, color: COLORS.ink,
      backgroundColor: accent, borderRadius: 10, padding: "4px 18px", marginBottom: 26 }}>{chip}</div>
    {children}
  </div>
);

export const Row: React.FC<{ label: string; text: string; color: string }> = ({ label, text, color }) => (
  <div style={{ display: "flex", gap: 18, alignItems: "baseline", marginTop: 22 }}>
    <span style={{ flexShrink: 0, fontSize: 26, fontWeight: 700, color: COLORS.ink,
      backgroundColor: color, borderRadius: 8, padding: "2px 14px" }}>{label}</span>
    <span style={{ fontSize: 32, color: COLORS.text, lineHeight: 1.4 }}>{text}</span>
  </div>
);
```

- [ ] **Step 2: ProblemPanel.tsx（题干本体；math 段用 KaTeX 行内）**

```tsx
import { COLORS, FONT_FAMILY } from "../../engine/constants";
import katex from "katex";
import { useMemo } from "react";
import type { ContentJson, ProblemLine } from "../../engine/types";

const inline = (tex: string) =>
  katex.renderToString(tex, { throwOnError: false, output: "html" });

const Line: React.FC<{ line: ProblemLine; highlighted: boolean }> = ({ line, highlighted }) => (
  <div style={{ display: "flex", alignItems: "center", gap: 16, padding: "10px 18px", borderRadius: 12,
    backgroundColor: highlighted ? "rgba(255,213,74,0.16)" : "transparent",
    borderLeft: highlighted ? `6px solid ${COLORS.hl}` : "6px solid transparent",
    transition: "none" }}>
    {line.segments.map((seg, i) => seg.type === "text"
      ? <span key={i} style={{ fontSize: 50, color: COLORS.text, fontFamily: FONT_FAMILY }}>{seg.value}</span>
      : <span key={i} style={{ fontSize: 46, color: highlighted ? COLORS.hl : COLORS.accent }}
          dangerouslySetInnerHTML={{ __html: inline(seg.value) }} />)}
  </div>
);

export const ProblemPanel: React.FC<{ content: ContentJson; highlightId?: string; compact?: boolean }> =
({ content, highlightId, compact }) => (
  <div style={{ backgroundColor: COLORS.card, border: `3px solid ${COLORS.cardBorder}`, borderRadius: 24,
    padding: compact ? 24 : 40, opacity: compact ? 0.96 : 1 }}>
    <div style={{ display: "inline-block", fontSize: 28, fontWeight: 700, color: COLORS.ink,
      backgroundColor: COLORS.accent, borderRadius: 10, padding: "4px 18px", marginBottom: 18 }}>题目</div>
    {content.problem.lines.map((l) => (
      <Line key={l.id} line={l} highlighted={highlightId === l.id} />
    ))}
  </div>
);
```

- [ ] **Step 3: ProblemStage.tsx（生命周期：全屏→18 帧缩移→常驻→act4 开始 18 帧淡出）**

```tsx
import { interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { TRANSITION_F } from "../engine/constants";
import type { Layout, Rect } from "../engine/layout";
import type { ContentJson } from "../engine/types";
import type { Timeline } from "../engine/timeline";
import { ProblemPanel } from "./components/ProblemPanel";

export const ProblemStage: React.FC<{ content: ContentJson; timeline: Timeline; layout: Layout }> =
({ content, timeline, layout }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = timeline.problemScene;
  const pStart = p.startFrame, pEnd = p.startFrame + p.durFrames;
  const act4Start = timeline.act4StartFrame;
  if (frame < pStart || frame > act4Start + TRANSITION_F) return null;

  const design = layout.problemFull, corner = layout.corner;
  const t = interpolate(frame, [pEnd - TRANSITION_F, pEnd], [0, 1],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const rect: Rect = {
    x: design.x + (corner.x - design.x) * t,
    y: design.y + (corner.y - design.y) * t,
    w: design.w + (corner.w - design.w) * t,
    h: design.h + (corner.h - design.h) * t,
  };
  const enter = spring({ frame: frame - pStart, fps, config: { damping: 18, stiffness: 90 }, durationInFrames: 14 });
  const exitFade = interpolate(frame, [act4Start, act4Start + TRANSITION_F], [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });

  const cur = timeline.scenes.find((s) => frame >= s.startFrame && frame < s.startFrame + s.durFrames);
  const anchor = cur?.stepAnchor;

  return (
    <div style={{ position: "absolute", left: rect.x, top: rect.y, width: rect.w, opacity: enter * exitFade }}>
      <div style={{ width: design.w, transform: `scale(${rect.w / design.w})`, transformOrigin: "top left" }}>
        <ProblemPanel content={content} highlightId={anchor} compact={t === 1} />
      </div>
    </div>
  );
};
```

- [ ] **Step 4: ChapterTitle.tsx（scrim 淡入 8 帧、文字 spring、下划线宽度动画、末 12 帧退场）**

```tsx
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { COLORS, FONT_FAMILY } from "../engine/constants";

export const ChapterTitle: React.FC<{ title: string; durFrames: number }> = ({ title, durFrames }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const scrim = interpolate(f, [0, 8, durFrames - 12, durFrames], [0, 0.55, 0.55, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const s = spring({ frame: f - 4, fps, config: { damping: 14, stiffness: 120 }, durationInFrames: 16 });
  const barW = interpolate(f, [8, 30], [0, 360], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ backgroundColor: `rgba(14,18,32,${scrim})`, justifyContent: "center",
      alignItems: "center", flexDirection: "column" }}>
      <div style={{ opacity: s, transform: `scale(${0.85 + 0.15 * s})`, textAlign: "center" }}>
        <div style={{ fontSize: 110, fontWeight: 800, color: COLORS.text, fontFamily: FONT_FAMILY,
          letterSpacing: 10 }}>{title}</div>
        <div style={{ height: 8, width: barW, backgroundColor: COLORS.accent, borderRadius: 4,
          margin: "26px auto 0" }} />
      </div>
    </AbsoluteFill>
  );
};
```

- [ ] **Step 5: KnowledgeCard.tsx**

```tsx
import { COLORS } from "../../engine/constants";
import type { Knowledge } from "../../engine/types";
import { Katex } from "./Katex";
import { CardShell, Row } from "./CardShell";
import { SceneCard } from "./anim";

export const KnowledgeCard: React.FC<{ k: Knowledge; durFrames: number }> = ({ k, durFrames }) => (
  <SceneCard durFrames={durFrames}>
    <CardShell chip="考点">
      <div style={{ fontSize: 44, fontWeight: 700, color: COLORS.text, lineHeight: 1.35 }}>{k.claim}</div>
      <div style={{ marginTop: 26 }}><Katex tex={k.formula} fontSize={58} color={COLORS.accent} /></div>
      <Row label="前提" text={k.premise} color={COLORS.sub} />
      <Row label="易忽略" text={k.trap} color={COLORS.warn} />
    </CardShell>
  </SceneCard>
);
```

- [ ] **Step 6: 验证 + Commit**

```bash
cd template && npx tsc --noEmit
cd /e/codebase/remotion_java && git add template && git commit -m "feat(template): 常驻题干 ProblemStage/章节大字/卡片壳/考点卡"
```

---

### Task 8: Act1 + Act5（固定幕，立即可渲染验证）

**Files:**
- Create: `template/src/acts/Act1Intro.tsx`, `template/src/acts/Act5Outro.tsx`

**Interfaces:**
- Consumes: `Timeline.acts[0] / acts[4]` 窗口；`COLORS.white/ink`。
- Produces:
  - `<Act1Intro durFrames />`：深色底，居中「WhatsYourFuture帮你讲」108px 淡入 12 帧→停留→末 15 帧淡出；下方「WhatsYourFuture版权所有」38px sub 色。
  - `<Act5Outro durFrames />`：纯白底；打字机「WhatsYourFuture出品」（局部帧 6 起、2 帧/字）→「希望能够帮到你」（局部帧 46 起）；末 20 帧整体淡出。

- [ ] **Step 1: Act1Intro.tsx**

```tsx
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { COLORS, FONT_FAMILY } from "../engine/constants";

export const Act1Intro: React.FC<{ durFrames: number }> = ({ durFrames }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f, fps, config: { damping: 20, stiffness: 80 }, durationInFrames: 12 });
  const out = interpolate(f, [durFrames - 15, durFrames], [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <AbsoluteFill style={{ backgroundColor: COLORS.bg, justifyContent: "center", alignItems: "center" }}>
      <div style={{ opacity: s * out, textAlign: "center" }}>
        <div style={{ fontSize: 108, fontWeight: 800, color: COLORS.text, fontFamily: FONT_FAMILY }}>
          WhatsYourFuture<span style={{ color: COLORS.accent }}>帮你讲</span>
        </div>
        <div style={{ marginTop: 30, fontSize: 38, color: COLORS.sub, letterSpacing: 6 }}>
          WhatsYourFuture 版权所有
        </div>
      </div>
    </AbsoluteFill>
  );
};
```

- [ ] **Step 2: Act5Outro.tsx（打字机 = 按帧数截取字符串；光标方块闪烁）**

```tsx
import { AbsoluteFill, interpolate, useCurrentFrame } from "remotion";
import { COLORS, FONT_FAMILY } from "../engine/constants";

const typed = (text: string, f: number, startF: number) =>
  text.slice(0, Math.max(0, Math.floor((f - startF) / 2)));

export const Act5Outro: React.FC<{ durFrames: number }> = ({ durFrames }) => {
  const f = useCurrentFrame();
  const out = interpolate(f, [durFrames - 20, durFrames], [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const l1 = typed("WhatsYourFuture出品", f, 6);
  const l2 = typed("希望能够帮到你", f, 46);
  return (
    <AbsoluteFill style={{ backgroundColor: COLORS.white, justifyContent: "center",
      alignItems: "center", opacity: out }}>
      <div style={{ textAlign: "center" }}>
        <div style={{ fontSize: 92, fontWeight: 800, color: COLORS.ink, fontFamily: FONT_FAMILY,
          minHeight: 120 }}>
          {l1}<span style={{ display: "inline-block", width: 8, height: 80, backgroundColor: COLORS.accent,
            marginLeft: 6, opacity: f % 20 < 10 ? 1 : 0 }} />
        </div>
        <div style={{ marginTop: 24, fontSize: 56, color: COLORS.sub, minHeight: 80 }}>
          {l2}{l2.length > 0 && l2.length < 7 ? "_" : ""}
        </div>
      </div>
    </AbsoluteFill>
  );
};
```

- [ ] **Step 3: 临时挂进 LectureVideo 渲染验证（Task 11 会重写总装）**

`LectureVideo.tsx` 暂时替换为（验证固定幕曲线 + 音频占位）：

```tsx
import { AbsoluteFill, Sequence, staticFile, Audio } from "remotion";
import { COLORS, FONT_FAMILY } from "../engine/constants";
import { Act1Intro } from "./Act1Intro";
import { Act5Outro } from "./Act5Outro";

export const LectureVideo: React.FC<{ aspect: "16:9" | "9:16" }> = ({ aspect }) => (
  <AbsoluteFill style={{ backgroundColor: COLORS.bg, color: COLORS.text, fontFamily: FONT_FAMILY }}>
    <Sequence durationInFrames={120}>
      <Act1Intro durFrames={120} />
      <Audio src={staticFile("audio/fixed/act1.wav")} />
    </Sequence>
    <Sequence from={130} durationInFrames={180}>
      <Act5Outro durFrames={180} />
      <Audio src={staticFile("audio/fixed/act5.wav")} />
    </Sequence>
  </AbsoluteFill>
);
```

- [ ] **Step 4: 抽帧验证**

```bash
cd template && npx remotion still Lecture169 out/qa/act1_f60.png --frame=60
npx remotion still Lecture169 out/qa/act5_f200.png --frame=200
npx remotion still Lecture169 out/qa/act5_f300.png --frame=300
```

Expected: act1 深色底标题完整清晰；act5 白底黑字打字机（f200 两行基本齐全，f300 仍在白底）。人工对照验收：无黑字（白底文字是 ink 色）、无瞬现。

- [ ] **Step 5: Commit**

```bash
cd /e/codebase/remotion_java && git add template && git commit -m "feat(template): 幕1片头/幕5片尾固定组件"
```

---

### Task 9: 数据接线 + Act2 + LectureVideo 总装 v1 + pick_frames

**Files:**
- Create: `template/src/engine/data.ts`, `template/src/acts/Act2Knowledge.tsx`, `template/scripts/pick_frames.mjs`
- Modify: `template/src/acts/LectureVideo.tsx`（总装 v1）, `template/src/Root.tsx`（正式 duration）

**Interfaces:**
- Consumes: Task 3 `buildTimeline/validateContract`、Task 7/8 组件、Task 5 `audio_meta.json`。
- Produces:
  - `engine/data.ts`: `content: ContentJson`、`audioMeta: AudioMeta`、`timeline: Timeline`（模块加载即校验，契约错直接 throw 中止渲染）。
  - `Act2Knowledge {content, timeline, layout}`：幕2 knowledge-card 场序列。
  - `pick_frames.mjs`：镜像时间轴公式输出审核帧号（后续所有视觉任务的抽帧工具）。
  - **LectureVideo 最终形态确立**（本任务起不再改结构，Task 10/11 只加 Act3/Act4 两行）。

- [ ] **Step 1: engine/data.ts**

```ts
import contentJson from "../data/content.json";
import audioMetaJson from "../data/audio_meta.json";
import { buildTimeline } from "./timeline";
import { validateContract } from "./contract";
import type { AudioMeta, ContentJson } from "./types";

export const content = contentJson as unknown as ContentJson;
export const audioMeta = audioMetaJson as unknown as AudioMeta;

const errs = validateContract(content, audioMeta);
if (errs.length > 0) {
  throw new Error("content.json 契约校验失败:\n" + errs.join("\n"));
}
export const timeline = buildTimeline(content, audioMeta);
```

- [ ] **Step 2: Act2Knowledge.tsx**

```tsx
import { Sequence } from "remotion";
import type { Layout } from "../engine/layout";
import type { ContentJson } from "../engine/types";
import type { Timeline } from "../engine/timeline";
import { KnowledgeCard } from "./components/KnowledgeCard";

export const Act2Knowledge: React.FC<{ content: ContentJson; timeline: Timeline; layout: Layout }> =
({ content, timeline, layout }) => (
  <div style={{ position: "absolute", left: layout.main.x, top: layout.main.y,
    width: layout.main.w, height: layout.main.h }}>
    {timeline.scenes
      .filter((w) => w.act === 2 && w.component === "knowledge-card")
      .map((w) => (
        <Sequence key={w.sceneId} from={w.startFrame} durationInFrames={w.durFrames}>
          <KnowledgeCard k={content.knowledge[w.props.knowledgeRef! - 1]} durFrames={w.durFrames} />
        </Sequence>
      ))}
  </div>
);
```

- [ ] **Step 3: LectureVideo 总装 v1（最终结构；幕3/幕4 行 Task 10/11 加）**

```tsx
import { AbsoluteFill, Audio, Sequence, staticFile } from "remotion";
import { CHAPTER_F, COLORS, FONT_FAMILY } from "../engine/constants";
import { LAYOUTS } from "../engine/layout";
import { audioMeta, content, timeline } from "../engine/data";
import { Act1Intro } from "./Act1Intro";
import { Act5Outro } from "./Act5Outro";
import { ProblemStage } from "./ProblemStage";
import { Act2Knowledge } from "./Act2Knowledge";
import { ChapterTitle } from "./components/ChapterTitle";

export const LectureVideo: React.FC<{ aspect: "16:9" | "9:16" }> = ({ aspect }) => {
  // 布局由 composition 参数决定（一套内容两个 composition 都能渲）；
  // content.meta.aspect 只是请求画幅记录，Phase 2 的 V1 负责与任务参数核对，渲染层不做 throw。
  const layout = LAYOUTS[aspect];
  const act1 = timeline.acts[0], act5 = timeline.acts[4];
  return (
    <AbsoluteFill style={{ backgroundColor: COLORS.bg, color: COLORS.text, fontFamily: FONT_FAMILY }}>
      {/* 幕1 固定片头 */}
      <Sequence durationInFrames={act1.durFrames}>
        <Act1Intro durFrames={act1.durFrames} />
        <Audio src={staticFile(audioMeta.fixed.act1.file)} />
      </Sequence>

      {/* 幕2-4 */}
      <ProblemStage content={content} timeline={timeline} layout={layout} />
      <Act2Knowledge content={content} timeline={timeline} layout={layout} />
      {/* Act3 / Act4: Task 10 / 11 */}
      {timeline.chapterSlots.map((c) => (
        <Sequence key={c.act} from={c.fromFrame} durationInFrames={CHAPTER_F}>
          <ChapterTitle title={c.title} durFrames={CHAPTER_F} />
        </Sequence>
      ))}
      {timeline.scenes.map((w) => (
        <Sequence key={w.sceneId} from={w.startFrame} durationInFrames={w.durFrames}>
          <Audio src={staticFile(w.lineFile)} />
        </Sequence>
      ))}

      {/* 幕5 固定片尾 */}
      <Sequence from={act5.startFrame} durationInFrames={act5.durFrames}>
        <Act5Outro durFrames={act5.durFrames} />
        <Audio src={staticFile(audioMeta.fixed.act5.file)} />
      </Sequence>
    </AbsoluteFill>
  );
};
```

- [ ] **Step 4: Root.tsx 正式版（时长来自 timeline）**

```tsx
import { Composition } from "remotion";
import { LectureVideo } from "./acts/LectureVideo";
import { timeline } from "./engine/data";

export const RemotionRoot: React.FC = () => (
  <>
    <Composition id="Lecture169" component={LectureVideo} width={1920} height={1080} fps={30}
      durationInFrames={timeline.totalFrames} defaultProps={{ aspect: "16:9" }} />
    <Composition id="Lecture916" component={LectureVideo} width={1080} height={1920} fps={30}
      durationInFrames={timeline.totalFrames} defaultProps={{ aspect: "9:16" }} />
  </>
);
```

- [ ] **Step 5: scripts/pick_frames.mjs（镜像时间轴公式，禁 import src 代码——QA 侧独立复算，防止同源错误互相掩护）**

```js
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
```

- [ ] **Step 6: 抽帧验证幕1/幕2/章节字**

```bash
cd template
node scripts/pick_frames.mjs > out/qa/frames.txt   # out 目录不存在先 mkdir -p out/qa
cat out/qa/frames.txt
for name in s-s01 s-s02 act2-章节字; do
  f=$(grep -P "^\Q$name" out/qa/frames.txt | head -1 | cut -f2)
  npx remotion still Lecture169 "out/qa/${name//\//_}.png" --frame=$f
done
```

Expected：s01 帧 = 题干全屏卡片居中完整；s02 帧 = 左上角题干小卡 + 右侧考点卡；章节字帧 = 「知识点回顾」大字 + 半透明 scrim。人工核对无重叠/无黑字。

- [ ] **Step 7: Commit**

```bash
cd /e/codebase/remotion_java && git add template && git commit -m "feat(template): 数据接线+幕2组装+总装v1+审核帧工具"
```

---

### Task 10: 幕3 白名单组件 + Act3（分组 + 结论条）

**Files:**
- Create: `template/src/acts/components/StepCard.tsx`, `template/src/acts/components/DerivationPopup.tsx`, `template/src/acts/components/PitfallCard.tsx`, `template/src/acts/components/ChecklistCard.tsx`, `template/src/acts/Act3Solution.tsx`

**Interfaces:**
- Consumes: Task 6/7 组件与工具、Timeline。
- Produces:
  - `StepCard {step, index, total, durFrames}` — 步骤卡（statement + derivation KaTeX + note），SceneCard 退出 = 用完即消。
  - `DerivationPopup {formula, note, durFrames}` — 推导弹窗（主区下半，warn 角标）。
  - `PitfallCard {p, durFrames}`；`ChecklistCard {pitfalls, durFrames}` — ✓ 逐项 stagger 弹出。
  - `Act3Solution {content, timeline, layout}`：
    - **分组规则**：act3 内连续的 `step-card`/`derivation-popup`（同 stepRef）合并为一组，同组共享一个 Sequence（组内步骤卡不闪退），组末 SceneCard 退出；pitfall/checklist 场独立成组。
    - **结论条**：act3 最后一个含 step-card 的组结束后，主区顶部常驻小型结论卡（该步 derivation 公式 + ✓），至 act4Start 淡出（`TRANSITION_F`）；pitfall/checklist 卡片容器整体下移 220px 避让。

- [ ] **Step 1: StepCard.tsx**

```tsx
import { COLORS } from "../../engine/constants";
import type { Step } from "../../engine/types";
import { Katex } from "./Katex";
import { CardShell } from "./CardShell";
import { SceneCard } from "./anim";

export const StepCard: React.FC<{ step: Step; index: number; total: number; durFrames: number }> =
({ step, index, total, durFrames }) => (
  <SceneCard durFrames={durFrames}>
    <CardShell chip={`第 ${index} 步 / 共 ${total} 步`}>
      <div style={{ fontSize: 44, fontWeight: 700, color: COLORS.text, lineHeight: 1.35 }}>
        {step.statement}
      </div>
      <div style={{ marginTop: 24, backgroundColor: COLORS.bg2, borderRadius: 16, padding: "20px 28px" }}>
        <Katex tex={step.derivation} fontSize={54} color={COLORS.accent} />
      </div>
      <div style={{ marginTop: 20, fontSize: 30, color: COLORS.sub }}>
        <span style={{ color: COLORS.hl, fontWeight: 700 }}>注 </span>{step.note}
      </div>
    </CardShell>
  </SceneCard>
);
```

- [ ] **Step 2: DerivationPopup.tsx**

```tsx
import { COLORS } from "../../engine/constants";
import { Katex } from "./Katex";
import { CardShell } from "./CardShell";
import { SceneCard } from "./anim";

export const DerivationPopup: React.FC<{ formula: string; note: string; durFrames: number }> =
({ formula, note, durFrames }) => (
  <SceneCard durFrames={durFrames} delay={4}
    style={{ justifyContent: "flex-end", paddingBottom: 8 }}>
    <CardShell chip="推演" accent={COLORS.warn}>
      <Katex tex={formula} fontSize={56} color={COLORS.hl} />
      <div style={{ marginTop: 16, fontSize: 30, color: COLORS.text, opacity: 0.9 }}>{note}</div>
    </CardShell>
  </SceneCard>
);
```

- [ ] **Step 3: PitfallCard.tsx + ChecklistCard.tsx**

```tsx
import { COLORS } from "../../engine/constants";
import type { Pitfall } from "../../engine/types";
import { CardShell, Row } from "./CardShell";
import { SceneCard } from "./anim";

export const PitfallCard: React.FC<{ p: Pitfall; durFrames: number }> = ({ p, durFrames }) => (
  <SceneCard durFrames={durFrames}>
    <CardShell chip="易错点" accent={COLORS.warn}>
      <div style={{ fontSize: 46, fontWeight: 700, color: COLORS.text, lineHeight: 1.35 }}>✗ {p.claim}</div>
      <Row label="为什么" text={p.why} color={COLORS.warn} />
    </CardShell>
  </SceneCard>
);
```

```tsx
import { spring, useCurrentFrame, useVideoConfig } from "remotion";
import { COLORS } from "../../engine/constants";
import type { Pitfall } from "../../engine/types";
import { CardShell } from "./CardShell";
import { SceneCard } from "./anim";

export const ChecklistCard: React.FC<{ pitfalls: Pitfall[]; durFrames: number }> =
({ pitfalls, durFrames }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  return (
    <SceneCard durFrames={durFrames}>
      <CardShell chip="检查清单" accent={COLORS.ok}>
        {pitfalls.map((p, i) => {
          const s = spring({ frame: f - 10 - i * 8, fps, config: { damping: 15, stiffness: 130 }, durationInFrames: 12 });
          return (
            <div key={i} style={{ display: "flex", gap: 18, alignItems: "center", marginTop: 20, opacity: s }}>
              <span style={{ fontSize: 40, color: COLORS.ok, fontWeight: 800 }}>✓</span>
              <span style={{ fontSize: 38, color: COLORS.text }}>{p.claim}</span>
            </div>
          );
        })}
      </CardShell>
    </SceneCard>
  );
};
```

- [ ] **Step 4: Act3Solution.tsx（分组 + 结论条）**

```tsx
import { Sequence, interpolate, useCurrentFrame } from "remotion";
import { COLORS, EXIT_F, TRANSITION_F } from "../engine/constants";
import type { Layout } from "../engine/layout";
import type { ContentJson, SceneWindow } from "../engine/types";
import type { Timeline } from "../engine/timeline";
import { StepCard } from "./components/StepCard";
import { DerivationPopup } from "./components/DerivationPopup";
import { PitfallCard } from "./components/PitfallCard";
import { ChecklistCard } from "./components/ChecklistCard";
import { Katex } from "./components/Katex";
import { useEnter } from "./components/anim";

export interface Act3Group {
  scenes: SceneWindow[]; start: number; end: number; stepRef?: number; hasStepCard: boolean;
}
export const buildGroups = (timeline: Timeline): Act3Group[] => {
  const groups: Act3Group[] = [];
  for (const w of timeline.scenes.filter((s) => s.act === 3)) {
    const last = groups[groups.length - 1];
    const continuable = last && last.stepRef != null && last.stepRef === w.props.stepRef
      && (w.component === "step-card" || w.component === "derivation-popup");
    if (continuable) {
      last.scenes.push(w);
      last.end = w.startFrame + w.durFrames;
    } else {
      groups.push({ scenes: [w], start: w.startFrame, end: w.startFrame + w.durFrames,
        stepRef: (w.component === "step-card" || w.component === "derivation-popup") ? w.props.stepRef : undefined,
        hasStepCard: w.component === "step-card" });
    }
  }
  return groups;
};

const ConclusionBar: React.FC<{ formula: string; span: number }> = ({ formula, span }) => {
  const f = useCurrentFrame();
  const enter = useEnter(0);
  const exit = interpolate(f, [span - TRANSITION_F, span], [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <div style={{ opacity: enter.opacity * exit, transform: `translateY(${enter.y}px)` }}>
      <div style={{ display: "flex", alignItems: "center", gap: 20, backgroundColor: COLORS.card,
        border: `2px solid ${COLORS.ok}`, borderRadius: 18, padding: "16px 28px" }}>
        <span style={{ fontSize: 38, color: COLORS.ok, fontWeight: 800 }}>✓ 结论</span>
        <Katex tex={formula} fontSize={44} color={COLORS.text} />
      </div>
    </div>
  );
};

export const Act3Solution: React.FC<{ content: ContentJson; timeline: Timeline; layout: Layout }> =
({ content, timeline, layout }) => {
  const groups = buildGroups(timeline);
  const stepGroups = groups.filter((g) => g.hasStepCard);
  const lastStep = stepGroups[stepGroups.length - 1];
  const afterStepScenes = groups.filter((g) => !g.hasStepCard && (!lastStep || g.start >= lastStep.end));
  const needOffset = lastStep && afterStepScenes.length > 0;
  const total = content.steps.length;
  return (
    <>
      <div style={{ position: "absolute", left: layout.main.x, top: layout.main.y,
        width: layout.main.w, height: layout.main.h }}>
        {lastStep && (
          <Sequence from={lastStep.end} durationInFrames={timeline.act4StartFrame - lastStep.end}>
            <ConclusionBar formula={content.steps[lastStep.stepRef! - 1].derivation}
              span={timeline.act4StartFrame - lastStep.end} />
          </Sequence>
        )}
        <div style={needOffset ? { position: "relative", top: 220 } : undefined}>
          {groups.map((g) => {
            const span = g.end - g.start;
            const head = g.scenes[0];
            return (
              <Sequence key={g.start} from={g.start} durationInFrames={span}>
                {g.hasStepCard && (
                  <StepCard step={content.steps[g.stepRef! - 1]} index={g.stepRef!} total={total} durFrames={span} />
                )}
                {g.scenes.filter((w) => w.component === "derivation-popup").map((w) => (
                  <Sequence key={w.sceneId} from={w.startFrame - g.start} durationInFrames={w.durFrames}>
                    <DerivationPopup formula={w.props.formula!}
                      note={content.steps[w.props.stepRef! - 1].note} durFrames={w.durFrames} />
                  </Sequence>
                ))}
                {head.component === "pitfall-card" && (
                  <PitfallCard p={content.pitfalls[head.props.pitfallRef! - 1]} durFrames={span} />
                )}
                {head.component === "checklist-card" && (
                  <ChecklistCard pitfalls={head.props.pitfallRefs!.map((r) => content.pitfalls[r - 1])}
                    durFrames={span} />
                )}
              </Sequence>
            );
          })}
        </div>
      </div>
    </>
  );
};
```

> 实现提示：`EXIT_F` 若最终未被引用可从 import 中去掉（保留 lint 干净）。结论条与 pitfall 组若时间重叠（needOffset=false 但有 pitfall），布局仍安全——结论条高度 ≈150px，pitfall 卡在 top:220 下方渲染。

- [ ] **Step 5: 挂进 LectureVideo（替换 `{/* Act3 / Act4: Task 10 / 11 */}` 的 Act3 一行）**

```tsx
import { Act3Solution } from "./Act3Solution";
// JSX 内 Act2Knowledge 之后：
<Act3Solution content={content} timeline={timeline} layout={layout} />
```

- [ ] **Step 6: 抽帧验证幕3**

```bash
cd template && node scripts/pick_frames.mjs > out/qa/frames.txt
for name in s-s05 s-s06 s-s09 s-s12 s-s14 act3-章节字; do
  f=$(grep -P "^\Q$name\E\t" out/qa/frames.txt | head -1 | cut -f2)
  npx remotion still Lecture169 "out/qa/${name}.png" --frame=$f
done
```

Expected：s05 步骤卡（第1步/共5步 + 公式面板）；s06 步骤卡不闪退 + 推导弹窗在其下方；s09 判别式推演；s12 易错卡（结论条同时可见且不重叠）；s14 清单 ✓ 分条弹出；章节字「本题解法」。左上角题干在 s05-s07 有高亮行（L1/L2 金色左边条）。

- [ ] **Step 7: Commit**

```bash
cd /e/codebase/remotion_java && git add template && git commit -m "feat(template): 幕3 步骤/推演/易错/清单组件 + 分组与结论条"
```

---

### Task 11: general-list + Act4（通法列表逐条生长）+ 总装完成

**Files:**
- Create: `template/src/acts/components/GeneralList.tsx`, `template/src/acts/Act4Method.tsx`
- Modify: `template/src/acts/LectureVideo.tsx`（挂 Act4）

**Interfaces:**
- Produces:
  - `GeneralList {items, activeIndex, span}` — 单个 Sequence 内渲染已讲到第 activeIndex 条的累积列表：历史条目 0.6 透明度，当前条目全亮 + 金色左边条 + spring 进入；整体末 `EXIT_F` 帧淡出。
  - `Act4Method {content, timeline, layout}` — act4 全程一个 Sequence（主区），逐场驱动 GeneralList。

- [ ] **Step 1: GeneralList.tsx**

```tsx
import { Sequence, interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { COLORS, EXIT_F } from "../../engine/constants";
import type { MethodItem } from "../../engine/types";
import { CardShell, Row } from "./CardShell";

const Item: React.FC<{ item: MethodItem; index: number; active: boolean; localStart: number }> =
({ item, index, active, localStart }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const s = spring({ frame: f - localStart, fps, config: { damping: 15, stiffness: 120 }, durationInFrames: 14 });
  if (s <= 0) return null;
  return (
    <div style={{ opacity: active ? 1 : 0.6 * s, borderLeft: active ? `8px solid ${COLORS.hl}` : "8px solid transparent",
      paddingLeft: 20, transform: `translateY(${(1 - s) * 24}px)` }}>
      <div style={{ fontSize: 34, fontWeight: 700, color: active ? COLORS.hl : COLORS.text, marginTop: 14 }}>
        {index + 1}. {item.step}
      </div>
      <Row label="套路" text={item.trick} color={COLORS.sub} />
    </div>
  );
};

export const GeneralList: React.FC<{ items: MethodItem[]; activeIndex: number;
  starts: number[]; span: number }> = ({ items, activeIndex, starts, span }) => {
  const f = useCurrentFrame();
  const exit = interpolate(f, [span - EXIT_F, span], [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  return (
    <div style={{ opacity: exit }}>
      <CardShell chip="通法总结" accent={COLORS.hl}>
        {items.map((item, i) => (
          <Item key={i} item={item} index={i} active={i === activeIndex} localStart={starts[i]} />
        ))}
      </CardShell>
    </div>
  );
};
```

- [ ] **Step 2: Act4Method.tsx**

```tsx
import { Sequence, useCurrentFrame } from "remotion";
import type { Layout } from "../engine/layout";
import type { ContentJson } from "../engine/types";
import type { Timeline } from "../engine/timeline";
import { GeneralList } from "./components/GeneralList";

export const Act4Method: React.FC<{ content: ContentJson; timeline: Timeline; layout: Layout }> =
({ content, timeline, layout }) => {
  const scenes = timeline.scenes.filter((w) => w.act === 4);
  const start = scenes[0].startFrame;
  const lastEnd = scenes[scenes.length - 1].startFrame + scenes[scenes.length - 1].durFrames;
  const span = lastEnd - start;
  const shown = scenes[scenes.length - 1].props.itemRef!;   // 末场时列表应完整
  return (
    <div style={{ position: "absolute", left: layout.main.x, top: layout.main.y,
      width: layout.main.w, height: layout.main.h }}>
      <Sequence from={start} durationInFrames={span}>
        <Act4Inner content={content} timeline={timeline} span={span} shown={shown} start={start} />
      </Sequence>
    </div>
  );
};

const Act4Inner: React.FC<{ content: ContentJson; timeline: Timeline; span: number;
  shown: number; start: number }> = ({ content, timeline, span, shown, start }) => {
  const f = useCurrentFrame();
  const scenes = timeline.scenes.filter((w) => w.act === 4);
  let activeIndex = 0;
  for (let i = 0; i < scenes.length; i++) {
    if (f + start >= scenes[i].startFrame) activeIndex = scenes[i].props.itemRef! - 1;
  }
  return (
    <GeneralList items={content.generalMethod.slice(0, shown)} activeIndex={activeIndex}
      starts={scenes.map((w) => w.startFrame - start)} span={span} />
  );
};
```

- [ ] **Step 3: 挂进 LectureVideo（Act3Solution 之后加）**

```tsx
import { Act4Method } from "./Act4Method";
// JSX 内 Act3Solution 之后：
<Act4Method content={content} timeline={timeline} layout={layout} />
```

同时删除 LectureVideo 中的 `{/* Act3 / Act4: Task 10 / 11 */}` 注释行——总装完成。

- [ ] **Step 4: 抽帧验证幕4 + 全时间轴单测回归**

```bash
cd template
npx vitest run
node scripts/pick_frames.mjs > out/qa/frames.txt
for name in s-s15 s-s17 act4-章节字 act5-定格末尾; do
  f=$(grep -P "^\Q$name\E\t" out/qa/frames.txt | head -1 | cut -f2)
  npx remotion still Lecture169 "out/qa/${name}.png" --frame=$f
done
```

Expected：s15 只有第 1 条（高亮）；s17 三条齐全、第 3 条高亮、前两条变暗；章节字「以后怎么做」；定格末尾帧 = 纯白（幕5 淡出后）。vitest 全绿。

- [ ] **Step 5: Commit**

```bash
cd /e/codebase/remotion_java && git add template && git commit -m "feat(template): 幕4 通法列表 + 五幕总装完成"
```

---

### Task 12: 双画幅全片渲染 + GLM 审帧 + 缺陷迭代

**Files:**
- Create: `template/scripts/qa_glm.py`
- 产物（gitignore）: `template/out/qa/*.png`, `template/out/final_169.mp4`, `template/out/final_916.mp4`

**Interfaces:**
- Consumes: `pick_frames.mjs` 帧清单、GLM Anthropic 兼容端点（`https://open.bigmodel.cn/api/anthropic/v1/messages`，model `glm-5.3-flash`，spec D4）。
- Produces: `out/qa/report.md`（逐帧审帧结论）；**Phase 1 验收证据**。

- [ ] **Step 1: 写 scripts/qa_glm.py（移植旧 check_frames.py，只审客观项 spec §10-V6）**

```python
# -*- coding: utf-8 -*-
"""
qa_glm.py — GLM-5.3-flash 客观审帧（重叠/乱码/黑字/越界/公式崩坏，不审审美）
用法: python scripts/qa_glm.py [png ...]   （缺省审 out/qa/ 全部 png）
Key: ZHIPU_API_KEY / GLM_API_KEY / ANTHROPIC_AUTH_TOKEN 环境变量，回落 ~/.claude/settings.json
输出: out/qa/report.md；任一 FAIL → exit 1
"""
import base64, json, mimetypes, os, pathlib, sys, time
import requests

API_URL = "https://open.bigmodel.cn/api/anthropic/v1/messages"  # Anthropic 兼容端点
MODEL = "glm-5.3-flash"
PROMPT = (
    "这是讲题教学视频的一帧渲染截图（深色科技风正文，或纯白片尾）。只审客观项，不评审美：\n"
    "1) 文字/卡片/公式是否相互重叠、遮挡、溢出画面边界；\n"
    "2) 是否有乱码、方框缺字、明显错误字符；\n"
    "3) 是否有黑底黑字/白底白字等对比度失效（文字不可见）；\n"
    "4) 数学公式是否渲染崩坏（LaTeX 源码裸露、符号错位堆叠）。\n"
    "逐项简答，最后一行输出：PASS 或 FAIL（一句话理由）。"
)

def load_key():
    for k in ("ZHIPU_API_KEY", "ZHIPUAI_API_KEY", "GLM_API_KEY", "ANTHROPIC_AUTH_TOKEN"):
        if os.environ.get(k):
            return os.environ[k].strip()
    settings = pathlib.Path.home() / ".claude" / "settings.json"
    if settings.exists():
        d = json.loads(settings.read_text(encoding="utf-8"))
        tok = d.get("env", {}).get("ANTHROPIC_AUTH_TOKEN")
        if tok:
            return tok.strip()
    sys.exit("[fatal] 未找到 GLM Key")

def check(key, path):
    mime = mimetypes.guess_type(str(path))[0] or "image/png"
    b64 = base64.b64encode(path.read_bytes()).decode()
    body = {"model": MODEL, "max_tokens": 1024, "messages": [{"role": "user", "content": [
        {"type": "image", "source": {"type": "base64", "media_type": mime, "data": b64}},
        {"type": "text", "text": PROMPT}]}]}
    for attempt in range(5):
        try:
            r = requests.post(API_URL, headers={
                "x-api-key": key, "Authorization": f"Bearer {key}",
                "anthropic-version": "2023-06-01", "Content-Type": "application/json"},
                json=body, timeout=180)
            if r.status_code == 429:
                time.sleep(20 * (attempt + 1)); continue
            if r.status_code != 200:
                return f"[error] {r.status_code} {r.text[:200]}", False
            text = "".join(p.get("text", "") for p in r.json().get("content", []) if p.get("type") == "text")
            ok = bool(text) and "PASS" in text.strip().splitlines()[-1].upper()
            return text.strip(), ok
        except Exception:
            time.sleep(5)
    return "[error] 重试耗尽", False

def main():
    key = load_key()
    root = pathlib.Path(__file__).resolve().parent.parent
    targets = [pathlib.Path(a) for a in sys.argv[1:]] or sorted((root/"out"/"qa").glob("*.png"))
    targets = [t for t in targets if t.suffix == ".png" and t.name != "scaffold.png"]
    if not targets:
        sys.exit("[fatal] 没有待审帧")
    fails, lines = [], [f"# GLM 审帧报告 — {MODEL}\n"]
    for p in targets:
        text, ok = check(key, p)
        print(f"[{'PASS' if ok else 'FAIL'}] {p.name}")
        lines.append(f"## {p.name}\n\n{text}\n")
        if not ok:
            fails.append(p.name)
        time.sleep(2)
    out = root/"out"/"qa"/"report.md"
    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"\n报告: {out}；FAIL {len(fails)}/{len(targets)}")
    sys.exit(1 if fails else 0)

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 全片渲染（双画幅）**

```bash
cd template
npx remotion render Lecture169 out/final_169.mp4
npx remotion render Lecture916 out/final_916.mp4
```

Expected: 两条 mp4 成功产出（时长 = totalFrames/30）。

- [ ] **Step 3: 批量抽帧（每镜头 ≥1 帧 + 幕边界/片尾各 ≥1 帧，spec §16 验收口径）**

```bash
cd template && node scripts/pick_frames.mjs > out/qa/frames.txt
while IFS=$'\t' read -r name f; do
  [ "$name" = "totalFrames =" ] && continue
  safe=$(echo "$name" | tr -d '/')
  npx remotion still Lecture169 "out/qa/${safe}.png" --frame="$f" || echo "STILL FAIL $name"
done < out/qa/frames.txt
```

- [ ] **Step 4: 人工 + GLM 双审**

```bash
export ZHIPU_API_KEY="<GLM key>"    # 或已配 ~/.claude/settings.json 则跳过
python scripts/qa_glm.py
```

同时人工翻看 `out/qa/*.png`。9:16 再跑一遍 Step 3-4（still 目标换 `Lecture916`，输出加 `_916` 后缀）。

- [ ] **Step 5: 缺陷迭代（循环直至全 PASS）**

常见修复方向（对照 spec §17）：文字越界 → 调 layout 分区/字号；KaTeX 溢出 → Katex fontSize 下调；黑字 → 检查显式 color；重叠 → 调整组件分区偏移。每次修复：改组件源码 → 重抽该帧 still 确认 → 受影响画幅重渲染。**只改 template 源码**，不改两个 JSON 的数据语义。

- [ ] **Step 6: Commit**

```bash
cd /e/codebase/remotion_java && git add template && git commit -m "feat(template): GLM 审帧脚本 + 双画幅全片验收通过"
```

---

### Task 13: 模板契约 README + 封版

**Files:**
- Create: `template/README.md`, `template/.gitignore`

- [ ] **Step 1: template/.gitignore**

```
out/
node_modules/
```

- [ ] **Step 2: template/README.md（Phase 2 Java 服务的操作契约，必须与实现一致）**

内容须覆盖（写成正式文档，含代码块）：
1. **定位**：封版模板；改版面/动画 = 改模板代码并重新封版，绝不为单条视频改代码。
2. **单条视频生产 SOP（Phase 2 自动化对象）**：
   ```
   cp -r template/ workspace/{jobId}/
   1. 覆写 src/data/content.json        （三工位产物，过 V1-V4）
   2. 覆写 src/data/audio_meta.json     （TTS 管线产物，durationSec=字节数÷byte_rate 实测）
   3. 覆写 public/audio/fixed 不动；覆写 public/audio/lines/line_NN.wav
   4. npx remotion render Lecture169 out/final.mp4   （或 Lecture916）
   ```
3. **两个 JSON 的字段契约**（照本计划"数据契约"节全文抄录，含 props 矩阵表）。
4. **时间轴公式**（照"数据契约"节；QA 脚本 pick_frames.mjs 为镜像实现，两者必须同步改）。
5. **白名单矩阵**：act2 = problem-card/knowledge-card；act3 = step-card/derivation-popup/pitfall-card/checklist-card；act4 = general-list。
6. **QA 流程**：`node scripts/pick_frames.mjs` → `npx remotion still` → `python scripts/qa_glm.py`（客观项，FAIL 即 exit 1）。
7. **已钉死常量**：FPS 30 / breath 0.18s / 章节字 1.8s / 片尾定格 2s / TRANSITION_F 18 / EXIT_F 10 / 配色表 / Edge 路径。
8. **渲染引擎约束**：`remotion.config.ts` 指向本机 Edge，禁止下载 Chrome Headless Shell；语速恒 1.0。

- [ ] **Step 3: 封版 tag**

```bash
cd /e/codebase/remotion_java
git add template && git commit -m "docs(template): 模板使用契约 README，封版 v0.1"
git tag template-v0.1
```

---

## Phase 1 验收清单（spec §16）

- [ ] `template/out/final_169.mp4` 与 `final_916.mp4` 成片可播放，时长 = `pick_frames.mjs` 输出的 totalFrames/30。
- [ ] 审帧覆盖：每个镜头至少一帧 + 三个章节字帧 + act1 中段 + act5 打字机/定格末尾帧。
- [ ] 人工过帧 + `qa_glm.py` 全 PASS（report.md 留档）。
- [ ] vitest 全绿（timeline/contract）。
- [ ] 幕结构核对：题干全屏→左上角常驻（幕2 进入时）→行高亮随 stepRef（幕3）→题干淡出（幕4 开始 18 帧）→结论条常驻（幕3 末至幕4）→幕5 白底打字机。
- [ ] 音画同步抽查：任选 3 场，台词起止与画面切换对齐（±1 帧内）。

## 自审记录（写计划时已核对）

- **Spec 覆盖**：§5 结构→Task 1/9；§6 契约→数据契约节+Task 4；§7 白名单→数据契约矩阵；§8 五幕→Task 7-11；§12 判据→Task 5；§16 验收→Task 12/验收清单。§9-11/13-14（工位/校验链/REST）属 Phase 2 计划，不在本文件。
- **遗留给 Phase 2 的契约锚点**：props 矩阵、audio_meta 字段、`line_NN` 命名（scenes 数组顺序）、复制-覆写-渲染 SOP、V1 规则 = `contract.ts` 的超集。
- **已知取舍**：①QA 脚本独立复算时间轴（不同源，防同源错误互相掩护）；②Phase 1 TTS 失败即整批人工重跑，自动整批重录在 Phase 2 Java 实现；③结论条避让 = pitfall 容器 top:220，若实测拥挤由 Task 12 迭代调布局。





