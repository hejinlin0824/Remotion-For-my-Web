// src/engine/fit.test.ts — v0.3 排版自适应估算纯函数（Task 15a）
import { describe, expect, it } from "vitest";
import { estimateLineCount, estimateMathWidth, estimateTextWidth, fitScale } from "./fit";

describe("estimateTextWidth（CJK≈1em / 其余≈0.55em）", () => {
  it("纯 CJK：字数 × fontSize", () => {
    expect(estimateTextWidth("已知函数", 50)).toBeCloseTo(4 * 50);
  });
  it("ASCII 与空格按 0.55em", () => {
    expect(estimateTextWidth("ab1 ", 50)).toBeCloseTo(4 * 0.55 * 50);
  });
  it("全角标点（，：）计入 CJK 全角块", () => {
    expect(estimateTextWidth("，：", 50)).toBeCloseTo(2 * 50);
  });
  it("混排：已知函数+空格", () => {
    expect(estimateTextWidth("已知函数 ", 50)).toBeCloseTo(4 * 50 + 0.55 * 50);
  });
});

describe("estimateMathWidth（0.6em × TeX 源字符数）", () => {
  it("按源字符数（含反斜杠/花括号）× 0.6em", () => {
    expect(estimateMathWidth("f(x)", 46)).toBeCloseTo(4 * 0.6 * 46);
  });
  it("45 字符长公式在 fontSize 46 下估宽", () => {
    const tex = "x".repeat(45);
    expect(estimateMathWidth(tex, 46)).toBeCloseTo(45 * 0.6 * 46);
  });
});

describe("estimateLineCount", () => {
  it("估宽不超可用宽度 = 1 行", () => {
    expect(estimateLineCount("已知函数", 34, 10 * 34)).toBe(1);
  });
  it("超宽向上取整为多行", () => {
    expect(estimateLineCount("一二三四五", 34, 3 * 34)).toBe(2);
  });
  it("可用宽度非法时按 1 行兜底", () => {
    expect(estimateLineCount("一二三", 34, 0)).toBe(1);
  });
});

describe("fitScale（确定性收敛，floor 兜底）", () => {
  it("需求在预算内 → 恒 1（golden 零漂移前提）", () => {
    expect(fitScale((s) => 300 * s, 700, 0.55)).toBe(1);
  });
  it("线性需求：收敛到 needed(s)=budget 的不动点", () => {
    expect(fitScale((s) => 100 + 1000 * s, 706, 0.55)).toBeCloseTo(606 / 1000, 10);
  });
  it("换行非线性：第二轮修正后不超预算", () => {
    // 行数随字号减半而跳降：s=1 需 1200，s=0.6 需 660 ≤ 700 → 最终 0.6
    const needed = (s: number) => (s > 0.8 ? 1200 : 660);
    const s = fitScale(needed, 700, 0.55);
    expect(s).toBeCloseTo(700 / 1200, 10);
    expect(needed(s)).toBeLessThanOrEqual(700);
  });
  it("floor 兜底：预算再小也不低于 floor，且停在 floor", () => {
    expect(fitScale((s) => 10000 * s, 100, 0.55)).toBe(0.55);
  });
  it("非正预算 → 直接 floor", () => {
    expect(fitScale(() => 1, 0, 0.55)).toBe(0.55);
  });
  it("同输入恒同输出（确定性）", () => {
    const needed = (s: number) => 40 + 900 * s;
    expect(fitScale(needed, 500, 0.6)).toBe(fitScale(needed, 500, 0.6));
  });
});
