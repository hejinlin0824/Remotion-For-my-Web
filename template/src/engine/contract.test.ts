// src/engine/contract.test.ts
import { describe, expect, it } from "vitest";
import { validateContract } from "./contract";
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

describe("validateContract", () => {
  it("① 合法 fixture 返回空数组", () => {
    const scenes = content([
      { id: "s01", act: 2, component: "problem-card", ttsText: "a", props: {} },
      { id: "s02", act: 2, component: "knowledge-card", ttsText: "b", props: { knowledgeRef: 1 } },
      { id: "s03", act: 3, component: "step-card", ttsText: "c", props: { stepRef: 1 } },
      { id: "s04", act: 3, component: "derivation-popup", ttsText: "d", props: { stepRef: 1, formula: "f" } },
      { id: "s05", act: 3, component: "pitfall-card", ttsText: "e", props: { pitfallRef: 1 } },
      { id: "s06", act: 3, component: "checklist-card", ttsText: "f", props: { pitfallRefs: [1] } },
      { id: "s07", act: 4, component: "general-list", ttsText: "g", props: { itemRef: 1 } },
    ]);
    const errs = validateContract(scenes, audio([1, 1, 1, 1, 1, 1, 1]));
    expect(errs).toEqual([]);
  });

  it("② knowledgeRef: 5（越界）返回含 knowledgeRef 的错误", () => {
    const scenes = content([
      { id: "s01", act: 2, component: "problem-card", ttsText: "a", props: {} },
      { id: "s02", act: 2, component: "knowledge-card", ttsText: "b", props: { knowledgeRef: 5 } },
    ]);
    const errs = validateContract(scenes, audio([1, 1]));
    expect(errs.length).toBeGreaterThan(0);
    expect(errs.some((e) => e.includes("knowledgeRef"))).toBe(true);
  });

  it("③ derivation-popup 缺 formula 返回含 formula 的错误", () => {
    const scenes = content([
      { id: "s01", act: 2, component: "problem-card", ttsText: "a", props: {} },
      { id: "s02", act: 3, component: "derivation-popup", ttsText: "d", props: { stepRef: 1 } },
    ]);
    const errs = validateContract(scenes, audio([1, 1]));
    expect(errs.length).toBeGreaterThan(0);
    expect(errs.some((e) => e.includes("formula"))).toBe(true);
  });

  it("④ checklist-card 的 pitfallRefs 越界报错", () => {
    const scenes = content([
      { id: "s01", act: 2, component: "problem-card", ttsText: "a", props: {} },
      { id: "s02", act: 3, component: "checklist-card", ttsText: "f", props: { pitfallRefs: [5] } },
    ]);
    const errs = validateContract(scenes, audio([1, 1]));
    expect(errs.length).toBeGreaterThan(0);
    expect(errs.some((e) => e.includes("pitfallRefs"))).toBe(true);
  });

  it("⑤ problem-card 不在 act2 第一场报错", () => {
    const scenes = content([
      { id: "s01", act: 2, component: "knowledge-card", ttsText: "b", props: { knowledgeRef: 1 } },
      { id: "s02", act: 2, component: "problem-card", ttsText: "a", props: {} },
    ]);
    const errs = validateContract(scenes, audio([1, 1]));
    expect(errs.length).toBeGreaterThan(0);
    expect(errs.some((e) => e.includes("problem-card") && e.includes("第一场"))).toBe(true);
  });

  it("⑥ act: 1 出现在 scenes 报错（白名单只允许 2/3/4）", () => {
    const scenes = content([
      { id: "s01", act: 1, component: "problem-card", ttsText: "a", props: {} },
    ]);
    const errs = validateContract(scenes, audio([1]));
    expect(errs.length).toBeGreaterThan(0);
    expect(errs.some((e) => e.includes("act 只允许 2/3/4"))).toBe(true);
  });
});
