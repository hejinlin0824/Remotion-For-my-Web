// src/engine/fit.ts — 排版自适应共享估算（v0.3，Task 15a）
// 用途：ProblemPanel 行级宽度自适应 + 列表类卡片（GeneralList/ChecklistCard）高度自适应。
// 铁律：全部纯函数、确定性——字符级估宽，无 DOM 测量、无 Math.random、无时钟、无异步。
// 系数为 v0.3 目验校准值（Microsoft YaHei 正文 / KaTeX html 输出）：
//   CJK ≈ 1.0em；ASCII/数字/半角标点 ≈ 0.55em；KaTeX math 段 ≈ 0.6em × TeX 源字符数。

/** 宽度估算系数（em 倍数）。 */
export const WIDTH_COEFF = { cjk: 1.0, other: 0.55, math: 0.6 } as const;

const isCJK = (cp: number): boolean =>
  (cp >= 0x2e80 && cp <= 0x9fff) ||   // CJK 部首/符号/统一表意文字（含「」『』等 0x3000 块）
  (cp >= 0xf900 && cp <= 0xfaff) ||   // CJK 兼容表意文字
  (cp >= 0xff00 && cp <= 0xffef);     // 全角形式（，．：；！？等）

/** 文本估宽 = 字符折算 em 数 × fontSize。 */
export function estimateTextWidth(text: string, fontSize: number): number {
  let units = 0;
  for (const ch of text) {
    units += isCJK(ch.codePointAt(0) ?? 0) ? WIDTH_COEFF.cjk : WIDTH_COEFF.other;
  }
  return units * fontSize;
}

/** KaTeX 段估宽 = TeX 源字符数 × 0.6em（含控制符，系统性偏高 → 偏保守安全）。 */
export function estimateMathWidth(tex: string, fontSize: number): number {
  return [...tex].length * WIDTH_COEFF.math * fontSize;
}

/** 估行数（≥1）：估宽除以可用宽度向上取整（同字号下整行等比缩放时行数随之变化）。 */
export function estimateLineCount(text: string, fontSize: number, availWidth: number): number {
  if (!(availWidth > 0)) return 1;
  return Math.max(1, Math.ceil(estimateTextWidth(text, fontSize) / availWidth));
}

/**
 * 确定性缩放收敛：neededAt(s) 为「整块按比例 s 缩放后的总需求高度/宽度」，
 * 返回不超 budget 的 s（下限 floor）。需求对 s 非线性（换行数随字号变化）时逐轮修正，
 * 最多 16 轮（需求线性时 2-3 轮即收敛到不动点 needed(s)=budget）；
 * 缩到 floor 仍放不下 → 停在 floor 照常渲染（floor 兜底，QA 审帧拦截）。
 * 同输入恒同输出；需求在预算内时恒返回 1（未触发内容零影响，golden 零漂移前提）。
 */
export function fitScale(neededAt: (s: number) => number, budget: number, floor: number): number {
  if (!(budget > 0)) return floor;
  let s = 1;
  for (let i = 0; i < 16; i++) {
    const needed = neededAt(s);
    if (needed <= budget) return s;
    const next = Math.max(floor, s * (budget / needed));
    if (next === s) break;
    s = next;
  }
  // 收敛轮次用尽仍略超预算（非线性需求的高阶残差）：末轮按比例再收一次后夹紧 floor。
  const residual = neededAt(s);
  return residual <= budget ? s : Math.max(floor, s * (budget / residual));
}
