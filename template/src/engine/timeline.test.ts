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
