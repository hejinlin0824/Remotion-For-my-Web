import { COLORS, FONT_FAMILY } from "../../engine/constants";
import { estimateMathWidth, estimateTextWidth, fitScale } from "../../engine/fit";
import { LAYOUTS } from "../../engine/layout";
import katex from "katex";
import type { ContentJson, ProblemLine } from "../../engine/types";

const inline = (tex: string) =>
  katex.renderToString(tex, { throwOnError: false, output: "html" });

const TEXT_FONT = 50;   // text 段基准字号（v0.1 钉死）
const MATH_FONT = 46;   // math 段基准字号（v0.1 钉死）
const PANEL_BORDER_X = 3;   // 面板 border 左右各 3
const LINE_PAD_X = 18;      // Line 行容器左右 padding
const LINE_BORDER_X = 6;    // Line 左侧高亮条（含透明态）宽度
const LINE_GAP = 16;        // 段间 gap
const FIT_FLOOR = 0.6;      // 行级缩放下限：缩到 0.6 仍放不下 → 保持 0.6 照常渲染（QA 审帧兜底）

/**
 * v0.3 行级宽度自适应：按字符估算整行需求宽（text≈1em/0.55em，math≈0.6em×TeX 字符数），
 * 超出该行可用宽度预算（面板宽 − 面板 padding/border − 行 padding/高亮条）→
 * 整行 fontSize 按 budget/needed 等比缩小（下限 0.6），保持单行；
 * 未超预算恒返回 1（golden 零漂移前提）。纯函数、确定性。
 */
export const problemLineScale = (line: ProblemLine, panelWidth: number, panelPadX: number): number => {
  const budget = panelWidth - panelPadX * 2 - PANEL_BORDER_X * 2 - LINE_PAD_X * 2 - LINE_BORDER_X;
  const gapTotal = LINE_GAP * Math.max(0, line.segments.length - 1);
  const segTotal = line.segments.reduce((w, seg) => seg.type === "math"
    ? w + estimateMathWidth(seg.value, MATH_FONT)
    : w + estimateTextWidth(seg.value, TEXT_FONT), 0);
  return fitScale((s) => gapTotal + s * segTotal, budget, FIT_FLOOR);
};

const Line: React.FC<{ line: ProblemLine; highlighted: boolean; scale: number }> =
({ line, highlighted, scale }) => (
  <div style={{ display: "flex", flexWrap: "wrap", rowGap: 8, alignItems: "center", gap: 16, padding: "10px 18px", borderRadius: 12,
    backgroundColor: highlighted ? "rgba(255,213,74,0.16)" : "transparent",
    borderLeft: highlighted ? `6px solid ${COLORS.hl}` : "6px solid transparent",
    transition: "none" }}>
    {line.segments.map((seg, i) => seg.type === "text"
      ? <span key={i} style={{ fontSize: TEXT_FONT * scale, color: COLORS.text, fontFamily: FONT_FAMILY }}>{seg.value}</span>
      : <span key={i} style={{ fontSize: MATH_FONT * scale, color: highlighted ? COLORS.hl : COLORS.accent, whiteSpace: "nowrap", flexShrink: 0 }}
          dangerouslySetInnerHTML={{ __html: inline(seg.value) }} />)}
  </div>
);

export const ProblemPanel: React.FC<{ content: ContentJson; highlightId?: string; compact?: boolean;
  panelWidth?: number }> = ({ content, highlightId, compact, panelWidth }) => {
  // 可用宽度真源：题干区布局宽（problemFull.w，16:9=1400）；ProblemStage 显式传入，
  // 未传时按 content.meta.aspect 从布局常量推导。
  const width = panelWidth ?? LAYOUTS[content.meta.aspect].problemFull.w;
  const padX = compact ? 24 : 40;
  return (
    <div style={{ backgroundColor: COLORS.card, border: `3px solid ${COLORS.cardBorder}`, borderRadius: 24,
      padding: compact ? 24 : 40, opacity: compact ? 0.96 : 1 }}>
      <div style={{ display: "inline-block", fontSize: 28, fontWeight: 700, color: COLORS.ink,
        backgroundColor: COLORS.accent, borderRadius: 10, padding: "4px 18px", marginBottom: 18 }}>题目</div>
      {content.problem.lines.map((l) => (
        <Line key={l.id} line={l} highlighted={highlightId === l.id} scale={problemLineScale(l, width, padX)} />
      ))}
    </div>
  );
};
