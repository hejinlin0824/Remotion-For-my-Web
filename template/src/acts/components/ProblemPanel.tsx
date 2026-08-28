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
